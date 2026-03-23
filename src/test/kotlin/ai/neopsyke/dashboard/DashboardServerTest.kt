package ai.neopsyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actioncontrol.ActionControlDecisionResult
import ai.neopsyke.agent.actioncontrol.ActionControlService
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.metrics.MetricsQueryProvider
import java.io.BufferedReader
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
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
            val actionControl = get("http://127.0.0.1:${started.port}/action-control")

            assertEquals(200, root.statusCode())
            assertEquals(200, dashboard.statusCode())
            assertEquals(200, actionControl.statusCode())
            assertTrue(root.body().contains("NeoPsyke Conversations"))
            assertTrue(dashboard.body().contains("NeoPsyke Realtime Dashboard"))
            assertTrue(dashboard.body().contains("tl-structured-chip"))
            assertTrue(actionControl.body().contains("NeoPsyke Action Control"))
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
    fun `action control page can inspect and authorize staged actions`() {
        startServer().use { started ->
            started.server.actionControlService = TestActionControlService()

            val staged = get("http://127.0.0.1:${started.port}/api/action-control/staged")
            assertEquals(200, staged.statusCode())
            assertTrue(staged.body().contains("stage-1"))
            assertTrue(staged.body().contains("WAITING_AUTHORIZATION"))

            val authorize = postJson(
                "http://127.0.0.1:${started.port}/api/action-control/staged/stage-1/authorize",
                emptyMap()
            )
            assertEquals(200, authorize.statusCode())
            assertTrue(authorize.body().contains("\"Executed\"") || authorize.body().contains("\"receipt\""))

            val receipts = get("http://127.0.0.1:${started.port}/api/action-control/receipts")
            assertEquals(200, receipts.statusCode())
            assertTrue(receipts.body().contains("receipt-1"))
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
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
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

    private class TestActionControlService : ActionControlService {
        private val context = ConversationContext.default()
        private var staged = StagedAction(
            id = "stage-1",
            preparedActionId = "prepared-1",
            actionType = ActionType.CONTACT_USER,
            summary = "Review and send owner reply",
            payload = "reply body",
            conversationContext = context,
            status = StagedActionStatus.WAITING_AUTHORIZATION,
            actionHash = "hash-1",
            commitMode = CommitMode.APPROVAL_BACKED,
        )
        private var receipt: ActionReceipt? = null

        override suspend fun handleAuthorizationDecision(
            action: PendingAction,
            decision: ai.neopsyke.agent.model.AuthorizationDecision,
            conversationContext: ConversationContext,
        ): ActionControlDecisionResult =
            ActionControlDecisionResult.Refused("unused", "UNUSED")

        override suspend fun authorizeStagedAction(
            stagedActionId: String,
            grantedBy: ConversationSecurityContext,
        ): ActionControlDecisionResult {
            val authorization = CommitAuthorization(
                id = "auth-1",
                stagedActionId = stagedActionId,
                commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id,
                grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = "test-v1",
                actionHash = staged.actionHash,
            )
            receipt = ActionReceipt(
                id = "receipt-1",
                stagedActionId = stagedActionId,
                authorizationId = authorization.id,
                actionType = staged.actionType,
                executionStatus = ActionExecutionStatus.SUCCESS,
                statusSummary = "Authorized from dashboard",
            )
            staged = staged.copy(
                status = StagedActionStatus.COMPLETED,
                authorizationId = authorization.id,
                receiptId = receipt?.id,
            )
            return ActionControlDecisionResult.Executed(
                stagedAction = staged,
                authorization = authorization,
                receipt = receipt ?: error("Receipt missing"),
                outcome = ai.neopsyke.agent.model.ActionOutcome(
                    statusSummary = "Authorized from dashboard",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                executedAction = PendingAction(
                    id = 1,
                    urgency = ai.neopsyke.agent.model.Urgency.MEDIUM,
                    type = staged.actionType,
                    payload = staged.payload,
                    summary = staged.summary,
                    conversationContext = staged.conversationContext,
                ),
            )
        }

        override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> = emptyList()

        override suspend fun recordBypassExecution(
            action: PendingAction,
            conversationContext: ConversationContext,
            outcome: ai.neopsyke.agent.model.ActionOutcome,
            reason: String,
            reasonCode: String?,
        ): ActionReceipt? = null

        override fun stagedActions(limit: Int): List<StagedAction> = listOf(staged)
        override fun stagedAction(id: String): StagedAction? = staged.takeIf { it.id == id }
        override fun receipts(limit: Int): List<ActionReceipt> = listOfNotNull(receipt)
        override fun receipt(id: String): ActionReceipt? = receipt?.takeIf { it.id == id }
    }
}
