package ai.neopsyke.agent.goal

import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import java.nio.file.Path
import java.time.Instant
import ai.neopsyke.agent.model.ConversationContext

data class Goal(
    val id: String,
    val title: String,
    val instruction: String,
    val status: GoalStatus,
    val priority: GoalPriority,
    val plan: GoalPlan,
    val completionCriteria: String,
    val createdAt: Instant,
    val lastWorkedAt: Instant? = null,
    val suspendedUntil: Instant? = null,
    val cronExpression: String? = null,
    val workspacePath: Path,
    val metadata: Map<String, String> = emptyMap(),
)

enum class GoalStatus {
    CREATED,
    PLANNING,
    ACTIVE,
    BLOCKED,
    SUSPENDED,
    COMPLETED,
    FAILED,
}

enum class GoalPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class GoalPlan(
    val steps: List<PlanStep>,
    val generatedAt: Instant,
    val revisedAt: Instant? = null,
) {
    companion object {
        fun empty(): GoalPlan = GoalPlan(
            steps = emptyList(),
            generatedAt = Instant.EPOCH,
        )
    }
}

data class PlanStep(
    val id: String,
    val description: String,
    val status: StepStatus,
    val acceptanceCriteria: String,
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
    val waitCondition: WaitCondition? = null,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    val lastAttemptAt: Instant? = null,
    val completedAt: Instant? = null,
    val notes: String = "",
)

enum class StepStatus {
    PENDING,
    READY,
    IN_PROGRESS,
    BLOCKED,
    DONE,
    FAILED,
    SKIPPED,
}

data class WaitCondition(
    val type: WaitConditionType,
    val params: Map<String, String>,
    val registeredAt: Instant,
    val timeoutAt: Instant? = null,
    val onTimeout: TimeoutAction = TimeoutAction.FAIL,
    val asyncWait: AsyncActionWait? = null,
)

enum class WaitConditionType {
    TIMER,
    // Reserved for future push-based runtime events (webhooks, approvals, connector callbacks).
    // Persist/restore works today, but a generic event-ingestion satisfier is not implemented yet.
    EXTERNAL_EVENT,
    // Reserved for future pull-based named predicate checks that are not naturally async handles.
    // Persist/restore works today, but a generic checker registry/satisfier is not implemented yet.
    CONDITION_CHECK,
    CRON,
    ASYNC_OPERATION,
}

enum class TimeoutAction {
    FAIL,
    RETRY,
    ESCALATE,
}

/**
 * A unit of goal work selected by the GoalManager for the Ego to execute.
 */
data class GoalRunActivation(
    val goalId: String,
    val stepId: String,
    val rootInputId: String,
    val stepDescription: String,
    val acceptanceCriteria: String,
    val workingContext: String,
    val conversationContext: ConversationContext,
    val actionSuggestion: String = "",
    val wakeReason: String = "",
)

/**
 * Compact summary of a goal (~100 tokens) — Tier 1 context.
 * Always kept in memory by the GoalManager.
 */
data class GoalTier1Summary(
    val goalId: String,
    val title: String,
    val status: GoalStatus,
    val priority: GoalPriority,
    val currentStepDescription: String?,
    val blockers: List<String>,
    val lastWorkedAt: Instant?,
    val cronExpression: String? = null,
)
