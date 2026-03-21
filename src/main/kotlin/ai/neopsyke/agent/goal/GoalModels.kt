package ai.neopsyke.agent.goal

import java.time.Instant

data class Goal(
    val id: String,
    val title: String,
    val objective: String,
    val kind: GoalKind,
    val priority: GoalPriority,
    val lifecycle: GoalLifecycle,
    val triggerPolicy: GoalTriggerPolicy,
    val sourceBindings: List<GoalSourceBinding> = emptyList(),
    val outputPolicy: GoalOutputPolicy = GoalOutputPolicy(),
    val autonomyPolicy: GoalAutonomyPolicy = GoalAutonomyPolicy(),
    val memoryPolicy: GoalMemoryPolicy = GoalMemoryPolicy(),
    val recurrencePolicy: GoalRecurrencePolicy? = null,
    val activeRunId: String? = null,
    val createdAt: Instant,
    val lastActivatedAt: Instant? = null,
    val lastCompletedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class GoalKind {
    SYNTHESIS,
    MONITORING,
    MAINTENANCE,
    SEARCH,
    AUDIT,
    OPTIMIZATION,
    EXECUTION,
}

enum class GoalPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class GoalLifecycle {
    DORMANT,
    ACTIVE,
    BLOCKED,
    SUSPENDED,
    COMPLETED,
    FAILED,
    ARCHIVED,
}

data class GoalRecurrencePolicy(
    val enabled: Boolean = false,
    val scheduleSpec: String? = null,
)

data class GoalTriggerPolicy(
    val triggers: List<GoalTrigger>,
    val cooldown: GoalCooldownPolicy? = null,
    val dedupe: GoalDedupePolicy? = null,
)

data class GoalCooldownPolicy(
    val minInterval: String? = null,
)

data class GoalDedupePolicy(
    val fingerprintScope: String = "goal",
    val suppressWindow: String? = null,
)

sealed interface GoalTrigger {
    data class Schedule(val spec: String) : GoalTrigger
    data class ExternalEvent(val source: String, val filter: Map<String, String> = emptyMap()) : GoalTrigger
    data class SourcePoll(val sourceId: String, val interval: String) : GoalTrigger
    data class StateCondition(val condition: String) : GoalTrigger
    data class AsyncResume(val providerType: String) : GoalTrigger
    data class Manual(val label: String = "manual") : GoalTrigger
}

data class GoalSourceBinding(
    val sourceId: String,
    val sourceType: String,
    val accessMode: GoalSourceAccessMode,
    val query: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class GoalSourceAccessMode {
    READ,
    READ_WRITE,
}

data class GoalOutputPolicy(
    val notifyMode: GoalNotifyMode = GoalNotifyMode.BATCH,
    val channel: String = "default",
    val onlyIfChanged: Boolean = true,
    val minPriorityToNotify: GoalPriority = GoalPriority.MEDIUM,
)

enum class GoalNotifyMode {
    IMMEDIATE,
    BATCH,
    DIGEST,
    SILENT,
}

data class GoalAutonomyPolicy(
    val mayDraft: Boolean = true,
    val mayNotify: Boolean = true,
    val mayActWithoutApproval: Boolean = false,
    val mayFinalizeWithoutReview: Boolean = false,
)

data class GoalMemoryPolicy(
    val retainSeenFingerprints: Boolean = true,
    val retainCheckpoints: Boolean = true,
    val retainLastNotification: Boolean = true,
    val noveltyWindow: String? = null,
)

data class GoalMemoryState(
    val seenFingerprints: Set<String> = emptySet(),
    val checkpoints: Map<String, String> = emptyMap(),
    val lastNotificationFingerprint: String? = null,
    val lastRunSummary: String? = null,
)

data class GoalRun(
    val id: String,
    val goalId: String,
    val triggerCause: String,
    val cognitiveThreadId: String,
    val status: GoalRunStatus,
    val plan: GoalExecutionPlan? = null,
    val waitingOn: List<GoalWaitCondition> = emptyList(),
    val startedAt: Instant,
    val lastUpdatedAt: Instant,
)

enum class GoalRunStatus {
    CREATED,
    ACTIVE,
    BLOCKED,
    SUSPENDED,
    DONE,
    FAILED,
}

data class GoalWaitCondition(
    val type: String,
    val timeoutAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class GoalExecutionPlan(
    val steps: List<GoalStep>,
    val generatedAt: Instant,
    val revisedAt: Instant? = null,
)

data class GoalStep(
    val id: String,
    val description: String,
    val acceptanceCriteria: String,
    val status: GoalStepStatus,
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
)

enum class GoalStepStatus {
    PENDING,
    READY,
    IN_PROGRESS,
    BLOCKED,
    DONE,
    FAILED,
    SKIPPED,
}
