package psyke.mcp.memory.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import psyke.mcp.memory.metrics.MemoryServerMetrics

private val jackson = jacksonObjectMapper()

fun registerMetricsTool(server: Server, metrics: MemoryServerMetrics) {
    server.addTool(
        name = "get_memory_metrics",
        description = "Returns usage metrics for the pgvector memory server: embedding API tokens/requests, cache hit rates, DB operation counts and latencies.",
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
