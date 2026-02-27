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

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class MistralChatClient(
    private val apiKey: String = System.getenv("MISTRAL_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : ChatModelClient {

    init {
        require(apiKey.isNotBlank()) { "Mistral API key must be provided (set MISTRAL_API_KEY)." }
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        require(messages.isNotEmpty()) { "At least one chat message is required." }

        val payload = MistralChatCompletionRequest(
            model = modelName,
            messages = messages.map { MistralChatMessage(role = it.role.apiValue, content = it.content) },
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            safePrompt = options.safePrompt
        )

        val requestBody = mapper.writeValueAsString(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        logger.debug { "Sending chat request to $modelName with ${messages.size} message(s)." }

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                val detail = responseBody?.let { " Response: $it" } ?: ""
                throw IOException("Mistral chat failed with status ${response.code}.$detail")
            }

            if (responseBody.isNullOrBlank()) {
                throw IOException("Mistral chat returned an empty response body.")
            }

            val parsed = mapper.readValue<MistralChatCompletionResponse>(responseBody)
            val choice = parsed.choices.firstOrNull()
                ?: throw IOException("Mistral chat returned no choices.")

            return ChatCompletion(
                content = choice.message.content,
                model = parsed.model ?: modelName,
                finishReason = choice.finishReason,
                id = parsed.id
            )
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
    }
}

private data class MistralChatCompletionRequest(
    val model: String,
    val messages: List<MistralChatMessage>,
    val temperature: Double? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    @JsonProperty("safe_prompt")
    val safePrompt: Boolean? = null,
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
)

private data class MistralChoice(
    val index: Int? = null,
    val message: MistralResponseMessage,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)

private data class MistralResponseMessage(
    val role: String,
    val content: String,
)
