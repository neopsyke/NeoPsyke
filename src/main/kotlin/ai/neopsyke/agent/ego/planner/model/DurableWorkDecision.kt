package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from GoalWorkPlanner.
 */
sealed interface DurableWorkDecision {

    data class ExecuteStep(val candidate: ExecutionCandidate) : DurableWorkDecision

    data class RefinePlan(
        val urgency: Urgency = Urgency.MEDIUM,
        val goal: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : DurableWorkDecision

    data class MarkStepComplete(val reason: String) : DurableWorkDecision

    data class RequestClarification(val question: String) : DurableWorkDecision

    data class FailStep(val reason: String) : DurableWorkDecision
}
