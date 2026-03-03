package psyke.mcp.memory.embedding

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import psyke.mcp.memory.MemoryServerConfig
import java.io.IOException
import java.time.Duration

private val logger = KotlinLogging.logger {}

class MistralEmbedder(private val config: MemoryServerConfig) : Embedder {

    companion object {
        const val MAX_RETRY_ATTEMPTS = 2
        const val MAX_INPUT_CHARS = 8192
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override val dimensions: Int get() = config.embeddingDimensions

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(15))
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(15))
        .build()

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override fun embed(text: String): FloatArray {
        val clamped = text.take(MAX_INPUT_CHARS)
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                return callEmbeddingApi(clamped)
            } catch (ex: Exception) {
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    logger.warn(ex) { "Embedding call failed, retrying (attempt $attempt/$MAX_RETRY_ATTEMPTS)" }
                } else {
                    logger.warn(ex) { "Embedding call failed after $MAX_RETRY_ATTEMPTS attempts" }
                    throw ex
                }
            }
        }
        throw IllegalStateException("Unreachable")
    }

    private fun callEmbeddingApi(text: String): FloatArray {
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to config.embeddingModel,
                "input" to listOf(text)
            )
        )
        val request = Request.Builder()
            .url("${config.embeddingBaseUrl.trimEnd('/')}/embeddings")
            .header("Authorization", "Bearer ${config.embeddingApiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string()?.take(500) ?: ""
                throw IOException("Embedding API returned ${response.code}: $body")
            }
            val tree = mapper.readTree(response.body?.string() ?: "")
            val data = tree["data"]?.firstOrNull()
                ?: throw IOException("No embedding data in response")
            val embeddingNode = data["embedding"]
                ?: throw IOException("Missing embedding field in response")
            if (!embeddingNode.isArray) {
                throw IOException("Embedding field is not an array")
            }
            return FloatArray(embeddingNode.size()) { i -> embeddingNode[i].floatValue() }
        }
    }
}
