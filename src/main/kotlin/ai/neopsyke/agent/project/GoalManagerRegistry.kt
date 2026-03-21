package ai.neopsyke.agent.project

import ai.neopsyke.agent.id.GoalRegistry
import ai.neopsyke.agent.id.Project as IdProject
import java.time.Instant

/**
 * Adapter that implements [GoalRegistry] (used by existing Ego ambient context)
 * by delegating to [GoalManager].
 */
class GoalManagerRegistry(
    private val projectManager: GoalManager,
) : GoalRegistry {

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
