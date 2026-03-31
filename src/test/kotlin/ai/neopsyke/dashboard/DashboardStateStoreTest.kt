package ai.neopsyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PendingThought
import ai.neopsyke.agent.model.QueueState
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.metrics.MetricsSnapshot
import ai.neopsyke.metrics.MetricsTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardStateStoreTest {
    private val mapper = jacksonObjectMapper()
    private val nowMs = System.currentTimeMillis()

    @Test
    fun `snapshot aggregates state and keeps events ordered by id`() {
        val store = DashboardStateStore(maxEvents = 100)
        val queues = QueueState(
            inputs = listOf(PendingInput(1, "hello")),
            thoughts = listOf(PendingThought(2, Urgency.HIGH, "think", 1)),
            actions = listOf(PendingAction(3, Urgency.MEDIUM, ActionType.CONTACT_USER, "payload", "sum", 0))
        )
        val metrics = MetricsSnapshot(
            runId = "run-1",
            provider = "mistral",
            keyFingerprint = "fp",
            updatedAtIso = "2026-01-01T00:00:00Z",
            runTotals = MetricsTotals(1, 2, 3, 5, 0, 0),
            persistentTotals = MetricsTotals(2, 4, 6, 10, 1, 0),
            runCountForScope = 2,
            runSuperegoTokens = 3,
            persistentSuperegoTokens = 6
        )

        store.onEvent(AgentEvent(id = 2, type = "loop_step", data = mapOf("step" to 2, "task_type" to "thought")))
        store.onEvent(AgentEvent(id = 1, type = "loop_status", data = mapOf("status" to "running", "message" to "ok")))
        store.onEvent(AgentEvent(id = 3, type = "queue_snapshot", data = mapOf("queues" to queues)))
        store.onEvent(AgentEvent(id = 4, type = "superego_input", data = mapOf("allow" to false)))
        store.onEvent(AgentEvent(id = 5, type = "superego_output", data = mapOf("allow" to true)))
        store.onEvent(
            AgentEvent(
                id = 6,
                type = "action_capabilities",
                data = mapOf(
                    "statuses" to listOf(
                        mapOf("action_type" to "contact_user", "available" to true, "detail" to "ok"),
                        mapOf("action_type" to "website_fetch", "available" to false, "detail" to "offline")
                    )
                )
            )
        )
        store.onEvent(AgentEvent(id = 7, type = "limits_config", data = mapOf("limits" to mapOf("max_prompt_tokens" to 2400))))
        store.onEvent(AgentEvent(id = 8, type = "metrics_snapshot", data = mapOf("metrics" to metrics)))
        store.onEvent(
            AgentEvent(
                id = 9,
                type = "queue_saturation",
                data = mapOf("queue_type" to "thought", "pending" to 32, "capacity" to 32, "reason" to "full")
            )
        )
        store.recordDroppedEvents(3)

        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertEquals("running", snapshot.loopStatus)
        assertEquals("ok", snapshot.loopMessage)
        assertEquals(2, snapshot.loopStep)
        assertEquals("thought", snapshot.currentTaskType)
        assertEquals(1, snapshot.queues.inputs.size)
        assertEquals(1, snapshot.queues.thoughts.size)
        assertEquals(1, snapshot.queues.actions.size)
        assertEquals(true, snapshot.lastSuperegoOutput?.get("allow"))
        assertEquals(2, snapshot.actionCapabilities.size)
        assertEquals("website_fetch", snapshot.actionCapabilities[1]["action_type"])
        assertEquals(false, snapshot.actionCapabilities[1]["available"])
        assertEquals(2400, snapshot.limits["max_prompt_tokens"])
        assertEquals(metrics, snapshot.metrics)
        assertEquals(3L, (snapshot.instrumentationHealth["dropped_events"] as Number).toLong())
        assertEquals(1L, (snapshot.instrumentationHealth["queue_saturation_events"] as Number).toLong())
        assertEquals(0L, (snapshot.taskVerifierStats["total_reviews"] as Number).toLong())
        assertEquals(0L, (snapshot.promptBudgetStats["total_allocations"] as Number).toLong())
        @Suppress("UNCHECKED_CAST")
        val saturationByType = snapshot.instrumentationHealth["queue_saturation_by_type"] as Map<String, Any?>
        assertEquals(1L, (saturationByType["thought"] as Number).toLong())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), snapshot.recentEvents.map { it.id })
    }

    @Test
    fun `snapshot aggregates task verifier stats`() {
        val store = DashboardStateStore(maxEvents = 30)
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "task_verifier_review",
                data = mapOf(
                    "allow" to false,
                    "reason_code" to "TASK_EVIDENCE_REQUIRED",
                    "intent_category" to "volatile_fact",
                    "volatility_level" to "high",
                    "requires_external_evidence" to true
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "task_verifier_review",
                data = mapOf(
                    "allow" to true,
                    "reason_code" to "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL",
                    "intent_category" to "volatile_fact",
                    "volatility_level" to "high",
                    "requires_external_evidence" to true
                )
            )
        )

        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertEquals(2L, (snapshot.taskVerifierStats["total_reviews"] as Number).toLong())
        assertEquals(1L, (snapshot.taskVerifierStats["deny_count"] as Number).toLong())
        assertEquals(1L, (snapshot.taskVerifierStats["graceful_allow_count"] as Number).toLong())
        @Suppress("UNCHECKED_CAST")
        val byReason = snapshot.taskVerifierStats["by_reason_code"] as Map<String, Any?>
        assertEquals(1L, (byReason["TASK_EVIDENCE_REQUIRED"] as Number).toLong())
        assertEquals(1L, (byReason["TASK_EVIDENCE_UNAVAILABLE_GRACEFUL"] as Number).toLong())
    }

    @Test
    fun `snapshot aggregates prompt budget stats`() {
        val store = DashboardStateStore(maxEvents = 30)
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "prompt_budget_allocation",
                data = mapOf(
                    "call_site" to "planner_prompt",
                    "single_message_fallback" to false,
                    "degradation_path" to "trim_optional",
                    "floor_violation_count" to 0,
                    "dropped_section_count" to 3
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "prompt_budget_allocation",
                data = mapOf(
                    "call_site" to "action_verifier_prompt",
                    "single_message_fallback" to true,
                    "degradation_path" to "single_message_fallback",
                    "floor_violation_count" to 2,
                    "dropped_section_count" to 8
                )
            )
        )

        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertEquals(2L, (snapshot.promptBudgetStats["total_allocations"] as Number).toLong())
        assertEquals(1L, (snapshot.promptBudgetStats["single_message_fallback_count"] as Number).toLong())
        assertEquals(1L, (snapshot.promptBudgetStats["floor_violation_events"] as Number).toLong())
        assertEquals(11L, (snapshot.promptBudgetStats["dropped_sections_total"] as Number).toLong())
        @Suppress("UNCHECKED_CAST")
        val byCallSite = snapshot.promptBudgetStats["by_call_site"] as Map<String, Any?>
        assertEquals(1L, (byCallSite["planner_prompt"] as Number).toLong())
        assertEquals(1L, (byCallSite["action_verifier_prompt"] as Number).toLong())
    }

    @Test
    fun `planner decision is enriched with scoped structured output mode`() {
        val store = DashboardStateStore(maxEvents = 20)
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "llm_call",
                data = mapOf(
                    "actor" to "ego",
                    "call_site" to "thought_json_retry",
                    "structured_output_mode" to "relaxed",
                    "session_id" to "session-a",
                    "root_input_id" to "root-a",
                    "status" to "ok"
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "llm_call",
                data = mapOf(
                    "actor" to "ego",
                    "call_site" to "thought",
                    "structured_output_mode" to "strict",
                    "session_id" to "session-b",
                    "root_input_id" to "root-b",
                    "status" to "ok"
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 3,
                type = "planner_decision",
                data = mapOf(
                    "trigger" to "thought",
                    "decision_type" to "action",
                    "session_id" to "session-a",
                    "root_input_id" to "root-a"
                )
            )
        )

        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        val decision = snapshot.recentEvents.single { it.type == "planner_decision" }
        assertEquals("relaxed", decision.data["structured_output_mode"])
    }

    @Test
    fun `snapshot preserves cognitive stage observability events`() {
        val store = DashboardStateStore(maxEvents = 20)
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "cognitive_thread_updated",
                data = mapOf(
                    "root_input_id" to "root-1",
                    "thread_id" to "thread-1",
                    "thread_status" to "ACTIVE",
                    "reason" to "input_percept_bound"
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "opportunity_enqueued",
                data = mapOf(
                    "root_input_id" to "root-1",
                    "opportunity_id" to "opp-1",
                    "opportunity_kind" to "RESPOND",
                    "source" to "input"
                )
            )
        )

        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        val threadUpdate = snapshot.recentEvents.single { it.type == "cognitive_thread_updated" }
        val opportunity = snapshot.recentEvents.single { it.type == "opportunity_enqueued" }
        assertEquals("thread-1", threadUpdate.data["thread_id"])
        assertEquals("input_percept_bound", threadUpdate.data["reason"])
        assertEquals("opp-1", opportunity.data["opportunity_id"])
        assertEquals("input", opportunity.data["source"])
    }

    @Test
    fun `subscription receives json payload for incoming events`() = runBlocking {
        val store = DashboardStateStore(maxEvents = 10)
        val subscription = store.subscribe()
        store.onEvent(AgentEvent(id = 9, type = "warning", data = mapOf("message" to "test")))

        val payload = withTimeoutOrNull(200) { subscription.receive() }
        assertNotNull(payload)
        assertTrue(payload.contains("\"type\":\"warning\""))
        assertTrue(payload.contains("\"id\":9"))
        subscription.close()
        store.close()
    }

    @Test
    fun `filtered subscription only receives matching events`() = runBlocking {
        val store = DashboardStateStore(maxEvents = 10)
        val subscription = store.subscribe { event -> event.type.startsWith("goal_") }
        store.onEvent(AgentEvent(id = 1, type = "warning", data = mapOf("message" to "ignore")))
        store.onEvent(AgentEvent(id = 2, type = "goal_started", data = mapOf("goal_id" to "goal-1")))

        val payload = withTimeoutOrNull(200) { subscription.receive() }
        assertNotNull(payload)
        assertTrue(payload.contains("\"type\":\"goal_started\""))
        assertTrue(payload.contains("\"goal_id\":\"goal-1\""))
        subscription.close()
        store.close()
    }

    @Test
    fun `scratchpad debug snapshot is captured for api but not broadcast over sse`() {
        val store = DashboardStateStore(maxEvents = 20)
        val subscription = store.subscribe()
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "scratchpad_debug_snapshot",
                data = mapOf(
                    "root_input_id" to "root-99",
                    "root_input_received_at_ms" to 99L,
                    "update_type" to "plan_recorded",
                    "version" to 4L,
                    "updated_at_ms" to nowMs,
                    "goal" to "Verify pricing and summarize",
                    "section_count" to 2,
                    "evidence_count" to 1,
                    "scratchpad_confidence" to 0.72,
                    "bytes_estimate" to 222,
                    "sections" to listOf(
                        mapOf(
                            "title" to "Request",
                            "summary" to "find pricing",
                            "content" to "find official pricing page",
                            "source" to "input"
                        )
                    ),
                    "evidence" to listOf("official pricing page found")
                )
            )
        )

        val ssePayload = runBlocking { withTimeoutOrNull(120) { subscription.receive() } }
        assertNull(ssePayload)

        val index: Map<String, Any?> = mapper.readValue(store.scratchpadIndexJson())
        val items = index["items"] as List<*>
        assertEquals(1, items.size)
        @Suppress("UNCHECKED_CAST")
        val first = items.first() as Map<String, Any?>
        assertEquals("root-99", first["root_input_id"])
        assertEquals(4L, (first["version"] as Number).toLong())

        val detailJson = store.scratchpadSnapshotJson(rootInputId = "root-99")
        assertNotNull(detailJson)
        val detail: Map<String, Any?> = mapper.readValue(detailJson)
        assertEquals("Verify pricing and summarize", detail["goal"])
        @Suppress("UNCHECKED_CAST")
        val sections = detail["sections"] as List<Map<String, Any?>>
        assertEquals(1, sections.size)
        assertEquals("Request", sections.first()["title"])

        subscription.close()
        store.close()
    }

    @Test
    fun `chat session captures user and assistant messages scoped by root input`() {
        val store = DashboardStateStore(maxEvents = 20)
        val session = store.createChatSession(title = "Test Session")
        val sessionId = session.sessionId

        val user = store.addUserMessage(sessionId = sessionId, content = "hello from web")
        assertNotNull(user)

        val rootInputId = "root-chat-4242"
        val rootInputMs = 4242L
        val conversationContext = ConversationContext(sessionId, Interlocutor.named("test-user"))
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "input_queued",
                data = mapOf(
                    "input" to PendingInput(
                        id = 10L,
                        content = "hello from web",
                        source = "chat:$sessionId",
                        rootInputId = rootInputId,
                        conversationContext = conversationContext,
                        receivedAtMs = rootInputMs
                    )
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "action_executed",
                data = mapOf(
                    "action" to PendingAction(
                        id = 11L,
                        urgency = Urgency.HIGH,
                        type = ActionType.CONTACT_USER,
                        payload = "assistant reply",
                        summary = "summary",
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputMs,
                        conversationContext = conversationContext
                    ),
                    "outcome_summary" to "ok"
                )
            )
        )

        val sessionPayload = store.chatSessionJson(sessionId)
        assertNotNull(sessionPayload)
        val detail: Map<String, Any?> = mapper.readValue(sessionPayload)
        @Suppress("UNCHECKED_CAST")
        val messages = detail["messages"] as List<Map<String, Any?>>
        assertEquals(2, messages.size)
        assertEquals("user", messages[0]["role"])
        assertEquals("assistant", messages[1]["role"])
        assertEquals("assistant reply", messages[1]["content"])

        store.close()
    }

    @Test
    fun `root input session mapping is retained through contact user execution and cleared on scratchpad destroy`() {
        val store = DashboardStateStore(maxEvents = 20)
        val sessionId = store.createChatSession(title = "Mapping Test").sessionId
        val rootInputId = "root-retain-1"
        val rootInputMs = 1234L
        val conversationContext = ConversationContext(sessionId, Interlocutor.named("owner"))

        store.onEvent(
            AgentEvent(
                id = 1,
                type = "input_queued",
                data = mapOf(
                    "input" to PendingInput(
                        id = 10L,
                        content = "hello",
                        source = "chat:$sessionId",
                        rootInputId = rootInputId,
                        conversationContext = conversationContext,
                        receivedAtMs = rootInputMs,
                    )
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "action_executed",
                data = mapOf(
                    "action" to PendingAction(
                        id = 11L,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.CONTACT_USER,
                        payload = "reply",
                        summary = "summary",
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputMs,
                        conversationContext = conversationContext,
                    ),
                    "outcome_summary" to "ok",
                )
            )
        )

        assertEquals(sessionId, store.resolveSessionForRootInput(rootInputId))

        store.onEvent(
            AgentEvent(
                id = 3,
                type = "scratchpad_destroyed",
                data = mapOf("root_input_id" to rootInputId),
            )
        )

        assertNull(store.resolveSessionForRootInput(rootInputId))
        store.close()
    }

    @Test
    fun `phase timings events are accumulated in ring buffer with max limit`() {
        val store = DashboardStateStore(maxEvents = 300)
        repeat(210) { i ->
            store.onEvent(
                AgentEvent(
                    id = (i + 1).toLong(),
                    type = "phase_timings",
                    data = mapOf(
                        "task_type" to "input",
                        "root_input_id" to "root-$i",
                        "total_duration_ms" to 100L,
                        "phases" to listOf(mapOf("name" to "alpha", "duration_ms" to 100L)),
                        "timestamp_ms" to System.currentTimeMillis(),
                    )
                )
            )
        }
        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertEquals(200, snapshot.phaseTimings.size)
        // oldest entries should have been dropped; most recent kept
        assertEquals("root-10", snapshot.phaseTimings.first()["root_input_id"])
        assertEquals("root-209", snapshot.phaseTimings.last()["root_input_id"])
    }

    @Test
    fun `heap snapshot event replaces previous snapshot`() {
        val store = DashboardStateStore(maxEvents = 10)
        store.onEvent(
            AgentEvent(
                id = 1,
                type = "heap_snapshot",
                data = mapOf(
                    "jvm_total_bytes" to 1_000_000L,
                    "jvm_used_bytes" to 500_000L,
                    "jvm_max_bytes" to 2_000_000L,
                    "jvm_used_percent" to 50.0,
                )
            )
        )
        store.onEvent(
            AgentEvent(
                id = 2,
                type = "heap_snapshot",
                data = mapOf(
                    "jvm_total_bytes" to 1_200_000L,
                    "jvm_used_bytes" to 700_000L,
                    "jvm_max_bytes" to 2_000_000L,
                    "jvm_used_percent" to 58.3,
                )
            )
        )
        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertNotNull(snapshot.heapMetrics)
        assertEquals(700_000L, (snapshot.heapMetrics!!["jvm_used_bytes"] as Number).toLong())
    }

    @Test
    fun `chat messages receive monotonically increasing per-session sequence numbers`() {
        val store = DashboardStateStore(maxEvents = 20)
        val session = store.createChatSession(title = "Sequence Test")
        val sessionId = session.sessionId

        val m1 = store.addUserMessage(sessionId = sessionId, content = "first")
        val m2 = store.addUserMessage(sessionId = sessionId, content = "second")
        val m3 = store.addUserMessage(sessionId = sessionId, content = "third")
        assertNotNull(m1)
        assertNotNull(m2)
        assertNotNull(m3)

        assertTrue(m1.sequence > 0, "sequence should be positive")
        assertTrue(m2.sequence > m1.sequence, "second message should have higher sequence")
        assertTrue(m3.sequence > m2.sequence, "third message should have higher sequence")

        // Verify it appears in the JSON output
        val sessionPayload = store.chatSessionJson(sessionId)
        assertNotNull(sessionPayload)
        assertTrue(sessionPayload.contains("\"sequence\""))
    }

    @Test
    fun `sequence numbers are independent per session`() {
        val store = DashboardStateStore(maxEvents = 20)
        val s1 = store.createChatSession(title = "S1").sessionId
        val s2 = store.createChatSession(title = "S2").sessionId

        val m1a = store.addUserMessage(sessionId = s1, content = "s1-msg1")!!
        val m2a = store.addUserMessage(sessionId = s2, content = "s2-msg1")!!
        val m1b = store.addUserMessage(sessionId = s1, content = "s1-msg2")!!

        // Each session has its own sequence counter
        assertEquals(1L, m1a.sequence)
        assertEquals(1L, m2a.sequence)
        assertEquals(2L, m1b.sequence)
    }

    @Test
    fun `snapshot includes store stats`() {
        val store = DashboardStateStore(maxEvents = 50)
        store.onEvent(AgentEvent(id = 1, type = "loop_step", data = mapOf("step" to 1)))
        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertTrue(snapshot.storeStats.containsKey("event_count"))
        assertTrue(snapshot.storeStats.containsKey("max_events"))
        assertEquals(50, (snapshot.storeStats["max_events"] as Number).toInt())
    }
}
