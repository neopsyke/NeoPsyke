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
    val metadata: ChatCallMetadata = ChatCallMetadata()
)

data class ChatCallMetadata(
    val actor: String? = null,
    val callSite: String? = null,
    val actionType: String? = null,
)

enum class ChatCallStatus {
    OK,
    ERROR
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
