package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.cortex.sensory.DurableWorkCue
import java.time.Instant

/**
 * Event-sourced events for goal state transitions.
 * Every state change is captured as an immutable event in the event log.
 */
sealed interface WorkItemEvent {
    val workItemId: String
    val timestamp: Instant

    // ── Lifecycle ────────────────────────────────────────────────────────

    data class Created(
        override val workItemId: String,
        val title: String,
        val instruction: String,
        val priority: WorkItemPriority,
        val completionCriteria: String,
        val contactChannel: String? = null,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class PlanGenerated(
        override val workItemId: String,
        val plan: WorkItemPlan,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class PlanRevised(
        override val workItemId: String,
        val plan: WorkItemPlan,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class Updated(
        override val workItemId: String,
        val cronExpression: String? = null,
        val instruction: String? = null,
        val title: String? = null,
        val completionCriteria: String? = null,
        val contactChannel: String? = null,
        val reason: String? = null,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Step progress ────────────────────────────────────────────────────

    data class StepStarted(
        override val workItemId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class StepActionExecuted(
        override val workItemId: String,
        val stepId: String,
        val actionResult: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class StepAcceptancePassed(
        override val workItemId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class StepAcceptanceFailed(
        override val workItemId: String,
        val stepId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class StepBlocked(
        override val workItemId: String,
        val stepId: String,
        val waitCondition: WaitCondition,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class StepUnblocked(
        override val workItemId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class StepSkipped(
        override val workItemId: String,
        val stepId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Wait conditions ──────────────────────────────────────────────────

    data class WaitConditionRegistered(
        override val workItemId: String,
        val stepId: String,
        val condition: WaitCondition,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class WaitConditionSatisfied(
        override val workItemId: String,
        val stepId: String,
        val conditionType: String,
        val resolutionSummary: String? = null,
        val resolutionStatus: String? = null,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class WaitConditionTimedOut(
        override val workItemId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Goal-level ────────────────────────────────────────────────────

    data class Suspended(
        override val workItemId: String,
        val reason: String,
        val resumeAt: Instant? = null,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class Resumed(
        override val workItemId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class CronCycleStarted(
        override val workItemId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class Completed(
        override val workItemId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class PriorityChanged(
        override val workItemId: String,
        val priority: WorkItemPriority,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class Failed(
        override val workItemId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Context management ───────────────────────────────────────────────

    data class ContextUpdated(
        override val workItemId: String,
        val tier: Int,
        val summary: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class WorkCycleCompleted(
        override val workItemId: String,
        val stepId: String,
        val actionsExecuted: Int,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Lease lifecycle ──────────────────────────────────────────────────

    data class LeaseAcquired(
        override val workItemId: String,
        val leaseToken: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class LeaseHeartbeat(
        override val workItemId: String,
        val leaseToken: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class LeaseExpired(
        override val workItemId: String,
        val leaseToken: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class WakeCoalesced(
        override val workItemId: String,
        val wakeReason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Activation lifecycle ─────────────────────────────────────────────

    data class ActivationStarted(
        override val workItemId: String,
        val stepId: String,
        val leaseToken: String,
        val planRevision: Int,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class ActivationFinished(
        override val workItemId: String,
        val stepId: String,
        val leaseToken: String,
        val actionsExecuted: Int,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class ActivationRecovered(
        override val workItemId: String,
        val leaseToken: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Health and delivery ──────────────────────────────────────────────

    data class MarkedStalled(
        override val workItemId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class MarkedNeedsAttention(
        override val workItemId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class DeliveryDeferred(
        override val workItemId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class DeliverySent(
        override val workItemId: String,
        val summary: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    // ── Effect intent lifecycle ──────────────────────────────────────────

    data class EffectIntentRecorded(
        override val workItemId: String,
        val effectIntentId: String,
        val actionType: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class EffectConfirmed(
        override val workItemId: String,
        val effectIntentId: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class EffectAbandoned(
        override val workItemId: String,
        val effectIntentId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent

    data class EffectUncertain(
        override val workItemId: String,
        val effectIntentId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : WorkItemEvent
}

/**
 * Commands emitted by the state machine transition function.
 * The DurableWorkRuntime dispatches these as side effects.
 */
sealed interface WorkItemCommand {
    data class EmitWorkReady(
        val cue: DurableWorkCue,
    ) : WorkItemCommand

    data class ScheduleWakeTimer(
        val workItemId: String,
        val stepId: String? = null,
        val wakeAt: Instant,
        val reason: String,
    ) : WorkItemCommand

    data class CancelWakeTimer(
        val workItemId: String,
        val reason: String,
    ) : WorkItemCommand

    data class RegisterWaitCondition(
        val workItemId: String,
        val stepId: String,
        val condition: WaitCondition,
    ) : WorkItemCommand

    data class ClearWaitCondition(
        val workItemId: String,
        val stepId: String,
    ) : WorkItemCommand

    data class PersistWorkItem(val workItemId: String) : WorkItemCommand
    data class NotifyUser(val workItemId: String, val message: String) : WorkItemCommand
}

data class WaitConditionResolution(
    val conditionType: String,
    val summary: String = "",
    val status: String? = null,
)
