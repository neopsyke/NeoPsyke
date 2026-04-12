package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.Urgency

/**
 * Typed result from ImpulsePlanner.
 */
sealed interface ImpulseDecision {

    data class Research(val candidate: ExecutionCandidate) : ImpulseDecision

    data class Reflect(
        val urgency: Urgency = Urgency.MEDIUM,
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : ImpulseDecision

    data class ContactUser(
        val urgency: Urgency = Urgency.MEDIUM,
        val payload: String,
        val summary: String,
    ) : ImpulseDecision

    data class Noop(val reason: String) : ImpulseDecision
}
