package ai.neopsyke.integrations.mistral.websearch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class MistralWebSearchAgentSession private constructor(
    val agentId: String?,
    private val createdAgentId: String?,
    private val apiKey: String,
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val ownsHttpClient: Boolean,
) : AutoCloseable {

    override fun close() {
        createdAgentId?.let { ephemeralAgentId ->
            try {
                deleteAgent(ephemeralAgentId)
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to delete ephemeral web-search agent id=$ephemeralAgentId." }
            }
        }
        if (ownsHttpClient) {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdown()
        }
    }

    private fun deleteAgent(agentId: String) {
        val encoded = URLEncoder.encode(agentId, StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/agents/$encoded")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .delete()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").trim()
                logger.warn {
                    "Ephemeral web-search agent cleanup failed status=${response.code}. body=${body.take(180)}"
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.mistral.ai/v1"
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun start(
            apiKey: String,
            model: String,
            providedAgentId: String?,
            tool: MistralWebSearchTool = MistralWebSearchTool.WEB_SEARCH,
            baseUrl: String = DEFAULT_BASE_URL,
            httpClient: OkHttpClient? = null,
        ): MistralWebSearchAgentSession {
            val resolvedProvided = providedAgentId?.trim().orEmpty()
            if (resolvedProvided.isNotBlank()) {
                return MistralWebSearchAgentSession(
                    agentId = resolvedProvided,
                    createdAgentId = null,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    httpClient = httpClient ?: defaultHttpClient(),
                    ownsHttpClient = httpClient == null
                )
            }

            val resolvedHttpClient = httpClient ?: defaultHttpClient()
            val ownsClient = httpClient == null
            return try {
                val createdId = createAgent(
                    apiKey = apiKey,
                    model = model,
                    tool = tool,
                    baseUrl = baseUrl,
                    httpClient = resolvedHttpClient
                )
                logger.info { "Created ephemeral web-search agent id=$createdId for this run." }
                MistralWebSearchAgentSession(
                    agentId = createdId,
                    createdAgentId = createdId,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    httpClient = resolvedHttpClient,
                    ownsHttpClient = ownsClient
                )
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to create ephemeral web-search agent; web_search will remain unavailable." }
                MistralWebSearchAgentSession(
                    agentId = null,
                    createdAgentId = null,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    httpClient = resolvedHttpClient,
                    ownsHttpClient = ownsClient
                )
            }
        }

        private fun createAgent(
            apiKey: String,
            model: String,
            tool: MistralWebSearchTool,
            baseUrl: String,
            httpClient: OkHttpClient,
        ): String {
            val payload = mapOf(
                "model" to model,
                "name" to "neopsyke-web-search-${UUID.randomUUID().toString().substring(0, 8)}",
                "description" to "Ephemeral web-search agent created by NeoPsyke runtime.",
                "instructions" to "Use ${tool.apiValue} for up-to-date public web information.",
                "tools" to listOf(mapOf("type" to tool.apiValue)),
                "metadata" to mapOf("origin" to "neopsyke", "lifecycle" to "ephemeral")
            )
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/agents")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(mapper.writeValueAsString(payload).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Mistral agent creation failed with status ${response.code}: ${body.replace(Regex("\\s+"), " ").trim().take(180)}"
                    )
                }
                val parsed = mapper.readTree(body)
                val id = parsed.path("id").asText().trim()
                if (id.isBlank()) {
                    throw IllegalStateException("Mistral agent creation response did not include id.")
                }
                return id
            }
        }

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(30))
                .build()
    }
}
