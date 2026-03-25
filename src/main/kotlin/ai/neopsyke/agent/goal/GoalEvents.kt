package ai.neopsyke.agent.goal

import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import java.time.Instant

/**
 * Event-sourced events for goal state transitions.
 * Every state change is captured as an immutable event in the event log.
 */
sealed interface GoalEvent {
    val goalId: String
    val timestamp: Instant

    // ── Lifecycle ────────────────────────────────────────────────────────

    data class Created(
        override val goalId: String,
        val title: String,
        val instruction: String,
        val priority: GoalPriority,
        val completionCriteria: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class PlanGenerated(
        override val goalId: String,
        val plan: GoalPlan,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class PlanRevised(
        override val goalId: String,
        val plan: GoalPlan,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class Updated(
        override val goalId: String,
        val cronExpression: String? = null,
        val instruction: String? = null,
        val title: String? = null,
        val completionCriteria: String? = null,
        val reason: String? = null,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    // ── Step progress ────────────────────────────────────────────────────

    data class StepStarted(
        override val goalId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class StepActionExecuted(
        override val goalId: String,
        val stepId: String,
        val actionResult: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class StepAcceptancePassed(
        override val goalId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class StepAcceptanceFailed(
        override val goalId: String,
        val stepId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class StepBlocked(
        override val goalId: String,
        val stepId: String,
        val waitCondition: WaitCondition,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class StepUnblocked(
        override val goalId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class StepSkipped(
        override val goalId: String,
        val stepId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    // ── Wait conditions ──────────────────────────────────────────────────

    data class WaitConditionRegistered(
        override val goalId: String,
        val stepId: String,
        val condition: WaitCondition,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class WaitConditionSatisfied(
        override val goalId: String,
        val stepId: String,
        val conditionType: String,
        val resolutionSummary: String? = null,
        val resolutionStatus: String? = null,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class WaitConditionTimedOut(
        override val goalId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    // ── Goal-level ────────────────────────────────────────────────────

    data class Suspended(
        override val goalId: String,
        val reason: String,
        val resumeAt: Instant? = null,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class Resumed(
        override val goalId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class CronCycleStarted(
        override val goalId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class Completed(
        override val goalId: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class PriorityChanged(
        override val goalId: String,
        val priority: GoalPriority,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class Failed(
        override val goalId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    // ── Context management ───────────────────────────────────────────────

    data class ContextUpdated(
        override val goalId: String,
        val tier: Int,
        val summary: String,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent

    data class WorkCycleCompleted(
        override val goalId: String,
        val stepId: String,
        val actionsExecuted: Int,
        override val timestamp: Instant = Instant.now(),
    ) : GoalEvent
}

/**
 * Commands emitted by the state machine transition function.
 * The GoalManager dispatches these as side effects.
 */
sealed interface GoalCommand {
    data class EmitWorkReady(
        val cue: GoalRuntimeCue,
    ) : GoalCommand

    data class ScheduleWakeTimer(
        val goalId: String,
        val stepId: String? = null,
        val wakeAt: Instant,
        val reason: String,
    ) : GoalCommand

    data class CancelWakeTimer(
        val goalId: String,
        val reason: String,
    ) : GoalCommand

    data class RegisterWaitCondition(
        val goalId: String,
        val stepId: String,
        val condition: WaitCondition,
    ) : GoalCommand

    data class ClearWaitCondition(
        val goalId: String,
        val stepId: String,
    ) : GoalCommand

    data class PersistGoal(val goalId: String) : GoalCommand
    data class NotifyUser(val goalId: String, val message: String) : GoalCommand
}

data class WaitConditionResolution(
    val conditionType: String,
    val summary: String = "",
    val status: String? = null,
)
