package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import java.nio.file.Path
import java.time.Instant
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import java.util.UUID

data class WorkItem(
    val id: String,
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
    val reviewPolicy: ReviewPolicy = ReviewPolicy(),
    val operatorSummary: String = "",
    val lastMeaningfulChangeAt: Instant? = null,
    val lastReviewAt: Instant? = null,
    val nextReviewAt: Instant? = null,
    val planRevision: Int = 1,
    val pendingWakeReasons: List<WakeReason> = emptyList(),
    val activeLease: String? = null,
    val failureWindow: FailureWindow = FailureWindow(),
    val schemaVersion: Int = CURRENT_WORK_ITEM_SCHEMA_VERSION,
    val activationCount: Int = 0,
    val createdAt: Instant,
    val lastWorkedAt: Instant? = null,
    val suspendedUntil: Instant? = null,
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val kind: WorkItemKind = WorkItemKind.RECURRENT_TASK,
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
    RETIRED,
}

enum class WorkItemKind {
    RECURRENT_TASK,
    RESPONSIBILITY,
    ;

    val userFacingLabel: String
        get() = when (this) {
            RECURRENT_TASK -> "RecurrentTask"
            RESPONSIBILITY -> "Responsibility"
        }

    companion object {
        fun fromSerialized(raw: String?): WorkItemKind =
            when (raw?.trim()?.uppercase()) {
                "RECURRENT_TASK", "RECURRING_TASK" -> RECURRENT_TASK
                "RESPONSIBILITY" -> RESPONSIBILITY
                // Phase-1 legacy shape.
                "LONG_TERM_GOAL" -> RECURRENT_TASK
                else -> RECURRENT_TASK
            }
    }
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

data class ReviewPolicy(
    val enabled: Boolean = false,
    val reviewIntervalMs: Long? = null,
    val maxSkippedReviews: Int = 3,
    val idReviewEligible: Boolean = true,
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
    val activationContext: ActivationContext = ActivationContext(),
    val groundingMetadata: GroundingMetadata,
    val planRevision: Int = 1,
    val deliveryPolicy: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val health: WorkItemHealth = WorkItemHealth.HEALTHY,
    val activationReason: String = "",
    val wakeSequence: Int = 0,
    val runtimeFacts: Map<String, String> = emptyMap(),
)

enum class WakeReasonType {
    PLAN_READY,
    STEP_COMPLETED,
    STEP_RETRY,
    STEP_UNBLOCKED,
    TIMER_DUE,
    WAIT_RESOLVED,
    DELIVERY_FLUSH,
    MANUAL_REVIEW,
    ID_REVIEW,
    RECOVERY,
    OVERDUE_CHECK,
    MONITOR_CHANGE_DETECTED,
    CRON_DUE,
    COALESCED_WAKE,
    WORK_ITEM_RESTORED_READY,
}

data class WakeReason(
    val type: WakeReasonType,
    val detail: String? = null,
    val occurredAt: Instant = Instant.now(),
) {
    fun renderHumanReadable(): String =
        detail?.takeIf { it.isNotBlank() }
            ?: type.name.lowercase().replace('_', ' ')
}

enum class RuntimeUrgency {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

data class MonitorActivationSnapshot(
    val summary: String = "",
    val sourceKeys: List<String> = emptyList(),
    val seenItemCount: Int = 0,
    val pendingChangeCount: Int = 0,
    val activeWindowKey: String? = null,
)

data class ActivationContext(
    val workItemId: String = "",
    val planRevision: Int = 1,
    val wakeSequence: Int = 0,
    val wakeReasons: List<WakeReason> = emptyList(),
    val runtimeUrgency: RuntimeUrgency = RuntimeUrgency.MEDIUM,
    val deliveryMode: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val monitoringSummary: MonitorActivationSnapshot = MonitorActivationSnapshot(),
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
    val schemaVersion: Int = CURRENT_WORK_ITEM_SCHEMA_VERSION,
    val leaseToken: String? = null,
    val leaseAcquiredAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
    val activationJournalPointer: Int = 0,
    val lastHeartbeatAt: Instant? = null,
    val retryCount: Int = 0,
    val lastWakeAt: Instant? = null,
    val wakeSequence: Int = 0,
    val lastWakeReasons: List<WakeReason> = emptyList(),
)

data class PlanState(
    val schemaVersion: Int = CURRENT_WORK_ITEM_SCHEMA_VERSION,
    val currentRevision: Int = 1,
    val currentStepPointer: String? = null,
    val producedKeys: Set<String> = emptySet(),
    val supersededRevisions: List<Int> = emptyList(),
)

enum class DeliveryDecision {
    NOTIFY_NOW,
    QUEUE_FOR_DIGEST,
    SUPPRESS_AS_DUPLICATE,
    SUPPRESS_AS_NO_CHANGE,
    MANUAL_REVIEW_ONLY,
    DEFER,
}

enum class DeliverySuppressionReason {
    DUPLICATE_DELTA,
    NO_MEANINGFUL_CHANGE,
    DIGEST_WINDOW_OPEN,
    MANUAL_REVIEW_POLICY,
    QUIET_HORIZON,
}

data class PendingDeliveryEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val summary: String,
    val itemKeys: List<String> = emptyList(),
    val fingerprint: String,
    val createdAt: Instant = Instant.now(),
    val meaningful: Boolean = true,
    val wakeReasonType: WakeReasonType? = null,
)

data class DigestWindow(
    val windowKey: String,
    val openedAt: Instant,
    val closedAt: Instant? = null,
    val itemKeys: List<String> = emptyList(),
)

data class DeliveryState(
    val schemaVersion: Int = CURRENT_WORK_ITEM_SCHEMA_VERSION,
    val notifyMode: DeliveryPolicy = DeliveryPolicy.IMMEDIATE,
    val lastDeliveryAt: Instant? = null,
    val lastDigestWindowAt: Instant? = null,
    val lastReportedSummary: String? = null,
    val pendingDigestEntries: List<String> = emptyList(),
    val pendingEntries: List<PendingDeliveryEntry> = emptyList(),
    val activeDigestWindow: DigestWindow? = null,
    val lastDecision: DeliveryDecision? = null,
    val lastSuppressionReason: DeliverySuppressionReason? = null,
    val lastDeliveredDeltaSignature: String? = null,
    val lastMeaningfulChangeAt: Instant? = null,
)

data class ArtifactsState(
    val schemaVersion: Int = CURRENT_WORK_ITEM_SCHEMA_VERSION,
    val artifactIndex: List<String> = emptyList(),
    val lastSummary: String? = null,
    val notableOutputs: List<String> = emptyList(),
)

data class MonitorSourceState(
    val sourceKey: String,
    val sourceKind: String = "generic",
    val cursorOrCheckpoint: String? = null,
    val lastScanAt: Instant? = null,
    val lastSuccessfulScanAt: Instant? = null,
    val lastScanSummary: String? = null,
)

enum class SeenItemLifecycleStatus {
    ACTIVE,
    CHANGED,
    REPORTED,
    QUIET,
    REMOVED,
}

data class SeenItemRecord(
    val stableItemKey: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val lastReportedAt: Instant? = null,
    val lastFingerprint: String? = null,
    val lifecycleStatus: SeenItemLifecycleStatus = SeenItemLifecycleStatus.ACTIVE,
)

enum class ChangeClass {
    NEW,
    UPDATED,
    REMOVED,
    REVIEW,
    NOTEWORTHY,
}

data class ChangeRecord(
    val itemKey: String,
    val changeClass: ChangeClass,
    val observedAt: Instant,
    val reportEligible: Boolean = true,
    val summary: String? = null,
)

data class ReportingWindowState(
    val activeWindowKey: String? = null,
    val openedAt: Instant? = null,
    val closedAt: Instant? = null,
    val itemsIncluded: List<String> = emptyList(),
    val lastDigestWatermark: Instant? = null,
)

data class ReviewRecord(
    val reviewedAt: Instant,
    val wakeReasonType: WakeReasonType,
    val outcome: String,
    val summary: String? = null,
)

data class ReviewState(
    val lastReviewAt: Instant? = null,
    val nextReviewDueAt: Instant? = null,
    val skippedReviewCount: Int = 0,
    val latestReviewReason: String? = null,
    val history: List<ReviewRecord> = emptyList(),
)

data class MonitorState(
    val schemaVersion: Int = CURRENT_WORK_ITEM_SCHEMA_VERSION,
    val sources: List<MonitorSourceState> = emptyList(),
    val seenItems: List<SeenItemRecord> = emptyList(),
    val changeLedger: List<ChangeRecord> = emptyList(),
    val reporting: ReportingWindowState = ReportingWindowState(),
    val review: ReviewState = ReviewState(),
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
    val kind: WorkItemKind = WorkItemKind.RECURRENT_TASK,
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

const val CURRENT_WORK_ITEM_SCHEMA_VERSION: Int = 2
