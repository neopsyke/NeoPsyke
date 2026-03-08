package psyke.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import kotlin.math.max

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class MistralChatClient(
    private val apiKey: String = System.getenv("MISTRAL_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        require(apiKey.isNotBlank()) { "Mistral API key must be provided (set MISTRAL_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "mistral",
            apiKey = apiKey,
            modelName = modelName,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        require(messages.isNotEmpty()) { "At least one chat message is required." }
        val startedAt = System.nanoTime()

        val payload = MistralChatCompletionRequest(
            model = modelName,
            messages = messages.map { MistralChatMessage(role = it.role.apiValue, content = it.content) },
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            safePrompt = options.safePrompt,
            responseFormat = options.responseFormat.toMistralResponseFormat()
        )

        val requestBody = mapper.writeValueAsString(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        logger.debug {
            "Sending chat request to $modelName with ${messages.size} message(s), " +
                "response_format=${if (options.responseFormat == null) "none" else "json_schema"}."
        }

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    throw MistralHttpException(
                        statusCode = response.code,
                        responseBody = responseBody
                    )
                }

                if (responseBody.isNullOrBlank()) {
                    throw IOException("Mistral chat returned an empty response body.")
                }

                val parsed = mapper.readValue<MistralChatCompletionResponse>(responseBody)
                val choices = parsed.choices
                val firstChoice = choices.firstOrNull()
                    ?: throw IOException("Mistral chat returned no choices.")
                val selectedChoice = choices.firstOrNull { choice ->
                    extractAssistantContentInfo(choice.message.content).trimmedChars > 0
                }
                if (selectedChoice == null) {
                    val contentInfo = extractAssistantContentInfo(firstChoice.message.content)
                    throw IOException(
                        "Mistral chat returned empty message content " +
                            "(finish_reason=${firstChoice.finishReason ?: "none"}, " +
                            "${contentInfo.summary()}, choices=${choices.size})."
                    )
                }
                val content = extractAssistantContentInfo(selectedChoice.message.content).trimmedText

                val usage = parsed.usage?.toChatUsage()
                val resolvedModel = parsed.model ?: modelName

                observeCall(
                    record = ChatCallRecord(
                        model = resolvedModel,
                        metadata = options.metadata,
                        latencyMs = elapsedMillis(startedAt),
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        totalTokens = usage?.totalTokens,
                        status = ChatCallStatus.OK
                    )
                )

                return ChatCompletion(
                    content = content,
                    model = resolvedModel,
                    finishReason = selectedChoice.finishReason,
                    id = parsed.id,
                    usage = usage
                )
            }
        } catch (ex: Exception) {
            observeCall(
                record = ChatCallRecord(
                    model = modelName,
                    metadata = options.metadata,
                    latencyMs = elapsedMillis(startedAt),
                    status = ChatCallStatus.ERROR,
                    errorCode = ex.toErrorCode(),
                    errorMessage = ex.toErrorMessage()
                )
            )
            throw ex
        }
    }

    override fun close() {
        // Free resources held by the HTTP client when needed.
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        const val DEFAULT_MODEL = "mistral-small-latest"
        private const val DEFAULT_BASE_URL = "https://api.mistral.ai/v1"

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(30))
                .build()

        private fun elapsedMillis(startedAtNanos: Long): Long =
            max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)
    }

    private fun observeCall(record: ChatCallRecord) {
        try {
            effectiveCallObserver?.onChatCall(record)
        } catch (ignored: Exception) {
            logger.warn(ignored) { "Failed to persist chat-call metrics; continuing." }
        }
    }

    private fun ChatResponseFormat?.toMistralResponseFormat(): MistralResponseFormat? =
        when (this) {
            null -> null
            is ChatResponseFormat.JsonSchema -> MistralResponseFormat(
                type = "json_schema",
                jsonSchema = MistralJsonSchemaFormat(
                    name = name,
                    strict = strict,
                    schema = mapper.readTree(schemaJson)
                )
            )
        }
}

private class MistralHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Mistral chat failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is MistralHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toErrorMessage(): String {
    val raw = when (this) {
        is MistralHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return raw.replace(Regex("\\s+"), " ").trim().take(180)
}

private data class MistralChatCompletionRequest(
    val model: String,
    val messages: List<MistralChatMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    @JsonProperty("safe_prompt")
    val safePrompt: Boolean? = null,
    @JsonProperty("response_format")
    val responseFormat: MistralResponseFormat? = null,
)

private data class MistralResponseFormat(
    val type: String,
    @JsonProperty("json_schema")
    val jsonSchema: MistralJsonSchemaFormat,
)

private data class MistralJsonSchemaFormat(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonNode,
)

private data class MistralChatMessage(
    val role: String,
    val content: String,
)

private data class MistralChatCompletionResponse(
    val id: String?,
    @JsonProperty("object")
    val objectType: String? = null,
    val model: String?,
    val choices: List<MistralChoice> = emptyList(),
    val usage: MistralUsage? = null,
)

private data class MistralChoice(
    val index: Int? = null,
    val message: MistralResponseMessage,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)

private data class MistralResponseMessage(
    val role: String,
    val content: JsonNode? = null,
)

private data class MistralUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int? = null,
    @JsonProperty("completion_tokens")
    val completionTokens: Int? = null,
    @JsonProperty("total_tokens")
    val totalTokens: Int? = null,
) {
    fun toChatUsage(): ChatUsage =
        ChatUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
}
