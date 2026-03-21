package ai.neopsyke.llm

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

class GroqChatClient(
    private val apiKey: String = System.getenv("GROQ_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        require(apiKey.isNotBlank()) { "Groq API key must be provided (set GROQ_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "groq",
            apiKey = apiKey,
            modelName = modelName,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        require(messages.isNotEmpty()) { "At least one chat message is required." }
        val startedAt = System.nanoTime()
        val responseFormatAdaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.GROQ,
            modelName = modelName,
            responseFormat = options.responseFormat,
            mapper = mapper
        )
        StructuredOutputCompatibility.warningMessageIfNeeded(
            provider = LlmProvider.GROQ,
            modelName = modelName,
            requestedFormat = options.responseFormat,
            adaptation = responseFormatAdaptation,
            metadata = options.metadata
        )?.let { warning ->
            logger.warn { warning }
        }

        val payload = GroqChatCompletionRequest(
            model = modelName,
            messages = messages.map { GroqChatMessage(role = it.role.apiValue, content = it.content) },
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            responseFormat = responseFormatAdaptation.responseFormat.toGroqResponseFormat()
        )

        val requestBody = mapper.writeValueAsString(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
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
                    throw GroqHttpException(
                        statusCode = response.code,
                        responseBody = responseBody
                    )
                }

                if (responseBody.isNullOrBlank()) {
                    throw IOException("Groq chat returned an empty response body.")
                }

                val parsed = mapper.readValue<GroqChatCompletionResponse>(responseBody)
                val choices = parsed.choices
                val firstChoice = choices.firstOrNull()
                    ?: throw IOException("Groq chat returned no choices.")
                val selectedChoice = choices.firstOrNull { choice ->
                    extractAssistantContentInfo(choice.message.content).trimmedChars > 0
                }
                if (selectedChoice == null) {
                    val contentInfo = extractAssistantContentInfo(firstChoice.message.content)
                    throw IOException(
                        "Groq chat returned empty message content " +
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
            val errorDetails = ex.toProviderErrorDetails()
            observeCall(
                record = ChatCallRecord(
                    model = modelName,
                    metadata = options.metadata,
                    latencyMs = elapsedMillis(startedAt),
                    status = ChatCallStatus.ERROR,
                    errorCode = ex.toErrorCode(),
                    errorMessage = errorDetails.summary,
                    providerErrorType = errorDetails.providerErrorType,
                    providerErrorCode = errorDetails.providerErrorCode,
                    failedGenerationPreview = errorDetails.failedGenerationPreview,
                    errorBodyPreview = errorDetails.errorBodyPreview
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
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        private const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

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

    private fun ChatResponseFormat?.toGroqResponseFormat(): GroqResponseFormat? =
        when (this) {
            null -> null
            is ChatResponseFormat.JsonSchema -> GroqResponseFormat(
                type = "json_schema",
                jsonSchema = GroqJsonSchemaFormat(
                    name = name,
                    strict = strict,
                    schema = mapper.readTree(schemaJson)
                )
            )
        }
}

private class GroqHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Groq chat failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is GroqHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toProviderErrorDetails(): ProviderErrorDetails {
    val raw = when (this) {
        is GroqHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return ProviderErrorDetailsExtractor.fromRaw(raw)
}

private data class GroqChatCompletionRequest(
    val model: String,
    val messages: List<GroqChatMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    @JsonProperty("response_format")
    val responseFormat: GroqResponseFormat? = null,
)

private data class GroqResponseFormat(
    val type: String,
    @JsonProperty("json_schema")
    val jsonSchema: GroqJsonSchemaFormat,
)

private data class GroqJsonSchemaFormat(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonNode,
)

private data class GroqChatMessage(
    val role: String,
    val content: String,
)

private data class GroqChatCompletionResponse(
    val id: String?,
    @JsonProperty("object")
    val objectType: String? = null,
    val model: String?,
    val choices: List<GroqChoice> = emptyList(),
    val usage: GroqUsage? = null,
)

private data class GroqChoice(
    val index: Int? = null,
    val message: GroqResponseMessage,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)

private data class GroqResponseMessage(
    val role: String,
    val content: JsonNode? = null,
)

private data class GroqUsage(
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
