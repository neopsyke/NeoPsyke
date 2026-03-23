package ai.neopsyke.agent.model

import java.time.Instant

enum class DialogueRole {
    USER,
    ASSISTANT,
    /** Turns originating from the Id (internal drives). Visible to planner/superego, ignored by DecisionVerifier's "latest user turn" heuristic. */
    INTERNAL
}

data class DialogueTurn(
    val role: DialogueRole,
    val content: String,
    val sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
    val interlocutor: Interlocutor? = null,
    val timestamp: Instant? = null,
)
