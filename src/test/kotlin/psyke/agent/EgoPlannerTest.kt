package psyke.agent

import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRole
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EgoPlannerTest {
    @Test
    fun `planner returns clamped thought decision and emits events`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"thought","urgency":"high","thought":"abcdefghi"}""")
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(maxThoughtChars = 5, maxCompletionTokens = 88)),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "hi")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(
                    pendingInputCount = 1,
                    pendingThoughtCount = 2,
                    pendingActionCount = 3
                )
            )
        )

        val thought = assertIs<psyke.agent.core.EgoDecision.EnqueueThought>(decision)
        assertEquals(Urgency.HIGH, thought.urgency)
        assertEquals("abcde", thought.content)
        assertEquals("ego", llm.lastOptions.metadata.actor)
        assertEquals("input", llm.lastOptions.metadata.callSite)
        assertEquals(88, llm.lastOptions.maxTokens)
        assertTrue(instrumentation.events.any { it.type == "planner_start" })
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" && it.data["decision_type"] == "thought"
            }
        )
    }

    @Test
    fun `planner returns action decision with clamped payload and summary`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"action",
              "urgency":"medium",
              "action_type":"answer",
              "action_payload":"payload-too-long",
              "action_summary":"summary-too-long"
            }
            """.trimIndent()
        )
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(maxActionPayloadChars = 7, maxActionSummaryChars = 8))
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.PendingThoughtInput(PendingThought(7, Urgency.LOW, "think", 1)),
            context = PlannerContext(
                recentDialogue = listOf(
                    DialogueTurn(DialogueRole.USER, "u"),
                    DialogueTurn(DialogueRole.ASSISTANT, "a")
                ),
                queue = QueueSnapshot(
                    pendingInputCount = 0,
                    pendingThoughtCount = 1,
                    pendingActionCount = 0
                )
            )
        )

        val action = assertIs<psyke.agent.core.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.ANSWER, action.actionType)
        assertEquals("payload", action.payload)
        assertEquals("summary-", action.summary)
        assertEquals("thought", llm.lastOptions.metadata.callSite)
    }

    @Test
    fun `planner accepts structured action payload object by normalizing to json string`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"action",
              "urgency":"medium",
              "action_type":"mcp_fetch",
              "action_payload":{"url":"https://openai.com/pricing","max_chars":1200},
              "action_summary":"Fetch OpenAI pricing page content"
            }
            """.trimIndent()
        )
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.PendingThoughtInput(PendingThought(7, Urgency.LOW, "think", 1)),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<psyke.agent.core.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.MCP_FETCH, action.actionType)
        assertTrue(action.payload.contains("\"url\":\"https://openai.com/pricing\""))
        assertTrue(action.payload.contains("\"max_chars\":1200"))
    }

    @Test
    fun `planner rejects actions unavailable at runtime`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"action",
              "urgency":"medium",
              "action_type":"mcp_fetch",
              "action_payload":"{\"url\":\"https://example.com\"}",
              "action_summary":"fetch page"
            }
            """.trimIndent()
        )
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "fetch this page")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.ANSWER, ActionType.WEB_SEARCH)
            )
        )

        val noop = assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `planner converts invalid payload and parse failures to noop`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"action","action_type":"answer"}""")
        llm.enqueueRawResponse("not-json")
        llm.enqueueRawResponseForCallSite("input_json_retry", "still-not-json")
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )
        val trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(2, "hello"))
        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0)
        )

        val invalidAction = planner.decide(trigger, context)
        assertIs<psyke.agent.core.EgoDecision.Noop>(invalidAction)
        assertTrue(invalidAction.reason.contains("invalid action", ignoreCase = true))

        val invalidJson = planner.decide(trigger, context)
        assertIs<psyke.agent.core.EgoDecision.Noop>(invalidJson)
        assertTrue(invalidJson.reason.contains("non-parseable", ignoreCase = true))
        assertTrue(instrumentation.events.any { it.type == "warning" })
    }

    @Test
    fun `planner retries with strict json prompt and recovers parse failures`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("not-json")
            enqueueRawResponseForCallSite(
                callSite = "input_json_retry",
                content = """{"decision":"noop","reason":"recovered"}"""
            )
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertEquals("recovered", noop.reason)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "input_json_retry" })
    }

    @Test
    fun `planner repairs invalid json escapes in model output`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"Costs are \${'$'}20 per month","action_summary":"deliver answer"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "pricing")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<psyke.agent.core.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.ANSWER, action.actionType)
        assertTrue(action.payload.contains("\$20"))
        assertEquals(1, repairCount)
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_output_repaired" &&
                    it.data["repair"] == "invalid_json_escape"
            }
        )
    }

    @Test
    fun `planner repairs missing action summary from payload and records repair`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"action",
                  "urgency":"medium",
                  "action_type":"answer",
                  "action_payload":"first useful line\nsecond line"
                }
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(maxActionSummaryChars = 180)),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<psyke.agent.core.EgoDecision.ProposeAction>(decision)
        assertEquals("first useful line", action.summary)
        assertEquals(1, repairCount)
        assertTrue(instrumentation.events.any { it.type == "planner_output_repaired" })
    }

    @Test
    fun `planner runs action verifier and applies one-pass action repair`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"2+2 is 5","action_summary":"respond"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"answer","action_payload":"2+2 is 4","action_summary":"correct arithmetic answer","reason":"fixed contradiction"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "what is 2+2?")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<psyke.agent.core.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.ANSWER, action.actionType)
        assertEquals("2+2 is 4", action.payload)
        assertEquals("correct arithmetic answer", action.summary)
        assertEquals(1, repairCount)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier" })
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_output_repaired" &&
                    it.data["repair"] == "action_verifier_repair"
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" &&
                    it.data["verdict"] == "repair" &&
                    it.data["repaired"] == true
            }
        )
    }

    @Test
    fun `planner accepts structured action payload from action verifier repair`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"mcp_fetch","action_payload":"{\"url\":\"https://example.com\"}","action_summary":"fetch"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"mcp_fetch","action_payload":{"url":"https://openai.com/pricing","max_chars":900},"action_summary":"fetch pricing"}
                """.trimIndent()
            )
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "pricing")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<psyke.agent.core.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.MCP_FETCH, action.actionType)
        assertTrue(action.payload.contains("\"url\":\"https://openai.com/pricing\""))
        assertTrue(action.payload.contains("\"max_chars\":900"))
    }

    @Test
    fun `planner converts verifier reject into noop`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"unsafe","action_summary":"respond"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """{"verdict":"reject","reason":"internally inconsistent with trigger"}"""
            )
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "safe response only")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("inconsistent", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" &&
                    it.data["verdict"] == "reject"
            }
        )
    }

    @Test
    fun `planner retries action verifier with strict json and applies retry verdict`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"unsafe","action_summary":"respond"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "not-json")
            enqueueRawResponseForCallSite(
                callSite = "action_verifier_json_retry",
                content = """{"verdict":"reject","reason":"retry parsed and rejected"}"""
            )
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "safe response only")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("rejected", ignoreCase = true))
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier_json_retry" })
    }

    @Test
    fun `planner trips action verifier parse-failure circuit breaker and bypasses one decision`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a1","action_summary":"s1"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a2","action_summary":"s2"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a3","action_summary":"s3"}""")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-1")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-1-retry")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-2")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-2-retry")
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )
        val trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "test"))
        val context = PlannerContext(recentDialogue = emptyList(), queue = QueueSnapshot(0, 0, 0))

        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(trigger, context))
        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(trigger, context))
        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(trigger, context))

        val verifierCalls = llm.calls.count { it.options.metadata.callSite == "action_verifier" }
        val verifierRetryCalls = llm.calls.count { it.options.metadata.callSite == "action_verifier_json_retry" }
        assertEquals(2, verifierCalls)
        assertEquals(2, verifierRetryCalls)
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("circuit breaker tripped", ignoreCase = true) == true
            }
        )
        assertTrue(instrumentation.events.any { it.type == "action_verifier_circuit_breaker" })
    }

    @Test
    fun `planner action verifier bypass is scoped per input and action type`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a1","action_summary":"s1"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a2","action_summary":"s2"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a3","action_summary":"s3"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"answer","action_payload":"b1","action_summary":"s4"}""")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-1")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-1-retry")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-2")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-2-retry")
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())
        val context = PlannerContext(recentDialogue = emptyList(), queue = QueueSnapshot(0, 0, 0))
        val triggerA = psyke.agent.core.EgoTrigger.IncomingInput(
            PendingInput(id = 1, content = "test-a", receivedAtMs = 1L)
        )
        val triggerB = psyke.agent.core.EgoTrigger.IncomingInput(
            PendingInput(id = 2, content = "test-b", receivedAtMs = 2L)
        )

        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(triggerA, context))
        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(triggerA, context))
        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(triggerA, context)) // bypassed
        assertIs<psyke.agent.core.EgoDecision.ProposeAction>(planner.decide(triggerB, context)) // verifier active again

        val verifierCalls = llm.calls.count { it.options.metadata.callSite == "action_verifier" }
        val verifierRetryCalls = llm.calls.count { it.options.metadata.callSite == "action_verifier_json_retry" }
        assertEquals(3, verifierCalls)
        assertEquals(2, verifierRetryCalls)
    }

    @Test
    fun `planner trims oversized prompt before sending to model`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(maxPromptTokens = 120))
        )

        val context = PlannerContext(
            recentDialogue = List(15) { idx ->
                DialogueTurn(
                    role = if (idx % 2 == 0) DialogueRole.USER else DialogueRole.ASSISTANT,
                    content = "content-$idx-" + "x".repeat(40)
                )
            },
            queue = QueueSnapshot(
                pendingInputCount = 5,
                pendingThoughtCount = 4,
                pendingActionCount = 3
            )
        )
        planner.decide(psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "ask")), context)

        assertTrue(llm.lastMessages.isNotEmpty())
        assertTrue(llm.lastMessages.any { it.role == ChatRole.USER && it.content.contains("Trigger:") })
        val estimatedPromptTokens = llm.lastMessages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 120)
        assertIs<ChatMessage>(llm.lastMessages.first())
    }

    @Test
    fun `planner includes short-term context summary in prompt context`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )
        val context = PlannerContext(
            recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "hello")),
            queue = QueueSnapshot(
                pendingInputCount = 1,
                pendingThoughtCount = 0,
                pendingActionCount = 0
            ),
            shortTermContextSummary = "Short-term context summary:\n- user likes concise answers"
        )

        planner.decide(psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "question")), context)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Short-term context summary:"))
        assertTrue(prompt.contains("user likes concise answers"))
    }

    @Test
    fun `planner includes long-term memory recall in prompt context`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )
        val context = PlannerContext(
            recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "hello")),
            queue = QueueSnapshot(
                pendingInputCount = 1,
                pendingThoughtCount = 0,
                pendingActionCount = 0
            ),
            shortTermContextSummary = "Short-term context summary:\n- user likes concise answers",
            longTermMemoryRecall = "- last week user asked for deploy checklist"
        )

        planner.decide(psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "question")), context)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Long-term memory recall:"))
        assertTrue(prompt.contains("deploy checklist"))
    }

    @Test
    fun `planner includes deliberation pressure and meta guidance`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )
        val context = PlannerContext(
            recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "hello")),
            queue = QueueSnapshot(0, 1, 0),
            deliberation = DeliberationState(
                stepIndex = 22,
                decisionPressure = 0.82,
                staleStreak = 5,
                progressScore = 0.24,
                denialCount = 1,
                stepsSinceNewEvidence = 6,
                repeatSignatureHits = 2,
                noopStreak = 3
            ),
            metaGuidance = "Finalize now with concise answer."
        )

        planner.decide(psyke.agent.core.EgoTrigger.PendingThoughtInput(PendingThought(1, Urgency.MEDIUM, "think")), context)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Deliberation pressure:"))
        assertTrue(prompt.contains("decision_pressure=0.820"))
        assertTrue(prompt.contains("Meta reasoning guidance:"))
        assertTrue(prompt.contains("Finalize now"))
    }

    @Test
    fun `planner includes external evidence hints in prompt context`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        planner.decide(
            psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "question")),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                evidenceHints = "successful_evidence_signals=official pricing page fetched"
            )
        )

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("External evidence hints:"))
        assertTrue(prompt.contains("official pricing page fetched"))
    }

    @Test
    fun `planner prompt hardening enforces action summary contract`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )

        planner.decide(
            psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "question")),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val systemPrompt = llm.lastMessages.first().content
        assertTrue(systemPrompt.contains("Do not return decision=action without both action_payload and action_summary."))
        assertTrue(systemPrompt.contains("\"action_summary\":\"required when decision=action"))
    }

    @Test
    fun `planner falls back to noop when model call fails`() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ) = throw IllegalStateException("planner unavailable")
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = failingClient,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("unavailable", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("Planner call failed", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `planner honors configured retry attempts`() {
        var calls = 0
        val flakyClient = object : ChatModelClient {
            override val modelName: String = "flaky"

            override fun chat(
                messages: List<ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ): psyke.llm.ChatCompletion {
                calls += 1
                if (calls < 3) {
                    throw IllegalStateException("temporary failure")
                }
                return psyke.llm.ChatCompletion(
                    content = """{"decision":"noop","reason":"ok"}""",
                    model = modelName
                )
            }
        }
        val planner = LlmEgoPlanner(
            modelClient = flakyClient,
            config = AgentConfig(planner = PlannerConfig(llmRetryAttempts = 3))
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertEquals(3, calls)
    }

    @Test
    fun `planner caps configured retry attempts to three`() {
        var calls = 0
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ): psyke.llm.ChatCompletion {
                calls += 1
                throw IllegalStateException("still failing")
            }
        }
        val planner = LlmEgoPlanner(
            modelClient = failingClient,
            config = AgentConfig(planner = PlannerConfig(llmRetryAttempts = 10))
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertEquals(3, calls)
    }

    @Test
    fun `planner returns plan decision with clamped steps`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"plan",
              "urgency":"medium",
              "plan_goal":"Find verified current pricing",
              "plan_steps":["Search for pricing page","Fetch pricing content","Synthesize answer"]
            }
            """.trimIndent()
        )
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(maxPlanSteps = 2, maxPlanStepDescriptionChars = 10)),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "pricing?")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val plan = assertIs<psyke.agent.core.EgoDecision.EnqueuePlan>(decision)
        assertEquals(Urgency.MEDIUM, plan.urgency)
        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps[0].length <= 10)
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" && it.data["decision_type"] == "plan"
            }
        )
    }

    @Test
    fun `planner returns noop for plan with empty steps`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """{"decision":"plan","urgency":"medium","plan_goal":"do stuff","plan_steps":[]}"""
        )
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "test")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("empty steps", ignoreCase = true))
    }

    @Test
    fun `planner returns noop for plan with blank goal`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """{"decision":"plan","plan_goal":"","plan_steps":["step 1"]}"""
        )
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "test")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<psyke.agent.core.EgoDecision.Noop>(decision)
    }

    @Test
    fun `planner prompt includes plan decision type in schema`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        planner.decide(
            psyke.agent.core.EgoTrigger.IncomingInput(PendingInput(1, "test")),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val allPromptText = llm.lastMessages.joinToString("\n") { it.content }
        assertTrue(allPromptText.contains("plan:"))
        assertTrue(allPromptText.contains("plan_goal"))
        assertTrue(allPromptText.contains("plan_steps"))
    }
}
