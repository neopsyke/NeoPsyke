package psyke.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mu.KotlinLogging
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class DashboardServer(
    private val store: DashboardStateStore,
    port: Int,
    host: String = "127.0.0.1",
) : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    val url: String = "http://$host:$port/"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path != "/") {
                respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                return@createContext
            }
            respondText(exchange, 200, DashboardAssets.indexHtml, "text/html; charset=utf-8")
        }
        server.createContext("/api/snapshot") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            respondText(exchange, 200, store.snapshotJson(), "application/json; charset=utf-8")
        }
        server.createContext("/api/events") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            handleSse(exchange)
        }
        server.createContext("/api/workspace") { exchange ->
            if (exchange.requestMethod != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return@createContext
            }
            handleWorkspaceApi(exchange)
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
            while (true) {
                val payload = subscription.poll(30_000)
                if (payload == null) {
                    output.write(": heartbeat\n\n")
                    output.flush()
                    continue
                }
                output.write("event: agent\n")
                output.write("data: $payload\n\n")
                output.flush()
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
        if (path == "/api/workspace") {
            respondText(exchange, 200, store.workspaceIndexJson(), "application/json; charset=utf-8")
            return
        }
        val rootIdRaw = path.removePrefix("/api/workspace/").trim()
        if (rootIdRaw.isBlank() || rootIdRaw == path) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val rootId = rootIdRaw.toLongOrNull()
        if (rootId == null) {
            respondText(exchange, 400, "Invalid workspace id", "text/plain; charset=utf-8")
            return
        }
        val version = parseQueryParam(exchange.requestURI.query, "version")?.toLongOrNull()
        val snapshot = store.workspaceSnapshotJson(rootInputEnqueuedAtMs = rootId, version = version)
        if (snapshot == null) {
            respondText(exchange, 404, "Workspace snapshot not found", "text/plain; charset=utf-8")
            return
        }
        respondText(exchange, 200, snapshot, "application/json; charset=utf-8")
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
