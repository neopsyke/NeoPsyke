package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from ContinuationPlanner.
 */
sealed interface ContinuationDecision {

    data class Execute(val candidate: ExecutionCandidate) : ContinuationDecision

    data class RefinePlan(
        val urgency: Urgency = Urgency.MEDIUM,
        val goal: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : ContinuationDecision

    data class SkipStep(val reason: String) : ContinuationDecision

    data class Answer(val payload: String, val summary: String) : ContinuationDecision

    data class Clarify(val question: String) : ContinuationDecision

    data class Fail(val reason: String) : ContinuationDecision
}
