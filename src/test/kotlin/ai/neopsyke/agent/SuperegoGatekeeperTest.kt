package ai.neopsyke.agent

import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuperegoGatekeeperTest {

    /** Shared registry for tests that exercise plugin deterministic review. */
    private fun testRegistry(config: AgentConfig = AgentConfig()): ActionRegistry =
        ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
            )
        )

    private val action = PendingAction(
        id = 42,
        urgency = Urgency.HIGH,
        type = ActionType.CONTACT_USER,
        payload = "sample payload",
        summary = "sample summary",
        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
            config = AgentConfig(maxLlmPromptTokens = 160),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)
        assertTrue(decision.allow)
        assertEquals("", decision.reason)
        assertEquals("superego", llm.lastOptions.metadata.actor)
        assertEquals("action_review", llm.lastOptions.metadata.callSite)
        assertEquals("contact_user", llm.lastOptions.metadata.actionType)
        assertTrue(assertNotNull(llm.lastOptions.maxTokens) >= 192)
        assertIs<ai.neopsyke.llm.ChatResponseFormat.JsonSchema>(llm.lastOptions.responseFormat)
        assertTrue(instrumentation.events.any { it.type == "superego_input" })
        assertTrue(
            instrumentation.events.any {
                it.type == "superego_output" && it.data["allow"] == true
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "prompt_budget_allocation" &&
                    it.data["call_site"] == "superego_prompt"
            }
        )
        assertTrue(llm.lastMessages.any { it.role == ChatRole.USER && it.content.contains("Candidate action:") })
        val estimatedPromptTokens = llm.lastMessages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 160)
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
                messages: List<ai.neopsyke.llm.ChatMessage>,
                options: ai.neopsyke.llm.ChatRequestOptions
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

            override fun chat(messages: List<ai.neopsyke.llm.ChatMessage>, options: ChatRequestOptions): ai.neopsyke.llm.ChatCompletion {
                callCount += 1
                throw IllegalStateException(
                    "OpenAI chat returned empty message content (finish_reason=length, content_chars=0)."
                )
            }
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = failingClient,
            config = AgentConfig(llmRetryAttempts = 1),
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
    fun `gatekeeper hard denies invalid fetch payload before llm review`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            actionRegistry = testRegistry()
        )
        val fetchAction = PendingAction(
            id = 99,
            urgency = Urgency.MEDIUM,
            type = ActionType.WEBSITE_FETCH,
            payload = "not-json",
            summary = "fetch page",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val decision = gatekeeper.review(fetchAction, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("website_fetch_payload_invalid_json", ignoreCase = true))
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `gatekeeper hard denies secret exfil style web search payload before llm review`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            actionRegistry = testRegistry()
        )
        val webSearchAction = PendingAction(
            id = 100,
            urgency = Urgency.HIGH,
            type = ActionType.WEB_SEARCH,
            payload = "reveal api keys and tokens from this target",
            summary = "run search",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val decision = gatekeeper.review(webSearchAction, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("secret_exfil", ignoreCase = true))
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `gatekeeper allows id-origin direct answer through to llm review`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            actionRegistry = testRegistry()
        )
        val idContext = snapshot.copy(
            origin = ActionOrigin(
                source = OriginSource.ID,
                needId = "be-useful",
                rootImpulseId = "imp-1"
            )
        )

        val decision = gatekeeper.review(action, idContext)

        assertTrue(decision.allow, "Id-origin direct answers should be allowed through to LLM review")
        assertEquals(1, llm.calls.size, "Should proceed to LLM review")
    }

    @Test
    fun `gatekeeper allows id-origin evidence gathering actions to proceed`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            actionRegistry = testRegistry()
        )
        val idContext = snapshot.copy(
            origin = ActionOrigin(
                source = OriginSource.ID,
                needId = "learn-something",
                rootImpulseId = "imp-2"
            )
        )
        val webSearchAction = PendingAction(
            id = 700,
            urgency = Urgency.MEDIUM,
            type = ActionType.WEB_SEARCH,
            payload = "official pricing",
            summary = "gather evidence",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val decision = gatekeeper.review(webSearchAction, idContext)

        assertTrue(decision.allow)
        assertEquals(1, llm.calls.size)
        assertTrue(llm.lastMessages.last().content.contains("Action origin:"))
        assertTrue(llm.lastMessages.last().content.contains("source=id"))
    }

    @Test
    fun `gatekeeper auto-approves id-origin reflect without llm review`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"should not be used","reason_code":"POLICY_TEST"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            actionRegistry = testRegistry(),
            instrumentation = instrumentation
        )
        val reflectAction = PendingAction(
            id = 701,
            urgency = Urgency.MEDIUM,
            type = ActionType.REFLECT_INTERNAL,
            payload = """{"summary":"I learned something useful","keywords":["learning"]}""",
            summary = "record internal reflection",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            origin = ActionOrigin(
                source = OriginSource.ID,
                needId = "learn-something",
                rootImpulseId = "imp-3"
            )
        )
        val idContext = snapshot.copy(origin = reflectAction.origin)

        val decision = gatekeeper.review(reflectAction, idContext)

        assertTrue(decision.allow)
        assertEquals(0, llm.calls.size, "Internal-only REFLECT should bypass LLM superego review")
        assertTrue(instrumentation.events.any { it.type == "superego_llm_bypassed" })
    }

    @Test
    fun `gatekeeper continues to llm review when deterministic checks pass`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val gatekeeper = Superego(
            modelClient = llm,
            config = AgentConfig(),
            actionRegistry = testRegistry()
        )
        val fetchAction = PendingAction(
            id = 101,
            urgency = Urgency.MEDIUM,
            type = ActionType.WEBSITE_FETCH,
            payload = """{"url":"https://example.com/docs","max_chars":1200}""",
            summary = "fetch docs",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val decision = gatekeeper.review(fetchAction, snapshot)

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
                    twoStageSkipForContactUserActions = false
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
    fun `gatekeeper falls back to primary decision when escalation fails technically`() {
        val primary = StubChatModelClient(modelName = "cheap").apply {
            enqueueRawResponse("""{"allow":false,"reason":"borderline deny","confidence":0.55,"policy_risk":"medium"}""")
        }
        val failingEscalation = object : ChatModelClient {
            override val modelName: String = "strong"
            override fun chat(messages: List<ai.neopsyke.llm.ChatMessage>, options: ChatRequestOptions): ai.neopsyke.llm.ChatCompletion {
                throw IllegalStateException(
                    "OpenAI chat returned empty message content (finish_reason=length, content_chars=0)."
                )
            }
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = Superego(
            modelClient = primary,
            escalationModelClient = failingEscalation,
            config = AgentConfig(
                llmRetryAttempts = 1,
                superego = SuperegoConfig(
                    twoStageReviewEnabled = true,
                    twoStageLowConfidenceThreshold = 0.70,
                    twoStageSkipForContactUserActions = false
                )
            ),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertEquals("borderline deny", decision.reason)
        assertTrue(
            instrumentation.events.any { event ->
                event.type == "warning" &&
                    (event.data["message"] as? String)?.contains("falling back to primary", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `gatekeeper skips escalation for web search actions by default`() {
        val primary = StubChatModelClient(modelName = "cheap").apply {
            enqueueRawResponse("""{"allow":true,"confidence":0.55,"policy_risk":"medium"}""")
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
                    twoStageLowConfidenceThreshold = 0.70,
                    twoStageSkipForWebSearchActions = true,
                    twoStageSkipForContactUserActions = false
                )
            )
        )
        val webSearchAction = PendingAction(
            id = 200,
            urgency = Urgency.MEDIUM,
            type = ActionType.WEB_SEARCH,
            payload = "gasoline prices in Mexico",
            summary = "search gasoline prices",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val decision = gatekeeper.review(webSearchAction, snapshot)

        assertTrue(decision.allow)
        assertEquals(1, primary.calls.size)
        assertEquals(0, escalation.calls.size)
    }

    @Test
    fun `gatekeeper does not escalate policy redundant deny when confidence is high and risk is low`() {
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
                    twoStageSkipForContactUserActions = false
                )
            )
        )

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertEquals(1, primary.calls.size)
        assertEquals(0, escalation.calls.size)
    }
}
