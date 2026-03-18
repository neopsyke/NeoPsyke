package psyke.dashboard

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import psyke.agent.project.DeterministicProjectPlanner
import psyke.agent.project.ProjectConfig
import psyke.agent.project.ProjectManager
import psyke.agent.project.ProjectPriority
import psyke.agent.project.ProjectStore
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
import java.nio.file.Files
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

    @Test
    fun `projects api returns structured summaries and details`() {
        val root = Files.createTempDirectory("psyke-dashboard-projects")
        val manager = ProjectManager(
            config = ProjectConfig(enabled = true, workspaceRoot = root),
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
        )
        manager.start(testScope())
        try {
            val projectId = manager.createProject(
                instruction = "Handle \"quotes\" and line\nbreaks safely",
                title = "Project \"Alpha\" & Beta",
                priority = ProjectPriority.HIGH,
                completionCriteria = "Done when payload stays valid JSON.",
            )

            startServer(projectManager = manager).use { started ->
                val listResponse = get("http://127.0.0.1:${started.port}/api/projects")
                assertEquals(200, listResponse.statusCode())
                val summaries: List<Map<String, Any?>> = mapper.readValue(listResponse.body())
                assertEquals(1, summaries.size)
                val summary = summaries.single()
                assertEquals(projectId, summary["projectId"])
                assertEquals("Project \"Alpha\" & Beta", summary["title"])
                assertEquals("ACTIVE", summary["status"])
                assertEquals("HIGH", summary["priority"])
                assertEquals(1, (summary["totalSteps"] as Number).toInt())
                assertEquals(0, (summary["doneSteps"] as Number).toInt())
                val summarySteps = mapper.convertValue(summary["steps"], mapListType())
                assertEquals(1, summarySteps.size)
                assertEquals("Handle \"quotes\" and line\nbreaks safely", summarySteps.single()["description"])

                val detailResponse = get("http://127.0.0.1:${started.port}/api/projects/$projectId")
                assertEquals(200, detailResponse.statusCode())
                val detail: Map<String, Any?> = mapper.readValue(detailResponse.body())
                assertEquals(projectId, detail["projectId"])
                assertEquals("Project \"Alpha\" & Beta", detail["title"])
                assertEquals("Handle \"quotes\" and line\nbreaks safely", detail["instruction"])
                assertEquals("Done when payload stays valid JSON.", detail["completionCriteria"])
                assertEquals(2, (detail["eventCount"] as Number).toInt())
                val detailSteps = mapper.convertValue(detail["steps"], mapListType())
                assertEquals(1, detailSteps.size)
                assertEquals("step-1", detailSteps.single()["id"])
                assertEquals("READY", detailSteps.single()["status"])
            }
        } finally {
            manager.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `projects api returns empty list with manager and 503 when projects are unavailable`() {
        val root = Files.createTempDirectory("psyke-dashboard-projects-empty")
        val manager = ProjectManager(
            config = ProjectConfig(enabled = true, workspaceRoot = root),
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
        )
        manager.start(testScope())
        try {
            startServer(projectManager = manager).use { started ->
                val emptyResponse = get("http://127.0.0.1:${started.port}/api/projects")
                assertEquals(200, emptyResponse.statusCode())
                val summaries: List<Map<String, Any?>> = mapper.readValue(emptyResponse.body())
                assertTrue(summaries.isEmpty())
            }

            startServer().use { started ->
                val unavailable = get("http://127.0.0.1:${started.port}/api/projects")
                assertEquals(503, unavailable.statusCode())
                assertEquals("[]", unavailable.body())
            }
        } finally {
            manager.stop()
            root.toFile().deleteRecursively()
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

    private fun startServer(projectManager: ProjectManager? = null): StartedServer {
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
        server.projectManager = projectManager
        server.start()
        return StartedServer(
            port = port,
            store = store,
            server = server,
            sensory = sensory
        )
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun mapListType() = object : TypeReference<List<Map<String, Any?>>>() {}

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
