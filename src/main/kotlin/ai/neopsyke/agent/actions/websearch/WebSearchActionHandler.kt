package ai.neopsyke.agent.actions.websearch

import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.support.TextSecurity
import java.net.URI

class WebSearchActionHandler(
    private val engine: WebSearchEngine,
) {
    fun execute(query: String, maxResults: Int): ActionOutcome {
        val queryScan = PromptInjectionDefense.scan(query)
        val result = engine.search(query, maxResults)
        val summary = TextSecurity.clamp(
            PromptInjectionDefense.sanitizeExternalText(
                result.summary.trim().ifBlank { "No summary returned." },
                280
            ),
            280
        )
        val snippets = if (result.snippets.isEmpty()) {
            "no snippets"
        } else {
            result.snippets
                .map { PromptInjectionDefense.sanitizeExternalText(it.trim(), 200) }
                .joinToString(" | ")
                .ifBlank { "no snippets" }
        }
        val prioritizedSources = prioritizeSources(result.sources)
        val sources = prioritizedSources
            .filter { it.url.isNotBlank() }
            .take(3)
            .mapIndexed { index, source ->
                val safeTitle = PromptInjectionDefense.sanitizeExternalText(source.title.ifBlank { source.url }, 120)
                "[${index + 1}] $safeTitle - ${source.url}"
            }
            .joinToString(" | ")
            .ifBlank { "none" }
        val confidence = inferSourceConfidence(prioritizedSources)
        val keySources = prioritizedSources
            .take(2)
            .joinToString(" | ") { source ->
                val safeTitle = PromptInjectionDefense.sanitizeExternalText(source.title.ifBlank { source.url }, 90)
                "$safeTitle (${source.url})"
            }
            .ifBlank { "none" }
        val promptInjectionSignals = if (queryScan.suspicious) {
            queryScan.signalIds.sorted().joinToString(",")
        } else {
            "none"
        }
        val observedEvidence = !summary.contains("unavailable", ignoreCase = true) &&
            !summary.contains("timeout", ignoreCase = true) &&
            (prioritizedSources.isNotEmpty() || result.snippets.any { it.isNotBlank() })
        val plannerSignal = TextSecurity.clamp(
            "web_search result: $summary; key_sources: $keySources; source_confidence: $confidence; query_injection_signals: $promptInjectionSignals",
            420
        )

        return ActionOutcome(
            statusSummary = "Web search summary: $summary; snippets: $snippets; sources: $sources; source_confidence: $confidence; query_injection_signals: $promptInjectionSignals",
            plannerSignal = plannerSignal,
            executionStatus = if (observedEvidence) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.NO_EFFECT,
            effects = if (observedEvidence) {
                setOf(ActionEffect.TASK_PROGRESS, ActionEffect.EVIDENCE_GATHERED)
            } else {
                emptySet()
            },
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
