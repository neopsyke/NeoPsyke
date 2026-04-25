package ai.neopsyke.integrations.mistral.websearch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.RetryPolicy
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.prompt.PromptCatalog

private val logger = KotlinLogging.logger {}

@Deprecated(
    message = "Use MistralConversationsWebSearchEngine for real web-search tool execution.",
    replaceWith = ReplaceWith("MistralConversationsWebSearchEngine")
)
class LegacyPromptWebSearchEngine(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : WebSearchEngine {

    override fun search(query: String, maxResults: Int): WebSearchResult {
        val prompt = PromptCatalog.shared.renderSections(
            "integrations/legacy-web-search",
            mapOf("query" to query, "max_results" to maxResults.toString())
        )
        val promptAllocation = PromptBudgetAllocator.allocate(
            sections = prompt.sections,
            maxTokens = config.maxLlmPromptTokens
        )
        instrumentation.emit(
            AgentEvent(
                type = "prompt_budget_allocation",
                data = promptAllocation.diagnostics.toTelemetryData(callSite = "legacy_web_search_prompt"),
            )
        )
        val messages = promptAllocation.messages

        var response = null as ai.neopsyke.llm.ChatCompletion?
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.llmRetryAttempts)
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
