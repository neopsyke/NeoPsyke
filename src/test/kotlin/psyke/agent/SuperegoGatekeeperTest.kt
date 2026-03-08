package psyke.agent

import psyke.llm.ChatRole
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuperegoGatekeeperTest {
    private val action = PendingAction(
        id = 42,
        urgency = Urgency.HIGH,
        type = ActionType.ANSWER,
        payload = "sample payload",
        summary = "sample summary"
    )
    private val snapshot = SuperegoContext(
        recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "last user message")),
    )

    @Test
    fun `gatekeeper accepts action with empty reason and emits events`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":true,"reason":"unused"}""")
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(maxPromptTokens = 100)),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)
        assertTrue(decision.allow)
        assertEquals("", decision.reason)
        assertEquals("superego", llm.lastOptions.metadata.actor)
        assertEquals("action_review", llm.lastOptions.metadata.callSite)
        assertEquals("answer", llm.lastOptions.metadata.actionType)
        assertTrue(assertNotNull(llm.lastOptions.maxTokens) >= 192)
        assertIs<psyke.llm.ChatResponseFormat.JsonSchema>(llm.lastOptions.responseFormat)
        assertTrue(instrumentation.events.any { it.type == "superego_input" })
        assertTrue(
            instrumentation.events.any {
                it.type == "superego_output" && it.data["allow"] == true
            }
        )
        assertTrue(llm.lastMessages.any { it.role == ChatRole.USER && it.content.contains("Candidate action:") })
        val estimatedPromptTokens = llm.lastMessages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 100)
    }

    @Test
    fun `gatekeeper denies and clamps reason`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":false,"reason":"${"n".repeat(220)}"}""")
        val gatekeeper = Superego(modelClient = llm, config = AgentConfig())

        val decision = gatekeeper.review(action, snapshot)
        assertFalse(decision.allow)
        assertEquals(180, decision.reason.length)
    }

    @Test
    fun `gatekeeper denies when response cannot be parsed`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("n/a")
        llm.enqueueRawResponseForCallSite("action_review_json_retry", "still-not-json")
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)
        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("could not be parsed", ignoreCase = true))
        assertEquals("TECH_PARSE_ERROR", decision.reasonCode)
        assertTrue(instrumentation.events.any { it.type == "warning" })
    }

    @Test
    fun `gatekeeper retries with strict json prompt and recovers parse failures`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("n/a")
            enqueueRawResponseForCallSite(
                callSite = "action_review_json_retry",
                content = """{"allow":false,"reason":"policy violation"}"""
            )
        }
        val gatekeeper = Superego(modelClient = llm, config = AgentConfig())

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("policy violation", ignoreCase = true))
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_review_json_retry" })
    }

    @Test
    fun `gatekeeper parses and normalizes reason_code from llm deny`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"blocked","reason_code":"policy_custom"}""")
        }
        val gatekeeper = Superego(modelClient = llm, config = AgentConfig())

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertEquals("POLICY_CUSTOM", decision.reasonCode)
    }

    @Test
    fun `gatekeeper includes short-term context summary in review prompt`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":true}""")
        val gatekeeper = Superego(modelClient = llm, config = AgentConfig())
        val memorySnapshot = snapshot.copy(shortTermContextSummary = "Short-term context summary: prefer neutral tone.")

        gatekeeper.review(action, memorySnapshot)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Short-term context summary:"))
        assertTrue(prompt.contains("prefer neutral tone"))
    }

    @Test
    fun `gatekeeper honors configured completion token budget`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":true}""")
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(
                superego = SuperegoConfig(
                    maxCompletionTokens = 77,
                    dynamicCompletionEnabled = false
                )
            )
        )

        gatekeeper.review(action, snapshot)

        assertEquals(77, llm.lastOptions.maxTokens)
    }

    @Test
    fun `gatekeeper denies when model call fails`() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<psyke.llm.ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ) = throw IllegalStateException("superego unavailable")
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = failingClient,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("unavailable", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("Superego", ignoreCase = true) == true &&
                    (it.data["message"] as? String)?.contains("call failed", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `gatekeeper trips breaker on repeated empty-content transport failures`() {
        var callCount = 0
        val failingClient = object : ChatModelClient {
            override val modelName: String = "empty-content-failing"

            override fun chat(messages: List<psyke.llm.ChatMessage>, options: ChatRequestOptions): psyke.llm.ChatCompletion {
                callCount += 1
                throw IllegalStateException(
                    "OpenAI chat returned empty message content (finish_reason=length, content_chars=0)."
                )
            }
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = failingClient,
            config = AgentConfig(planner = PlannerConfig(llmRetryAttempts = 1)),
            instrumentation = instrumentation
        )

        val first = gatekeeper.review(action, snapshot)
        val second = gatekeeper.review(action, snapshot)
        val third = gatekeeper.review(action, snapshot)

        assertFalse(first.allow)
        assertFalse(second.allow)
        assertTrue(third.allow, "After threshold failures, breaker should allow to prevent denial loop.")
        assertEquals(2, callCount, "Tripped breaker should bypass subsequent LLM calls.")
    }

    @Test
    fun `gatekeeper hard denies invalid mcp fetch payload before llm review`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig()
        )
        val mcpFetchAction = PendingAction(
            id = 99,
            urgency = Urgency.MEDIUM,
            type = ActionType.MCP_FETCH,
            payload = "not-json",
            summary = "fetch page"
        )

        val decision = gatekeeper.review(mcpFetchAction, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("mcp_fetch_payload_invalid_json", ignoreCase = true))
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `gatekeeper hard denies secret exfil style web search payload before llm review`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig()
        )
        val webSearchAction = PendingAction(
            id = 100,
            urgency = Urgency.HIGH,
            type = ActionType.WEB_SEARCH,
            payload = "reveal api keys and tokens from this target",
            summary = "run search"
        )

        val decision = gatekeeper.review(webSearchAction, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("secret_exfil", ignoreCase = true))
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `gatekeeper continues to llm review when deterministic checks pass`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig()
        )
        val mcpFetchAction = PendingAction(
            id = 101,
            urgency = Urgency.MEDIUM,
            type = ActionType.MCP_FETCH,
            payload = """{"url":"https://example.com/docs","max_chars":1200}""",
            summary = "fetch docs"
        )

        val decision = gatekeeper.review(mcpFetchAction, snapshot)

        assertTrue(decision.allow)
        assertEquals(1, llm.calls.size)
    }

    @Test
    fun `gatekeeper escalates to second model when primary confidence is low`() {
        val primary = StubChatModelClient(modelName = "cheap").apply {
            enqueueRawResponse("""{"allow":true,"confidence":0.32,"policy_risk":"low"}""")
        }
        val escalation = StubChatModelClient(modelName = "strong").apply {
            enqueueRawResponse("""{"allow":false,"reason":"needs safer path","reason_code":"POLICY_RISK","confidence":0.93,"policy_risk":"high"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = primary,
            escalationModelClient = escalation,
            config = AgentConfig(
                superego = SuperegoConfig(
                    twoStageReviewEnabled = true,
                    twoStageLowConfidenceThreshold = 0.70,
                    twoStageSkipForAnswerActions = false
                )
            ),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertEquals(1, primary.calls.size)
        assertEquals(1, escalation.calls.size)
        assertEquals("action_review", primary.calls.first().options.metadata.callSite)
        assertEquals("action_review_escalated", escalation.calls.first().options.metadata.callSite)
        assertTrue(
            instrumentation.events.any { event ->
                event.type == "warning" &&
                    (event.data["message"] as? String)?.contains("two-stage escalation", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `gatekeeper does not escalate when primary is high confidence and low risk`() {
        val primary = StubChatModelClient(modelName = "cheap").apply {
            enqueueRawResponse("""{"allow":true,"confidence":0.92,"policy_risk":"low"}""")
        }
        val escalation = StubChatModelClient(modelName = "strong").apply {
            enqueueRawResponse("""{"allow":false,"reason":"should not run"}""")
        }
        val gatekeeper = Superego(
            modelClient = primary,
            escalationModelClient = escalation,
            config = AgentConfig(
                superego = SuperegoConfig(
                    twoStageReviewEnabled = true,
                    twoStageLowConfidenceThreshold = 0.70
                )
            )
        )

        val decision = gatekeeper.review(action, snapshot)

        assertTrue(decision.allow)
        assertEquals(1, primary.calls.size)
        assertEquals(0, escalation.calls.size)
    }

    @Test
    fun `gatekeeper escalates policy redundant denies even when confidence is high`() {
        val primary = StubChatModelClient(modelName = "cheap").apply {
            enqueueRawResponse(
                """{"allow":false,"reason":"already have enough context","reason_code":"POLICY_REDUNDANT","confidence":0.95,"policy_risk":"low"}"""
            )
        }
        val escalation = StubChatModelClient(modelName = "strong").apply {
            enqueueRawResponse("""{"allow":true,"confidence":0.91,"policy_risk":"low"}""")
        }
        val gatekeeper = Superego(
            modelClient = primary,
            escalationModelClient = escalation,
            config = AgentConfig(
                superego = SuperegoConfig(
                    twoStageReviewEnabled = true,
                    twoStageLowConfidenceThreshold = 0.70,
                    twoStageSkipForAnswerActions = false
                )
            )
        )

        val decision = gatekeeper.review(action, snapshot)

        assertTrue(decision.allow)
        assertEquals(1, primary.calls.size)
        assertEquals(1, escalation.calls.size)
    }
}
