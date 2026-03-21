package ai.neopsyke.agent.project

import kotlinx.coroutines.CoroutineScope
import ai.neopsyke.agent.ego.ActionLifecycleObserver
import ai.neopsyke.agent.id.ProjectRegistry
import ai.neopsyke.agent.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction

interface ProjectsGateway : ProjectRegistry, ActionLifecycleObserver {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    fun pendingWorkSummary(): String = ""
    fun nextWorkFromCue(cue: GoalRuntimeCue): ProjectWorkUnit? = null
    fun finalizeProjectCycle(rootInputId: String) {}
    fun executeOperation(request: ProjectOperationRequest): ProjectOperationResult =
        ProjectOperationResult(false, "Projects feature is disabled.")
    fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int = 0
    fun allProjects(): List<ProjectTier1Summary> = emptyList()
    fun projectStatus(projectId: String): ProjectState? = null
}

object NoopProjectsGateway : ProjectsGateway {
    override fun activeProjects(): List<ai.neopsyke.agent.id.Project> = emptyList()
}

data class ProjectOperationRequest(
    val operation: ProjectOperation,
    val projectId: String? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: ProjectPriority? = null,
    val completionCriteria: String? = null,
    val reason: String? = null,
)

data class ProjectOperationResult(
    val success: Boolean,
    val message: String,
    val projectId: String? = null,
)

enum class ProjectOperation {
    CREATE,
    STATUS,
    LIST,
    PAUSE,
    RESUME,
    REPRIORITIZE,
    COMPLETE,
    REVISE_PLAN,
}
