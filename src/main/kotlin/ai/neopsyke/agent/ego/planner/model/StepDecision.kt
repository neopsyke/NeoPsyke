package ai.neopsyke.agent.ego.planner.model

/**
 * Typed result from DeferredStepPlanner.
 */
sealed interface StepDecision {

    data class Execute(val candidate: ExecutionCandidate) : StepDecision

    data class RefinePlan(
        val goal: String,
        val steps: List<PlanDecomposition.PlanStep>,
    ) : StepDecision

    data class SkipStep(val reason: String) : StepDecision

    data class Answer(val payload: String, val summary: String) : StepDecision

    data class Defer(
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : StepDecision

    data class Clarify(val question: String) : StepDecision

    data class Fail(val reason: String) : StepDecision
}
