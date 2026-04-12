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
    fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult =
        DurableWorkOperationResult(false, "Goals feature is disabled.")
    fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int = 0
    fun allWorkItems(): List<WorkItemTier1Summary> = emptyList()
    fun workItemStatus(workItemId: String): WorkItemState? = null
    fun workItemProjection(workItemId: String): WorkItemProjection? = null
    fun allProjections(): List<WorkItemProjection> = emptyList()
}

object NoopDurableWorkGateway : DurableWorkGateway {
    override fun activeWorkItems(): List<ai.neopsyke.agent.id.WorkItemCommitment> = emptyList()
}

data class DurableWorkOperationRequest(
    val operation: DurableWorkOperation,
    val workItemId: String? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: WorkItemPriority? = null,
    val completionCriteria: String? = null,
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val reason: String? = null,
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
    PAUSE,
    RESUME,
    REPRIORITIZE,
    COMPLETE,
    DELETE,
    DELETE_ALL,
    REVISE_PLAN,
    UPDATE,
}
