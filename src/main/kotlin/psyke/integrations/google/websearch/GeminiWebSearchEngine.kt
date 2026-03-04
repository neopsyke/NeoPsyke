package psyke.integrations.google.websearch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import psyke.agent.support.TextSecurity
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchEngineHealth
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.actions.websearch.WebSearchSource
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCallObserver
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import psyke.llm.ChatUsage
import psyke.llm.bindFailSafeMetricsObserver
import java.io.IOException
import java.net.URI
import java.time.Duration
import kotlin.math.max

private val logger = KotlinLogging.logger {}
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class GeminiWebSearchEngine(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val maxRawResponseChars: Int = 4_000,
) : WebSearchEngine, AutoCloseable {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        require(apiKey.isNotBlank()) { "Google API key must be provided (set GOOGLE_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "google",
            apiKey = apiKey,
            modelName = model,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun healthCheck(): WebSearchEngineHealth =
        WebSearchEngineHealth(
            available = true,
            detail = "Gemini web search configured via OpenAI-compatible endpoint."
        )

    override fun search(query: String, maxResults: Int): WebSearchResult {
        val startedAt = System.nanoTime()
        val requestBody = buildRequestBody(query, maxResults)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string()
                if (!response.isSuccessful) {
                    throw GeminiWebSearchHttpException(
                        statusCode = response.code,
                        responseBody = rawBody
                    )
                }
                if (rawBody.isNullOrBlank()) {
                    throw IOException("Gemini web search returned an empty response body.")
                }

                instrumentation.emit(
                    AgentEvents.llmRawResponse(
                        actor = "ego",
                        callSite = "web_search",
                        actionType = "web_search",
                        rawResponse = TextSecurity.clamp(rawBody, maxRawResponseChars)
                    )
                )

                val root = mapper.readTree(rawBody)
                val assistantText = root.extractAssistantText()
                val usage = root.extractUsage()

                val result = resolveResult(
                    assistantText = assistantText,
                    root = root,
                    maxResults = maxResults
                )

                observeCall(
                    ChatCallRecord(
                        model = root.path("model").asText().ifBlank { model },
                        metadata = WEB_SEARCH_METADATA,
                        latencyMs = elapsedMillis(startedAt),
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        totalTokens = usage?.totalTokens,
                        status = ChatCallStatus.OK
                    )
                )
                result
            }
        } catch (ex: Exception) {
            observeCall(
                ChatCallRecord(
                    model = model,
                    metadata = WEB_SEARCH_METADATA,
                    latencyMs = elapsedMillis(startedAt),
                    status = ChatCallStatus.ERROR,
                    errorCode = ex.toErrorCode(),
                    errorMessage = ex.toErrorMessage()
                )
            )
            logger.warn(ex) { "Gemini web search failed for query='${TextSecurity.preview(query, 100)}'." }
            WebSearchResult(
                summary = TextSecurity.clamp("Gemini web search unavailable: ${ex.message ?: "request failed"}", 280),
                snippets = emptyList(),
                sources = emptyList()
            )
        }
    }

    override fun close() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    internal fun buildRequestBody(query: String, maxResults: Int): String {
        val prompt = """
            Search query: $query
            Return STRICT JSON only:
            {
              "summary":"short finding summary",
              "snippets":["bullet-sized snippet", "... up to $maxResults items"],
              "sources":[{"title":"source title","url":"https://...","snippet":"optional source snippet"}]
            }
            Keep snippets factual and concise.
            Prioritize primary sources (official vendor docs, official pricing pages, standards, research papers).
            Avoid forums, social posts, link-aggregators, and SEO summaries unless primary sources are unavailable.
            If only secondary/community sources are available, state that clearly in summary.
        """.trimIndent()

        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to prompt
                )
            )
        )
        return mapper.writeValueAsString(payload)
    }

    private fun resolveResult(
        assistantText: String,
        root: JsonNode,
        maxResults: Int,
    ): WebSearchResult {
        parseStructuredPayload(assistantText, maxResults)?.let { parsed ->
            val fallbackSources = root.extractSources(
                assistantText = assistantText,
                maxResults = maxResults
            )
            val mergedSources = (parsed.sources + fallbackSources)
                .distinctBy { normalizeUrl(it.url) }
                .take(maxResults)
            return parsed.copy(sources = mergedSources)
        }

        val fallbackSources = root.extractSources(
            assistantText = assistantText,
            maxResults = maxResults
        )
        val fallbackSummary = assistantText.trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .ifBlank { "No summary returned." }
        val snippets = assistantText.lineSequence()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
            .take(maxResults)
            .map { TextSecurity.clamp(it, 200) }
            .toList()
        return WebSearchResult(
            summary = TextSecurity.clamp(fallbackSummary, 280),
            snippets = snippets,
            sources = fallbackSources
        )
    }

    private fun parseStructuredPayload(rawText: String, maxResults: Int): WebSearchResult? {
        return try {
            val json = TextSecurity.extractJsonObject(rawText)
            val payload = mapper.readValue<SearchPayload>(json)
            val summary = payload.summary?.trim().orEmpty().ifBlank { "No summary returned." }
            val snippets = payload.snippets.orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(maxResults)
                .map { TextSecurity.clamp(it, 200) }
            val sources = payload.sources.orEmpty()
                .mapNotNull { source ->
                    val url = source.url?.trim().orEmpty()
                    if (!url.isHttpUrl()) {
                        null
                    } else {
                        WebSearchSource(
                            title = source.title?.trim().orEmpty().ifBlank { hostOrUrl(url) },
                            url = url,
                            snippet = source.snippet?.trim()?.ifBlank { null }?.let { TextSecurity.clamp(it, 200) }
                        )
                    }
                }
                .distinctBy { normalizeUrl(it.url) }
                .take(maxResults)
            WebSearchResult(
                summary = TextSecurity.clamp(summary, 280),
                snippets = snippets,
                sources = sources
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun observeCall(record: ChatCallRecord) {
        try {
            effectiveCallObserver?.onChatCall(record)
        } catch (_: Exception) {
            // keep web-search execution robust when telemetry fan-out fails
        }
    }

    private data class SearchPayload(
        val summary: String? = null,
        val snippets: List<String>? = null,
        val sources: List<SearchSourcePayload>? = null,
    )

    private data class SearchSourcePayload(
        val title: String? = null,
        val url: String? = null,
        val snippet: String? = null,
    )

    companion object {
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
        private val WEB_SEARCH_METADATA = ChatCallMetadata(
            actor = "ego",
            callSite = "web_search",
            actionType = "web_search"
        )
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(45))
                .build()

        private fun elapsedMillis(startedAtNanos: Long): Long =
            max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)

        private fun normalizeUrl(url: String): String = url.trim().removeSuffix("/").lowercase()

        private fun hostOrUrl(rawUrl: String): String {
            val host = try {
                URI(rawUrl).host
            } catch (_: Exception) {
                null
            }
            return host?.ifBlank { rawUrl } ?: rawUrl
        }
    }
}

private class GeminiWebSearchHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Gemini web search failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is GeminiWebSearchHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toErrorMessage(): String {
    val raw = when (this) {
        is GeminiWebSearchHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return raw.replace(Regex("\\s+"), " ").trim().take(180)
}

private fun JsonNode.extractUsage(): ChatUsage? {
    val usage = path("usage")
    if (usage.isMissingNode || usage.isNull) {
        return null
    }
    val prompt = usage.path("prompt_tokens").asIntOrNull()
    val completion = usage.path("completion_tokens").asIntOrNull()
    val total = usage.path("total_tokens").asIntOrNull()
    if (prompt == null && completion == null && total == null) {
        return null
    }
    return ChatUsage(
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = total
    )
}

private fun JsonNode.extractAssistantText(): String {
    val candidates = mutableListOf<String>()

    val choices = path("choices")
    if (choices.isArray) {
        choices.forEach { choice ->
            collectText(choice.path("message").path("content"), candidates)
        }
    }

    collectText(path("message").path("content"), candidates)
    collectText(path("content"), candidates)

    return candidates.firstOrNull { it.isNotBlank() }.orEmpty()
}

private fun collectText(node: JsonNode, sink: MutableList<String>) {
    if (node.isMissingNode || node.isNull) {
        return
    }
    when {
        node.isTextual -> sink += node.asText().trim()
        node.isArray -> node.forEach { item -> collectText(item, sink) }
        node.isObject -> {
            collectText(node.path("text"), sink)
            collectText(node.path("content"), sink)
            collectText(node.path("value"), sink)
        }
    }
}

private fun JsonNode.extractSources(assistantText: String, maxResults: Int): List<WebSearchSource> {
    val collected = mutableListOf<WebSearchSource>()
    walkForSources(this, collected)

    val knownUrls = collected.map { it.url }.map { it.trim() }.toMutableSet()
    URL_REGEX.findAll(assistantText)
        .map { it.value.trim().trimEnd('.', ',', ';', ')') }
        .filter { it.isHttpUrl() }
        .forEach { url ->
            if (knownUrls.add(url)) {
                collected += WebSearchSource(
                    title = url,
                    url = url,
                    snippet = null
                )
            }
        }

    return collected
        .distinctBy { it.url.trim().removeSuffix("/").lowercase() }
        .take(maxResults)
}

private fun walkForSources(node: JsonNode, sink: MutableList<WebSearchSource>) {
    when {
        node.isObject -> {
            val url = node.path("url").asText().trim()
            if (url.isHttpUrl()) {
                val title = sequenceOf("title", "name", "source")
                    .map { key -> node.path(key).asText().trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: url
                val snippet = sequenceOf("snippet", "text", "quote")
                    .map { key -> node.path(key).asText().trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { TextSecurity.clamp(it, 200) }
                sink += WebSearchSource(
                    title = TextSecurity.clamp(title, 160),
                    url = url,
                    snippet = snippet
                )
            }
            node.fields().forEachRemaining { (_, child) ->
                walkForSources(child, sink)
            }
        }

        node.isArray -> node.forEach { child -> walkForSources(child, sink) }
    }
}

private fun JsonNode.asIntOrNull(): Int? = if (isInt || isLong) asInt() else null

private fun String.isHttpUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private val URL_REGEX = Regex("""https?://[^\s<>"']+""")
