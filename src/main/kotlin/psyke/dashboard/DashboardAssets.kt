package psyke.dashboard

import java.nio.charset.StandardCharsets

object DashboardAssets {
    val indexHtml: String by lazy {
        loadText("/dashboard/index.html")
    }

    private fun loadText(path: String): String {
        val stream = DashboardAssets::class.java.getResourceAsStream(path)
            ?: error("Missing dashboard asset: $path")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
