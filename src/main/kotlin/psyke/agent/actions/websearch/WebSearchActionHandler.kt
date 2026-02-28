package psyke.agent.actions.websearch

import psyke.agent.core.ActionOutcome
import psyke.agent.support.TextSecurity
import java.net.URI

class WebSearchActionHandler(
    private val engine: WebSearchEngine,
) {
    fun execute(query: String, maxResults: Int): ActionOutcome {
        val result = engine.search(query, maxResults)
        val summary = TextSecurity.clamp(
            result.summary.trim().ifBlank { "No summary returned." },
            280
        )
        val snippets = if (result.snippets.isEmpty()) {
            "no snippets"
        } else {
            result.snippets
                .map { TextSecurity.clamp(it.trim(), 200) }
                .joinToString(" | ")
                .ifBlank { "no snippets" }
        }
        val prioritizedSources = prioritizeSources(result.sources)
        val sources = prioritizedSources
            .filter { it.url.isNotBlank() }
            .take(3)
            .mapIndexed { index, source ->
                "[${index + 1}] ${TextSecurity.clamp(source.title.ifBlank { source.url }, 120)} - ${source.url}"
            }
            .joinToString(" | ")
            .ifBlank { "none" }
        val confidence = inferSourceConfidence(prioritizedSources)
        val keySources = prioritizedSources
            .take(2)
            .joinToString(" | ") { source ->
                "${TextSecurity.clamp(source.title.ifBlank { source.url }, 90)} (${source.url})"
            }
            .ifBlank { "none" }
        val observedEvidence = !summary.contains("unavailable", ignoreCase = true) &&
            !summary.contains("timeout", ignoreCase = true) &&
            (prioritizedSources.isNotEmpty() || result.snippets.any { it.isNotBlank() })
        val plannerSignal = TextSecurity.clamp(
            "web_search result: $summary; key_sources: $keySources; source_confidence: $confidence",
            420
        )

        return ActionOutcome(
            statusSummary = "Web search summary: $summary; snippets: $snippets; sources: $sources; source_confidence: $confidence",
            plannerSignal = plannerSignal,
            observedEvidence = observedEvidence
        )
    }

    fun healthCheck(): WebSearchEngineHealth = engine.healthCheck()

    private fun prioritizeSources(sources: List<WebSearchSource>): List<WebSearchSource> =
        sources
            .filter { it.url.isHttpUrl() }
            .sortedWith(
                compareBy<WebSearchSource> { sourceTrustRank(it.url) }
                    .thenBy { sourceHost(it.url) ?: it.url }
            )

    private fun sourceTrustRank(url: String): Int {
        val host = sourceHost(url) ?: return 2
        if (host in LOW_TRUST_HOSTS) {
            return 3
        }
        if (host.contains("docs.") || host.contains(".docs") || host.contains("developer.") || host.contains("api.")) {
            return 0
        }
        if (host == "github.com" || host.endsWith(".github.com")) {
            return 1
        }
        if (host.endsWith(".gov") || host.endsWith(".edu") || host.endsWith(".org")) {
            return 1
        }
        return 2
    }

    private fun inferSourceConfidence(sources: List<WebSearchSource>): String {
        if (sources.isEmpty()) {
            return "none"
        }
        val topHosts = sources.take(2).mapNotNull { sourceHost(it.url) }
        if (topHosts.isEmpty()) {
            return "low"
        }
        return if (topHosts.all { it in LOW_TRUST_HOSTS }) "low" else "medium"
    }

    private fun sourceHost(url: String): String? =
        try {
            URI(url.trim()).host?.removePrefix("www.")?.lowercase()
        } catch (_: Exception) {
            null
        }

    companion object {
        private val LOW_TRUST_HOSTS = setOf(
            "news.ycombinator.com",
            "linkedin.com",
            "reddit.com",
            "substack.com",
            "medium.com",
            "slashdot.org",
            "community.home-assistant.io",
            "x.com",
            "twitter.com"
        )
    }
}

private fun String.isHttpUrl(): Boolean {
    val normalized = this.trim().lowercase()
    return normalized.startsWith("https://") || normalized.startsWith("http://")
}
