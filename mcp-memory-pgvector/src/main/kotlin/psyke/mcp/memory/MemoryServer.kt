package psyke.mcp.memory

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import mu.KotlinLogging
import psyke.mcp.memory.db.MemoryRepository
import psyke.mcp.memory.embedding.EmbeddingCache
import psyke.mcp.memory.embedding.MistralEmbedder
import psyke.mcp.memory.metrics.MemoryServerMetrics
import psyke.mcp.memory.tools.registerCreateMemoryTool
import psyke.mcp.memory.tools.registerGraphCompatTools
import psyke.mcp.memory.tools.registerMetricsTool
import psyke.mcp.memory.tools.registerRememberTool
import psyke.mcp.memory.tools.registerSearchMemoryTool
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

private val logger = KotlinLogging.logger {}

fun main() {
    val config = MemoryServerConfig.fromEnv()

    if (config.embeddingApiKey.isBlank()) {
        logger.error { "EMBEDDING_API_KEY or MISTRAL_API_KEY is required. Set one and restart." }
        System.exit(1)
    }

    // Central metrics registry
    val metrics = MemoryServerMetrics()

    // Initialize database
    val repository = MemoryRepository(config, metrics)
    try {
        repository.initSchema()
    } catch (ex: Exception) {
        logger.error(ex) { "Failed to initialize database schema. Is PostgreSQL running?" }
        System.exit(1)
    }

    // Initialize embedding client (with LRU cache)
    val embedder = EmbeddingCache(MistralEmbedder(config, metrics), metrics = metrics)

    // Create MCP server
    val server = Server(
        serverInfo = Implementation(
            name = config.serverName,
            version = config.serverVersion,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    // Register tools (all receive metrics for per-tool invocation tracking)
    registerSearchMemoryTool(server, repository, embedder, config, metrics)
    registerRememberTool(server, repository, embedder, metrics)
    registerCreateMemoryTool(server, repository, embedder, metrics)
    registerGraphCompatTools(server, repository, embedder, metrics)
    registerMetricsTool(server, metrics)

    // Periodic metrics logging (every 5 minutes)
    val metricsTimer = Timer("metrics-logger", true)
    metricsTimer.scheduleAtFixedRate(METRICS_LOG_INTERVAL_MS, METRICS_LOG_INTERVAL_MS) {
        metrics.logSummary()
    }

    // Start stdio transport
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )

    logger.info { "Starting ${config.serverName} v${config.serverVersion} on stdio (metrics enabled)" }

    runBlocking {
        server.connect(transport)
    }
}

private const val METRICS_LOG_INTERVAL_MS = 5L * 60 * 1000 // 5 minutes
