package ai.neopsyke.agent.goal

import ai.neopsyke.agent.id.GoalRegistry
import ai.neopsyke.agent.id.GoalCommitment

/**
 * Adapter that implements [GoalRegistry] (used by existing Ego ambient context)
 * by delegating to [GoalManager].
 */
class GoalManagerRegistry(
    private val goalManager: GoalManager,
) : GoalRegistry {

    override fun activeGoals(): List<GoalCommitment> =
        goalManager.allGoals()
            .filter { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.BLOCKED }
            .map { summary ->
                GoalCommitment(
                    id = summary.goalId,
                    instruction = summary.title,
                    lastActedAt = summary.lastWorkedAt,
                )
            }
}
