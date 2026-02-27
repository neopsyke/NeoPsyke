package psyke.llm

import java.io.Closeable

enum class ChatRole(val apiValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

data class ChatMessage(val role: ChatRole, val content: String)

data class ChatRequestOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val safePrompt: Boolean? = null
)

data class ChatCompletion(
    val content: String,
    val model: String,
    val finishReason: String? = null,
    val id: String? = null
)

interface ChatModelClient : Closeable {
    val modelName: String

    fun chat(messages: List<ChatMessage>, options: ChatRequestOptions = ChatRequestOptions()): ChatCompletion

    override fun close() {}
}
