package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import psyke.agent.cortex.sensory.ProjectSignal
import psyke.agent.cortex.sensory.Signal
import psyke.agent.id.Project as IdProject
import psyke.agent.model.ActionOutcome
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingAction
import psyke.agent.model.RootInputIds
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private data class ProjectExecutionSession(
    val projectId: String,
    val stepId: String,
    val rootInputId: String,
    val createdAt: Instant = Instant.now(),
    val actionCount: Int = 0,
    val lastResultSummary: String = "",
    val allowFollowUp: Boolean = true,
    val requeueReason: String? = null,
)

class ProjectManager(
    private val config: ProjectConfig,
    private val store: ProjectStore,
    private val planner: ProjectPlanner = DeterministicProjectPlanner(),
    private val verifier: ProjectStepVerifier = DeterministicProjectStepVerifier(),
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val signalEmitter: (Signal) -> Unit = {},
) : ProjectsGateway {
    private val states = ConcurrentHashMap<String, ProjectState>()
    private val sessionsByRootInputId = ConcurrentHashMap<String, ProjectExecutionSession>()

    @Volatile
    private var timerScheduler: TimerScheduler? = null

    @Volatile
    private var waitConditionMonitor: WaitConditionMonitor? = null

    override fun start(scope: CoroutineScope) {
        timerScheduler = TimerScheduler(
            resolutionMs = config.timerResolutionMs,
            onWakeUp = ::onTimerWake,
        ).also { it.start(scope) }
        waitConditionMonitor = WaitConditionMonitor(
            checkIntervalMs = config.conditionCheckIntervalMs,
            onConditionSatisfied = { projectId, stepId ->
                onWaitConditionSatisfied(projectId, stepId, "condition_check")
            },
            onConditionTimedOut = { projectId, stepId ->
                applyEvent(projectId, ProjectEvent.WaitConditionTimedOut(projectId, stepId))
            },
        ).also { it.start(scope) }

        restoreProjects()
    }

    override fun stop() {
        timerScheduler?.stop()
        timerScheduler = null
        waitConditionMonitor?.stop()
        waitConditionMonitor = null
    }

    override fun nextWorkFromSignal(signal: ProjectSignal): ProjectWorkUnit? {
        val workSignal = signal as? ProjectSignal.WorkReady ?: return null
        val state = states[workSignal.projectId] ?: return null
        val step = state.nextRunnableStep() ?: return null
        val startedState = if (step.status == StepStatus.READY) {
            applyEvent(state.id, ProjectEvent.StepStarted(state.id, step.id)) ?: state
        } else {
            state
        }
        val startedStep = startedState.project.plan.steps.firstOrNull { it.id == step.id } ?: step
        val rootInputId = buildProjectRootInputId(state.id, step.id)
        sessionsByRootInputId[rootInputId] = ProjectExecutionSession(
            projectId = state.id,
            stepId = step.id,
            rootInputId = rootInputId,
        )
        instrumentation.emit(AgentEvents.projectWakeUp(state.id, "work_ready", workSignal.reason))
        return ProjectContextLoader.buildWorkUnit(
            state = startedState,
            step = startedStep,
            rootInputId = rootInputId,
            wakeReason = workSignal.reason,
        )
    }

    override fun pendingWorkSummary(): String {
        val active = states.values
            .filter { !it.isTerminal() }
            .sortedByDescending { it.project.priority.ordinal }
        if (active.isEmpty()) return ""
        return active.joinToString("\n") { state ->
            val step = state.nextRunnableStep()
            val stepInfo = step?.let { "${it.status.name.lowercase()}: ${it.description}" } ?: "no runnable steps"
            "- [${state.project.priority}] ${state.project.title} ($stepInfo)"
        }
    }

    override fun onActionExecuted(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {
        if (action.origin.source != OriginSource.PROJECT) return
        val rootInputId = action.rootInputId ?: return
        val session = sessionsByRootInputId[rootInputId] ?: return
        val actionCount = session.actionCount + 1
        sessionsByRootInputId[rootInputId] = session.copy(
            actionCount = actionCount,
            lastResultSummary = outcome.statusSummary,
            allowFollowUp = actionCount < config.actionsPerCycle,
        )

        val afterAction = applyEvent(
            session.projectId,
            ProjectEvent.StepActionExecuted(session.projectId, session.stepId, outcome.statusSummary)
        ) ?: return
        val step = afterAction.project.plan.steps.firstOrNull { it.id == session.stepId } ?: return
        val verification = verifier.evaluate(afterAction.project, step, action, outcome, observedEvidence)

        when (verification.verdict) {
            ProjectStepVerdict.PASS -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(session.projectId, ProjectEvent.StepAcceptancePassed(session.projectId, session.stepId))
            }

            ProjectStepVerdict.RETRY -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(
                    session.projectId,
                    ProjectEvent.StepAcceptanceFailed(
                        session.projectId,
                        session.stepId,
                        verification.reason.ifBlank { outcome.statusSummary },
                    )
                )
            }

            ProjectStepVerdict.BLOCK -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                val waitCondition = verification.waitCondition ?: WaitCondition(
                    type = WaitConditionType.CONDITION_CHECK,
                    params = emptyMap(),
                    registeredAt = Instant.now(),
                )
                applyEvent(
                    session.projectId,
                    ProjectEvent.StepBlocked(session.projectId, session.stepId, waitCondition)
                )
            }

            ProjectStepVerdict.CONTINUE -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(
                        allowFollowUp = actionCount < config.actionsPerCycle,
                        requeueReason = "step_continue"
                    )
                    ?: return
            }

            ProjectStepVerdict.FAIL -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(
                    session.projectId,
                    ProjectEvent.StepAcceptanceFailed(
                        session.projectId,
                        session.stepId,
                        verification.reason.ifBlank { outcome.statusSummary },
                    )
                )
            }
        }
    }

    override fun onActionBlocked(action: PendingAction, reason: String, reasonCode: String?, source: String) {
        if (action.origin.source != OriginSource.PROJECT) return
        val rootInputId = action.rootInputId ?: return
        val session = sessionsByRootInputId[rootInputId] ?: return
        sessionsByRootInputId[rootInputId] = session.copy(allowFollowUp = false, requeueReason = null)
        applyEvent(
            session.projectId,
            ProjectEvent.StepAcceptanceFailed(
                projectId = session.projectId,
                stepId = session.stepId,
                reason = listOfNotNull(reasonCode, source, reason).joinToString(": "),
            )
        )
    }

    override fun allowFollowUp(action: PendingAction): Boolean {
        if (action.origin.source != OriginSource.PROJECT) return true
        val session = action.rootInputId?.let { sessionsByRootInputId[it] } ?: return true
        return session.allowFollowUp && session.actionCount < config.actionsPerCycle
    }

    override fun finalizeProjectCycle(rootInputId: String) {
        val session = sessionsByRootInputId.remove(rootInputId) ?: return
        val state = states[session.projectId] ?: return

        writeWorkspaceCycleArtifacts(state, session)
        applyEvent(
            session.projectId,
            ProjectEvent.WorkCycleCompleted(
                projectId = session.projectId,
                stepId = session.stepId,
                actionsExecuted = session.actionCount,
            )
        )

        if (!session.requeueReason.isNullOrBlank()) {
            val refreshed = states[session.projectId] ?: return
            val runnable = refreshed.nextRunnableStep() ?: return
            signalEmitter(
                ProjectSignal.WorkReady(
                    projectId = refreshed.id,
                    stepId = runnable.id,
                    reason = session.requeueReason,
                )
            )
        }
    }

    override fun executeOperation(request: ProjectOperationRequest): ProjectOperationResult =
        when (request.operation) {
            ProjectOperation.CREATE -> {
                val instruction = request.instruction?.trim().orEmpty()
                if (instruction.isBlank()) {
                    ProjectOperationResult(false, "Project creation requires an instruction.")
                } else {
                    val projectId = createProject(
                        instruction = instruction,
                        title = request.title?.takeIf { it.isNotBlank() } ?: instruction.take(60),
                        priority = request.priority ?: ProjectPriority.MEDIUM,
                        completionCriteria = request.completionCriteria ?: "User confirms the goal is met.",
                    )
                    if (projectId.isBlank()) {
                        ProjectOperationResult(false, "Project creation was rejected.")
                    } else {
                        ProjectOperationResult(true, "Project created.", projectId)
                    }
                }
            }

            ProjectOperation.STATUS -> {
                val projectId = request.projectId.orEmpty()
                val state = states[projectId]
                if (state == null) {
                    ProjectOperationResult(false, "Project not found.")
                } else {
                    val step = state.nextRunnableStep()
                    ProjectOperationResult(
                        true,
                        "status=${state.project.status} next_step=${step?.description ?: "none"}",
                        projectId = projectId,
                    )
                }
            }

            ProjectOperation.LIST -> {
                val summaries = allProjects()
                val message = if (summaries.isEmpty()) {
                    "No projects."
                } else {
                    summaries.joinToString("\n") { summary ->
                        "${summary.projectId}: ${summary.title} (${summary.status})"
                    }
                }
                ProjectOperationResult(true, message)
            }

            ProjectOperation.PAUSE -> {
                val projectId = request.projectId.orEmpty()
                if (!states.containsKey(projectId)) {
                    ProjectOperationResult(false, "Project not found.")
                } else {
                    applyEvent(
                        projectId,
                        ProjectEvent.Suspended(
                            projectId = projectId,
                            reason = request.reason ?: "Paused by user",
                        )
                    )
                    ProjectOperationResult(true, "Project paused.", projectId)
                }
            }

            ProjectOperation.RESUME -> {
                val projectId = request.projectId.orEmpty()
                if (!states.containsKey(projectId)) {
                    ProjectOperationResult(false, "Project not found.")
                } else {
                    applyEvent(projectId, ProjectEvent.Resumed(projectId))
                    ProjectOperationResult(true, "Project resumed.", projectId)
                }
            }

            ProjectOperation.REPRIORITIZE -> {
                val projectId = request.projectId.orEmpty()
                val state = states[projectId]
                val newPriority = request.priority
                if (state == null || newPriority == null) {
                    ProjectOperationResult(false, "Project reprioritize requires projectId and priority.")
                } else {
                    applyEvent(projectId, ProjectEvent.PriorityChanged(projectId, newPriority))
                    ProjectOperationResult(true, "Project priority updated to $newPriority.", projectId)
                }
            }

            ProjectOperation.COMPLETE -> {
                val projectId = request.projectId.orEmpty()
                if (!states.containsKey(projectId)) {
                    ProjectOperationResult(false, "Project not found.")
                } else {
                    applyEvent(projectId, ProjectEvent.Completed(projectId))
                    ProjectOperationResult(true, "Project marked completed.", projectId)
                }
            }

            ProjectOperation.REVISE_PLAN -> {
                val projectId = request.projectId.orEmpty()
                val state = states[projectId]
                if (state == null) {
                    ProjectOperationResult(false, "Project not found.")
                } else {
                    val plan = planner.generatePlan(state.project)
                    applyEvent(
                        projectId,
                        ProjectEvent.PlanRevised(
                            projectId = projectId,
                            plan = plan,
                            reason = request.reason ?: "Revised by user request",
                        )
                    )
                    ProjectOperationResult(true, "Project plan revised.", projectId)
                }
            }
        }

    override fun allProjects(): List<ProjectTier1Summary> =
        states.values
            .sortedByDescending { it.project.priority.ordinal }
            .map { ProjectContextLoader.tier1Summary(it) }

    override fun projectStatus(projectId: String): ProjectState? = states[projectId]

    override fun activeProjects(): List<IdProject> =
        states.values
            .filter { !it.isTerminal() }
            .sortedByDescending { it.project.priority.ordinal }
            .map { state ->
                IdProject(
                    id = state.id,
                    instruction = state.project.instruction,
                    lastActedAt = state.project.lastWorkedAt,
                )
            }

    fun createProject(
        instruction: String,
        title: String = instruction.take(60),
        priority: ProjectPriority = ProjectPriority.MEDIUM,
        completionCriteria: String = "User confirms the goal is met.",
        cronExpression: String? = null,
    ): String {
        val activeCount = states.values.count { !it.isTerminal() }
        if (activeCount >= config.maxActiveProjects) {
            logger.warn { "Max active projects reached (${config.maxActiveProjects}), rejecting creation" }
            return ""
        }

        val projectId = generateProjectId(title)
        val workspacePath = store.createWorkspace(projectId)
        val created = ProjectEvent.Created(
            projectId = projectId,
            title = title,
            instruction = instruction,
            priority = priority,
            completionCriteria = completionCriteria,
        )
        val initial = ProjectStateMachine.initialState(created, workspacePath).let { state ->
            if (cronExpression.isNullOrBlank()) {
                state
            } else {
                state.copy(project = state.project.copy(cronExpression = cronExpression))
            }
        }
        states[projectId] = initial
        store.eventLog(projectId).append(created)
        persistState(projectId, initial)

        if (!cronExpression.isNullOrBlank()) {
            timerScheduler?.registerCron(projectId, cronExpression)
        }
        generatePlan(projectId)
        instrumentation.emit(AgentEvents.projectCreated(projectId, title, priority.name))
        logger.info { "Project created: $projectId ('$title')" }
        return projectId
    }

    fun applyEventExternal(projectId: String, event: ProjectEvent) {
        applyEvent(projectId, event)
    }

    private fun generatePlan(projectId: String) {
        val state = states[projectId] ?: return
        val plan = planner.generatePlan(state.project)
        applyEvent(projectId, ProjectEvent.PlanGenerated(projectId, plan))
    }

    private fun applyEvent(projectId: String, event: ProjectEvent): ProjectState? {
        val state = states[projectId] ?: return null
        val oldStatus = state.project.status
        val (newState, commands) = ProjectStateMachine.transition(state, event)
        states[projectId] = newState
        store.eventLog(projectId).append(event)

        if (oldStatus != newState.project.status) {
            instrumentation.emit(AgentEvents.projectStatusChanged(projectId, oldStatus.name, newState.project.status.name))
        }
        when (event) {
            is ProjectEvent.StepStarted -> {
                val step = newState.project.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.projectStepStarted(projectId, event.stepId, step?.description.orEmpty())
                )
            }

            is ProjectEvent.StepAcceptancePassed -> {
                val step = newState.project.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.projectStepCompleted(projectId, event.stepId, true, step?.attempts ?: 1)
                )
            }

            is ProjectEvent.StepAcceptanceFailed -> {
                val step = newState.project.plan.steps.firstOrNull { it.id == event.stepId }
                if (step?.status == StepStatus.FAILED) {
                    instrumentation.emit(
                        AgentEvents.projectStepCompleted(projectId, event.stepId, false, step.attempts)
                    )
                }
            }

            is ProjectEvent.StepBlocked -> {
                instrumentation.emit(
                    AgentEvents.projectBlocked(projectId, event.stepId, event.waitCondition.type.name)
                )
            }

            is ProjectEvent.WorkCycleCompleted -> {
                instrumentation.emit(
                    AgentEvents.projectWorkCycleCompleted(projectId, event.stepId, event.actionsExecuted)
                )
            }

            else -> {}
        }

        dispatchCommands(commands)
        if (newState.isTerminal()) {
            instrumentation.emit(AgentEvents.projectCompleted(projectId))
            logger.info { "Project $projectId is terminal (${newState.project.status})" }
        }
        return newState
    }

    private fun dispatchCommands(commands: List<ProjectCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is ProjectCommand.EmitWorkReady -> signalEmitter(cmd.signal)
                is ProjectCommand.ScheduleWakeTimer -> timerScheduler?.register(cmd.projectId, cmd.wakeAt)
                is ProjectCommand.CancelWakeTimer -> timerScheduler?.cancel(cmd.projectId)
                is ProjectCommand.RegisterWaitCondition -> {
                    if (cmd.condition.type != WaitConditionType.TIMER) {
                        waitConditionMonitor?.register(cmd.projectId, cmd.stepId, cmd.condition)
                    }
                }

                is ProjectCommand.ClearWaitCondition -> waitConditionMonitor?.unregister(cmd.projectId, cmd.stepId)
                is ProjectCommand.PersistProject -> {
                    val state = states[cmd.projectId] ?: continue
                    persistState(cmd.projectId, state)
                }

                is ProjectCommand.NotifyUser -> {
                    logger.info { "User notification (${cmd.projectId}): ${cmd.message}" }
                }
            }
        }
    }

    private fun restoreProjects() {
        pruneExpiredCompletedProjects()
        for (projectId in store.scanProjects()) {
            try {
                val state = store.loadProject(projectId) ?: continue
                states[projectId] = state
                restoreSchedules(state)
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to restore project $projectId" }
            }
        }
    }

    private fun restoreSchedules(state: ProjectState) {
        val project = state.project
        if (!project.cronExpression.isNullOrBlank()) {
            timerScheduler?.registerCron(state.id, project.cronExpression)
        }
        if (project.status == ProjectStatus.SUSPENDED && project.suspendedUntil != null) {
            timerScheduler?.register(state.id, project.suspendedUntil)
        }
        project.plan.steps
            .filter { it.status == StepStatus.BLOCKED && it.waitCondition != null }
            .forEach { step ->
                val condition = step.waitCondition ?: return@forEach
                when (condition.type) {
                    WaitConditionType.TIMER -> {
                        val wakeAt = condition.timeoutAt ?: condition.params["wake_at"]?.let(Instant::parse)
                        if (wakeAt != null) {
                            timerScheduler?.register(state.id, wakeAt)
                        }
                    }

                    else -> waitConditionMonitor?.register(state.id, step.id, condition)
                }
            }
    }

    private fun onTimerWake(projectId: String, scheduledAtMs: Long) {
        val state = states[projectId] ?: return
        val scheduledAt = Instant.ofEpochMilli(scheduledAtMs)
        if (state.project.status == ProjectStatus.SUSPENDED) {
            val resumeAt = state.project.suspendedUntil
            if (resumeAt != null && resumeAt.toEpochMilli() <= scheduledAtMs) {
                applyEvent(projectId, ProjectEvent.Resumed(projectId, scheduledAt))
            }
        }

        val timerSteps = state.project.plan.steps.filter { step ->
            step.status == StepStatus.BLOCKED &&
                step.waitCondition?.type == WaitConditionType.TIMER &&
                (step.waitCondition.timeoutAt?.let { it.toEpochMilli() <= scheduledAtMs }
                    ?: step.waitCondition.params["wake_at"]?.let { Instant.parse(it).toEpochMilli() <= scheduledAtMs }
                    ?: false)
        }
        timerSteps.forEach { step ->
            applyEvent(
                projectId,
                ProjectEvent.WaitConditionSatisfied(projectId, step.id, "timer")
            )
        }
    }

    private fun onWaitConditionSatisfied(projectId: String, stepId: String, conditionType: String) {
        applyEvent(projectId, ProjectEvent.WaitConditionSatisfied(projectId, stepId, conditionType))
    }

    private fun writeWorkspaceCycleArtifacts(state: ProjectState, session: ProjectExecutionSession) {
        val summary = session.lastResultSummary.ifBlank { "Cycle completed." }
        try {
            ProjectContextLoader.writeContext(state, session.stepId, summary)
            store.appendScratchEntry(
                state.id,
                buildString {
                    appendLine("## ${Instant.now()}")
                    appendLine("- step_id: ${session.stepId}")
                    appendLine("- root_input_id: ${session.rootInputId}")
                    appendLine("- actions_executed: ${session.actionCount}")
                    appendLine("- result: $summary")
                }.trim()
            )
            store.writeArtifact(
                projectId = state.id,
                stepId = session.stepId,
                artifactName = "cycle-${state.eventCount}.md",
                content = buildString {
                    appendLine("# Project Cycle")
                    appendLine()
                    appendLine("- project_id: ${state.id}")
                    appendLine("- step_id: ${session.stepId}")
                    appendLine("- root_input_id: ${session.rootInputId}")
                    appendLine("- actions_executed: ${session.actionCount}")
                    appendLine("- wake_reason: ${session.requeueReason ?: "n/a"}")
                    appendLine()
                    appendLine("## Summary")
                    appendLine(summary)
                }
            )
            applyEvent(state.id, ProjectEvent.ContextUpdated(state.id, 2, "workspace_cycle_written"))
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to write workspace cycle artifacts for project=${state.id}" }
        }
    }

    private fun persistState(projectId: String, state: ProjectState) {
        store.saveProjectState(projectId, state)
        if (state.eventCount % config.snapshotEveryNEvents == 0) {
            store.saveSnapshot(projectId, state)
        }
    }

    private fun pruneExpiredCompletedProjects() {
        if (!Files.isDirectory(config.workspaceRoot)) return
        val cutoff = Instant.now().minus(config.completedProjectRetentionDays.toLong(), ChronoUnit.DAYS)
        for (projectId in store.scanProjects()) {
            val state = runCatching { store.loadProject(projectId) }.getOrNull() ?: continue
            if (state.project.status != ProjectStatus.COMPLETED) continue
            val completedAt = state.project.plan.steps.mapNotNull { it.completedAt }.maxOrNull() ?: state.project.lastWorkedAt
            if (completedAt != null && completedAt.isBefore(cutoff)) {
                runCatching { store.deleteProject(projectId) }
                    .onFailure { ex -> logger.warn(ex) { "Failed to prune completed project $projectId" } }
            }
        }
    }

    private fun buildProjectRootInputId(projectId: String, stepId: String): String =
        "project:$projectId:$stepId:${RootInputIds.next()}"

    private fun generateProjectId(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
        val suffix = System.currentTimeMillis().toString(36).takeLast(6)
        return "$slug-$suffix"
    }
}
