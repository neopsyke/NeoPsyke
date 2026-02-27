package psyke.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

data class WebSearchResult(
    val summary: String,
    val snippets: List<String>,
)

interface WebSearchProvider {
    fun search(query: String, maxResults: Int): WebSearchResult
}

class MistralWebSearchProvider(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : WebSearchProvider {

    override fun search(query: String, maxResults: Int): WebSearchResult {
        val messages = listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                """
                You are a web research assistant.
                If your runtime has web access, use it. If not, provide best-effort knowledge and mark uncertainty.
                Return STRICT JSON only.
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.SYSTEM,
                """
                JSON schema:
                {
                  "summary":"short finding summary",
                  "snippets":["bullet-sized snippet", "... up to $maxResults items"]
                }
                Keep snippets short and factual.
                """.trimIndent()
            ),
            ChatMessage(ChatRole.USER, "Search query: $query")
        )

        val boundedMessages = TextSecurity.trimMessagesToBudget(messages, config.maxPromptTokens)
        val response = modelClient.chat(
            messages = boundedMessages,
            options = ChatRequestOptions(
                temperature = 0.1,
                maxTokens = config.maxCompletionTokens,
                metadata = ChatCallMetadata(
                    actor = "ego",
                    callSite = "web_search",
                    actionType = "web_search"
                )
            )
        )
        return parse(response.content, maxResults)
    }

    private fun parse(raw: String, maxResults: Int): WebSearchResult {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<WebSearchPayload>(json)
            val summary = payload.summary?.trim().orEmpty().ifBlank { "No summary returned." }
            val snippets = payload.snippets.orEmpty()
                .filter { it.isNotBlank() }
                .take(maxResults)
                .map { TextSecurity.clamp(it.trim(), 200) }
            WebSearchResult(summary = TextSecurity.clamp(summary, 280), snippets = snippets)
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to parse web-search response. Raw response: $raw" }
            WebSearchResult(
                summary = "Search output could not be parsed.",
                snippets = emptyList()
            )
        }
    }

    private data class WebSearchPayload(
        val summary: String? = null,
        val snippets: List<String>? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
