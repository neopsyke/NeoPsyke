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
interface GoalRegistry {
    fun activeGoals(): List<GoalCommitment>
}

data class GoalCommitment(
    val id: String,
    val instruction: String,
    val lastActedAt: Instant? = null,
)

object EmptyGoalRegistry : GoalRegistry {
    override fun activeGoals(): List<GoalCommitment> = emptyList()
}
