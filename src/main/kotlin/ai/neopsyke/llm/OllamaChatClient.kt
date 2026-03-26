package ai.neopsyke.llm

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonInclude
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

class OllamaChatClient(
    private val apiKey: String = System.getenv("OLLAMA_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        val binding = bindFailSafeMetricsObserver(
            provider = "ollama",
            apiKey = apiKey.ifBlank { "local-ollama" },
            modelName = modelName,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        require(messages.isNotEmpty()) { "At least one chat message is required." }
        val startedAt = System.nanoTime()
        val responseFormatAdaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.OLLAMA,
            modelName = modelName,
            responseFormat = options.responseFormat,
            mapper = mapper
        )
        StructuredOutputCompatibility.warningMessageIfNeeded(
            provider = LlmProvider.OLLAMA,
            modelName = modelName,
            requestedFormat = options.responseFormat,
            adaptation = responseFormatAdaptation,
            metadata = options.metadata
        )?.let { warning ->
            logger.warn { warning }
        }

        val payload = OllamaChatRequest(
            model = modelName,
            messages = messages.map { OllamaChatMessage(role = it.role.apiValue, content = it.content) },
            stream = false,
            format = responseFormatAdaptation.responseFormat.toOllamaFormat(),
            options = OllamaRequestOptions(
                temperature = options.temperature,
                numPredict = options.maxTokens
            ).takeIf { it.temperature != null || it.numPredict != null }
        )
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat")
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val request = builder
            .post(mapper.writeValueAsString(payload).toRequestBody(jsonMediaType))
            .build()

        logger.debug {
            "Sending Ollama chat request to $modelName with ${messages.size} message(s), " +
                "stream=false, response_format=${if (options.responseFormat == null) "none" else "json_schema"}."
        }

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    throw OllamaHttpException(
                        statusCode = response.code,
                        responseBody = responseBody
                    )
                }
                if (responseBody.isNullOrBlank()) {
                    throw IOException("Ollama chat returned an empty response body.")
                }

                val parsed = mapper.readValue<OllamaChatResponse>(responseBody)
                val content = parsed.message?.content?.trim().orEmpty()
                if (content.isBlank()) {
                    throw IOException(
                        "Ollama chat returned empty message content " +
                            "(done_reason=${parsed.doneReason ?: "none"})."
                    )
                }
                val usage = parsed.toChatUsage()
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
                    finishReason = parsed.doneReason,
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
        const val DEFAULT_MODEL = "gpt-oss"
        private const val DEFAULT_BASE_URL = "http://localhost:11434/api"

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(60))
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

    private fun ChatResponseFormat?.toOllamaFormat(): JsonNode? =
        when (this) {
            null -> null
            is ChatResponseFormat.JsonSchema -> mapper.readTree(schemaJson)
        }
}

private class OllamaHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Ollama chat failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is OllamaHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toProviderErrorDetails(): ProviderErrorDetails {
    val raw = when (this) {
        is OllamaHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return ProviderErrorDetailsExtractor.fromRaw(raw)
}

private data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = false,
    val format: JsonNode? = null,
    val options: OllamaRequestOptions? = null,
)

private data class OllamaChatMessage(
    val role: String,
    val content: String,
)

private data class OllamaRequestOptions(
    val temperature: Double? = null,
    @param:JsonProperty("num_predict")
    val numPredict: Int? = null,
)

private data class OllamaChatResponse(
    val model: String?,
    val message: OllamaResponseMessage? = null,
    val done: Boolean? = null,
    @param:JsonProperty("done_reason")
    val doneReason: String? = null,
    @param:JsonProperty("prompt_eval_count")
    val promptEvalCount: Int? = null,
    @param:JsonProperty("eval_count")
    val evalCount: Int? = null,
) {
    fun toChatUsage(): ChatUsage? {
        val prompt = promptEvalCount
        val completion = evalCount
        if (prompt == null && completion == null) return null
        return ChatUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = listOfNotNull(prompt, completion).sum()
        )
    }
}

private data class OllamaResponseMessage(
    val role: String? = null,
    val content: String? = null,
)
