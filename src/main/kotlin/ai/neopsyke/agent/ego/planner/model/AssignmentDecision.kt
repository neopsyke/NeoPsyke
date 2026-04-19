package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from AssignmentPlanner.
 */
sealed interface AssignmentDecision {

    data class ExecuteStep(val candidate: ExecutionCandidate) : AssignmentDecision

    data class RefinePlan(
        val urgency: Urgency = Urgency.MEDIUM,
        val assignment: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : AssignmentDecision

    data class MarkStepComplete(val reason: String) : AssignmentDecision

    data class RequestClarification(val question: String) : AssignmentDecision

    data class FailStep(val reason: String) : AssignmentDecision
}
