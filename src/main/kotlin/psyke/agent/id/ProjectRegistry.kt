package psyke.agent.id

import java.time.Instant

/**
 * Registry of long-term user-sanctioned projects.
 *
 * Projects represent persistent user instructions that the agent should act on
 * continuously (e.g., "keep my inbox organised", "look for apartments in X area").
 * The Ego consults this registry when planning Id-driven activity, and the Superego
 * uses project alignment to decide whether to approve Id-originated external actions.
 *
 * Stub interface — returns an empty list until the project system is implemented.
 */
interface ProjectRegistry {
    fun activeProjects(): List<Project>
}

data class Project(
    val id: String,
    val instruction: String,
    val lastActedAt: Instant? = null,
)

object EmptyProjectRegistry : ProjectRegistry {
    override fun activeProjects(): List<Project> = emptyList()
}
