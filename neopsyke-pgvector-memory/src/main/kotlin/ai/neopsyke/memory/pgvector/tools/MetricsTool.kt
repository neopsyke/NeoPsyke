package ai.neopsyke.memory.pgvector.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics

private val jackson = jacksonObjectMapper()

fun registerMetricsTool(server: Server, metrics: MemoryProviderMetrics) {
    server.addTool(
        name = "get_memory_metrics",
        description = "Returns usage metrics for the pgvector memory provider: embedding API tokens/requests, cache hit rates, DB operation counts and latencies.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {},
        )
    ) { _ ->
        val snapshot = metrics.snapshot()
        CallToolResult(
            content = listOf(TextContent(text = jackson.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)))
        )
    }
}
