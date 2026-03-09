package psyke.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import psyke.metrics.MetricsQueryProvider
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private const val SSE_HEARTBEAT_TIMEOUT_MS: Long = 30_000L

class DashboardServer(
    private val store: DashboardStateStore,
    private val chatBridge: ChatRuntimeBridge? = null,
    @Volatile var metricsQueryProvider: MetricsQueryProvider? = null,
    port: Int,
    host: String = "127.0.0.1",
) : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val mapper = jacksonObjectMapper()
    val url: String = "http://$host:$port/"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path != "/") {
                respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                return@createContext
            }
            respondText(exchange, 200, DashboardAssets.conversationsHtml, "text/html; charset=utf-8")
        }
        server.createContext("/dashboard") { exchange ->
            if (exchange.requestURI.path != "/dashboard") {
                respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                return@createContext
            }
            respondText(exchange, 200, DashboardAssets.observabilityHtml, "text/html; charset=utf-8")
        }
        server.createContext("/metrics") { exchange ->
            if (exchange.requestURI.path != "/metrics") {
                respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                return@createContext
            }
            respondText(exchange, 200, DashboardAssets.metricsHtml, "text/html; charset=utf-8")
        }
        server.createContext("/api/obs/llm-stats") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            handleLlmStatsApi(exchange)
        }
        server.createContext("/api/obs/snapshot") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            respondText(exchange, 200, store.snapshotJson(), "application/json; charset=utf-8")
        }
        server.createContext("/api/obs/events") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            handleSse(exchange)
        }
        server.createContext("/api/obs/workspace") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            handleWorkspaceApi(exchange)
        }
        server.createContext("/api/chat/sessions") { exchange ->
            handleChatApi(exchange)
        }
        server.createContext("/health") { exchange ->
            respondText(exchange, 200, "ok", "text/plain; charset=utf-8")
        }
    }

    fun start() {
        server.start()
        logger.info { "Dashboard server started at $url" }
    }

    override fun close() {
        try {
            server.stop(0)
        } finally {
            executor.shutdownNow()
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        logger.info { "Dashboard server stopped." }
    }

    private fun handleSse(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val subscription = store.subscribe()
        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val payload = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        subscription.receive()
                    }
                    if (payload != null) {
                        output.write("event: agent\n")
                        output.write("data: $payload\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            subscription.close()
            output.close()
        }
    }

    private fun handleWorkspaceApi(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (path == "/api/obs/workspace") {
            respondText(exchange, 200, store.workspaceIndexJson(), "application/json; charset=utf-8")
            return
        }
        val rootIdRaw = path.removePrefix("/api/obs/workspace/").trim()
        if (rootIdRaw.isBlank() || rootIdRaw == path) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val rootId = rootIdRaw
        val version = parseQueryParam(exchange.requestURI.query, "version")?.toLongOrNull()
        val snapshot = store.workspaceSnapshotJson(rootInputId = rootId, version = version)
        if (snapshot == null) {
            respondText(exchange, 404, "Workspace snapshot not found", "text/plain; charset=utf-8")
            return
        }
        respondText(exchange, 200, snapshot, "application/json; charset=utf-8")
    }

    private fun handleChatApi(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val basePath = "/api/chat/sessions"
        if (!(path == basePath || path.startsWith("$basePath/"))) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        if (chatBridge == null) {
            respondText(exchange, 503, "Chat API unavailable", "text/plain; charset=utf-8")
            return
        }
        val suffix = path.removePrefix(basePath).trim()
        if (suffix.isBlank()) {
            when (exchange.requestMethod.uppercase()) {
                "GET" -> respondText(exchange, 200, chatBridge.listSessionsJson(), "application/json; charset=utf-8")
                "POST" -> handleCreateChatSession(exchange, chatBridge)
                else -> respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            }
            return
        }

        val normalizedSuffix = suffix.removePrefix("/")
        if (normalizedSuffix.isBlank()) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val parts = normalizedSuffix.split("/")
        val sessionId = parts.first().trim()
        if (sessionId.isBlank()) {
            respondText(exchange, 400, "Invalid session id", "text/plain; charset=utf-8")
            return
        }
        if (parts.size == 1) {
            if (exchange.requestMethod.uppercase() != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return
            }
            val payload = chatBridge.sessionJson(sessionId)
            if (payload == null) {
                respondText(exchange, 404, "Session not found", "text/plain; charset=utf-8")
                return
            }
            respondText(exchange, 200, payload, "application/json; charset=utf-8")
            return
        }

        val action = parts.getOrNull(1)?.trim().orEmpty()
        when (action) {
            "messages" -> {
                if (exchange.requestMethod.uppercase() != "POST") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleSubmitChatMessage(exchange, chatBridge, sessionId)
            }
            "events" -> {
                if (exchange.requestMethod.uppercase() != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleChatSse(exchange, chatBridge, sessionId)
            }
            else -> respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
        }
    }

    private fun handleCreateChatSession(exchange: HttpExchange, bridge: ChatRuntimeBridge) {
        val body = readJsonBody(exchange)
        val title = body?.get("title")?.toString()
        val session = bridge.createSession(title = title)
        val payload = mapOf(
            "session_id" to session.sessionId,
            "title" to session.title,
            "created_at_ms" to session.createdAtMs,
            "updated_at_ms" to session.updatedAtMs,
            "message_count" to session.messageCount
        )
        respondText(exchange, 201, mapper.writeValueAsString(payload), "application/json; charset=utf-8")
    }

    private fun handleSubmitChatMessage(
        exchange: HttpExchange,
        bridge: ChatRuntimeBridge,
        sessionId: String,
    ) {
        val body = readJsonBody(exchange) ?: run {
            respondText(exchange, 400, "Invalid JSON body", "text/plain; charset=utf-8")
            return
        }
        val content = body["content"]?.toString().orEmpty()
        val result = bridge.submitMessage(sessionId = sessionId, content = content)
        if (!result.accepted) {
            val statusCode = if (result.detail.contains("Unknown session", ignoreCase = true)) 404 else 400
            val payload = mapOf(
                "accepted" to false,
                "detail" to result.detail
            )
            respondText(exchange, statusCode, mapper.writeValueAsString(payload), "application/json; charset=utf-8")
            return
        }
        val payload = mapOf(
            "accepted" to true,
            "detail" to result.detail
        )
        respondText(exchange, 202, mapper.writeValueAsString(payload), "application/json; charset=utf-8")
    }

    private fun handleChatSse(
        exchange: HttpExchange,
        bridge: ChatRuntimeBridge,
        sessionId: String,
    ) {
        val subscription = bridge.subscribe(sessionId)
        if (subscription == null) {
            respondText(exchange, 404, "Session not found", "text/plain; charset=utf-8")
            return
        }

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\",\"session_id\":\"$sessionId\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val payload = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        subscription.receive()
                    }
                    if (payload != null) {
                        output.write("event: chat\n")
                        output.write("data: $payload\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            subscription.close()
            output.close()
        }
    }

    private fun handleLlmStatsApi(exchange: HttpExchange) {
        val provider = metricsQueryProvider
        if (provider == null) {
            respondText(exchange, 503, """{"error":"Metrics query not available"}""", "application/json; charset=utf-8")
            return
        }
        val query = exchange.requestURI.query
        val scope = parseQueryParam(query, "scope") ?: "run"
        val runOnly = scope != "all"
        val timeframeMs = parseQueryParam(query, "timeframe")?.toLongOrNull()
        val report = provider.llmCallStats(runOnly = runOnly, timeframeMs = timeframeMs)
        respondText(exchange, 200, mapper.writeValueAsString(report), "application/json; charset=utf-8")
    }

    private fun parseQueryParam(query: String?, key: String): String? {
        if (query.isNullOrBlank()) return null
        return query
            .split("&")
            .mapNotNull { part ->
                val (k, v) = part.split("=", limit = 2).let { tokens ->
                    when (tokens.size) {
                        2 -> tokens[0] to tokens[1]
                        1 -> tokens[0] to ""
                        else -> return@mapNotNull null
                    }
                }
                if (k == key) v else null
            }
            .firstOrNull()
    }

    private fun readJsonBody(exchange: HttpExchange): Map<String, Any?>? {
        return try {
            val bytes = exchange.requestBody.readBytes()
            if (bytes.isEmpty()) {
                emptyMap()
            } else {
                mapper.readValue<Map<String, Any?>>(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun respondText(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate")
        exchange.responseHeaders.add("Pragma", "no-cache")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
