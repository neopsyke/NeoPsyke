package ai.neopsyke.memory.pgvector

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

private val httpMemoryLogger = KotlinLogging.logger {}

class HttpMemoryProviderServer(
    private val host: String,
    private val port: Int,
    private val runtime: ProviderRuntime,
) : AutoCloseable {
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val service = ProviderMemoryService(
        config = runtime.config,
        repository = runtime.repository,
        embedder = runtime.embedder,
    )
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/health") { exchange ->
            writeJson(exchange, 200, mapOf(
                "provider" to runtime.config.providerName,
                "available" to true,
                "detail" to "http_ready",
                "degraded" to false,
            ))
        }
        createContext("/metrics") { exchange ->
            writeJson(exchange, 200, runtime.metrics.snapshot())
        }
        createContext("/v1/recall") { exchange ->
            val request = readJson(exchange, ProviderRecallRequest::class.java)
            writeJson(exchange, 200, service.recall(request))
        }
        createContext("/v1/imprint") { exchange ->
            val request = readJson(exchange, ProviderImprintRequest::class.java)
            writeJson(exchange, 200, service.imprint(request))
        }
        createContext("/v1/admin/reset") { exchange ->
            val request = readJson(exchange, ProviderResetRequest::class.java)
            writeJson(exchange, 200, service.reset(request))
        }
        createContext("/v1/admin/forget") { exchange ->
            val request = readJson(exchange, ProviderForgetRequest::class.java)
            writeJson(exchange, 200, service.forget(request))
        }
    }

    fun start() {
        server.start()
        httpMemoryLogger.info {
            "Starting ${runtime.config.providerName} v${runtime.config.providerVersion} on http://$host:$port"
        }
    }

    override fun close() {
        server.stop(0)
        runtime.close()
    }

    private fun <T> readJson(exchange: HttpExchange, type: Class<T>): T {
        val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
        return mapper.readValue(body, type)
    }

    private fun writeJson(exchange: HttpExchange, status: Int, payload: Any) {
        val json = mapper.writeValueAsBytes(payload)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
    }
}
