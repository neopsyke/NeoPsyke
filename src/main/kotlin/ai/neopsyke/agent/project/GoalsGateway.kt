package ai.neopsyke.agent.project

import kotlinx.coroutines.CoroutineScope
import ai.neopsyke.agent.ego.ActionLifecycleObserver
import ai.neopsyke.agent.id.GoalRegistry
import ai.neopsyke.agent.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction

interface GoalsGateway : GoalRegistry, ActionLifecycleObserver {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    fun pendingWorkSummary(): String = ""
    fun nextWorkFromCue(cue: GoalRuntimeCue): GoalRunActivation? = null
    fun finalizeGoalCycle(rootInputId: String) {}
    fun executeOperation(request: GoalOperationRequest): GoalOperationResult =
        GoalOperationResult(false, "Goals feature is disabled.")
    fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int = 0
    fun allProjects(): List<ProjectTier1Summary> = emptyList()
    fun projectStatus(projectId: String): ProjectState? = null
}

object NoopGoalsGateway : GoalsGateway {
    override fun activeGoals(): List<ai.neopsyke.agent.id.Goal> = emptyList()
}

data class GoalOperationRequest(
    val operation: GoalOperation,
    val projectId: String? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: ProjectPriority? = null,
    val completionCriteria: String? = null,
    val reason: String? = null,
)

data class GoalOperationResult(
    val success: Boolean,
    val message: String,
    val projectId: String? = null,
)

enum class GoalOperation {
    CREATE,
    STATUS,
    LIST,
    PAUSE,
    RESUME,
    REPRIORITIZE,
    COMPLETE,
    REVISE_PLAN,
}
