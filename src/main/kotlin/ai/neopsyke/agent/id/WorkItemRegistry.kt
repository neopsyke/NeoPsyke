package ai.neopsyke.agent.id

import java.time.Instant

/**
 * Registry of long-term user-sanctioned goals.
 *
 * Goals represent persistent user instructions that the agent should act on
 * continuously (e.g., "keep my inbox organised", "look for apartments in X area").
 * The Ego consults this registry when planning Id-driven activity, and the Superego
 * uses goal alignment to decide whether to approve Id-originated external actions.
 *
 * Stub interface — returns an empty list until the goal system is implemented.
 */
interface WorkItemRegistry {
    fun activeWorkItems(): List<WorkItemCommitment>
}

data class WorkItemCommitment(
    val id: String,
    val instruction: String,
    val lastActedAt: Instant? = null,
)

object EmptyWorkItemRegistry : WorkItemRegistry {
    override fun activeWorkItems(): List<WorkItemCommitment> = emptyList()
}
