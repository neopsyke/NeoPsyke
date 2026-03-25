package ai.neopsyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actioncontrol.ActionControlDecisionResult
import ai.neopsyke.agent.actioncontrol.ActionControlService
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionRecordImportance
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
    fun `public dashboard routes serve the shell with route-based tabs`() {
        startServer().use { started ->
            val root = get("http://127.0.0.1:${started.port}/")
            val dashboard = get("http://127.0.0.1:${started.port}/dashboard")
            val actionControl = get("http://127.0.0.1:${started.port}/action-control")

            assertEquals(200, root.statusCode())
            assertEquals(200, dashboard.statusCode())
            assertEquals(200, actionControl.statusCode())
            assertTrue(root.body().contains("NeoPsyke Dashboard"))
            assertTrue(root.body().contains("href=\"/dashboard\""))
            assertTrue(dashboard.body().contains("framePath: \"/__dashboard/observability\""))
            assertTrue(actionControl.body().contains("framePath: \"/__dashboard/action-control\""))
        }
    }

    @Test
    fun `internal dashboard routes still serve the standalone page implementations`() {
        startServer().use { started ->
            val conversations = get("http://127.0.0.1:${started.port}/__dashboard/conversations")
            val observability = get("http://127.0.0.1:${started.port}/__dashboard/observability")
            val metrics = get("http://127.0.0.1:${started.port}/__dashboard/metrics")

            assertEquals(200, conversations.statusCode())
            assertEquals(200, observability.statusCode())
            assertEquals(200, metrics.statusCode())
            assertTrue(conversations.body().contains("NeoPsyke Conversations"))
            assertTrue(observability.body().contains("NeoPsyke Realtime Dashboard"))
            assertTrue(metrics.body().contains("NeoPsyke Metrics"))
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
            assertTrue(submitRes.body().contains("\"recorded\":true"))
            assertTrue(submitRes.body().contains("\"message\""))
        }
    }

    @Test
    fun `session sse is scoped while obs sse remains global`() {
        startServer().use { started ->
            val s1 = createSession(started.port, "S1")
            val s2 = createSession(started.port, "S2")
            val sse1 = openSse("http://127.0.0.1:${started.port}/api/chat/sessions/$s1/stream")
            val sse2 = openSse("http://127.0.0.1:${started.port}/api/chat/sessions/$s2/stream")
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
    fun `combined conversation stream emits chat and thinking over one connection`() {
        startServer().use { started ->
            val sessionId = createSession(started.port, "Combined")
            val stream = openSse("http://127.0.0.1:${started.port}/api/chat/sessions/$sessionId/stream")

            stream.use {
                readNextEvent(stream)

                started.innerVoiceStore.emit(
                    InnerVoiceEvent(
                        id = 7,
                        type = InnerVoiceEventType.PLAN,
                        content = "think-first",
                        rootInputId = "root-7",
                        sessionId = sessionId,
                        ts = System.currentTimeMillis(),
                        sequence = 1,
                    )
                )

                val thinkingEvent = readNextEvent(stream, timeoutMs = 2_000)
                assertNotNull(thinkingEvent)
                assertEquals("thinking", thinkingEvent.first)
                assertTrue(thinkingEvent.second.contains("think-first"))

                postJson(
                    "http://127.0.0.1:${started.port}/api/chat/sessions/$sessionId/messages",
                    mapOf("content" to "hello-stream")
                )

                val chatEvent = readNextEvent(stream, timeoutMs = 2_000)
                assertNotNull(chatEvent)
                assertEquals("chat", chatEvent.first)
                assertTrue(chatEvent.second.contains("hello-stream"))
            }
        }
    }

    @Test
    fun `goals sse only emits goal events`() {
        startServer().use { started ->
            val goals = openSse("http://127.0.0.1:${started.port}/api/goals/events")
            goals.use {
                readNextEvent(goals)

                started.store.onEvent(
                    AgentEvent(
                        id = 1,
                        type = "warning",
                        data = mapOf("message" to "not-a-goal-event")
                    )
                )
                val nonGoalEvent = readNextEvent(goals, timeoutMs = 600)
                assertNull(nonGoalEvent)

                started.store.onEvent(
                    AgentEvent(
                        id = 2,
                        type = "goal_started",
                        data = mapOf("goal_id" to "goal-1")
                    )
                )
                val goalEvent = readNextEvent(goals, timeoutMs = 2_000)
                assertNotNull(goalEvent)
                assertEquals("agent", goalEvent.first)
                assertTrue(goalEvent.second.contains("\"type\":\"goal_started\""))
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
    fun `action control page can inspect authorize and deny staged actions plus ledger activity`() {
        startServer().use { started ->
            started.server.actionControlService = TestActionControlService()
            val actionEvents = openSse("http://127.0.0.1:${started.port}/api/action-control/events")

            actionEvents.use {
                readNextEvent(actionEvents)

                val staged = get("http://127.0.0.1:${started.port}/api/action-control/staged")
                assertEquals(200, staged.statusCode())
                assertTrue(staged.body().contains("stage-1"))
                assertTrue(staged.body().contains("WAITING_AUTHORIZATION"))

                val ledgerBefore = get("http://127.0.0.1:${started.port}/api/action-control/ledger")
                assertEquals(200, ledgerBefore.statusCode())
                assertTrue(ledgerBefore.body().contains("\"SIGNAL\""))

                val authorize = postJson(
                    "http://127.0.0.1:${started.port}/api/action-control/staged/stage-1/authorize",
                    emptyMap()
                )
                assertEquals(200, authorize.statusCode())
                assertTrue(authorize.body().contains("\"Executed\"") || authorize.body().contains("\"receipt\""))

                val event = readNextEvent(actionEvents, timeoutMs = 2_000)
                assertNotNull(event)
                assertEquals("action-control", event.first)
                assertTrue(event.second.contains("\"action_control_state_changed\""))
                assertTrue(event.second.contains("\"authorize\""))

                val receipts = get("http://127.0.0.1:${started.port}/api/action-control/receipts")
                assertEquals(200, receipts.statusCode())
                assertTrue(receipts.body().contains("receipt-1"))

                val defaultSession = get("http://127.0.0.1:${started.port}/api/chat/sessions/default")
                assertEquals(200, defaultSession.statusCode())
                assertTrue(defaultSession.body().contains("reply body"))

                val deny = postJson(
                    "http://127.0.0.1:${started.port}/api/action-control/staged/stage-1/deny",
                    mapOf("reason" to "No longer needed")
                )
                assertEquals(409, deny.statusCode())
            }
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
        repeat(5) { attempt ->
            val port = ServerSocket(0).use { it.localPort }
            val store = DashboardStateStore()
            val sensory = AsyncSignalSource(
                includeStdin = false,
                emitStdinClosedSignal = false
            )
            val bridge = ChatRuntimeBridge(store = store, sensoryInput = sensory)
            val innerVoiceStore = InnerVoiceStore()
            try {
                val server = DashboardServer(
                    store = store,
                    chatBridge = bridge,
                    innerVoiceStore = innerVoiceStore,
                    port = port
                )
                server.start()
                return StartedServer(
                    port = port,
                    store = store,
                    server = server,
                    sensory = sensory,
                    innerVoiceStore = innerVoiceStore,
                )
            } catch (ex: java.net.BindException) {
                serverCloseQuietly(store, sensory, innerVoiceStore)
                if (attempt == 4) throw ex
            }
        }
        error("Unreachable")
    }

    private data class StartedServer(
        val port: Int,
        val store: DashboardStateStore,
        val server: DashboardServer,
        val sensory: AsyncSignalSource,
        val innerVoiceStore: InnerVoiceStore,
    ) : Closeable {
        override fun close() {
            server.close()
            innerVoiceStore.close()
            sensory.close()
            store.close()
        }
    }

    private fun serverCloseQuietly(
        store: DashboardStateStore,
        sensory: AsyncSignalSource,
        innerVoiceStore: InnerVoiceStore,
    ) {
        runCatching { innerVoiceStore.close() }
        runCatching { sensory.close() }
        runCatching { store.close() }
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
        private val ledgerEntries = mutableListOf(
            ActionLedgerEntry(
                id = "ledger-0",
                kind = ActionLedgerKind.STAGED,
                importance = ActionRecordImportance.SIGNAL,
                actionType = ActionType.CONTACT_USER,
                summary = "Approval needed for owner reply",
                rootInputId = "root-1",
                stagedActionId = "stage-1",
                source = "stage",
                conversationContext = context,
            )
        )
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
                importance = ActionRecordImportance.BACKGROUND,
                executionStatus = ActionExecutionStatus.SUCCESS,
                statusSummary = "Authorized from dashboard",
            )
            staged = staged.copy(
                status = StagedActionStatus.COMPLETED,
                authorizationId = authorization.id,
                receiptId = receipt?.id,
            )
            ledgerEntries += ActionLedgerEntry(
                id = "ledger-1",
                kind = ActionLedgerKind.AUTHORIZED,
                importance = ActionRecordImportance.BACKGROUND,
                actionType = staged.actionType,
                summary = "Action authorized for commit.",
                rootInputId = "root-1",
                stagedActionId = stagedActionId,
                authorizationId = authorization.id,
                source = "authorize",
                conversationContext = context,
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

        override suspend fun denyStagedAction(
            stagedActionId: String,
            deniedBy: ConversationSecurityContext,
            reason: String,
            reasonCode: String?,
        ): ActionControlDecisionResult =
            ActionControlDecisionResult.Refused(reason = "Stage already completed.", reasonCode = "STAGED_ACTION_NOT_DENYABLE")

        override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> = emptyList()

        override suspend fun recordBypassExecution(
            action: PendingAction,
            conversationContext: ConversationContext,
            outcome: ai.neopsyke.agent.model.ActionOutcome,
            reason: String,
            reasonCode: String?,
        ): ActionReceipt? = null

        override fun recordLedgerEntry(
            action: PendingAction,
            conversationContext: ConversationContext,
            kind: ActionLedgerKind,
            importance: ActionRecordImportance,
            summary: String,
            reasonCode: String?,
            source: String?,
            stagedActionId: String?,
            authorizationId: String?,
            receiptId: String?,
        ): ActionLedgerEntry =
            ActionLedgerEntry(
                id = "ledger-dynamic",
                kind = kind,
                importance = importance,
                actionType = action.type,
                summary = summary,
                rootInputId = action.rootInputId,
                stagedActionId = stagedActionId,
                authorizationId = authorizationId,
                receiptId = receiptId,
                reasonCode = reasonCode,
                source = source,
                conversationContext = conversationContext,
            ).also { ledgerEntries += it }

        override fun stagedActions(limit: Int): List<StagedAction> = listOf(staged)
        override fun stagedAction(id: String): StagedAction? = staged.takeIf { it.id == id }
        override fun receipts(limit: Int): List<ActionReceipt> = listOfNotNull(receipt)
        override fun receipt(id: String): ActionReceipt? = receipt?.takeIf { it.id == id }
        override fun ledgerEntries(limit: Int): List<ActionLedgerEntry> = ledgerEntries.takeLast(limit).reversed()
        override fun ledgerEntry(id: String): ActionLedgerEntry? = ledgerEntries.lastOrNull { it.id == id }
    }
}
