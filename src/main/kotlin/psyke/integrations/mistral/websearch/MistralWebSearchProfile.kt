package psyke.integrations.mistral.websearch

enum class MistralWebSearchMode {
    AGENT_ID,
    PER_REQUEST_TOOLS,
}

enum class MistralWebSearchTool(val apiValue: String) {
    WEB_SEARCH("web_search"),
    WEB_SEARCH_PREMIUM("web_search_premium"),
}

enum class WebSearchFailurePolicy {
    FAIL_FAST,
}

data class MistralWebSearchProfile(
    val mode: MistralWebSearchMode = MistralWebSearchMode.AGENT_ID,
    val model: String = DEFAULT_MODEL,
    val agentId: String? = null,
    val tool: MistralWebSearchTool = MistralWebSearchTool.WEB_SEARCH,
) {
    companion object {
        const val DEFAULT_MODEL = "mistral-small-latest"
    }
}
