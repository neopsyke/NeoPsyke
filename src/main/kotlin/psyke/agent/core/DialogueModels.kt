package psyke.agent.core

import java.time.Instant

enum class DialogueRole {
    USER,
    ASSISTANT
}

data class DialogueTurn(
    val role: DialogueRole,
    val content: String,
    val sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
    val interlocutor: Interlocutor? = null,
    val timestamp: Instant? = null,
)
