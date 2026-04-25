package ai.neopsyke.integrations.groq.websearch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngineHealth
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchSource
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatCallObserver
import ai.neopsyke.llm.ChatCallRecord
import ai.neopsyke.llm.ChatCallStatus
import ai.neopsyke.llm.ChatUsage
import ai.neopsyke.prompt.PromptCatalog
import ai.neopsyke.llm.bindFailSafeMetricsObserver
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.math.max

private val logger = KotlinLogging.logger {}
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class GroqConversationsWebSearchEngine(
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
        require(apiKey.isNotBlank()) { "Groq API key must be provided (set GROQ_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "groq",
            apiKey = apiKey,
            modelName = model,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun healthCheck(): WebSearchEngineHealth =
        WebSearchEngineHealth(
            available = true,
            detail = "Groq web search configured."
        )

    override fun search(query: String, maxResults: Int): WebSearchResult {
        val startedAt = System.nanoTime()
        val normalizedQuery = TextSecurity.clamp(query.trim(), MAX_QUERY_CHARS)
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt += 1
            val requestBody = buildRequestBody(normalizedQuery, maxResults)
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string()
                    if (!response.isSuccessful) {
                        throw GroqConversationsHttpException(
                            statusCode = response.code,
                            responseBody = rawBody
                        )
                    }
                    if (rawBody.isNullOrBlank()) {
                        throw IOException("Groq web search returned an empty response body.")
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
                    val usage = root.extractUsage()
                    val assistantText = root.extractAssistantText()
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
                    return result
                }
            } catch (ex: Exception) {
                lastError = ex
                val shouldRetry = ex.isRetryableWebSearchFailure() && attempt < MAX_RETRY_ATTEMPTS
                if (!shouldRetry) {
                    break
                }
                instrumentation.emit(
                    AgentEvents.warning(
                        "Groq web search attempt $attempt/$MAX_RETRY_ATTEMPTS failed; retrying."
                    )
                )
                try {
                    Thread.sleep(RETRY_BASE_DELAY_MS * attempt.toLong())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        val failure = lastError ?: IOException("Groq web search failed with unknown error.")
        observeCall(
            ChatCallRecord(
                model = model,
                metadata = WEB_SEARCH_METADATA,
                latencyMs = elapsedMillis(startedAt),
                status = ChatCallStatus.ERROR,
                errorCode = failure.toErrorCode(),
                errorMessage = failure.toErrorMessage()
            )
        )
        logger.warn(failure) { "Groq web search failed for query='${TextSecurity.preview(normalizedQuery, 100)}'." }
        return WebSearchResult(
            summary = TextSecurity.clamp("Groq web search unavailable: ${failure.message ?: "request failed"}", 280),
            snippets = emptyList(),
            sources = emptyList()
        )
    }

    override fun close() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    internal fun buildRequestBody(query: String, maxResults: Int): String {
        val prompt = PromptCatalog.shared.renderText(
            "integrations/web-search-request",
            mapOf("query" to query, "max_results" to maxResults.toString())
        ).text

        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to prompt
                )
            )
        )
        if (requiresBrowserSearchTool(model)) {
            payload["tools"] = listOf(
                mapOf("type" to "browser_search")
            )
            payload["tool_choice"] = "required"
            payload["reasoning_effort"] = "low"
        }
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
                            title = source.title?.trim().orEmpty().ifBlank { url },
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

    private fun requiresBrowserSearchTool(modelName: String): Boolean =
        !modelName.trim().lowercase().startsWith("groq/compound")

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
        private const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 150L
        private val WEB_SEARCH_METADATA = ChatCallMetadata(
            actor = "ego",
            callSite = "web_search",
            actionType = "web_search"
        )
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(45))
                .writeTimeout(Duration.ofSeconds(45))
                .callTimeout(Duration.ofSeconds(50))
                .build()

        private const val MAX_QUERY_CHARS = 320

        private fun elapsedMillis(startedAtNanos: Long): Long =
            max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)

        private fun normalizeUrl(url: String): String = url.trim().removeSuffix("/").lowercase()
    }
}

private class GroqConversationsHttpException(
    val statusCode: Int,
    val responseBody: String?,
) : IOException("Groq web search failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is GroqConversationsHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.isRetryableWebSearchFailure(): Boolean =
    when (this) {
        is GroqConversationsHttpException -> statusCode == 429 || statusCode >= 500
        is SocketTimeoutException -> true
        is IOException -> true
        else -> false
    }

private fun Exception.toErrorMessage(): String {
    val raw = when (this) {
        is GroqConversationsHttpException -> responseBody ?: message.orEmpty()
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
    collectText(path("output_text"), candidates)
    collectText(path("output"), candidates)

    val choices = path("choices")
    if (choices.isArray) {
        choices.forEach { choice ->
            collectText(choice.path("message").path("content"), candidates)
            collectText(choice.path("message"), candidates)
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
