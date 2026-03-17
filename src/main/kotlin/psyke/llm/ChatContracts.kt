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
    val safePrompt: Boolean? = null,
    val responseFormat: ChatResponseFormat? = null,
    val metadata: ChatCallMetadata = ChatCallMetadata()
)

sealed interface ChatResponseFormat {
    data class JsonSchema(
        val name: String,
        val schemaJson: String,
        val strict: Boolean = true,
        val relaxedSchemaJson: String? = null,
    ) : ChatResponseFormat
}

data class ChatCallMetadata(
    val actor: String = "",
    val callSite: String = "",
    val actionType: String? = null,
    val structuredOutputMode: String? = null,
    val sessionId: String? = null,
    val rootInputId: String? = null,
)

enum class ChatCallStatus {
    OK,
    ERROR,
    TIMEOUT,
    RATE_LIMITED,
    INVALID_RESPONSE
}

data class ChatCallRecord(
    val model: String,
    val metadata: ChatCallMetadata,
    val latencyMs: Long,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val status: ChatCallStatus,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

fun interface ChatCallObserver {
    fun onChatCall(record: ChatCallRecord)
}

data class ChatUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

data class ChatCompletion(
    val content: String,
    val model: String,
    val finishReason: String? = null,
    val id: String? = null,
    val usage: ChatUsage? = null
)

interface ChatModelClient : Closeable {
    val modelName: String

    fun chat(messages: List<ChatMessage>, options: ChatRequestOptions = ChatRequestOptions()): ChatCompletion

    override fun close() {}
}
