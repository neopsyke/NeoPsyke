package ai.neopsyke.llm

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
    val reasoningEffort: String? = null,
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
    val cognitiveRole: String? = null,
    val trigger: String? = null,
    val originSource: String? = null,
    val needId: String? = null,
    val rootImpulseId: String? = null,
    val thoughtId: Long? = null,
    val planId: String? = null,
    val planStepIndex: Int? = null,
    val planTotalSteps: Int? = null,
    val planStepDescription: String? = null,
    val structuredOutputMode: String? = null,
    val promptId: String? = null,
    val promptVersion: Int? = null,
    val promptHash: String? = null,
    val schemaId: String? = null,
    val schemaHash: String? = null,
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
    val providerErrorType: String? = null,
    val providerErrorCode: String? = null,
    val failedGenerationPreview: String? = null,
    val errorBodyPreview: String? = null,
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
