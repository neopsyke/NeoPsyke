package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import psyke.agent.cortex.sensory.ProjectSignal
import psyke.agent.cortex.sensory.Signal
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Central coordinator for the project subsystem.
 *
 * Manages project lifecycle, dispatches state-machine commands,
 * and provides work units for the Ego loop.
 *
 * Fully optional: only instantiated when `config.projects.enabled == true`.
 */
class ProjectManager(
    private val config: ProjectConfig,
    private val store: ProjectStore,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val signalEmitter: (Signal) -> Unit = {},
) {
    private val states = mutableMapOf<String, ProjectState>()

    /**
     * R3: Per-project action counter for the current work cycle.
     * Tracks how many work cycles have been dispatched since the last budget reset.
     * Hidden from the Ego — enforced entirely inside pickWork().
     */
    private val cycleActionsCount = mutableMapOf<String, Int>()
    private lateinit var timerScheduler: TimerScheduler
    private lateinit var waitConditionMonitor: WaitConditionMonitor

    fun start(scope: CoroutineScope) {
        timerScheduler = TimerScheduler(
            resolutionMs = config.timerResolutionMs,
            onWakeUp = { projectId, scheduledAtMs ->
                signalEmitter(ProjectSignal.ScheduledWakeUp(projectId, scheduledAtMs))
            },
        )
        waitConditionMonitor = WaitConditionMonitor(
            checkIntervalMs = config.conditionCheckIntervalMs,
            onConditionSatisfied = { projectId, stepId ->
                signalEmitter(
                    ProjectSignal.WaitConditionMet(projectId, stepId, "condition_check")
                )
            },
            onConditionTimedOut = { projectId, stepId ->
                applyEvent(projectId, ProjectEvent.WaitConditionTimedOut(projectId, stepId))
            },
        )

        timerScheduler.start(scope)
        waitConditionMonitor.start(scope)

        // Load existing projects from disk
        for (projectId in store.scanProjects()) {
            try {
                val state = store.loadProject(projectId)
                if (state != null && !state.isTerminal()) {
                    states[projectId] = state
                    logger.info { "Loaded project: $projectId (status=${state.project.status})" }
                    // M1: Re-register recurring cron schedule on startup
                    val cron = state.project.cronExpression
                    if (!cron.isNullOrBlank()) {
                        timerScheduler.registerCron(projectId, cron)
                        logger.info { "Cron schedule restored: project=$projectId, expr='$cron'" }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to load project $projectId: ${e.message}" }
            }
        }
    }

    fun stop() {
        if (::timerScheduler.isInitialized) timerScheduler.stop()
        if (::waitConditionMonitor.isInitialized) waitConditionMonitor.stop()
    }

    /**
     * Pick the next unit of work from the highest-priority active project.
     * Called by the Ego when a [ProjectSignal] arrives or when idle.
     */
    fun pickWork(signal: ProjectSignal? = null): ProjectWorkUnit? {
        if (signal != null) {
            val signalType = signal::class.simpleName ?: "Unknown"
            val projectId = when (signal) {
                is ProjectSignal.ScheduledWakeUp -> signal.projectId
                is ProjectSignal.StepCompleted -> signal.projectId
                is ProjectSignal.WaitConditionMet -> signal.projectId
                is ProjectSignal.ProjectCreated -> signal.projectId
            }
            instrumentation.emit(AgentEvents.projectWakeUp(projectId, "pickWork", signalType))
        }

        val targetProject = when (signal) {
            is ProjectSignal.ScheduledWakeUp -> states[signal.projectId]
            is ProjectSignal.StepCompleted -> null // step completed, no immediate work
            is ProjectSignal.WaitConditionMet -> {
                applyEvent(
                    signal.projectId,
                    ProjectEvent.StepUnblocked(signal.projectId, signal.stepId)
                )
                states[signal.projectId]
            }
            is ProjectSignal.ProjectCreated -> states[signal.projectId]
            null -> null
        }

        val state = targetProject ?: states.values
            .filter { it.project.status == ProjectStatus.ACTIVE }
            .sortedByDescending { it.project.priority.ordinal }
            .firstOrNull { it.nextReadyStep() != null }
            ?: return null

        // R3: Enforce action budget — hidden from the Ego.
        // If this project has exhausted its cycle budget, reset the counter (new cycle)
        // and yield so other work can proceed. The project re-enters via the next signal.
        val count = cycleActionsCount[state.id] ?: 0
        if (count >= config.actionsPerCycle) {
            logger.debug { "Project ${state.id} hit action budget ($count/${config.actionsPerCycle}), yielding" }
            cycleActionsCount.remove(state.id)
            return null
        }

        val step = state.nextReadyStep() ?: return null
        return ProjectContextLoader.buildWorkUnit(state, step)
    }

    /**
     * R1 + R3: Called by the Ego at the end of a project work cycle (after runLoop drains).
     *
     * Writes `context.md` (Tier 2 persistence) so the agent retains inter-session state,
     * increments the per-project cycle action counter (budget enforcement), and emits
     * the [ProjectEvent.WorkCycleCompleted] event for observability.
     *
     * This is the end-of-cycle hook — not a per-action callback.
     * The [resultSummary] is the best available description of what happened this cycle;
     * it comes from the step's current notes (set by [onStepResult]) or a generic description.
     */
    fun finalizeWorkCycle(projectId: String, stepId: String) {
        val state = states[projectId] ?: return
        val step = state.project.plan.steps.firstOrNull { it.id == stepId }
        val resultSummary = step?.notes?.takeIf { it.isNotBlank() } ?: "Cycle completed"

        // Increment cycle counter (R3 budget tracking)
        val newCount = (cycleActionsCount[projectId] ?: 0) + 1
        cycleActionsCount[projectId] = newCount

        // R1: Write context.md — this is the core multi-session continuity mechanism
        try {
            ProjectContextLoader.writeContext(state, stepId, resultSummary)
            logger.debug { "context.md written for project $projectId after step $stepId" }
            // M8: Emit ContextUpdated for telemetry
            applyEvent(projectId, ProjectEvent.ContextUpdated(projectId, 2, "context.md written after cycle"))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to write context.md for project $projectId" }
        }

        // Emit WorkCycleCompleted event
        applyEvent(projectId, ProjectEvent.WorkCycleCompleted(projectId, stepId, newCount))
    }

    /**
     * Report the result of a step execution back to the state machine.
     */
    fun onStepResult(projectId: String, stepId: String, success: Boolean, resultSummary: String) {
        applyEvent(projectId, ProjectEvent.StepActionExecuted(projectId, stepId, resultSummary))
        if (success) {
            applyEvent(projectId, ProjectEvent.StepAcceptancePassed(projectId, stepId))
        } else {
            applyEvent(projectId, ProjectEvent.StepAcceptanceFailed(projectId, stepId, resultSummary))
        }
    }

    /**
     * Create a new project from a user instruction.
     */
    fun createProject(
        instruction: String,
        title: String = instruction.take(60),
        priority: ProjectPriority = ProjectPriority.MEDIUM,
        completionCriteria: String = "User confirms the goal is met.",
        cronExpression: String? = null,
    ): String {
        if (states.size >= config.maxActiveProjects) {
            logger.warn { "Max active projects reached (${config.maxActiveProjects}), rejecting creation" }
            return ""
        }

        val projectId = generateProjectId(title)
        store.createWorkspace(projectId)
        val event = ProjectEvent.Created(
            projectId = projectId,
            title = title,
            instruction = instruction,
            priority = priority,
            completionCriteria = completionCriteria,
        )
        val baseState = ProjectStateMachine.initialState(event, store.projectDir(projectId).resolve("workspace"))
        // Attach cronExpression to the project if provided
        val state = if (!cronExpression.isNullOrBlank()) {
            baseState.copy(project = baseState.project.copy(cronExpression = cronExpression))
        } else {
            baseState
        }
        states[projectId] = state
        store.eventLog(projectId).append(event)

        applyEvent(projectId, event)

        // M1: Register cron schedule if provided
        if (!cronExpression.isNullOrBlank() && ::timerScheduler.isInitialized) {
            timerScheduler.registerCron(projectId, cronExpression)
            logger.info { "Cron schedule registered: project=$projectId, expr='$cronExpression'" }
        }

        signalEmitter(ProjectSignal.ProjectCreated(projectId))
        instrumentation.emit(AgentEvents.projectCreated(projectId, title, priority.name))
        logger.info { "Project created: $projectId ('$title')" }
        return projectId
    }

    /**
     * Compact summary of pending project work, for inclusion in planner context
     * when the Ego is processing Id impulses.
     */
    fun pendingWorkSummary(): String {
        val active = states.values.filter { it.project.status == ProjectStatus.ACTIVE }
        if (active.isEmpty()) return ""
        return active.joinToString("\n") { state ->
            val step = state.nextReadyStep()
            val stepInfo = step?.let { "next: ${it.description}" } ?: "no ready steps"
            "- [${state.project.priority}] ${state.project.title} ($stepInfo)"
        }
    }

    fun hasReadyWork(): Boolean =
        states.values.any { it.project.status == ProjectStatus.ACTIVE && it.nextReadyStep() != null }

    fun projectStatus(projectId: String): ProjectState? = states[projectId]

    fun allProjects(): List<ProjectTier1Summary> =
        states.values.map { ProjectContextLoader.tier1Summary(it) }

    /**
     * Apply an event from outside the ProjectManager (e.g. from the Ego).
     * Used when the Ego needs to notify the state machine about step lifecycle transitions.
     */
    fun applyEventExternal(projectId: String, event: ProjectEvent) {
        applyEvent(projectId, event)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun applyEvent(projectId: String, event: ProjectEvent) {
        val state = states[projectId] ?: return
        val oldStatus = state.project.status
        val (newState, commands) = ProjectStateMachine.transition(state, event)
        states[projectId] = newState
        store.eventLog(projectId).append(event)

        if (newState.eventCount % config.snapshotEveryNEvents == 0) {
            store.saveSnapshot(projectId, newState)
        }

        // Emit instrumentation events based on what happened
        val newStatus = newState.project.status
        if (oldStatus != newStatus) {
            instrumentation.emit(AgentEvents.projectStatusChanged(projectId, oldStatus.name, newStatus.name))
        }
        when (event) {
            is ProjectEvent.StepStarted -> {
                val step = newState.project.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.projectStepStarted(projectId, event.stepId, step?.description ?: "")
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
    }

    private fun dispatchCommands(commands: List<ProjectCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is ProjectCommand.EmitSignal -> signalEmitter(cmd.signal)
                is ProjectCommand.ScheduleTimer -> timerScheduler.register(cmd.projectId, cmd.wakeAt)
                is ProjectCommand.CancelTimer -> timerScheduler.cancel(cmd.projectId)
                is ProjectCommand.PersistState -> {
                    val state = states[cmd.projectId]
                    if (state != null) store.saveSnapshot(cmd.projectId, state)
                }
                is ProjectCommand.RequestPlan -> {
                    logger.debug { "Plan requested for project ${cmd.projectId}" }
                    // Plan generation is delegated to the Ego/Planner via signal
                }
                is ProjectCommand.RequestStepExecution -> {
                    logger.debug { "Step execution requested: ${cmd.projectId}/${cmd.stepId}" }
                    // Step execution handled by Ego when it picks up work
                }
                is ProjectCommand.NotifyUser -> {
                    logger.info { "User notification (${cmd.projectId}): ${cmd.message}" }
                    // Notification routed through instrumentation or contact_user action
                }
            }
        }
    }

    private fun generateProjectId(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
        val suffix = System.currentTimeMillis().toString(36).takeLast(6)
        return "$slug-$suffix"
    }
}
