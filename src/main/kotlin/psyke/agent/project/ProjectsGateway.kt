package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import psyke.agent.ego.ActionLifecycleObserver
import psyke.agent.id.ProjectRegistry
import psyke.agent.model.ActionOutcome
import psyke.agent.model.PendingAction

interface ProjectsGateway : ProjectRegistry, ActionLifecycleObserver {
    fun start(scope: CoroutineScope) {}
    fun stop() {}
    fun pendingWorkSummary(): String = ""
    fun nextWorkFromSignal(signal: psyke.agent.cortex.sensory.ProjectSignal): ProjectWorkUnit? = null
    fun finalizeProjectCycle(rootInputId: String) {}
    fun executeOperation(request: ProjectOperationRequest): ProjectOperationResult =
        ProjectOperationResult(false, "Projects feature is disabled.")
    fun allProjects(): List<ProjectTier1Summary> = emptyList()
    fun projectStatus(projectId: String): ProjectState? = null
}

object NoopProjectsGateway : ProjectsGateway {
    override fun activeProjects(): List<psyke.agent.id.Project> = emptyList()
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
