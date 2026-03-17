package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import psyke.agent.cortex.sensory.AsyncSignalSource
import psyke.instrumentation.AgentEvent
import psyke.metrics.MetricsQueryProvider
import java.io.BufferedReader
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardServerTest {
    private val mapper = jacksonObjectMapper()
    private val client: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `routes serve split conversation and dashboard pages`() {
        startServer().use { started ->
            val root = get("http://127.0.0.1:${started.port}/")
            val dashboard = get("http://127.0.0.1:${started.port}/dashboard")

            assertEquals(200, root.statusCode())
            assertEquals(200, dashboard.statusCode())
            assertTrue(root.body().contains("Psyke Conversations"))
            assertTrue(dashboard.body().contains("Psyke Realtime Dashboard"))
            assertTrue(dashboard.body().contains("tl-structured-chip"))
        }
    }

    @Test
    fun `api namespaces expose obs and chat endpoints`() {
        startServer().use { started ->
            val snapshot = get("http://127.0.0.1:${started.port}/api/obs/snapshot")
            assertEquals(200, snapshot.statusCode())
            assertTrue(snapshot.body().contains("loopStatus"))

            val createRes = postJson(
                "http://127.0.0.1:${started.port}/api/chat/sessions",
                mapOf("title" to "Integration")
            )
            assertEquals(201, createRes.statusCode())
            val created: Map<String, Any?> = mapper.readValue(createRes.body())
            val sessionId = created["session_id"]?.toString()
            assertNotNull(sessionId)

            val listRes = get("http://127.0.0.1:${started.port}/api/chat/sessions")
            assertEquals(200, listRes.statusCode())
            assertTrue(listRes.body().contains(sessionId))

            val submitRes = postJson(
                "http://127.0.0.1:${started.port}/api/chat/sessions/$sessionId/messages",
                mapOf("content" to "hello")
            )
            assertEquals(202, submitRes.statusCode())
            assertTrue(submitRes.body().contains("\"accepted\":true"))
        }
    }

    @Test
    fun `session sse is scoped while obs sse remains global`() {
        startServer().use { started ->
            val s1 = createSession(started.port, "S1")
            val s2 = createSession(started.port, "S2")
            val sse1 = openSse("http://127.0.0.1:${started.port}/api/chat/sessions/$s1/events")
            val sse2 = openSse("http://127.0.0.1:${started.port}/api/chat/sessions/$s2/events")
            val obs = openSse("http://127.0.0.1:${started.port}/api/obs/events")

            sse1.use {
                sse2.use {
                    obs.use {
                        readNextEvent(sse1)
                        readNextEvent(sse2)
                        readNextEvent(obs)

                        postJson(
                            "http://127.0.0.1:${started.port}/api/chat/sessions/$s1/messages",
                            mapOf("content" to "only-s1")
                        )

                        val s1Event = readNextEvent(sse1, timeoutMs = 2_000)
                        assertNotNull(s1Event)
                        assertEquals("chat", s1Event.first)
                        assertTrue(s1Event.second.contains("only-s1"))

                        val s2Event = readNextEvent(sse2, timeoutMs = 600)
                        assertNull(s2Event)

                        started.store.onEvent(
                            AgentEvent(
                                id = 99,
                                type = "warning",
                                data = mapOf("message" to "obs-only")
                            )
                        )
                        val obsEvent = readNextEvent(obs, timeoutMs = 2_000)
                        assertNotNull(obsEvent)
                        assertEquals("agent", obsEvent.first)
                        assertTrue(obsEvent.second.contains("\"type\":\"warning\""))
                    }
                }
            }
        }
    }

    @Test
    fun `llm stats endpoint returns warmup payload immediately when provider throws`() {
        startServer().use { started ->
            started.server.metricsQueryProvider = object : MetricsQueryProvider {
                override fun llmCallStats(runOnly: Boolean, timeframeMs: Long?) =
                    throw IllegalStateException("boom")
            }

            val response = get("http://127.0.0.1:${started.port}/api/obs/llm-stats")
            assertEquals(202, response.statusCode())
            assertTrue(response.body().contains("\"warmup\":true"))
            assertTrue(response.body().contains("\"stale\":true"))
        }
    }

    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun postJson(url: String, payload: Map<String, Any?>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun createSession(port: Int, title: String): String {
        val response = postJson(
            "http://127.0.0.1:$port/api/chat/sessions",
            mapOf("title" to title)
        )
        assertEquals(201, response.statusCode())
        val payload: Map<String, Any?> = mapper.readValue(response.body())
        return payload["session_id"]?.toString() ?: error("Missing session id")
    }

    private fun openSse(url: String): SseConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2_000
            readTimeout = 2_000
        }
        assertEquals(200, connection.responseCode)
        val reader = connection.inputStream.bufferedReader()
        return SseConnection(connection, reader)
    }

    private fun readNextEvent(connection: SseConnection, timeoutMs: Int = 1_000): Pair<String, String>? {
        connection.connection.readTimeout = timeoutMs.coerceAtLeast(100)
        var eventName: String? = null
        var data: String? = null
        try {
            while (true) {
                val line = connection.reader.readLine() ?: return null
                if (line.isBlank()) {
                    if (eventName != null || data != null) {
                        return Pair(eventName ?: "message", data ?: "")
                    }
                    continue
                }
                if (line.startsWith("event:")) {
                    eventName = line.removePrefix("event:").trim()
                } else if (line.startsWith("data:")) {
                    data = line.removePrefix("data:").trim()
                }
            }
        } catch (_: SocketTimeoutException) {
            return null
        }
    }

    private fun startServer(): StartedServer {
        val port = ServerSocket(0).use { it.localPort }
        val store = DashboardStateStore()
        val sensory = AsyncSignalSource(
            includeStdin = false,
            emitStdinClosedSignal = false
        )
        val bridge = ChatRuntimeBridge(store = store, sensoryInput = sensory)
        val server = DashboardServer(
            store = store,
            chatBridge = bridge,
            port = port
        )
        server.start()
        return StartedServer(
            port = port,
            store = store,
            server = server,
            sensory = sensory
        )
    }

    private data class StartedServer(
        val port: Int,
        val store: DashboardStateStore,
        val server: DashboardServer,
        val sensory: AsyncSignalSource,
    ) : Closeable {
        override fun close() {
            server.close()
            sensory.close()
            store.close()
        }
    }

    private data class SseConnection(
        val connection: HttpURLConnection,
        val reader: BufferedReader,
    ) : Closeable {
        override fun close() {
            reader.close()
            connection.disconnect()
        }
    }
}
