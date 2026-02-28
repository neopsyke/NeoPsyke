package psyke.agent.core

enum class DialogueRole {
    USER,
    ASSISTANT
}

data class DialogueTurn(
    val role: DialogueRole,
    val content: String,
)
