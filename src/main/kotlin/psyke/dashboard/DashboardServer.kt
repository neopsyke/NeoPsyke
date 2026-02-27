package psyke.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mu.KotlinLogging
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

class DashboardServer(
    private val store: DashboardStateStore,
    port: Int,
    host: String = "127.0.0.1",
) : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    val url: String = "http://$host:$port/"

    init {
        server.executor = Executors.newCachedThreadPool()
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
        server.createContext("/health") { exchange ->
            respondText(exchange, 200, "ok", "text/plain; charset=utf-8")
        }
    }

    fun start() {
        server.start()
        logger.info { "Dashboard server started at $url" }
    }

    override fun close() {
        server.stop(0)
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
