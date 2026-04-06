package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from GoalWorkPlanner.
 */
sealed interface GoalWorkDecision {

    data class ExecuteStep(val candidate: ExecutionCandidate) : GoalWorkDecision

    data class RefinePlan(
        val urgency: Urgency = Urgency.MEDIUM,
        val goal: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : GoalWorkDecision

    data class DeferUntilCondition(
        val urgency: Urgency = Urgency.MEDIUM,
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : GoalWorkDecision

    data class MarkStepComplete(val reason: String) : GoalWorkDecision

    data class RequestClarification(val question: String) : GoalWorkDecision

    data class FailStep(val reason: String) : GoalWorkDecision
}
