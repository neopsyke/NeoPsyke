package ai.neopsyke.agent.cortex.motor.actions.websearch

data class WebSearchSource(
    val title: String,
    val url: String,
    val snippet: String? = null,
)

data class WebSearchResult(
    val summary: String,
    val snippets: List<String>,
    val sources: List<WebSearchSource> = emptyList(),
)

data class WebSearchEngineHealth(
    val available: Boolean,
    val detail: String,
)

interface WebSearchEngine {
    fun search(query: String, maxResults: Int): WebSearchResult

    fun healthCheck(): WebSearchEngineHealth = WebSearchEngineHealth(
        available = true,
        detail = "Web search engine configured (${this::class.simpleName})."
    )
}
