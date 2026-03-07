package psyke.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
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

class GeminiChatClient(
    private val apiKey: String = System.getenv("GOOGLE_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        require(apiKey.isNotBlank()) { "Google API key must be provided (set GOOGLE_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "google",
            apiKey = apiKey,
            modelName = modelName,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        require(messages.isNotEmpty()) { "At least one chat message is required." }
        val startedAt = System.nanoTime()

        val payload = GeminiChatCompletionRequest(
            model = modelName,
            messages = messages.map { GeminiChatMessage(role = it.role.apiValue, content = it.content) },
            temperature = options.temperature,
            maxTokens = options.maxTokens
        )

        val requestBody = mapper.writeValueAsString(payload).toRequestBody(jsonMediaType)
        // Note: Google AI Studio OpenAI-compatible endpoint typically expects the API key in Authorization header or as a query param.
        // We'll use the Bearer token approach which is standard for OpenAI-compatible endpoints.
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        logger.debug { "Sending chat request to $modelName with ${messages.size} message(s)." }

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    throw GeminiHttpException(
                        statusCode = response.code,
                        responseBody = responseBody
                    )
                }

                if (responseBody.isNullOrBlank()) {
                    throw IOException("Gemini chat returned an empty response body.")
                }

                val parsed = mapper.readValue<GeminiChatCompletionResponse>(responseBody)
                val choice = parsed.choices.firstOrNull()
                    ?: throw IOException("Gemini chat returned no choices.")
                val content = choice.message.content.trim()
                if (content.isBlank()) {
                    throw IOException("Gemini chat returned empty message content.")
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
                    content = content,
                    model = resolvedModel,
                    finishReason = choice.finishReason,
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
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(45)) // Gemini can sometimes be slower for complex reasoning
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
}

private class GeminiHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Gemini chat failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is GeminiHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toErrorMessage(): String {
    val raw = when (this) {
        is GeminiHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return raw.replace(Regex("\\s+"), " ").trim().take(180)
}

private data class GeminiChatCompletionRequest(
    val model: String,
    val messages: List<GeminiChatMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
)

private data class GeminiChatMessage(
    val role: String,
    val content: String,
)

private data class GeminiChatCompletionResponse(
    val id: String?,
    @JsonProperty("object")
    val objectType: String? = null,
    val model: String?,
    val choices: List<GeminiChoice> = emptyList(),
    val usage: GeminiUsage? = null,
)

private data class GeminiChoice(
    val index: Int? = null,
    val message: GeminiResponseMessage,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)

private data class GeminiResponseMessage(
    val role: String,
    val content: String,
)

private data class GeminiUsage(
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
