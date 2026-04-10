package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from FeedbackPlanner.
 */
sealed interface FeedbackDecision {

    data class Answer(
        val urgency: Urgency = Urgency.MEDIUM,
        val payload: String,
        val summary: String,
    ) : FeedbackDecision

    data class Retry(val candidate: ExecutionCandidate) : FeedbackDecision

    data class NextStep(val candidate: ExecutionCandidate) : FeedbackDecision

    data class RefinePlan(
        val urgency: Urgency = Urgency.MEDIUM,
        val goal: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : FeedbackDecision

    data class MarkBlocked(val reason: String) : FeedbackDecision

    data class MarkDone(val reason: String) : FeedbackDecision
}
