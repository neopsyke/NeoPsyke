package ai.neopsyke.agent.assignments

import kotlinx.coroutines.CoroutineScope
import ai.neopsyke.agent.ego.ActionLifecycleObserver
import ai.neopsyke.agent.id.WorkItemRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.sensory.AssignmentCue

interface AssignmentGateway : WorkItemRegistry, ActionLifecycleObserver {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    fun pendingWorkSummary(): String = ""
    fun nextWorkFromCue(cue: AssignmentCue): AssignmentActivation? = null
    fun finalizeAssignmentCycle(rootInputId: String) {}
    fun notifyStepPlannerNoop(rootInputId: String, reason: String) {}
    fun executeOperation(request: AssignmentOperationRequest): AssignmentOperationResult =
        AssignmentOperationResult(false, "Assignments feature is disabled.")
    fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int = 0
    fun allWorkItems(): List<WorkItemTier1Summary> = emptyList()
    fun workItemStatus(workItemId: String): WorkItemState? = null
    fun workItemProjection(workItemId: String): WorkItemProjection? = null
    fun allProjections(): List<WorkItemProjection> = emptyList()
    fun reviewableResponsibilities(limit: Int = 8): List<ReviewableResponsibility> = emptyList()
}

object NoopAssignmentGateway : AssignmentGateway {
    override fun activeWorkItems(): List<ai.neopsyke.agent.id.WorkItemCommitment> = emptyList()
}

enum class ReviewRequestSource {
    MANUAL,
    ID,
}

data class AssignmentOperationRequest(
    val operation: AssignmentOperation,
    val workItemId: String? = null,
    val workItemKind: WorkItemKind? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: WorkItemPriority? = null,
    val completionCriteria: String? = null,
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val operatorSummary: String? = null,
    val reason: String? = null,
    val planSteps: List<ai.neopsyke.agent.ego.planner.model.AssignmentPlanStepPayload>? = null,
    val reviewSource: ReviewRequestSource = ReviewRequestSource.MANUAL,
)

data class AssignmentOperationResult(
    val success: Boolean,
    val message: String,
    val workItemId: String? = null,
)

enum class AssignmentOperation {
    CREATE,
    STATUS,
    LIST,
    REVIEW,
    PAUSE,
    RESUME,
    REPRIORITIZE,
    COMPLETE,
    RETIRE,
    DELETE,
    DELETE_ALL,
    REVISE_PLAN,
    UPDATE,
}

data class ReviewableResponsibility(
    val workItemId: String,
    val title: String,
    val operatorSummary: String,
    val nextReviewAt: java.time.Instant? = null,
    val lastReviewAt: java.time.Instant? = null,
    val priority: WorkItemPriority = WorkItemPriority.MEDIUM,
)
