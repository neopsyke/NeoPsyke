package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import java.nio.file.Path
import java.time.Instant
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement

data class WorkItem(
    val id: String,
    val kind: WorkItemKind = WorkItemKind.LONG_TERM_GOAL,
    val title: String,
    val objective: String = "",
    val instruction: String,
    val status: WorkItemStatus,
    val health: WorkItemHealth = WorkItemHealth.HEALTHY,
    val priority: WorkItemPriority,
    val plan: WorkItemPlan,
    val completionCriteria: String,
    val triggerPolicy: TriggerPolicy = TriggerPolicy(),
    val deliveryPolicy: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val planRevision: Int = 1,
    val pendingWakeReasons: List<String> = emptyList(),
    val activeLease: String? = null,
    val failureWindow: FailureWindow = FailureWindow(),
    val schemaVersion: Int = 1,
    val activationCount: Int = 0,
    val createdAt: Instant,
    val lastWorkedAt: Instant? = null,
    val suspendedUntil: Instant? = null,
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val workspacePath: Path,
    val metadata: Map<String, String> = emptyMap(),
)

enum class WorkItemStatus {
    CREATED,
    PLANNING,
    ACTIVE,
    BLOCKED,
    SUSPENDED,
    COMPLETED,
    FAILED,
    STALLED,
    NEEDS_ATTENTION,
}

enum class WorkItemKind {
    RECURRING_TASK,
    LONG_TERM_GOAL,
}

enum class WorkItemHealth {
    HEALTHY,
    BLOCKED,
    STALLED,
    FAILED,
    NEEDS_ATTENTION,
}

enum class DeliveryPolicy {
    IMMEDIATE,
    DIGEST,
    ONLY_ON_CHANGE,
    MANUAL_REVIEW,
}

data class TriggerPolicy(
    val cronExpression: String? = null,
    val timerIntervalMs: Long? = null,
)

data class FailureWindow(
    val maxFailuresInWindow: Int = 5,
    val windowDurationMs: Long = 3_600_000,
    val failureCount: Int = 0,
    val windowStartMs: Long = 0,
)

enum class WorkItemPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class WorkItemPlan(
    val revision: Int = 1,
    val steps: List<PlanStep>,
    val generatedAt: Instant,
    val revisedAt: Instant? = null,
) {
    companion object {
        fun empty(): WorkItemPlan = WorkItemPlan(
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
    val groundingRequirement: GroundingRequirement = GroundingRequirement.NOT_REQUIRED,
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
 * A unit of durable work selected by the DurableWorkRuntime for the Ego to execute.
 */
data class DurableWorkActivation(
    val workItemId: String,
    val stepId: String,
    val rootInputId: String,
    val stepDescription: String,
    val acceptanceCriteria: String,
    val workingContext: String,
    val conversationContext: ConversationContext,
    val actionSuggestion: String = "",
    val wakeReason: String = "",
    val groundingMetadata: GroundingMetadata,
    val planRevision: Int = 1,
    val deliveryPolicy: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val health: WorkItemHealth = WorkItemHealth.HEALTHY,
    val activationReason: String = "",
    val wakeSequence: Int = 0,
    val runtimeFacts: Map<String, String> = emptyMap(),
)

// ── Namespaced durable state ────────────────────────────────────────────

/**
 * Top-level state envelope with explicit namespaces.
 * Each namespace is owned by a specific runtime subsystem.
 */
data class DurableWorkState(
    val runtime: RuntimeState = RuntimeState(),
    val plan: PlanState = PlanState(),
    val delivery: DeliveryState = DeliveryState(),
    val artifacts: ArtifactsState = ArtifactsState(),
    val monitor: MonitorState = MonitorState(),
)

data class RuntimeState(
    val schemaVersion: Int = 1,
    val leaseToken: String? = null,
    val leaseAcquiredAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
    val activationJournalPointer: Int = 0,
    val lastHeartbeatAt: Instant? = null,
    val retryCount: Int = 0,
    val lastWakeAt: Instant? = null,
)

data class PlanState(
    val schemaVersion: Int = 1,
    val currentRevision: Int = 1,
    val currentStepPointer: String? = null,
    val producedKeys: Set<String> = emptySet(),
    val supersededRevisions: List<Int> = emptyList(),
)

data class DeliveryState(
    val schemaVersion: Int = 1,
    val notifyMode: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val lastDeliveryAt: Instant? = null,
    val lastDigestWindowAt: Instant? = null,
    val lastReportedSummary: String? = null,
    val pendingDigestEntries: List<String> = emptyList(),
)

data class ArtifactsState(
    val schemaVersion: Int = 1,
    val artifactIndex: List<String> = emptyList(),
    val lastSummary: String? = null,
    val notableOutputs: List<String> = emptyList(),
)

data class MonitorState(
    val schemaVersion: Int = 1,
    val lastObservationHash: String? = null,
    val lastMeaningfulChangeAt: Instant? = null,
    val dedupeKeys: Set<String> = emptySet(),
)

/**
 * Compact summary of a work item (~100 tokens) — Tier 1 context.
 * Always kept in memory by the DurableWorkRuntime.
 */
data class WorkItemTier1Summary(
    val workItemId: String,
    val kind: WorkItemKind = WorkItemKind.LONG_TERM_GOAL,
    val title: String,
    val status: WorkItemStatus,
    val health: WorkItemHealth = WorkItemHealth.HEALTHY,
    val priority: WorkItemPriority,
    val deliveryPolicy: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val currentStepDescription: String?,
    val blockers: List<String>,
    val lastWorkedAt: Instant?,
    val cronExpression: String? = null,
)
