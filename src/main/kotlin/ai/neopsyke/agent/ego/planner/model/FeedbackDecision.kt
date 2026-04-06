package ai.neopsyke.agent.ego.planner.model

/**
 * Typed result from FeedbackPlanner.
 */
sealed interface FeedbackDecision {

    data class Answer(val payload: String, val summary: String) : FeedbackDecision

    data class Retry(val candidate: ExecutionCandidate) : FeedbackDecision

    data class NextStep(val candidate: ExecutionCandidate) : FeedbackDecision

    data class Defer(
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : FeedbackDecision

    data class MarkBlocked(val reason: String) : FeedbackDecision

    data class MarkDone(val reason: String) : FeedbackDecision
}
