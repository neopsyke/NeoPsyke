package ai.neopsyke.dashboard

import java.nio.charset.StandardCharsets

object DashboardAssets {
    val conversationsHtml: String by lazy {
        loadText("/dashboard/conversations.html")
    }

    val observabilityHtml: String by lazy {
        loadText("/dashboard/observability.html")
    }

    val metricsHtml: String by lazy {
        loadText("/dashboard/metrics.html")
    }

    val goalsHtml: String by lazy {
        loadText("/dashboard/goals.html")
    }

    val actionControlHtml: String by lazy {
        loadText("/dashboard/action-control.html")
    }

    private fun loadText(path: String): String {
        val stream = DashboardAssets::class.java.getResourceAsStream(path)
            ?: error("Missing dashboard asset: $path")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
