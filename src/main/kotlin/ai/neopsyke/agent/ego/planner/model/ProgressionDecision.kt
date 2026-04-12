package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from ProgressionPlanner (handles both continuations and action feedback).
 */
sealed interface ProgressionDecision {

    data class Execute(val candidate: ExecutionCandidate) : ProgressionDecision

    data class RefinePlan(
        val urgency: Urgency = Urgency.MEDIUM,
        val goal: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : ProgressionDecision

    data class Answer(
        val urgency: Urgency = Urgency.MEDIUM,
        val payload: String,
        val summary: String,
    ) : ProgressionDecision

    data class Fail(val reason: String) : ProgressionDecision
}
