package psyke.agent.actions.websearch

import psyke.agent.ActionOutcome
import psyke.agent.TextSecurity

class WebSearchActionHandler(
    private val engine: WebSearchEngine,
) {
    fun execute(query: String, maxResults: Int): ActionOutcome {
        val result = engine.search(query, maxResults)
        val snippets = if (result.snippets.isEmpty()) {
            "no snippets"
        } else {
            result.snippets
                .map { TextSecurity.clamp(it.trim(), 200) }
                .joinToString(" | ")
                .ifBlank { "no snippets" }
        }
        val sources = result.sources
            .filter { it.url.isNotBlank() }
            .take(3)
            .mapIndexed { index, source ->
                "[${index + 1}] ${TextSecurity.clamp(source.title.ifBlank { source.url }, 120)} - ${source.url}"
            }
            .joinToString(" | ")
            .ifBlank { "none" }

        return ActionOutcome(
            statusSummary = "Web search summary: ${TextSecurity.clamp(result.summary, 280)}; snippets: $snippets; sources: $sources"
        )
    }

    fun healthCheck(): WebSearchEngineHealth = engine.healthCheck()
}
