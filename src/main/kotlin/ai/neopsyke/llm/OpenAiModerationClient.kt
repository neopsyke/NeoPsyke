package ai.neopsyke.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration

private val moderationMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
private val moderationJsonMediaType = "application/json; charset=utf-8".toMediaType()

data class OpenAiModerationDecision(
    val flagged: Boolean,
    val categories: Set<String>,
    val model: String,
    val id: String?,
)

class OpenAiModerationClient(
    private val apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : AutoCloseable {
    init {
        require(apiKey.isNotBlank()) { "OpenAI API key must be provided (set OPENAI_API_KEY)." }
    }

    fun moderate(input: String): OpenAiModerationDecision {
        require(input.isNotBlank()) { "Moderation input must not be blank." }

        val payload = OpenAiModerationRequest(
            model = model,
            input = input
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/moderations")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(moderationMapper.writeValueAsString(payload).toRequestBody(moderationJsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw OpenAiModerationHttpException(
                    statusCode = response.code,
                    responseBody = responseBody
                )
            }
            if (responseBody.isNullOrBlank()) {
                throw IOException("OpenAI moderation returned an empty response body.")
            }
            val parsed = moderationMapper.readValue<OpenAiModerationResponse>(responseBody)
            val first = parsed.results.firstOrNull()
                ?: throw IOException("OpenAI moderation returned no results.")
            val flaggedCategories = first.categories.orEmpty()
                .filterValues { it }
                .keys
                .toSortedSet()
            return OpenAiModerationDecision(
                flagged = first.flagged,
                categories = flaggedCategories,
                model = parsed.model ?: model,
                id = parsed.id
            )
        }
    }

    override fun close() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        const val DEFAULT_MODEL = "omni-moderation-latest"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(30))
                .build()
    }
}

fun moderateWithOpenAi(
    input: String,
    apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    baseUrl: String = "https://api.openai.com/v1",
    model: String = OpenAiModerationClient.DEFAULT_MODEL,
    httpClient: OkHttpClient? = null,
): OpenAiModerationDecision {
    val client = if (httpClient == null) {
        OpenAiModerationClient(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model
        )
    } else {
        OpenAiModerationClient(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            httpClient = httpClient
        )
    }
    client.use { moderationClient ->
        return moderationClient.moderate(input)
    }
}

private class OpenAiModerationHttpException(
    val statusCode: Int,
    responseBody: String?,
) : IOException("OpenAI moderations failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private data class OpenAiModerationRequest(
    val model: String,
    val input: String,
)

private data class OpenAiModerationResponse(
    val id: String? = null,
    val model: String? = null,
    val results: List<OpenAiModerationResult> = emptyList(),
)

private data class OpenAiModerationResult(
    val flagged: Boolean = false,
    val categories: Map<String, Boolean>? = null,
)
