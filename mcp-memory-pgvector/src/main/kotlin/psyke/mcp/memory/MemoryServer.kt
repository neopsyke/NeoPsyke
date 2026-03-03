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
import psyke.mcp.memory.tools.registerCreateMemoryTool
import psyke.mcp.memory.tools.registerGraphCompatTools
import psyke.mcp.memory.tools.registerRememberTool
import psyke.mcp.memory.tools.registerSearchMemoryTool

private val logger = KotlinLogging.logger {}

fun main() {
    val config = MemoryServerConfig.fromEnv()

    if (config.embeddingApiKey.isBlank()) {
        logger.error { "EMBEDDING_API_KEY or MISTRAL_API_KEY is required. Set one and restart." }
        System.exit(1)
    }

    // Initialize database
    val repository = MemoryRepository(config)
    try {
        repository.initSchema()
    } catch (ex: Exception) {
        logger.error(ex) { "Failed to initialize database schema. Is PostgreSQL running?" }
        System.exit(1)
    }

    // Initialize embedding client (with LRU cache)
    val embedder = EmbeddingCache(MistralEmbedder(config))

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

    // Register tools
    registerSearchMemoryTool(server, repository, embedder, config)
    registerRememberTool(server, repository, embedder)
    registerCreateMemoryTool(server, repository, embedder)
    registerGraphCompatTools(server, repository, embedder)

    // Start stdio transport
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )

    logger.info { "Starting ${config.serverName} v${config.serverVersion} on stdio" }

    runBlocking {
        server.connect(transport)
    }
}
