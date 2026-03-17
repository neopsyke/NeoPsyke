package psyke.agent.project

import psyke.agent.id.ProjectRegistry
import psyke.agent.id.Project as IdProject
import java.time.Instant

/**
 * Adapter that implements [ProjectRegistry] (used by existing Ego ambient context)
 * by delegating to [ProjectManager].
 */
class ProjectManagerRegistry(
    private val projectManager: ProjectManager,
) : ProjectRegistry {

    override fun activeProjects(): List<IdProject> =
        projectManager.allProjects()
            .filter { it.status == ProjectStatus.ACTIVE || it.status == ProjectStatus.BLOCKED }
            .map { summary ->
                IdProject(
                    id = summary.projectId,
                    instruction = summary.title,
                    lastActedAt = summary.lastWorkedAt,
                )
            }
}
