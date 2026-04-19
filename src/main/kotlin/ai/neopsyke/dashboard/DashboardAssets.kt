package ai.neopsyke.dashboard

import java.nio.charset.StandardCharsets

object DashboardAssets {
    val shellHtml: String by lazy {
        loadText("/dashboard/shell.html")
    }

    val conversationsHtml: String by lazy {
        loadText("/dashboard/conversations.html")
    }

    val observabilityHtml: String by lazy {
        loadText("/dashboard/observability.html")
    }

    val metricsHtml: String by lazy {
        loadText("/dashboard/metrics.html")
    }

    val assignmentsHtml: String by lazy {
        loadText("/dashboard/assignments.html")
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
