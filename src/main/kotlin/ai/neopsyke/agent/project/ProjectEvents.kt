package ai.neopsyke.agent.project

import ai.neopsyke.agent.cortex.sensory.ProjectSignal
import java.time.Instant

/**
 * Event-sourced events for project state transitions.
 * Every state change is captured as an immutable event in the event log.
 */
sealed interface ProjectEvent {
    val projectId: String
    val timestamp: Instant

    // ── Lifecycle ────────────────────────────────────────────────────────

    data class Created(
        override val projectId: String,
        val title: String,
        val instruction: String,
        val priority: ProjectPriority,
        val completionCriteria: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class PlanGenerated(
        override val projectId: String,
        val plan: ProjectPlan,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class PlanRevised(
        override val projectId: String,
        val plan: ProjectPlan,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    // ── Step progress ────────────────────────────────────────────────────

    data class StepStarted(
        override val projectId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class StepActionExecuted(
        override val projectId: String,
        val stepId: String,
        val actionResult: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class StepAcceptancePassed(
        override val projectId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class StepAcceptanceFailed(
        override val projectId: String,
        val stepId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class StepBlocked(
        override val projectId: String,
        val stepId: String,
        val waitCondition: WaitCondition,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class StepUnblocked(
        override val projectId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class StepSkipped(
        override val projectId: String,
        val stepId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    // ── Wait conditions ──────────────────────────────────────────────────

    data class WaitConditionRegistered(
        override val projectId: String,
        val stepId: String,
        val condition: WaitCondition,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class WaitConditionSatisfied(
        override val projectId: String,
        val stepId: String,
        val conditionType: String,
        val resolutionSummary: String? = null,
        val resolutionStatus: String? = null,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class WaitConditionTimedOut(
        override val projectId: String,
        val stepId: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    // ── Project-level ────────────────────────────────────────────────────

    data class Suspended(
        override val projectId: String,
        val reason: String,
        val resumeAt: Instant? = null,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class Resumed(
        override val projectId: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class Completed(
        override val projectId: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class PriorityChanged(
        override val projectId: String,
        val priority: ProjectPriority,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class Failed(
        override val projectId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    // ── Context management ───────────────────────────────────────────────

    data class ContextUpdated(
        override val projectId: String,
        val tier: Int,
        val summary: String,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent

    data class WorkCycleCompleted(
        override val projectId: String,
        val stepId: String,
        val actionsExecuted: Int,
        override val timestamp: Instant = Instant.now(),
    ) : ProjectEvent
}

/**
 * Commands emitted by the state machine transition function.
 * The ProjectManager dispatches these as side effects.
 */
sealed interface ProjectCommand {
    data class EmitWorkReady(
        val signal: ProjectSignal.WorkReady,
    ) : ProjectCommand

    data class ScheduleWakeTimer(
        val projectId: String,
        val stepId: String? = null,
        val wakeAt: Instant,
        val reason: String,
    ) : ProjectCommand

    data class CancelWakeTimer(
        val projectId: String,
        val reason: String,
    ) : ProjectCommand

    data class RegisterWaitCondition(
        val projectId: String,
        val stepId: String,
        val condition: WaitCondition,
    ) : ProjectCommand

    data class ClearWaitCondition(
        val projectId: String,
        val stepId: String,
    ) : ProjectCommand

    data class PersistProject(val projectId: String) : ProjectCommand
    data class NotifyUser(val projectId: String, val message: String) : ProjectCommand
}

data class WaitConditionResolution(
    val conditionType: String,
    val summary: String = "",
    val status: String? = null,
)
