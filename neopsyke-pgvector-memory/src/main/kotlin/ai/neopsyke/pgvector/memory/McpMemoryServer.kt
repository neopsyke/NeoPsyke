package ai.neopsyke.pgvector.memory

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import ai.neopsyke.pgvector.memory.tools.registerCreateMemoryTool
import ai.neopsyke.pgvector.memory.tools.registerGraphCompatTools
import ai.neopsyke.pgvector.memory.tools.registerMetricsTool
import ai.neopsyke.pgvector.memory.tools.registerRememberTool
import ai.neopsyke.pgvector.memory.tools.registerSearchMemoryTool

class McpMemoryServer(
    private val runtime: ProviderRuntime,
) : AutoCloseable {
    fun run() {
        val server = Server(
            serverInfo = Implementation(
                name = runtime.config.serverName,
                version = runtime.config.serverVersion,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        registerSearchMemoryTool(server, runtime.repository, runtime.embedder, runtime.config, runtime.metrics)
        registerRememberTool(server, runtime.repository, runtime.embedder, runtime.config.defaultNamespace, runtime.config.factDefaultSubject, runtime.metrics)
        registerCreateMemoryTool(server, runtime.repository, runtime.embedder, runtime.config.defaultNamespace, runtime.config.factDefaultSubject, runtime.metrics)
        registerGraphCompatTools(server, runtime.repository, runtime.embedder, runtime.config.defaultNamespace, runtime.metrics)
        registerMetricsTool(server, runtime.metrics)

        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )

        runBlocking {
            val closed = CompletableDeferred<Unit>()
            server.onClose { closed.complete(Unit) }
            server.connect(transport)
            closed.await()
        }
    }

    override fun close() {
        runtime.close()
    }
}
