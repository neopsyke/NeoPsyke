package ai.neopsyke.agent.goal

import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import java.nio.file.Path
import java.time.Instant

/**
 * Immutable snapshot of a goal's full state, reconstructable by replaying events.
 */
data class GoalState(
    val goal: Goal,
    val producedKeys: Set<String> = emptySet(),
    val eventCount: Int = 0,
) {
    val id: String get() = goal.id

    fun readySteps(): List<PlanStep> =
        goal.plan.steps.filter { it.status == StepStatus.READY }

    fun nextRunnableStep(): PlanStep? =
        goal.plan.steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: readySteps().firstOrNull()

    fun isTerminal(): Boolean =
        goal.status == GoalStatus.COMPLETED || goal.status == GoalStatus.FAILED
}

/**
 * Pure state machine: no side effects, no I/O.
 * Returns the new state and a list of commands the GoalManager should dispatch.
 */
object GoalStateMachine {

    fun transition(
        state: GoalState,
        event: GoalEvent,
    ): Pair<GoalState, List<GoalCommand>> {
        require(event.goalId == state.id) {
            "Event goalId '${event.goalId}' does not match state id '${state.id}'"
        }
        val commands = mutableListOf<GoalCommand>()
        val newState = when (event) {
            is GoalEvent.Created -> handleCreated(state, event, commands)
            is GoalEvent.PlanGenerated -> handlePlanGenerated(state, event, commands)
            is GoalEvent.PlanRevised -> handlePlanRevised(state, event, commands)
            is GoalEvent.StepStarted -> handleStepStarted(state, event)
            is GoalEvent.StepActionExecuted -> handleStepActionExecuted(state, event)
            is GoalEvent.StepAcceptancePassed -> handleStepAcceptancePassed(state, event, commands)
            is GoalEvent.StepAcceptanceFailed -> handleStepAcceptanceFailed(state, event, commands)
            is GoalEvent.StepBlocked -> handleStepBlocked(state, event, commands)
            is GoalEvent.StepUnblocked -> handleStepUnblocked(state, event, commands)
            is GoalEvent.StepSkipped -> handleStepSkipped(state, event, commands)
            is GoalEvent.WaitConditionRegistered -> handleWaitConditionRegistered(state, event, commands)
            is GoalEvent.WaitConditionSatisfied -> handleWaitConditionSatisfied(state, event, commands)
            is GoalEvent.WaitConditionTimedOut -> handleWaitConditionTimedOut(state, event, commands)
            is GoalEvent.Suspended -> handleSuspended(state, event, commands)
            is GoalEvent.Resumed -> handleResumed(state, event, commands)
            is GoalEvent.CronCycleStarted -> handleCronCycleStarted(state, event, commands)
            is GoalEvent.Completed -> handleCompleted(state, event, commands)
            is GoalEvent.PriorityChanged -> handlePriorityChanged(state, event, commands)
            is GoalEvent.Failed -> handleFailed(state, event, commands)
            is GoalEvent.Updated -> handleUpdated(state, event)
            is GoalEvent.ContextUpdated -> state
            is GoalEvent.WorkCycleCompleted -> handleWorkCycleCompleted(state, event, commands)
        }
        val finalState = newState.copy(eventCount = newState.eventCount + 1)
        return finalState to commands
    }

    /**
     * Create an initial [GoalState] from a [GoalEvent.Created] event.
     */
    fun initialState(event: GoalEvent.Created, workspacePath: Path): GoalState =
        GoalState(
            goal = Goal(
                id = event.goalId,
                title = event.title,
                instruction = event.instruction,
                status = GoalStatus.PLANNING,
                priority = event.priority,
                plan = GoalPlan.empty(),
                completionCriteria = event.completionCriteria,
                createdAt = event.timestamp,
                contactChannel = event.contactChannel,
                workspacePath = workspacePath,
            ),
            eventCount = 1,
        )

    // ── Event handlers ───────────────────────────────────────────────────

    private fun handleCreated(
        state: GoalState,
        event: GoalEvent.Created,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        commands += GoalCommand.PersistGoal(event.goalId)
        return state.copy(
            goal = state.goal.copy(
                title = event.title,
                instruction = event.instruction,
                status = GoalStatus.PLANNING,
                priority = event.priority,
                completionCriteria = event.completionCriteria,
                contactChannel = event.contactChannel,
            )
        )
    }

    private fun handlePlanGenerated(
        state: GoalState,
        event: GoalEvent.PlanGenerated,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val stepsWithReady = promoteReadySteps(event.plan.steps, state.producedKeys)
        val newPlan = event.plan.copy(steps = stepsWithReady)
        val newGoal = state.goal.copy(plan = newPlan, status = GoalStatus.ACTIVE)
        commands += GoalCommand.PersistGoal(state.id)
        if (!shouldDeferRecurringKickoff(state)) {
            emitWorkReadyIfRunnable(state.id, stepsWithReady, "plan_generated", commands)
        }
        return state.copy(goal = newGoal)
    }

    private fun handlePlanRevised(
        state: GoalState,
        event: GoalEvent.PlanRevised,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val stepsWithReady = promoteReadySteps(event.plan.steps, state.producedKeys)
        val newPlan = event.plan.copy(steps = stepsWithReady, revisedAt = event.timestamp)
        val newGoal = state.goal.copy(plan = newPlan, status = GoalStatus.ACTIVE)
        commands += GoalCommand.PersistGoal(state.id)
        if (!shouldDeferRecurringKickoff(state)) {
            emitWorkReadyIfRunnable(state.id, stepsWithReady, "plan_revised", commands)
        }
        return state.copy(goal = newGoal)
    }

    private fun handleUpdated(
        state: GoalState,
        event: GoalEvent.Updated,
    ): GoalState {
        var goal = state.goal
        event.cronExpression?.let { goal = goal.copy(cronExpression = it) }
        event.instruction?.let { goal = goal.copy(instruction = it) }
        event.title?.let { goal = goal.copy(title = it) }
        event.completionCriteria?.let { goal = goal.copy(completionCriteria = it) }
        event.contactChannel?.let { goal = goal.copy(contactChannel = it) }
        return state.copy(goal = goal)
    }

    private fun handleStepStarted(
        state: GoalState,
        event: GoalEvent.StepStarted,
    ): GoalState = updateStep(state, event.stepId) { step ->
        step.copy(status = StepStatus.IN_PROGRESS, lastAttemptAt = event.timestamp)
    }.let { it.copy(goal = it.goal.copy(lastWorkedAt = event.timestamp)) }

    private fun handleStepActionExecuted(
        state: GoalState,
        event: GoalEvent.StepActionExecuted,
    ): GoalState = updateStep(state, event.stepId) { step ->
        step.copy(
            attempts = step.attempts + 1,
            notes = listOf(step.notes.trim(), event.actionResult.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n"),
        )
    }

    private fun handleStepAcceptancePassed(
        state: GoalState,
        event: GoalEvent.StepAcceptancePassed,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val withDone = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.DONE, completedAt = event.timestamp)
        }
        val completedStep = withDone.goal.plan.steps.first { it.id == event.stepId }
        val newProducedKeys = withDone.producedKeys + completedStep.produces
        val stepsPromoted = promoteReadySteps(withDone.goal.plan.steps, newProducedKeys)
        val completed = isGoalComplete(withDone.copy(goal = withDone.goal.copy(plan = withDone.goal.plan.copy(steps = stepsPromoted))))
        val updatedProject = withDone.goal.copy(
            plan = withDone.goal.plan.copy(steps = stepsPromoted),
            status = if (completed) GoalStatus.COMPLETED else withDone.goal.status,
        )
        val newState = withDone.copy(goal = updatedProject, producedKeys = newProducedKeys)

        if (completed) {
            commands += GoalCommand.PersistGoal(state.id)
            return newState
        }

        emitWorkReadyIfRunnable(state.id, stepsPromoted, "step_completed", commands)
        commands += GoalCommand.PersistGoal(state.id)
        return newState
    }

    private fun handleStepAcceptanceFailed(
        state: GoalState,
        event: GoalEvent.StepAcceptanceFailed,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val step = state.goal.plan.steps.first { it.id == event.stepId }
        return if (step.attempts >= step.maxAttempts) {
            val failed = updateStep(state, event.stepId) { it.copy(status = StepStatus.FAILED) }
            val withSkipped = skipDependentSteps(failed, event.stepId, "dependency '${event.stepId}' failed")
            commands += GoalCommand.NotifyUser(state.id, "Step '${step.description}' failed: ${event.reason}")
            val terminal = if (isGoalFailed(withSkipped)) {
                withSkipped.copy(goal = withSkipped.goal.copy(status = GoalStatus.FAILED))
            } else {
                withSkipped
            }
            commands += GoalCommand.PersistGoal(state.id)
            terminal
        } else {
            val retryState = updateStep(state, event.stepId) { it.copy(status = StepStatus.READY) }
            commands += GoalCommand.EmitWorkReady(
                GoalRuntimeCue(state.id, event.stepId, "step_retry")
            )
            commands += GoalCommand.PersistGoal(state.id)
            retryState
        }
    }

    private fun handleStepBlocked(
        state: GoalState,
        event: GoalEvent.StepBlocked,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.BLOCKED, waitCondition = event.waitCondition)
        }
        commands += GoalCommand.RegisterWaitCondition(state.id, event.stepId, event.waitCondition)
        if (event.waitCondition.type == WaitConditionType.TIMER && event.waitCondition.timeoutAt != null) {
            commands += GoalCommand.ScheduleWakeTimer(
                goalId = state.id,
                stepId = event.stepId,
                wakeAt = event.waitCondition.timeoutAt,
                reason = "step_blocked_timer"
            )
        }
        val goalStatus = if (allRemainingStepsBlocked(newState)) {
            GoalStatus.BLOCKED
        } else {
            newState.goal.status
        }
        commands += GoalCommand.PersistGoal(state.id)
        return newState.copy(goal = newState.goal.copy(status = goalStatus))
    }

    private fun handleStepUnblocked(
        state: GoalState,
        event: GoalEvent.StepUnblocked,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.READY, waitCondition = null)
        }
        val goalStatus = if (newState.goal.status == GoalStatus.BLOCKED) {
            GoalStatus.ACTIVE
        } else {
            newState.goal.status
        }
        commands += GoalCommand.ClearWaitCondition(state.id, event.stepId)
        commands += GoalCommand.EmitWorkReady(
            GoalRuntimeCue(state.id, event.stepId, "step_unblocked")
        )
        commands += GoalCommand.PersistGoal(state.id)
        return newState.copy(goal = newState.goal.copy(status = goalStatus))
    }

    private fun handleStepSkipped(
        state: GoalState,
        event: GoalEvent.StepSkipped,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.SKIPPED)
        }
        val withCascade = skipDependentSteps(newState, event.stepId, "dependency '${event.stepId}' skipped")
        commands += GoalCommand.PersistGoal(state.id)
        return withCascade
    }

    private fun handleWaitConditionRegistered(
        state: GoalState,
        event: GoalEvent.WaitConditionRegistered,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        if (event.condition.type == WaitConditionType.TIMER) {
            val wakeAt = event.condition.params["wake_at"]?.let { Instant.parse(it) }
            if (wakeAt != null) {
                commands += GoalCommand.ScheduleWakeTimer(
                    goalId = state.id,
                    stepId = event.stepId,
                    wakeAt = wakeAt,
                    reason = "wait_condition_registered"
                )
            }
        }
        commands += GoalCommand.RegisterWaitCondition(state.id, event.stepId, event.condition)
        commands += GoalCommand.PersistGoal(state.id)
        return updateStep(state, event.stepId) { step ->
            step.copy(waitCondition = event.condition, status = StepStatus.BLOCKED)
        }
    }

    private fun handleWaitConditionSatisfied(
        state: GoalState,
        event: GoalEvent.WaitConditionSatisfied,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        commands += GoalCommand.ClearWaitCondition(state.id, event.stepId)
        commands += GoalCommand.EmitWorkReady(
            GoalRuntimeCue(state.id, event.stepId, buildWaitSatisfiedReason(event))
        )
        commands += GoalCommand.PersistGoal(state.id)
        val updated = updateStep(state, event.stepId) { step ->
            step.copy(
                status = StepStatus.READY,
                waitCondition = null,
                notes = appendResolutionNote(step.notes, event),
            )
        }
        val goalStatus = if (updated.goal.status == GoalStatus.BLOCKED) {
            GoalStatus.ACTIVE
        } else {
            updated.goal.status
        }
        return updated.copy(goal = updated.goal.copy(status = goalStatus))
    }

    private fun handleWaitConditionTimedOut(
        state: GoalState,
        event: GoalEvent.WaitConditionTimedOut,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val step = state.goal.plan.steps.first { it.id == event.stepId }
        val action = step.waitCondition?.onTimeout ?: TimeoutAction.FAIL
        return when (action) {
            TimeoutAction.FAIL -> {
                val failed = updateStep(state, event.stepId) { it.copy(status = StepStatus.FAILED, waitCondition = null) }
                commands += GoalCommand.NotifyUser(state.id, "Step '${step.description}' timed out")
                commands += GoalCommand.ClearWaitCondition(state.id, event.stepId)
                commands += GoalCommand.PersistGoal(state.id)
                skipDependentSteps(failed, event.stepId, "dependency '${event.stepId}' timed out")
            }
            TimeoutAction.RETRY -> {
                commands += GoalCommand.ClearWaitCondition(state.id, event.stepId)
                commands += GoalCommand.EmitWorkReady(
                    GoalRuntimeCue(state.id, event.stepId, "wait_condition_timeout_retry")
                )
                commands += GoalCommand.PersistGoal(state.id)
                updateStep(state, event.stepId) { it.copy(status = StepStatus.READY, waitCondition = null) }
            }
            TimeoutAction.ESCALATE -> {
                commands += GoalCommand.NotifyUser(state.id, "Step '${step.description}' timed out — escalating to user")
                commands += GoalCommand.PersistGoal(state.id)
                state
            }
        }
    }

    private fun handleSuspended(
        state: GoalState,
        event: GoalEvent.Suspended,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        if (event.resumeAt != null) {
            commands += GoalCommand.ScheduleWakeTimer(
                goalId = state.id,
                wakeAt = event.resumeAt,
                reason = "goal_suspended_resume"
            )
        }
        commands += GoalCommand.PersistGoal(state.id)
        return state.copy(
            goal = state.goal.copy(
                status = GoalStatus.SUSPENDED,
                suspendedUntil = event.resumeAt,
            )
        )
    }

    private fun handleResumed(
        state: GoalState,
        event: GoalEvent.Resumed,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        val resumed = state.copy(
            goal = state.goal.copy(
                status = GoalStatus.ACTIVE,
                suspendedUntil = null,
            )
        )
        resumed.nextRunnableStep()?.let { step ->
            commands += GoalCommand.EmitWorkReady(
                GoalRuntimeCue(state.id, step.id, "goal_resumed")
            )
        }
        commands += GoalCommand.PersistGoal(state.id)
        return resumed
    }

    private fun handleCompleted(
        state: GoalState,
        event: GoalEvent.Completed,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        commands += GoalCommand.PersistGoal(state.id)
        return state.copy(
            goal = state.goal.copy(status = GoalStatus.COMPLETED)
        )
    }

    private fun handleCronCycleStarted(
        state: GoalState,
        event: GoalEvent.CronCycleStarted,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        if (state.goal.cronExpression.isNullOrBlank()) {
            return state
        }
        val resetSteps = promoteReadySteps(
            steps = resetRecurringPlanSteps(state.goal.plan.steps),
            producedKeys = emptySet(),
        )
        val restarted = state.copy(
            goal = state.goal.copy(
                status = GoalStatus.ACTIVE,
                plan = state.goal.plan.copy(steps = resetSteps),
            ),
            producedKeys = emptySet(),
        )
        emitWorkReadyIfRunnable(state.id, resetSteps, "cron_cycle_started", commands)
        commands += GoalCommand.PersistGoal(state.id)
        return restarted
    }

    private fun handleFailed(
        state: GoalState,
        event: GoalEvent.Failed,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        commands += GoalCommand.PersistGoal(state.id)
        return state.copy(
            goal = state.goal.copy(status = GoalStatus.FAILED)
        )
    }

    private fun handlePriorityChanged(
        state: GoalState,
        event: GoalEvent.PriorityChanged,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        commands += GoalCommand.PersistGoal(state.id)
        return state.copy(
            goal = state.goal.copy(priority = event.priority)
        )
    }

    private fun handleWorkCycleCompleted(
        state: GoalState,
        event: GoalEvent.WorkCycleCompleted,
        commands: MutableList<GoalCommand>,
    ): GoalState {
        commands += GoalCommand.PersistGoal(state.id)
        return state.copy(
            goal = state.goal.copy(lastWorkedAt = event.timestamp)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun shouldDeferRecurringKickoff(state: GoalState): Boolean =
        !state.goal.cronExpression.isNullOrBlank() &&
            state.goal.status == GoalStatus.PLANNING &&
            state.goal.lastWorkedAt == null

    private fun updateStep(
        state: GoalState,
        stepId: String,
        transform: (PlanStep) -> PlanStep,
    ): GoalState {
        val newSteps = state.goal.plan.steps.map { step ->
            if (step.id == stepId) transform(step) else step
        }
        return state.copy(
            goal = state.goal.copy(
                plan = state.goal.plan.copy(steps = newSteps)
            )
        )
    }

    private fun promoteReadySteps(
        steps: List<PlanStep>,
        producedKeys: Set<String>,
    ): List<PlanStep> {
        val doneIds = steps.filter { it.status == StepStatus.DONE }.map { it.id }.toSet()
        return steps.map { step ->
            if (step.status == StepStatus.PENDING && step.requires.all { req ->
                    req in doneIds || req in producedKeys
                }) {
                step.copy(status = StepStatus.READY)
            } else {
                step
            }
        }
    }

    private fun resetRecurringPlanSteps(steps: List<PlanStep>): List<PlanStep> =
        steps.map { step ->
            step.copy(
                status = StepStatus.PENDING,
                waitCondition = null,
                attempts = 0,
                lastAttemptAt = null,
                completedAt = null,
                notes = "",
            )
        }

    private fun skipDependentSteps(
        state: GoalState,
        failedStepId: String,
        reason: String,
    ): GoalState {
        val failedProduces = state.goal.plan.steps
            .first { it.id == failedStepId }
            .produces
        val stepsToSkip = state.goal.plan.steps.filter { step ->
            step.status in setOf(StepStatus.PENDING, StepStatus.READY) &&
                (failedStepId in step.requires || step.requires.any { it in failedProduces })
        }
        if (stepsToSkip.isEmpty()) return state
        var current = state
        for (step in stepsToSkip) {
            current = updateStep(current, step.id) { it.copy(status = StepStatus.SKIPPED) }
        }
        return current
    }

    private fun isGoalComplete(state: GoalState): Boolean =
        state.goal.plan.steps.all { it.status in setOf(StepStatus.DONE, StepStatus.SKIPPED) }

    private fun isGoalFailed(state: GoalState): Boolean {
        val remaining = state.goal.plan.steps.filter {
            it.status !in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED)
        }
        return remaining.isEmpty() && state.goal.plan.steps.any { it.status == StepStatus.FAILED }
    }

    private fun allRemainingStepsBlocked(state: GoalState): Boolean =
        state.goal.plan.steps
            .filter { it.status !in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED) }
            .all { it.status == StepStatus.BLOCKED }

    private fun emitWorkReadyIfRunnable(
        goalId: String,
        steps: List<PlanStep>,
        reason: String,
        commands: MutableList<GoalCommand>,
    ) {
        val step = steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: steps.firstOrNull { it.status == StepStatus.READY }
            ?: return
        commands += GoalCommand.EmitWorkReady(
            GoalRuntimeCue(goalId, step.id, reason)
        )
    }

    private fun buildWaitSatisfiedReason(event: GoalEvent.WaitConditionSatisfied): String {
        val suffix = listOfNotNull(
            event.resolutionStatus?.trim()?.takeIf { it.isNotBlank() },
            event.resolutionSummary?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(": ")
        return if (suffix.isBlank()) "wait_condition_satisfied" else "wait_condition_satisfied: $suffix"
    }

    private fun appendResolutionNote(existing: String, event: GoalEvent.WaitConditionSatisfied): String {
        val note = listOfNotNull(
            event.resolutionStatus?.trim()?.takeIf { it.isNotBlank() }?.let { "async_status=$it" },
            event.resolutionSummary?.trim()?.takeIf { it.isNotBlank() }?.let { "async_summary=$it" },
        ).joinToString(" | ")
        if (note.isBlank()) return existing
        return listOf(existing.trim(), note)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
