package psyke.agent.project

import java.nio.file.Path
import java.time.Instant

data class Project(
    val id: String,
    val title: String,
    val instruction: String,
    val status: ProjectStatus,
    val priority: ProjectPriority,
    val plan: ProjectPlan,
    val completionCriteria: String,
    val createdAt: Instant,
    val lastWorkedAt: Instant? = null,
    val suspendedUntil: Instant? = null,
    val cronExpression: String? = null,
    val workspacePath: Path,
    val metadata: Map<String, String> = emptyMap(),
)

enum class ProjectStatus {
    CREATED,
    PLANNING,
    ACTIVE,
    BLOCKED,
    SUSPENDED,
    COMPLETED,
    FAILED,
}

enum class ProjectPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class ProjectPlan(
    val steps: List<PlanStep>,
    val generatedAt: Instant,
    val revisedAt: Instant? = null,
) {
    companion object {
        fun empty(): ProjectPlan = ProjectPlan(
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
)

enum class WaitConditionType {
    TIMER,
    EXTERNAL_EVENT,
    CONDITION_CHECK,
    CRON,
}

enum class TimeoutAction {
    FAIL,
    RETRY,
    ESCALATE,
}

/**
 * A unit of project work selected by the ProjectManager for the Ego to execute.
 */
data class ProjectWorkUnit(
    val projectId: String,
    val stepId: String,
    val rootInputId: String,
    val stepDescription: String,
    val acceptanceCriteria: String,
    val workingContext: String,
    val actionSuggestion: String = "",
    val wakeReason: String = "",
)

/**
 * Compact summary of a project (~100 tokens) — Tier 1 context.
 * Always kept in memory by the ProjectManager.
 */
data class ProjectTier1Summary(
    val projectId: String,
    val title: String,
    val status: ProjectStatus,
    val priority: ProjectPriority,
    val currentStepDescription: String?,
    val blockers: List<String>,
    val lastWorkedAt: Instant?,
)
