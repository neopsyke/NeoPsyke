package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.cortex.sensory.DurableWorkCue
import java.nio.file.Path
import java.time.Instant

/**
 * Immutable snapshot of a work item's full state, reconstructable by replaying events.
 */
data class WorkItemState(
    val workItem: WorkItem,
    val producedKeys: Set<String> = emptySet(),
    val eventCount: Int = 0,
    val durableState: DurableWorkState = DurableWorkState(),
) {
    val id: String get() = workItem.id

    fun readySteps(): List<PlanStep> =
        workItem.plan.steps.filter { it.status == StepStatus.READY }

    fun nextRunnableStep(): PlanStep? =
        workItem.plan.steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: readySteps().firstOrNull()

    fun isTerminal(): Boolean =
        workItem.status == WorkItemStatus.COMPLETED || workItem.status == WorkItemStatus.FAILED
}

/**
 * Pure state machine: no side effects, no I/O.
 * Returns the new state and a list of commands the DurableWorkRuntime should dispatch.
 */
object WorkItemStateMachine {

    fun transition(
        state: WorkItemState,
        event: WorkItemEvent,
    ): Pair<WorkItemState, List<WorkItemCommand>> {
        require(event.workItemId == state.id) {
            "Event workItemId '${event.workItemId}' does not match state id '${state.id}'"
        }
        val commands = mutableListOf<WorkItemCommand>()
        val newState = when (event) {
            is WorkItemEvent.Created -> handleCreated(state, event, commands)
            is WorkItemEvent.PlanGenerated -> handlePlanGenerated(state, event, commands)
            is WorkItemEvent.PlanRevised -> handlePlanRevised(state, event, commands)
            is WorkItemEvent.StepStarted -> handleStepStarted(state, event)
            is WorkItemEvent.StepActionExecuted -> handleStepActionExecuted(state, event)
            is WorkItemEvent.StepAcceptancePassed -> handleStepAcceptancePassed(state, event, commands)
            is WorkItemEvent.StepAcceptanceFailed -> handleStepAcceptanceFailed(state, event, commands)
            is WorkItemEvent.StepBlocked -> handleStepBlocked(state, event, commands)
            is WorkItemEvent.StepUnblocked -> handleStepUnblocked(state, event, commands)
            is WorkItemEvent.StepSkipped -> handleStepSkipped(state, event, commands)
            is WorkItemEvent.WaitConditionRegistered -> handleWaitConditionRegistered(state, event, commands)
            is WorkItemEvent.WaitConditionSatisfied -> handleWaitConditionSatisfied(state, event, commands)
            is WorkItemEvent.WaitConditionTimedOut -> handleWaitConditionTimedOut(state, event, commands)
            is WorkItemEvent.Suspended -> handleSuspended(state, event, commands)
            is WorkItemEvent.Resumed -> handleResumed(state, event, commands)
            is WorkItemEvent.CronCycleStarted -> handleCronCycleStarted(state, event, commands)
            is WorkItemEvent.Completed -> handleCompleted(state, event, commands)
            is WorkItemEvent.PriorityChanged -> handlePriorityChanged(state, event, commands)
            is WorkItemEvent.Failed -> handleFailed(state, event, commands)
            is WorkItemEvent.Updated -> handleUpdated(state, event)
            is WorkItemEvent.ContextUpdated -> state
            is WorkItemEvent.WorkCycleCompleted -> handleWorkCycleCompleted(state, event, commands)
            // Lease and activation lifecycle events (runtime bookkeeping, no state change)
            is WorkItemEvent.LeaseAcquired -> state.copy(
                workItem = state.workItem.copy(activeLease = event.leaseToken)
            )
            is WorkItemEvent.LeaseHeartbeat -> state
            is WorkItemEvent.LeaseExpired -> state.copy(
                workItem = state.workItem.copy(activeLease = null)
            )
            is WorkItemEvent.WakeCoalesced -> state
            is WorkItemEvent.ActivationStarted -> state.copy(
                workItem = state.workItem.copy(activationCount = state.workItem.activationCount + 1)
            )
            is WorkItemEvent.ActivationFinished -> state.copy(
                workItem = state.workItem.copy(activeLease = null)
            )
            is WorkItemEvent.ActivationRecovered -> state.copy(
                workItem = state.workItem.copy(activeLease = null, health = WorkItemHealth.NEEDS_ATTENTION)
            )
            is WorkItemEvent.MarkedStalled -> state.copy(
                workItem = state.workItem.copy(
                    status = WorkItemStatus.STALLED,
                    health = WorkItemHealth.STALLED,
                )
            )
            is WorkItemEvent.MarkedNeedsAttention -> state.copy(
                workItem = state.workItem.copy(
                    status = WorkItemStatus.NEEDS_ATTENTION,
                    health = WorkItemHealth.NEEDS_ATTENTION,
                )
            )
            is WorkItemEvent.DeliveryDeferred -> state
            is WorkItemEvent.DeliverySent -> state
            // Effect intent lifecycle (bookkeeping, no state transition)
            is WorkItemEvent.EffectIntentRecorded -> state
            is WorkItemEvent.EffectConfirmed -> state
            is WorkItemEvent.EffectAbandoned -> state
            is WorkItemEvent.EffectUncertain -> state
        }
        val finalState = newState.copy(eventCount = newState.eventCount + 1)
        return finalState to commands
    }

    /**
     * Create an initial [WorkItemState] from a [WorkItemEvent.Created] event.
     */
    fun initialState(event: WorkItemEvent.Created, workspacePath: Path): WorkItemState =
        WorkItemState(
            workItem = WorkItem(
                id = event.workItemId,
                title = event.title,
                instruction = event.instruction,
                status = WorkItemStatus.PLANNING,
                priority = event.priority,
                plan = WorkItemPlan.empty(),
                completionCriteria = event.completionCriteria,
                createdAt = event.timestamp,
                contactChannel = event.contactChannel,
                workspacePath = workspacePath,
            ),
            eventCount = 1,
        )

    // ── Event handlers ───────────────────────────────────────────────────

    private fun handleCreated(
        state: WorkItemState,
        event: WorkItemEvent.Created,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        commands += WorkItemCommand.PersistWorkItem(event.workItemId)
        return state.copy(
            workItem = state.workItem.copy(
                title = event.title,
                instruction = event.instruction,
                status = WorkItemStatus.PLANNING,
                priority = event.priority,
                completionCriteria = event.completionCriteria,
                contactChannel = event.contactChannel,
            )
        )
    }

    private fun handlePlanGenerated(
        state: WorkItemState,
        event: WorkItemEvent.PlanGenerated,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val stepsWithReady = promoteReadySteps(event.plan.steps, state.producedKeys)
        val newPlan = event.plan.copy(steps = stepsWithReady)
        val newWorkItem = state.workItem.copy(plan = newPlan, status = WorkItemStatus.ACTIVE)
        commands += WorkItemCommand.PersistWorkItem(state.id)
        if (!shouldDeferRecurringKickoff(state)) {
            emitWorkReadyIfRunnable(state.id, stepsWithReady, "plan_generated", commands)
        }
        return state.copy(workItem = newWorkItem)
    }

    private fun handlePlanRevised(
        state: WorkItemState,
        event: WorkItemEvent.PlanRevised,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val stepsWithReady = promoteReadySteps(event.plan.steps, state.producedKeys)
        val newPlan = event.plan.copy(steps = stepsWithReady, revisedAt = event.timestamp)
        val newWorkItem = state.workItem.copy(plan = newPlan, status = WorkItemStatus.ACTIVE)
        commands += WorkItemCommand.PersistWorkItem(state.id)
        if (!shouldDeferRecurringKickoff(state)) {
            emitWorkReadyIfRunnable(state.id, stepsWithReady, "plan_revised", commands)
        }
        return state.copy(workItem = newWorkItem)
    }

    private fun handleUpdated(
        state: WorkItemState,
        event: WorkItemEvent.Updated,
    ): WorkItemState {
        var workItem = state.workItem
        event.cronExpression?.let { workItem = workItem.copy(cronExpression = it) }
        event.instruction?.let { workItem = workItem.copy(instruction = it) }
        event.title?.let { workItem = workItem.copy(title = it) }
        event.completionCriteria?.let { workItem = workItem.copy(completionCriteria = it) }
        event.contactChannel?.let { workItem = workItem.copy(contactChannel = it) }
        return state.copy(workItem = workItem)
    }

    private fun handleStepStarted(
        state: WorkItemState,
        event: WorkItemEvent.StepStarted,
    ): WorkItemState = updateStep(state, event.stepId) { step ->
        step.copy(status = StepStatus.IN_PROGRESS, lastAttemptAt = event.timestamp)
    }.let { it.copy(workItem = it.workItem.copy(lastWorkedAt = event.timestamp)) }

    private fun handleStepActionExecuted(
        state: WorkItemState,
        event: WorkItemEvent.StepActionExecuted,
    ): WorkItemState = updateStep(state, event.stepId) { step ->
        step.copy(
            attempts = step.attempts + 1,
            notes = listOf(step.notes.trim(), event.actionResult.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n"),
        )
    }

    private fun handleStepAcceptancePassed(
        state: WorkItemState,
        event: WorkItemEvent.StepAcceptancePassed,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val withDone = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.DONE, completedAt = event.timestamp)
        }
        val completedStep = withDone.workItem.plan.steps.first { it.id == event.stepId }
        val newProducedKeys = withDone.producedKeys + completedStep.produces
        val stepsPromoted = promoteReadySteps(withDone.workItem.plan.steps, newProducedKeys)
        val completed = isWorkItemComplete(withDone.copy(workItem = withDone.workItem.copy(plan = withDone.workItem.plan.copy(steps = stepsPromoted))))
        val updatedProject = withDone.workItem.copy(
            plan = withDone.workItem.plan.copy(steps = stepsPromoted),
            status = if (completed) WorkItemStatus.COMPLETED else withDone.workItem.status,
        )
        val newState = withDone.copy(workItem = updatedProject, producedKeys = newProducedKeys)

        if (completed) {
            commands += WorkItemCommand.PersistWorkItem(state.id)
            return newState
        }

        emitWorkReadyIfRunnable(state.id, stepsPromoted, "step_completed", commands)
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return newState
    }

    private fun handleStepAcceptanceFailed(
        state: WorkItemState,
        event: WorkItemEvent.StepAcceptanceFailed,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val step = state.workItem.plan.steps.first { it.id == event.stepId }
        return if (step.attempts >= step.maxAttempts) {
            val failed = updateStep(state, event.stepId) { it.copy(status = StepStatus.FAILED) }
            val withSkipped = skipDependentSteps(failed, event.stepId, "dependency '${event.stepId}' failed")
            commands += WorkItemCommand.NotifyUser(state.id, "Step '${step.description}' failed: ${event.reason}")
            val terminal = if (isWorkItemFailed(withSkipped)) {
                withSkipped.copy(workItem = withSkipped.workItem.copy(status = WorkItemStatus.FAILED))
            } else {
                withSkipped
            }
            commands += WorkItemCommand.PersistWorkItem(state.id)
            terminal
        } else {
            val retryState = updateStep(state, event.stepId) { it.copy(status = StepStatus.READY) }
            commands += WorkItemCommand.EmitWorkReady(
                DurableWorkCue(state.id, event.stepId, "step_retry")
            )
            commands += WorkItemCommand.PersistWorkItem(state.id)
            retryState
        }
    }

    private fun handleStepBlocked(
        state: WorkItemState,
        event: WorkItemEvent.StepBlocked,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.BLOCKED, waitCondition = event.waitCondition)
        }
        commands += WorkItemCommand.RegisterWaitCondition(state.id, event.stepId, event.waitCondition)
        if (event.waitCondition.type == WaitConditionType.TIMER && event.waitCondition.timeoutAt != null) {
            commands += WorkItemCommand.ScheduleWakeTimer(
                workItemId = state.id,
                stepId = event.stepId,
                wakeAt = event.waitCondition.timeoutAt,
                reason = "step_blocked_timer"
            )
        }
        val itemStatus = if (allRemainingStepsBlocked(newState)) {
            WorkItemStatus.BLOCKED
        } else {
            newState.workItem.status
        }
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return newState.copy(workItem = newState.workItem.copy(status = itemStatus))
    }

    private fun handleStepUnblocked(
        state: WorkItemState,
        event: WorkItemEvent.StepUnblocked,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.READY, waitCondition = null)
        }
        val itemStatus = if (newState.workItem.status == WorkItemStatus.BLOCKED) {
            WorkItemStatus.ACTIVE
        } else {
            newState.workItem.status
        }
        commands += WorkItemCommand.ClearWaitCondition(state.id, event.stepId)
        commands += WorkItemCommand.EmitWorkReady(
            DurableWorkCue(state.id, event.stepId, "step_unblocked")
        )
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return newState.copy(workItem = newState.workItem.copy(status = itemStatus))
    }

    private fun handleStepSkipped(
        state: WorkItemState,
        event: WorkItemEvent.StepSkipped,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.SKIPPED)
        }
        val withCascade = skipDependentSteps(newState, event.stepId, "dependency '${event.stepId}' skipped")
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return withCascade
    }

    private fun handleWaitConditionRegistered(
        state: WorkItemState,
        event: WorkItemEvent.WaitConditionRegistered,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        if (event.condition.type == WaitConditionType.TIMER) {
            val wakeAt = event.condition.params["wake_at"]?.let { Instant.parse(it) }
            if (wakeAt != null) {
                commands += WorkItemCommand.ScheduleWakeTimer(
                    workItemId = state.id,
                    stepId = event.stepId,
                    wakeAt = wakeAt,
                    reason = "wait_condition_registered"
                )
            }
        }
        commands += WorkItemCommand.RegisterWaitCondition(state.id, event.stepId, event.condition)
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return updateStep(state, event.stepId) { step ->
            step.copy(waitCondition = event.condition, status = StepStatus.BLOCKED)
        }
    }

    private fun handleWaitConditionSatisfied(
        state: WorkItemState,
        event: WorkItemEvent.WaitConditionSatisfied,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        commands += WorkItemCommand.ClearWaitCondition(state.id, event.stepId)
        commands += WorkItemCommand.EmitWorkReady(
            DurableWorkCue(state.id, event.stepId, buildWaitSatisfiedReason(event))
        )
        commands += WorkItemCommand.PersistWorkItem(state.id)
        val updated = updateStep(state, event.stepId) { step ->
            step.copy(
                status = StepStatus.READY,
                waitCondition = null,
                notes = appendResolutionNote(step.notes, event),
            )
        }
        val itemStatus = if (updated.workItem.status == WorkItemStatus.BLOCKED) {
            WorkItemStatus.ACTIVE
        } else {
            updated.workItem.status
        }
        return updated.copy(workItem = updated.workItem.copy(status = itemStatus))
    }

    private fun handleWaitConditionTimedOut(
        state: WorkItemState,
        event: WorkItemEvent.WaitConditionTimedOut,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val step = state.workItem.plan.steps.first { it.id == event.stepId }
        val action = step.waitCondition?.onTimeout ?: TimeoutAction.FAIL
        return when (action) {
            TimeoutAction.FAIL -> {
                val failed = updateStep(state, event.stepId) { it.copy(status = StepStatus.FAILED, waitCondition = null) }
                commands += WorkItemCommand.NotifyUser(state.id, "Step '${step.description}' timed out")
                commands += WorkItemCommand.ClearWaitCondition(state.id, event.stepId)
                commands += WorkItemCommand.PersistWorkItem(state.id)
                skipDependentSteps(failed, event.stepId, "dependency '${event.stepId}' timed out")
            }
            TimeoutAction.RETRY -> {
                commands += WorkItemCommand.ClearWaitCondition(state.id, event.stepId)
                commands += WorkItemCommand.EmitWorkReady(
                    DurableWorkCue(state.id, event.stepId, "wait_condition_timeout_retry")
                )
                commands += WorkItemCommand.PersistWorkItem(state.id)
                updateStep(state, event.stepId) { it.copy(status = StepStatus.READY, waitCondition = null) }
            }
            TimeoutAction.ESCALATE -> {
                commands += WorkItemCommand.NotifyUser(state.id, "Step '${step.description}' timed out — escalating to user")
                commands += WorkItemCommand.PersistWorkItem(state.id)
                state
            }
        }
    }

    private fun handleSuspended(
        state: WorkItemState,
        event: WorkItemEvent.Suspended,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        if (event.resumeAt != null) {
            commands += WorkItemCommand.ScheduleWakeTimer(
                workItemId = state.id,
                wakeAt = event.resumeAt,
                reason = "work_item_suspended_resume"
            )
        }
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return state.copy(
            workItem = state.workItem.copy(
                status = WorkItemStatus.SUSPENDED,
                suspendedUntil = event.resumeAt,
            )
        )
    }

    private fun handleResumed(
        state: WorkItemState,
        event: WorkItemEvent.Resumed,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        val resumed = state.copy(
            workItem = state.workItem.copy(
                status = WorkItemStatus.ACTIVE,
                suspendedUntil = null,
            )
        )
        resumed.nextRunnableStep()?.let { step ->
            commands += WorkItemCommand.EmitWorkReady(
                DurableWorkCue(state.id, step.id, "work_item_resumed")
            )
        }
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return resumed
    }

    private fun handleCompleted(
        state: WorkItemState,
        event: WorkItemEvent.Completed,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return state.copy(
            workItem = state.workItem.copy(status = WorkItemStatus.COMPLETED)
        )
    }

    private fun handleCronCycleStarted(
        state: WorkItemState,
        event: WorkItemEvent.CronCycleStarted,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        if (state.workItem.cronExpression.isNullOrBlank()) {
            return state
        }
        val resetSteps = promoteReadySteps(
            steps = resetRecurringPlanSteps(state.workItem.plan.steps),
            producedKeys = emptySet(),
        )
        val restarted = state.copy(
            workItem = state.workItem.copy(
                status = WorkItemStatus.ACTIVE,
                plan = state.workItem.plan.copy(steps = resetSteps),
            ),
            producedKeys = emptySet(),
        )
        emitWorkReadyIfRunnable(state.id, resetSteps, "cron_cycle_started", commands)
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return restarted
    }

    private fun handleFailed(
        state: WorkItemState,
        event: WorkItemEvent.Failed,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return state.copy(
            workItem = state.workItem.copy(status = WorkItemStatus.FAILED)
        )
    }

    private fun handlePriorityChanged(
        state: WorkItemState,
        event: WorkItemEvent.PriorityChanged,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return state.copy(
            workItem = state.workItem.copy(priority = event.priority)
        )
    }

    private fun handleWorkCycleCompleted(
        state: WorkItemState,
        event: WorkItemEvent.WorkCycleCompleted,
        commands: MutableList<WorkItemCommand>,
    ): WorkItemState {
        commands += WorkItemCommand.PersistWorkItem(state.id)
        return state.copy(
            workItem = state.workItem.copy(lastWorkedAt = event.timestamp)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun shouldDeferRecurringKickoff(state: WorkItemState): Boolean =
        !state.workItem.cronExpression.isNullOrBlank() &&
            state.workItem.status == WorkItemStatus.PLANNING &&
            state.workItem.lastWorkedAt == null

    private fun updateStep(
        state: WorkItemState,
        stepId: String,
        transform: (PlanStep) -> PlanStep,
    ): WorkItemState {
        val newSteps = state.workItem.plan.steps.map { step ->
            if (step.id == stepId) transform(step) else step
        }
        return state.copy(
            workItem = state.workItem.copy(
                plan = state.workItem.plan.copy(steps = newSteps)
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
        state: WorkItemState,
        failedStepId: String,
        reason: String,
    ): WorkItemState {
        val failedProduces = state.workItem.plan.steps
            .first { it.id == failedStepId }
            .produces
        val stepsToSkip = state.workItem.plan.steps.filter { step ->
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

    private fun isWorkItemComplete(state: WorkItemState): Boolean =
        state.workItem.plan.steps.all { it.status in setOf(StepStatus.DONE, StepStatus.SKIPPED) }

    private fun isWorkItemFailed(state: WorkItemState): Boolean {
        val remaining = state.workItem.plan.steps.filter {
            it.status !in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED)
        }
        return remaining.isEmpty() && state.workItem.plan.steps.any { it.status == StepStatus.FAILED }
    }

    private fun allRemainingStepsBlocked(state: WorkItemState): Boolean =
        state.workItem.plan.steps
            .filter { it.status !in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED) }
            .all { it.status == StepStatus.BLOCKED }

    private fun emitWorkReadyIfRunnable(
        workItemId: String,
        steps: List<PlanStep>,
        reason: String,
        commands: MutableList<WorkItemCommand>,
    ) {
        val step = steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: steps.firstOrNull { it.status == StepStatus.READY }
            ?: return
        commands += WorkItemCommand.EmitWorkReady(
            DurableWorkCue(workItemId, step.id, reason)
        )
    }

    private fun buildWaitSatisfiedReason(event: WorkItemEvent.WaitConditionSatisfied): String {
        val suffix = listOfNotNull(
            event.resolutionStatus?.trim()?.takeIf { it.isNotBlank() },
            event.resolutionSummary?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(": ")
        return if (suffix.isBlank()) "wait_condition_satisfied" else "wait_condition_satisfied: $suffix"
    }

    private fun appendResolutionNote(existing: String, event: WorkItemEvent.WaitConditionSatisfied): String {
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
