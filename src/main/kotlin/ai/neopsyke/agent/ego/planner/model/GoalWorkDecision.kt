package ai.neopsyke.agent.ego.planner.model

/**
 * Typed result from GoalWorkPlanner.
 */
sealed interface GoalWorkDecision {

    data class ExecuteStep(val candidate: ExecutionCandidate) : GoalWorkDecision

    data class DeferUntilCondition(
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : GoalWorkDecision

    data class MarkStepComplete(val reason: String) : GoalWorkDecision

    data class RequestClarification(val question: String) : GoalWorkDecision

    data class FailStep(val reason: String) : GoalWorkDecision
}
