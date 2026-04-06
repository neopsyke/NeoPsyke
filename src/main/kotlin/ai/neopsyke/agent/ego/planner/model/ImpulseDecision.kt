package ai.neopsyke.agent.ego.planner.model

/**
 * Typed result from ImpulsePlanner.
 */
sealed interface ImpulseDecision {

    data class Research(val candidate: ExecutionCandidate) : ImpulseDecision

    data class Reflect(
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : ImpulseDecision

    data class ContactUser(val payload: String, val summary: String) : ImpulseDecision

    data class Noop(val reason: String) : ImpulseDecision
}
