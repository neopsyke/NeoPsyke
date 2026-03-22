package ai.neopsyke.memory.pgvector

import mu.KotlinLogging

private val providerMainLogger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val config = PgvectorMemoryProviderConfig.fromEnv()

    if (config.embeddingApiKey.isBlank()) {
        providerMainLogger.error { "EMBEDDING_API_KEY or MISTRAL_API_KEY is required. Set one and restart." }
        System.exit(1)
    }

    val transport = resolveTransport(args)
    val port = resolvePort(args)
    val host = resolveHost(args)
    val runtime = try {
        ProviderRuntimeFactory.create(config)
    } catch (ex: Exception) {
        providerMainLogger.error(ex) {
            "Failed to initialize provider runtime. Check PostgreSQL connectivity and embedding dimensions."
        }
        System.exit(1)
        return
    }

    when (transport) {
        "http" -> HttpMemoryProviderServer(host = host, port = port, runtime = runtime).use { server ->
            server.start()
            Thread.currentThread().join()
        }

        "mcp" -> McpMemoryProviderServer(runtime).use { server ->
            server.run()
        }

        else -> {
            providerMainLogger.error { "Unsupported provider transport '$transport'. Expected http or mcp." }
            runtime.close()
            System.exit(1)
        }
    }
}

private fun resolveTransport(args: Array<String>): String =
    args.firstOrNull { it.startsWith("--transport=") }
        ?.substringAfter('=')
        ?.trim()
        ?.lowercase()
        ?.ifBlank { null }
        ?: System.getenv("MEMORY_PROVIDER_TRANSPORT")?.trim()?.lowercase()?.ifBlank { null }
        ?: "mcp"

private fun resolvePort(args: Array<String>): Int =
    args.firstOrNull { it.startsWith("--port=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toIntOrNull()
        ?: System.getenv("MEMORY_PROVIDER_HTTP_PORT")?.trim()?.toIntOrNull()
        ?: 7841

private fun resolveHost(args: Array<String>): String =
    args.firstOrNull { it.startsWith("--host=") }
        ?.substringAfter('=')
        ?.trim()
        ?.ifBlank { null }
        ?: System.getenv("MEMORY_PROVIDER_HTTP_HOST")?.trim()?.ifBlank { null }
        ?: "127.0.0.1"
