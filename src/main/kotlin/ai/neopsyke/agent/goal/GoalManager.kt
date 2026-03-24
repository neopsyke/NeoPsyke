package ai.neopsyke.agent.goal

import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import ai.neopsyke.agent.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import ai.neopsyke.agent.id.GoalCommitment
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import java.nio.file.Files
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private data class GoalRunSession(
    val goalId: String,
    val stepId: String,
    val rootInputId: String,
    val createdAt: Instant = Instant.now(),
    val actionCount: Int = 0,
    val lastResultSummary: String = "",
    val allowFollowUp: Boolean = true,
    val requeueReason: String? = null,
)

class GoalManager(
    private val config: GoalConfig,
    private val store: GoalStore,
    private val planner: GoalPlanner = DeterministicGoalPlanner(),
    private val verifier: GoalStepVerifier = DeterministicGoalStepVerifier(),
    private val asyncOperationRegistry: AsyncOperationRegistry = AsyncOperationRegistry.empty(),
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val cueEmitter: (GoalRuntimeCue) -> Unit = {},
) : GoalsGateway {
    private val states = ConcurrentHashMap<String, GoalState>()
    private val sessionsByRootInputId = ConcurrentHashMap<String, GoalRunSession>()

    @Volatile
    private var activeGoalsSnapshot: List<GoalCommitment> = emptyList()

    @Volatile
    private var pendingWorkSummarySnapshot: String = ""

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
            asyncOperationRegistry = asyncOperationRegistry,
            onConditionSatisfied = { goalId, stepId, resolution ->
                onWaitConditionSatisfied(goalId, stepId, resolution)
            },
            onConditionTimedOut = { goalId, stepId ->
                applyEvent(goalId, GoalEvent.WaitConditionTimedOut(goalId, stepId))
            },
        ).also { it.start(scope) }

        restoreGoals()
        refreshAmbientSnapshots()
    }

    override fun stop() {
        timerScheduler?.stop()
        timerScheduler = null
        waitConditionMonitor?.stop()
        waitConditionMonitor = null
    }

    override fun nextWorkFromCue(cue: GoalRuntimeCue): GoalRunActivation? {
        val state = states[cue.goalId] ?: return null
        val step = state.nextRunnableStep() ?: return null
        val startedState = if (step.status == StepStatus.READY) {
            applyEvent(state.id, GoalEvent.StepStarted(state.id, step.id)) ?: state
        } else {
            state
        }
        val startedStep = startedState.goal.plan.steps.firstOrNull { it.id == step.id } ?: step
        val rootInputId = buildGoalRootInputId(state.id, step.id)
        sessionsByRootInputId[rootInputId] = GoalRunSession(
            goalId = state.id,
            stepId = step.id,
            rootInputId = rootInputId,
        )
        instrumentation.emit(AgentEvents.goalWakeUp(state.id, "work_ready", cue.reason))
        return GoalContextLoader.buildWorkUnit(
            state = startedState,
            step = startedStep,
            rootInputId = rootInputId,
            wakeReason = cue.reason,
        )
    }

    override fun pendingWorkSummary(): String {
        return pendingWorkSummarySnapshot
    }

    override fun onActionExecuted(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {
        if (action.origin.source != OriginSource.GOAL) return
        val rootInputId = action.rootInputId ?: return
        val session = sessionsByRootInputId[rootInputId] ?: return
        val actionCount = session.actionCount + 1
        sessionsByRootInputId[rootInputId] = session.copy(
            actionCount = actionCount,
            lastResultSummary = outcome.statusSummary,
            allowFollowUp = actionCount < config.actionsPerCycle,
        )

        val afterAction = applyEvent(
            session.goalId,
            GoalEvent.StepActionExecuted(session.goalId, session.stepId, outcome.statusSummary)
        ) ?: return
        val step = afterAction.goal.plan.steps.firstOrNull { it.id == session.stepId } ?: return
        if (outcome.executionStatus == ai.neopsyke.agent.model.ActionExecutionStatus.WAITING && outcome.asyncWait == null) {
            val message =
                "Goal action returned WAITING without async handles; treating as retryable contract violation."
            logger.warn { "Goal async wait contract violation: goal=${session.goalId}, step=${session.stepId}, action=${action.type.id}" }
            instrumentation.emit(AgentEvents.warning(message))
            sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                ?.copy(allowFollowUp = false, requeueReason = null)
                ?: return
            applyEvent(
                session.goalId,
                GoalEvent.StepAcceptanceFailed(
                    goalId = session.goalId,
                    stepId = session.stepId,
                    reason = message,
                )
            )
            return
        }
        if (outcome.waiting) {
            sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                ?.copy(allowFollowUp = false, requeueReason = null)
                ?: return
            applyEvent(
                session.goalId,
                GoalEvent.StepBlocked(
                    goalId = session.goalId,
                    stepId = session.stepId,
                    waitCondition = buildWaitConditionForAsyncOutcome(outcome),
                )
            )
            return
        }
        val verification = verifier.evaluate(afterAction.goal, step, action, outcome, observedEvidence)

        when (verification.verdict) {
            GoalStepVerdict.PASS -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(session.goalId, GoalEvent.StepAcceptancePassed(session.goalId, session.stepId))
            }

            GoalStepVerdict.RETRY -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(
                    session.goalId,
                    GoalEvent.StepAcceptanceFailed(
                        session.goalId,
                        session.stepId,
                        verification.reason.ifBlank { outcome.statusSummary },
                    )
                )
            }

            GoalStepVerdict.BLOCK -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                val waitCondition = verification.waitCondition ?: WaitCondition(
                    type = WaitConditionType.CONDITION_CHECK,
                    params = emptyMap(),
                    registeredAt = Instant.now(),
                )
                applyEvent(
                    session.goalId,
                    GoalEvent.StepBlocked(session.goalId, session.stepId, waitCondition)
                )
            }

            GoalStepVerdict.CONTINUE -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(
                        allowFollowUp = actionCount < config.actionsPerCycle,
                        requeueReason = "step_continue"
                    )
                    ?: return
            }

            GoalStepVerdict.FAIL -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(
                    session.goalId,
                    GoalEvent.StepAcceptanceFailed(
                        session.goalId,
                        session.stepId,
                        verification.reason.ifBlank { outcome.statusSummary },
                    )
                )
            }
        }
    }

    override fun onActionBlocked(action: PendingAction, reason: String, reasonCode: String?, source: String) {
        if (action.origin.source != OriginSource.GOAL) return
        val rootInputId = action.rootInputId ?: return
        val session = sessionsByRootInputId[rootInputId] ?: return
        sessionsByRootInputId[rootInputId] = session.copy(allowFollowUp = false, requeueReason = null)
        applyEvent(
            session.goalId,
            GoalEvent.StepAcceptanceFailed(
                goalId = session.goalId,
                stepId = session.stepId,
                reason = listOfNotNull(reasonCode, source, reason).joinToString(": "),
            )
        )
    }

    override fun allowFollowUp(action: PendingAction): Boolean {
        if (action.origin.source != OriginSource.GOAL) return true
        val session = action.rootInputId?.let { sessionsByRootInputId[it] } ?: return true
        return session.allowFollowUp && session.actionCount < config.actionsPerCycle
    }

    override fun finalizeGoalCycle(rootInputId: String) {
        val session = sessionsByRootInputId.remove(rootInputId) ?: return
        val state = states[session.goalId] ?: return

        writeWorkspaceCycleArtifacts(state, session)
        applyEvent(
            session.goalId,
            GoalEvent.WorkCycleCompleted(
                goalId = session.goalId,
                stepId = session.stepId,
                actionsExecuted = session.actionCount,
            )
        )

        if (!session.requeueReason.isNullOrBlank()) {
            val refreshed = states[session.goalId] ?: return
            val runnable = refreshed.nextRunnableStep() ?: return
            cueEmitter(
                GoalRuntimeCue(
                    goalId = refreshed.id,
                    stepId = runnable.id,
                    reason = session.requeueReason,
                )
            )
        }
    }

    override fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int =
        waitConditionMonitor?.notifyAsyncEvent(event) ?: 0

    override fun executeOperation(request: GoalOperationRequest): GoalOperationResult =
        when (request.operation) {
            GoalOperation.CREATE -> {
                val instruction = request.instruction?.trim().orEmpty()
                val cronExpression = request.cronExpression?.trim().orEmpty()
                if (instruction.isBlank()) {
                    GoalOperationResult(false, "Goal creation requires an instruction.")
                } else if (cronExpression.isNotBlank() && !isValidCronExpression(cronExpression)) {
                    GoalOperationResult(false, "Goal creation requires a valid 5-field cron_expression.")
                } else {
                    val goalId = createGoal(
                        instruction = instruction,
                        title = request.title?.takeIf { it.isNotBlank() } ?: instruction.take(60),
                        priority = request.priority ?: GoalPriority.MEDIUM,
                        completionCriteria = request.completionCriteria ?: "User confirms the goal is met.",
                        cronExpression = cronExpression.ifBlank { null },
                    )
                    if (goalId.isBlank()) {
                        GoalOperationResult(false, "Goal creation was rejected.")
                    } else {
                        val scheduleSummary = cronExpression.takeIf { it.isNotBlank() }
                            ?.let { " Recurs on cron '$it'." }
                            .orEmpty()
                        GoalOperationResult(
                            true,
                            "Goal created: ${request.title?.takeIf { it.isNotBlank() } ?: instruction.take(60)}.$scheduleSummary",
                            goalId
                        )
                    }
                }
            }

            GoalOperation.STATUS -> {
                val goalId = request.goalId.orEmpty()
                val state = states[goalId]
                if (state == null) {
                    GoalOperationResult(false, "Goal not found.")
                } else {
                    val step = state.nextRunnableStep()
                    GoalOperationResult(
                        true,
                        "status=${state.goal.status} next_step=${step?.description ?: "none"}",
                        goalId = goalId,
                    )
                }
            }

            GoalOperation.LIST -> {
                val summaries = allGoals()
                val message = if (summaries.isEmpty()) {
                    "No goals."
                } else {
                    summaries.joinToString("\n") { summary ->
                        "${summary.goalId}: ${summary.title} (${summary.status})"
                    }
                }
                GoalOperationResult(true, message)
            }

            GoalOperation.PAUSE -> {
                val goalId = request.goalId.orEmpty()
                if (!states.containsKey(goalId)) {
                    GoalOperationResult(false, "Goal not found.")
                } else {
                    applyEvent(
                        goalId,
                        GoalEvent.Suspended(
                            goalId = goalId,
                            reason = request.reason ?: "Paused by user",
                        )
                    )
                    GoalOperationResult(true, "Goal paused.", goalId)
                }
            }

            GoalOperation.RESUME -> {
                val goalId = request.goalId.orEmpty()
                if (!states.containsKey(goalId)) {
                    GoalOperationResult(false, "Goal not found.")
                } else {
                    applyEvent(goalId, GoalEvent.Resumed(goalId))
                    GoalOperationResult(true, "Goal resumed.", goalId)
                }
            }

            GoalOperation.REPRIORITIZE -> {
                val goalId = request.goalId.orEmpty()
                val state = states[goalId]
                val newPriority = request.priority
                if (state == null || newPriority == null) {
                    GoalOperationResult(false, "Goal reprioritize requires goalId and priority.")
                } else {
                    applyEvent(goalId, GoalEvent.PriorityChanged(goalId, newPriority))
                    GoalOperationResult(true, "Goal priority updated to $newPriority.", goalId)
                }
            }

            GoalOperation.COMPLETE -> {
                val goalId = request.goalId.orEmpty()
                if (!states.containsKey(goalId)) {
                    GoalOperationResult(false, "Goal not found.")
                } else {
                    applyEvent(goalId, GoalEvent.Completed(goalId))
                    GoalOperationResult(true, "Goal marked completed.", goalId)
                }
            }

            GoalOperation.DELETE -> {
                val goalId = request.goalId.orEmpty()
                if (goalId.isBlank()) {
                    GoalOperationResult(false, "Goal delete requires goalId.")
                } else if (!deleteGoalState(goalId)) {
                    GoalOperationResult(false, "Goal not found.")
                } else {
                    GoalOperationResult(true, "Goal deleted.", goalId)
                }
            }

            GoalOperation.DELETE_ALL -> {
                val deletedCount = deleteAllGoalStates()
                val message = if (deletedCount == 0) {
                    "No goals to delete."
                } else {
                    "Deleted $deletedCount goals."
                }
                GoalOperationResult(true, message)
            }

            GoalOperation.REVISE_PLAN -> {
                val goalId = request.goalId.orEmpty()
                val state = states[goalId]
                if (state == null) {
                    GoalOperationResult(false, "Goal not found.")
                } else {
                    val plan = planner.generatePlan(state.goal)
                    applyEvent(
                        goalId,
                        GoalEvent.PlanRevised(
                            goalId = goalId,
                            plan = plan,
                            reason = request.reason ?: "Revised by user request",
                        )
                    )
                    GoalOperationResult(true, "Goal plan revised.", goalId)
                }
            }
        }

    override fun allGoals(): List<GoalTier1Summary> =
        states.values
            .sortedByDescending { it.goal.priority.ordinal }
            .map { GoalContextLoader.tier1Summary(it) }

    override fun goalStatus(goalId: String): GoalState? = states[goalId]

    override fun activeGoals(): List<GoalCommitment> = activeGoalsSnapshot

    fun createGoal(
        instruction: String,
        title: String = instruction.take(60),
        priority: GoalPriority = GoalPriority.MEDIUM,
        completionCriteria: String = "User confirms the goal is met.",
        cronExpression: String? = null,
    ): String {
        val activeCount = states.values.count { !it.isTerminal() }
        if (activeCount >= config.maxActiveGoals) {
            logger.warn { "Max active goals reached (${config.maxActiveGoals}), rejecting creation" }
            return ""
        }

        val goalId = generateGoalId(title)
        val workspacePath = store.createWorkspace(goalId)
        val created = GoalEvent.Created(
            goalId = goalId,
            title = title,
            instruction = instruction,
            priority = priority,
            completionCriteria = completionCriteria,
        )
        val initial = GoalStateMachine.initialState(created, workspacePath).let { state ->
            if (cronExpression.isNullOrBlank()) {
                state
            } else {
                state.copy(goal = state.goal.copy(cronExpression = cronExpression))
            }
        }
        states[goalId] = initial
        refreshAmbientSnapshots()
        store.goalEventLog(goalId).append(created)
        persistState(goalId, initial)

        if (!cronExpression.isNullOrBlank()) {
            timerScheduler?.registerCron(goalId, cronExpression)
        }
        generatePlan(goalId)
        instrumentation.emit(AgentEvents.goalCreated(goalId, title, priority.name))
        logger.info { "Goal created: $goalId ('$title')" }
        return goalId
    }

    fun applyEventExternal(goalId: String, event: GoalEvent) {
        applyEvent(goalId, event)
    }

    private fun generatePlan(goalId: String) {
        val state = states[goalId] ?: return
        val plan = planner.generatePlan(state.goal)
        applyEvent(goalId, GoalEvent.PlanGenerated(goalId, plan))
    }

    private fun applyEvent(goalId: String, event: GoalEvent): GoalState? {
        val state = states[goalId] ?: return null
        val oldStatus = state.goal.status
        val (newState, commands) = GoalStateMachine.transition(state, event)
        states[goalId] = newState
        refreshAmbientSnapshots()
        store.goalEventLog(goalId).append(event)

        if (oldStatus != newState.goal.status) {
            instrumentation.emit(AgentEvents.goalStatusChanged(goalId, oldStatus.name, newState.goal.status.name))
        }
        when (event) {
            is GoalEvent.StepStarted -> {
                val step = newState.goal.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.goalStepStarted(goalId, event.stepId, step?.description.orEmpty())
                )
            }

            is GoalEvent.StepAcceptancePassed -> {
                val step = newState.goal.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.goalStepCompleted(goalId, event.stepId, true, step?.attempts ?: 1)
                )
            }

            is GoalEvent.StepAcceptanceFailed -> {
                val step = newState.goal.plan.steps.firstOrNull { it.id == event.stepId }
                if (step?.status == StepStatus.FAILED) {
                    instrumentation.emit(
                        AgentEvents.goalStepCompleted(goalId, event.stepId, false, step.attempts)
                    )
                }
            }

            is GoalEvent.StepBlocked -> {
                instrumentation.emit(
                    AgentEvents.goalBlocked(goalId, event.stepId, event.waitCondition.type.name)
                )
            }

            is GoalEvent.WorkCycleCompleted -> {
                instrumentation.emit(
                    AgentEvents.goalWorkCycleCompleted(goalId, event.stepId, event.actionsExecuted)
                )
            }

            else -> {}
        }

        dispatchCommands(commands)
        if (newState.isTerminal()) {
            instrumentation.emit(AgentEvents.goalCompleted(goalId))
            logger.info { "Goal $goalId is terminal (${newState.goal.status})" }
        }
        return newState
    }

    private fun dispatchCommands(commands: List<GoalCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is GoalCommand.EmitWorkReady -> cueEmitter(cmd.cue)
                is GoalCommand.ScheduleWakeTimer -> timerScheduler?.register(cmd.goalId, cmd.wakeAt)
                is GoalCommand.CancelWakeTimer -> timerScheduler?.cancel(cmd.goalId)
                is GoalCommand.RegisterWaitCondition -> {
                    if (cmd.condition.type != WaitConditionType.TIMER) {
                        waitConditionMonitor?.register(cmd.goalId, cmd.stepId, cmd.condition)
                    }
                }

                is GoalCommand.ClearWaitCondition -> waitConditionMonitor?.unregister(cmd.goalId, cmd.stepId)
                is GoalCommand.PersistGoal -> {
                    val state = states[cmd.goalId] ?: continue
                    persistState(cmd.goalId, state)
                }

                is GoalCommand.NotifyUser -> {
                    logger.info { "User notification (${cmd.goalId}): ${cmd.message}" }
                }
            }
        }
    }

    private fun restoreGoals() {
        pruneExpiredCompletedGoals()
        for (goalId in store.scanGoals()) {
            try {
                val state = store.loadGoal(goalId) ?: continue
                states[goalId] = state
                restoreSchedules(state)
                emitRestoredWorkReady(state)
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to restore goal $goalId" }
            }
        }
        refreshAmbientSnapshots()
    }

    private fun emitRestoredWorkReady(state: GoalState) {
        if (state.isTerminal()) return
        val step = state.nextRunnableStep() ?: return
        cueEmitter(
            GoalRuntimeCue(
                goalId = state.id,
                stepId = step.id,
                reason = "goal_restored_work_ready",
            )
        )
    }

    private fun restoreSchedules(state: GoalState) {
        val goal = state.goal
        if (!goal.cronExpression.isNullOrBlank()) {
            timerScheduler?.registerCron(state.id, goal.cronExpression)
        }
        if (goal.status == GoalStatus.SUSPENDED && goal.suspendedUntil != null) {
            timerScheduler?.register(state.id, goal.suspendedUntil)
        }
        goal.plan.steps
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

    private fun onTimerWake(goalId: String, scheduledAtMs: Long) {
        val state = states[goalId] ?: return
        val scheduledAt = Instant.ofEpochMilli(scheduledAtMs)
        if (!state.goal.cronExpression.isNullOrBlank() && state.goal.status in setOf(GoalStatus.COMPLETED, GoalStatus.FAILED)) {
            applyEvent(goalId, GoalEvent.CronCycleStarted(goalId, scheduledAt))
        }
        if (state.goal.status == GoalStatus.SUSPENDED) {
            val resumeAt = state.goal.suspendedUntil
            if (resumeAt != null && resumeAt.toEpochMilli() <= scheduledAtMs) {
                applyEvent(goalId, GoalEvent.Resumed(goalId, scheduledAt))
            }
        }

        val timerSteps = state.goal.plan.steps.filter { step ->
            step.status == StepStatus.BLOCKED &&
                step.waitCondition?.type == WaitConditionType.TIMER &&
                (step.waitCondition.timeoutAt?.let { it.toEpochMilli() <= scheduledAtMs }
                    ?: step.waitCondition.params["wake_at"]?.let { Instant.parse(it).toEpochMilli() <= scheduledAtMs }
                    ?: false)
        }
        timerSteps.forEach { step ->
            applyEvent(
                goalId,
                GoalEvent.WaitConditionSatisfied(goalId, step.id, "timer")
            )
            }
    }

    private fun isValidCronExpression(expression: String): Boolean =
        CronParser.nextAfter(expression, ZonedDateTime.now()) != null

    private fun onWaitConditionSatisfied(
        goalId: String,
        stepId: String,
        resolution: WaitConditionResolution,
    ) {
        applyEvent(
            goalId,
            GoalEvent.WaitConditionSatisfied(
                goalId = goalId,
                stepId = stepId,
                conditionType = resolution.conditionType,
                resolutionSummary = resolution.summary.ifBlank { null },
                resolutionStatus = resolution.status,
            )
        )
    }

    private fun writeWorkspaceCycleArtifacts(state: GoalState, session: GoalRunSession) {
        val summary = session.lastResultSummary.ifBlank { "Cycle completed." }
        try {
            GoalContextLoader.writeContext(state, session.stepId, summary)
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
                goalId = state.id,
                stepId = session.stepId,
                artifactName = "cycle-${state.eventCount}.md",
                content = buildString {
                    appendLine("# Goal Cycle")
                    appendLine()
                    appendLine("- goal_id: ${state.id}")
                    appendLine("- step_id: ${session.stepId}")
                    appendLine("- root_input_id: ${session.rootInputId}")
                    appendLine("- actions_executed: ${session.actionCount}")
                    appendLine("- wake_reason: ${session.requeueReason ?: "n/a"}")
                    appendLine()
                    appendLine("## Summary")
                    appendLine(summary)
                }
            )
            applyEvent(state.id, GoalEvent.ContextUpdated(state.id, 2, "workspace_cycle_written"))
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to write workspace cycle artifacts for goal=${state.id}" }
        }
    }

    private fun persistState(goalId: String, state: GoalState) {
        store.saveGoalState(goalId, state)
        if (state.eventCount % config.snapshotEveryNEvents == 0) {
            store.saveSnapshot(goalId, state)
        }
    }

    private fun deleteAllGoalStates(): Int =
        states.keys.toList().count { goalId -> deleteGoalState(goalId) }

    private fun deleteGoalState(goalId: String): Boolean {
        val state = states[goalId] ?: return false
        runCatching { store.deleteGoal(goalId) }
            .onFailure { ex ->
                logger.warn(ex) { "Failed to delete goal workspace for goal=$goalId" }
                return false
            }
        timerScheduler?.cancel(goalId)
        state.goal.plan.steps.forEach { step ->
            waitConditionMonitor?.unregister(goalId, step.id)
        }
        states.remove(goalId)
        sessionsByRootInputId.entries.removeIf { (_, session) -> session.goalId == goalId }
        refreshAmbientSnapshots()
        return true
    }

    private fun pruneExpiredCompletedGoals() {
        if (!Files.isDirectory(config.workspaceRoot)) return
        val cutoff = Instant.now().minus(config.completedGoalRetentionDays.toLong(), ChronoUnit.DAYS)
        for (goalId in store.scanGoals()) {
            val state = runCatching { store.loadGoal(goalId) }.getOrNull() ?: continue
            if (state.goal.status != GoalStatus.COMPLETED) continue
            val completedAt = state.goal.plan.steps.mapNotNull { it.completedAt }.maxOrNull() ?: state.goal.lastWorkedAt
            if (completedAt != null && completedAt.isBefore(cutoff)) {
                runCatching { store.deleteGoal(goalId) }
                    .onFailure { ex -> logger.warn(ex) { "Failed to prune completed goal $goalId" } }
            }
        }
    }

    private fun buildGoalRootInputId(goalId: String, stepId: String): String =
        "goal:$goalId:$stepId:${RootInputIds.next()}"

    private fun refreshAmbientSnapshots() {
        val active = states.values
            .filter { !it.isTerminal() }
            .sortedByDescending { it.goal.priority.ordinal }

        activeGoalsSnapshot =
            active.map { state ->
                GoalCommitment(
                    id = state.id,
                    instruction = state.goal.instruction,
                    lastActedAt = state.goal.lastWorkedAt,
                )
            }

        pendingWorkSummarySnapshot = if (active.isEmpty()) {
            ""
        } else {
            active.joinToString("\n") { state ->
                val step = state.nextRunnableStep()
                val stepInfo = step?.let { "${it.status.name.lowercase()}: ${it.description}" } ?: "no runnable steps"
                "- [${state.goal.priority}] ${state.goal.title} ($stepInfo)"
            }
        }
    }

    private fun buildWaitConditionForAsyncOutcome(outcome: ActionOutcome): WaitCondition {
        val wait = requireNotNull(outcome.asyncWait) {
            "Goal async wait outcomes must provide asyncWait handles."
        }
        return WaitCondition(
            type = WaitConditionType.ASYNC_OPERATION,
            params = emptyMap(),
            registeredAt = Instant.now(),
            timeoutAt = wait.handles.mapNotNull { it.timeoutAt }.minOrNull(),
            onTimeout = TimeoutAction.FAIL,
            asyncWait = wait,
        )
    }

    private fun generateGoalId(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
        val suffix = System.currentTimeMillis().toString(36).takeLast(6)
        return "$slug-$suffix"
    }
}
