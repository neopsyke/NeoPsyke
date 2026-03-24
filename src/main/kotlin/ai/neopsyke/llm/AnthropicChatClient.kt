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

class AnthropicChatClient(
    private val apiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        require(apiKey.isNotBlank()) { "Anthropic API key must be provided (set ANTHROPIC_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "anthropic",
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
            provider = LlmProvider.ANTHROPIC,
            modelName = modelName,
            responseFormat = options.responseFormat,
            mapper = mapper
        )
        StructuredOutputCompatibility.warningMessageIfNeeded(
            provider = LlmProvider.ANTHROPIC,
            modelName = modelName,
            requestedFormat = options.responseFormat,
            adaptation = responseFormatAdaptation,
            metadata = options.metadata
        )?.let { warning ->
            logger.warn { warning }
        }

        val anthropicMessages = messages
            .filter { it.role != ChatRole.SYSTEM }
            .map { message ->
                AnthropicChatMessage(
                    role = when (message.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> error("system role is handled via top-level system prompt")
                    },
                    content = message.content
                )
            }
        require(anthropicMessages.isNotEmpty()) {
            "Anthropic chat requires at least one user or assistant message."
        }

        val payload = AnthropicMessageRequest(
            model = modelName,
            maxTokens = options.maxTokens ?: DEFAULT_MAX_TOKENS,
            messages = anthropicMessages,
            system = messages
                .filter { it.role == ChatRole.SYSTEM }
                .joinToString(separator = "\n\n") { it.content.trim() }
                .ifBlank { null },
            temperature = options.temperature,
            outputConfig = responseFormatAdaptation.responseFormat.toAnthropicOutputConfig()
        )

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Content-Type", "application/json")
            .post(mapper.writeValueAsString(payload).toRequestBody(jsonMediaType))
            .build()

        logger.debug {
            "Sending Anthropic message request to $modelName with ${anthropicMessages.size} message(s), " +
                "system_prompt=${payload.system?.isNotBlank() == true}, " +
                "response_format=${if (options.responseFormat == null) "none" else "json_schema"}."
        }

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    throw AnthropicHttpException(
                        statusCode = response.code,
                        responseBody = responseBody
                    )
                }
                if (responseBody.isNullOrBlank()) {
                    throw IOException("Anthropic chat returned an empty response body.")
                }

                val parsed = mapper.readValue<AnthropicMessageResponse>(responseBody)
                val textBlocks = parsed.content
                    .filter { it.type == "text" }
                    .mapNotNull { it.text?.trim() }
                    .filter { it.isNotBlank() }
                if (textBlocks.isEmpty()) {
                    throw IOException(
                        "Anthropic chat returned no text content " +
                            "(stop_reason=${parsed.stopReason ?: "none"}, content_blocks=${parsed.content.size})."
                    )
                }
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
                    content = textBlocks.joinToString(separator = "\n"),
                    model = resolvedModel,
                    finishReason = parsed.stopReason,
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
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        private const val DEFAULT_MAX_TOKENS = 1024
        private const val ANTHROPIC_API_VERSION = "2023-06-01"

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(45))
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

    private fun ChatResponseFormat?.toAnthropicOutputConfig(): AnthropicOutputConfig? =
        when (this) {
            null -> null
            is ChatResponseFormat.JsonSchema -> AnthropicOutputConfig(
                format = AnthropicJsonSchemaFormat(
                    type = "json_schema",
                    schema = mapper.readTree(schemaJson)
                )
            )
        }
}

private class AnthropicHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Anthropic chat failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is AnthropicHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toProviderErrorDetails(): ProviderErrorDetails {
    val raw = when (this) {
        is AnthropicHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return ProviderErrorDetailsExtractor.fromRaw(raw)
}

private data class AnthropicMessageRequest(
    val model: String,
    @param:JsonProperty("max_tokens")
    val maxTokens: Int,
    val messages: List<AnthropicChatMessage>,
    val system: String? = null,
    val temperature: Double? = null,
    @param:JsonProperty("output_config")
    val outputConfig: AnthropicOutputConfig? = null,
)

private data class AnthropicChatMessage(
    val role: String,
    val content: String,
)

private data class AnthropicOutputConfig(
    val format: AnthropicJsonSchemaFormat,
)

private data class AnthropicJsonSchemaFormat(
    val type: String,
    val schema: JsonNode,
)

private data class AnthropicMessageResponse(
    val id: String?,
    val model: String?,
    val content: List<AnthropicContentBlock> = emptyList(),
    @param:JsonProperty("stop_reason")
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

private data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
)

private data class AnthropicUsage(
    @param:JsonProperty("input_tokens")
    val inputTokens: Int? = null,
    @param:JsonProperty("output_tokens")
    val outputTokens: Int? = null,
) {
    fun toChatUsage(): ChatUsage =
        ChatUsage(
            promptTokens = inputTokens,
            completionTokens = outputTokens,
            totalTokens = listOfNotNull(inputTokens, outputTokens).sum().takeIf { inputTokens != null || outputTokens != null }
        )
}
