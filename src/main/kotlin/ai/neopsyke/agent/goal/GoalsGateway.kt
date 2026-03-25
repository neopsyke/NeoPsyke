package ai.neopsyke.agent.goal

import kotlinx.coroutines.CoroutineScope
import ai.neopsyke.agent.ego.ActionLifecycleObserver
import ai.neopsyke.agent.id.GoalRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue

interface GoalsGateway : GoalRegistry, ActionLifecycleObserver {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    fun pendingWorkSummary(): String = ""
    fun nextWorkFromCue(cue: GoalRuntimeCue): GoalRunActivation? = null
    fun finalizeGoalCycle(rootInputId: String) {}
    fun executeOperation(request: GoalOperationRequest): GoalOperationResult =
        GoalOperationResult(false, "Goals feature is disabled.")
    fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int = 0
    fun allGoals(): List<GoalTier1Summary> = emptyList()
    fun goalStatus(goalId: String): GoalState? = null
}

object NoopGoalsGateway : GoalsGateway {
    override fun activeGoals(): List<ai.neopsyke.agent.id.GoalCommitment> = emptyList()
}

data class GoalOperationRequest(
    val operation: GoalOperation,
    val goalId: String? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: GoalPriority? = null,
    val completionCriteria: String? = null,
    val cronExpression: String? = null,
    val reason: String? = null,
)

data class GoalOperationResult(
    val success: Boolean,
    val message: String,
    val goalId: String? = null,
)

enum class GoalOperation {
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
