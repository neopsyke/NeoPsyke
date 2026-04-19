package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.ActionType

/**
 * Typed multi-step plan structure produced by TaskDecompositionPlanner.
 */
data class PlanDecomposition(
    val assignment: String,
    val steps: List<PlanStep>,
) {
    data class PlanStep(
        val description: String,
        val expectedActionType: ActionType? = null,
    )
}
