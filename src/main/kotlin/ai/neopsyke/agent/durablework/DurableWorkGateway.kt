package ai.neopsyke.agent.durablework

import kotlinx.coroutines.CoroutineScope
import ai.neopsyke.agent.ego.ActionLifecycleObserver
import ai.neopsyke.agent.id.WorkItemRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.sensory.DurableWorkCue

interface DurableWorkGateway : WorkItemRegistry, ActionLifecycleObserver {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    fun pendingWorkSummary(): String = ""
    fun nextWorkFromCue(cue: DurableWorkCue): DurableWorkActivation? = null
    fun finalizeDurableWorkCycle(rootInputId: String) {}
    fun notifyStepPlannerNoop(rootInputId: String, reason: String) {}
    fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult =
        DurableWorkOperationResult(false, "Goals feature is disabled.")
    fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int = 0
    fun allWorkItems(): List<WorkItemTier1Summary> = emptyList()
    fun workItemStatus(workItemId: String): WorkItemState? = null
    fun workItemProjection(workItemId: String): WorkItemProjection? = null
    fun allProjections(): List<WorkItemProjection> = emptyList()
    fun reviewableResponsibilities(limit: Int = 8): List<ReviewableResponsibility> = emptyList()
}

object NoopDurableWorkGateway : DurableWorkGateway {
    override fun activeWorkItems(): List<ai.neopsyke.agent.id.WorkItemCommitment> = emptyList()
}

enum class ReviewRequestSource {
    MANUAL,
    ID,
}

data class DurableWorkOperationRequest(
    val operation: DurableWorkOperation,
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
    val planSteps: List<ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload>? = null,
    val reviewSource: ReviewRequestSource = ReviewRequestSource.MANUAL,
)

data class DurableWorkOperationResult(
    val success: Boolean,
    val message: String,
    val workItemId: String? = null,
)

enum class DurableWorkOperation {
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
