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
import java.sql.SQLException
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
        createContext(ProviderHttpContract.HEALTH_PATH) { exchange ->
            handle(exchange, method = "GET") {
                mapOf(
                    "provider" to runtime.config.providerName,
                    "available" to true,
                    "detail" to "http_ready",
                    "degraded" to false,
                )
            }
        }
        createContext(ProviderHttpContract.METRICS_PATH) { exchange ->
            handle(exchange, method = "GET") {
                runtime.metrics.snapshot()
            }
        }
        createContext(ProviderHttpContract.RECALL_PATH) { exchange ->
            handle(exchange, method = "POST") {
                val request = readJson(exchange, ProviderRecallRequest::class.java)
                service.recall(request)
            }
        }
        createContext(ProviderHttpContract.IMPRINT_PATH) { exchange ->
            handle(exchange, method = "POST") {
                val request = readJson(exchange, ProviderImprintRequest::class.java)
                service.imprint(request)
            }
        }
        createContext(ProviderHttpContract.RESET_PATH) { exchange ->
            handle(exchange, method = "POST") {
                val request = readJson(exchange, ProviderResetRequest::class.java)
                service.reset(request)
            }
        }
        createContext(ProviderHttpContract.FORGET_PATH) { exchange ->
            handle(exchange, method = "POST") {
                val request = readJson(exchange, ProviderForgetRequest::class.java)
                service.forget(request)
            }
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

    private fun handle(exchange: HttpExchange, method: String, block: () -> Any) {
        try {
            if (!exchange.requestMethod.equals(method, ignoreCase = true)) {
                writeJson(
                    exchange,
                    405,
                    ProviderHttpErrorResponse(
                        provider = runtime.config.providerName,
                        error = "method_not_allowed",
                        detail = "Expected $method for ${exchange.requestURI.path}.",
                    )
                )
                return
            }
            writeJson(exchange, 200, block())
        } catch (ex: ProviderBadRequestException) {
            writeJson(
                exchange,
                400,
                ProviderHttpErrorResponse(
                    provider = runtime.config.providerName,
                    error = ex.errorCode,
                    detail = ex.message,
                )
            )
        } catch (ex: ProviderDependencyException) {
            httpMemoryLogger.warn(ex) { "Provider dependency failure for path=${exchange.requestURI.path}" }
            writeJson(
                exchange,
                503,
                ProviderHttpErrorResponse(
                    provider = runtime.config.providerName,
                    error = ex.errorCode,
                    detail = ex.message,
                )
            )
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            writeJson(
                exchange,
                400,
                ProviderHttpErrorResponse(
                    provider = runtime.config.providerName,
                    error = "invalid_json",
                    detail = "Request body could not be parsed.",
                )
            )
        } catch (ex: SQLException) {
            httpMemoryLogger.warn(ex) { "SQL dependency failure for path=${exchange.requestURI.path}" }
            writeJson(
                exchange,
                503,
                ProviderHttpErrorResponse(
                    provider = runtime.config.providerName,
                    error = "storage_unavailable",
                    detail = "Storage dependency failed.",
                )
            )
        } catch (ex: Exception) {
            httpMemoryLogger.error(ex) { "Unexpected HTTP provider failure for path=${exchange.requestURI.path}" }
            writeJson(
                exchange,
                500,
                ProviderHttpErrorResponse(
                    provider = runtime.config.providerName,
                    error = "internal_error",
                    detail = "Unexpected internal provider error.",
                )
            )
        }
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
