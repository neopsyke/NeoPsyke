package psyke.integrations.mistral.websearch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.support.PromptBudgetAllocator
import psyke.agent.support.TextSecurity
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

@Deprecated(
    message = "Use MistralConversationsWebSearchEngine for real web-search tool execution.",
    replaceWith = ReplaceWith("MistralConversationsWebSearchEngine")
)
class LegacyPromptWebSearchEngine(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : WebSearchEngine {

    override fun search(query: String, maxResults: Int): WebSearchResult {
        val messages = PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 20,
                    content = """
                    You are a web research assistant.
                    If your runtime has web access, use it. If not, provide best-effort knowledge and mark uncertainty.
                    Return STRICT JSON only.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 16,
                    content = """
                    JSON schema:
                    {
                      "summary":"short finding summary",
                      "snippets":["bullet-sized snippet", "... up to $maxResults items"]
                    }
                    Keep snippets short and factual.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 12,
                    content = "Search query: $query"
                )
            ),
            maxTokens = config.planner.maxPromptTokens
        )

        var response = null as psyke.llm.ChatCompletion?
        var lastError: Exception? = null
        val retryAttempts = maxOf(1, config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.1,
                        maxTokens = config.planner.maxCompletionTokens,
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = "web_search",
                            actionType = "web_search"
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Legacy web search call failed for query='${TextSecurity.preview(query, 100)}'." }
            return WebSearchResult(
                summary = "Search unavailable due to model error.",
                snippets = emptyList()
            )
        }
        val resolvedResponse = response
        return parse(resolvedResponse.content, maxResults)
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
            logger.warn(ex) { "Failed to parse legacy web-search response. Raw response: $raw" }
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
