package ai.neopsyke.agent.ego.planner.model

/**
 * Typed clarification request emitted when a lane cannot resolve
 * the next step without user input.
 */
data class ClarificationRequest(
    val question: String,
    val context: String? = null,
)
