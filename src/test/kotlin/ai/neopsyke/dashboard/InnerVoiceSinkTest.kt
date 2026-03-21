package ai.neopsyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerVoiceSinkTest {
    private val mapper = jacksonObjectMapper()

    private fun buildStack(
        config: InnerVoiceConfig = InnerVoiceConfig()
    ): Triple<DashboardStateStore, InnerVoiceStore, InnerVoiceSink> {
        val dashboardStore = DashboardStateStore()
        val innerVoiceStore = InnerVoiceStore(maxEventsPerSession = config.maxEventsPerSession)
        val sink = InnerVoiceSink(
            dashboardStore = dashboardStore,
            innerVoiceStore = innerVoiceStore,
            config = config
        )
        return Triple(dashboardStore, innerVoiceStore, sink)
    }

    private fun seedSession(
        dashboardStore: DashboardStateStore,
        sessionId: String = "default",
        rootInputId: String = "root-1"
    ) {
        val input = PendingInput(
            id = 1,
            content = "test input",
            rootInputId = rootInputId,
            conversationContext = ConversationContext(
                sessionId = sessionId,
                interlocutor = ai.neopsyke.agent.model.Interlocutor("user-1")
            )
        )
        dashboardStore.onEvent(AgentEvents.inputQueued(input))
    }

    @Test
    fun `thought planner decision produces DELIBERATION event`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // First decision is a thought → activates inner voice
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "I need to search for recent information about this topic.",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            assertEquals("thinking", parsed["type"])
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("DELIBERATION", event["type"])
            assertEquals("I need to search for recent information about this topic.", event["content"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `single-step direct answer produces no events`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // First and only decision is action=answer → no activation
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "action",
                actionType = "answer",
                summary = "Direct answer",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(300) { sub.receive() }
            assertNull(payload, "Single-step answer should not produce inner voice events")
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `non-answer action produces INTENTION event`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "action",
                actionType = "web_search",
                summary = "Searching for latest news",
                payload = "latest news",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("INTENTION", event["type"])
            assertEquals("Searching for latest news", event["content"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `plan_created produces PLAN event and activates voice`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.planCreated(
                planId = "plan-1",
                goal = "Find and summarize recent AI developments",
                stepCount = 3,
                urgency = "high",
                steps = listOf("Search", "Fetch", "Summarize"),
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("PLAN", event["type"])
            assertEquals("Find and summarize recent AI developments", event["content"])
            @Suppress("UNCHECKED_CAST")
            val meta = event["metadata"] as Map<String, Any?>
            assertEquals(3, meta["step_count"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `action_denied produces RECONSIDERATION event when root is activated`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Activate by sending a thought first
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "Let me think...",
                rootInputId = "root-1",
            )
        )

        val action = PendingAction(
            id = 1,
            urgency = Urgency.HIGH,
            type = ActionType.WEBSITE_FETCH,
            payload = "https://example.com",
            summary = "Fetch page",
            rootInputId = "root-1"
        )
        sink.onEvent(AgentEvents.actionDenied(action, "External API calls restricted", "POLICY_EXTERNAL_CALLS"))

        runBlocking {
            // Skip the DELIBERATION event
            withTimeoutOrNull(1000) { sub.receive() }
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("RECONSIDERATION", event["type"])
            assertEquals("Reconsidering: External API calls restricted", event["content"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `memory_recall_result with hits produces RECALL event when rootInputId known`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Factory method now carries rootInputId
        sink.onEvent(
            AgentEvents.memoryRecallResult(
                trigger = "input",
                provider = "hippocampus",
                hitCount = 3,
                latencyMs = 42,
                recallChars = 200,
                truncated = false,
                recallTextPreview = "User previously asked about...",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("RECALL", event["type"])
            assertEquals("Recalled 3 memories (hippocampus)", event["content"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `memory_recall_result without rootInputId is silently dropped`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Standard factory event has no root_input_id
        sink.onEvent(
            AgentEvents.memoryRecallResult(
                trigger = "input",
                provider = "hippocampus",
                hitCount = 3,
                latencyMs = 42,
                recallChars = 200,
                truncated = false,
                recallTextPreview = "User previously asked about..."
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(300) { sub.receive() }
            // No rootInputId → no sessionId → emit drops it
            assertNull(payload)
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `memory_recall_result with zero hits is ignored`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.memoryRecallResult(
                trigger = "input",
                provider = "hippocampus",
                hitCount = 0,
                latencyMs = 10,
                recallChars = 0,
                truncated = false
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(300) { sub.receive() }
            assertNull(payload)
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `action_executed for non-answer produces OBSERVATION when activated`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Activate by sending a thought
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "Let me search...",
                rootInputId = "root-1",
            )
        )

        val action = PendingAction(
            id = 2,
            urgency = Urgency.HIGH,
            type = ActionType.WEB_SEARCH,
            payload = "AI news",
            summary = "Search for AI news",
            rootInputId = "root-1"
        )
        sink.onEvent(AgentEvents.actionExecuted(action, "Found 5 results for 'AI news'."))

        runBlocking {
            // Skip DELIBERATION
            withTimeoutOrNull(1000) { sub.receive() }
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("OBSERVATION", event["type"])
            assertEquals("Found 5 results for 'AI news'.", event["content"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `content is trimmed at maxContentChars boundary`() {
        val config = InnerVoiceConfig(maxContentChars = 20)
        val (dashboardStore, innerVoiceStore, sink) = buildStack(config)
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "This is a very long thought that exceeds the maximum content character limit.",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            val content = event["content"] as String
            assertEquals("This is a very long ...", content)
            assertEquals(23, content.length) // 20 chars + "..."
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `disabled config prevents all events`() {
        val config = InnerVoiceConfig(enabled = false)
        val (dashboardStore, innerVoiceStore, sink) = buildStack(config)
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "Should not appear",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(300) { sub.receive() }
            assertNull(payload)
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `irrelevant event types are ignored`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(AgentEvent(type = "loop_status", data = mapOf("status" to "running")))
        sink.onEvent(AgentEvent(type = "queue_snapshot", data = emptyMap()))
        sink.onEvent(AgentEvent(type = "heap_snapshot", data = emptyMap()))

        runBlocking {
            val payload = withTimeoutOrNull(300) { sub.receive() }
            assertNull(payload)
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `inner voice events carry positive sequence from shared session counter`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Add a chat message first so it gets sequence=1
        dashboardStore.addUserMessage(sessionId = "default", content = "hello")

        // Now emit a thinking event; it should get sequence=2 from the same counter
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "Let me think about this.",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            val sequence = (event["sequence"] as Number).toLong()
            assertTrue(sequence > 1L, "Inner voice sequence ($sequence) should be > 1 (after chat message)")
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `plan_step_started produces PLAN_STEP event when root is activated`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Activate by creating a plan first
        sink.onEvent(
            AgentEvents.planCreated(
                planId = "plan-1",
                goal = "Research AI developments",
                stepCount = 3,
                urgency = "high",
                steps = listOf("Search", "Fetch", "Summarize"),
                rootInputId = "root-1",
            )
        )

        // Emit plan step started
        sink.onEvent(
            AgentEvents.planStepStarted(
                planId = "plan-1",
                stepIndex = 0,
                totalSteps = 3,
                stepDescription = "Search for latest AI news articles",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            // Skip the PLAN event
            withTimeoutOrNull(1000) { sub.receive() }
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("PLAN_STEP", event["type"])
            assertEquals("Search for latest AI news articles", event["content"])
            @Suppress("UNCHECKED_CAST")
            val meta = event["metadata"] as Map<String, Any?>
            assertEquals(0, meta["step_index"])
            assertEquals(3, meta["total_steps"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `plan_step_started is ignored when root is not activated`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Emit plan step without prior activation
        sink.onEvent(
            AgentEvents.planStepStarted(
                planId = "plan-1",
                stepIndex = 0,
                totalSteps = 3,
                stepDescription = "Should not appear",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(300) { sub.receive() }
            assertNull(payload, "Plan step for non-activated root should not produce events")
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `impulse_processing registers rootInputId as Id-origin and auto-activates`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)

        // Register impulse origin
        sink.onEvent(
            AgentEvent(
                type = "impulse_processing",
                data = mapOf("root_impulse_id" to "impulse-1")
            )
        )

        // Map the impulse rootInputId to default session
        dashboardStore.onEvent(
            AgentEvent(
                type = "impulse_processing",
                data = mapOf("root_impulse_id" to "impulse-1")
            )
        )

        // Subscribe to Id global stream
        val idSub = innerVoiceStore.subscribeIdGlobal()

        // Now a planner decision with the impulse rootInputId should be auto-activated and Id-origin
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "impulse",
                decisionType = "thought",
                thought = "Id-driven thought about a need.",
                rootInputId = "impulse-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { idSub.receive() }
            assertNotNull(payload, "Id-origin event should arrive on global Id channel")
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("DELIBERATION", event["type"])
            assertEquals("id", event["origin"])
        }

        idSub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `Id-origin events do NOT appear on per-session conversation stream`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)

        // Register impulse
        sink.onEvent(AgentEvent(type = "impulse_processing", data = mapOf("root_impulse_id" to "impulse-2")))
        dashboardStore.onEvent(AgentEvent(type = "impulse_processing", data = mapOf("root_impulse_id" to "impulse-2")))

        val sessionSub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "impulse",
                decisionType = "thought",
                thought = "Id thought that should not be in conversation.",
                rootInputId = "impulse-2",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(300) { sessionSub.receive() }
            assertNull(payload, "Id-origin events should not appear on per-session conversation stream")
        }

        sessionSub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `user-origin events have origin=user and appear on session stream`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "User-driven thought.",
                rootInputId = "root-1",
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("user", event["origin"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `reflect action_executed produces REFLECTION event`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        val action = PendingAction(
            id = 10,
            urgency = Urgency.MEDIUM,
            type = ActionType.REFLECT,
            payload = """{"summary":"Learned that X is important","keywords":["X","important"]}""",
            summary = "Record insight about X",
            rootInputId = "root-1"
        )
        sink.onEvent(AgentEvents.actionExecuted(action, "Insight recorded: X is important"))

        runBlocking {
            val payload = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(payload, "Reflect action should produce REFLECTION event")
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val event = parsed["event"] as Map<String, Any?>
            assertEquals("REFLECTION", event["type"])
            assertEquals("Insight recorded: X is important", event["content"])
            @Suppress("UNCHECKED_CAST")
            val meta = event["metadata"] as Map<String, Any?>
            assertEquals("reflect", meta["action_type"])
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }

    @Test
    fun `workspace destroyed cleans up root tracking`() {
        val (dashboardStore, innerVoiceStore, sink) = buildStack()
        seedSession(dashboardStore)
        val sub = innerVoiceStore.subscribe("default")!!

        // Activate
        sink.onEvent(
            AgentEvents.plannerDecision(
                trigger = "input",
                decisionType = "thought",
                thought = "thinking...",
                rootInputId = "root-1",
            )
        )

        // Destroy
        sink.onEvent(
            AgentEvent(
                type = "scratchpad_destroyed",
                data = mapOf("root_input_id" to "root-1")
            )
        )

        // New event for same root after cleanup should not be activated
        sink.onEvent(
            AgentEvent(
                type = "action_executed",
                data = mapOf(
                    "action" to PendingAction(
                        id = 3,
                        urgency = Urgency.HIGH,
                        type = ActionType.WEB_SEARCH,
                        payload = "test",
                        summary = "test",
                        rootInputId = "root-1"
                    ),
                    "outcome_summary" to "Result"
                )
            )
        )

        runBlocking {
            // Only the first DELIBERATION event should arrive
            val p1 = withTimeoutOrNull(1000) { sub.receive() }
            assertNotNull(p1)
            val p2 = withTimeoutOrNull(300) { sub.receive() }
            assertNull(p2)
        }

        sub.close()
        sink.close()
        innerVoiceStore.close()
    }
}
