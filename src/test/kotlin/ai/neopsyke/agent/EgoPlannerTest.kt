package ai.neopsyke.agent

import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.AdaptiveStructuredOutputChatClient
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EgoPlannerTest {
    @Test
    fun `continuation planner returns action decision and emits events`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable",
              "urgency":"high",
              "action_type":"contact_user",
              "action_payload":"payload-too-long",
              "action_summary":"summary-too-long"
            }
            """.trimIndent()
        )
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxActionPayloadChars = 7, maxActionSummaryChars = 8),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(1, Urgency.HIGH, "think about it")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(
                    pendingInputCount = 1,
                    continuationCount = 2,
                    pendingActionCount = 3
                )
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals(Urgency.HIGH, action.urgency)
        assertEquals("payload", action.payload)
        assertEquals("summary-", action.summary)
        assertEquals("ego", llm.lastOptions.metadata.actor)
        assertEquals("continuation", llm.lastOptions.metadata.callSite)
        val plannerFormat = assertIs<ChatResponseFormat.JsonSchema>(llm.lastOptions.responseFormat)
        assertTrue(plannerFormat.strict)
        assertTrue(instrumentation.events.any { it.type == "planner_start" })
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" && it.data["decision_type"] == "intention"
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "prompt_budget_allocation" &&
                    it.data["call_site"] == "progression_prompt"
            }
        )
    }

    @Test
    fun `planner returns action decision with clamped payload and summary`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable",
              "urgency":"medium",
              "action_type":"contact_user",
              "action_payload":"payload-too-long",
              "action_summary":"summary-too-long"
            }
            """.trimIndent()
        )
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxActionPayloadChars = 7, maxActionSummaryChars = 8)
        )

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(7, Urgency.LOW, "think", 1)),
            context = PlannerContext(
                recentDialogue = listOf(
                    DialogueTurn(DialogueRole.USER, "u"),
                    DialogueTurn(DialogueRole.ASSISTANT, "a")
                ),
                queue = QueueSnapshot(
                    pendingInputCount = 0,
                    continuationCount = 1,
                    pendingActionCount = 0
                )
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertEquals("payload", action.payload)
        assertEquals("summary-", action.summary)
        assertEquals("continuation", llm.lastOptions.metadata.callSite)
    }

    @Test
    fun `planner attaches continuation and plan context metadata to llm calls`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"ok"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )
        val continuation = queuedContinuation(
            id = 17,
            urgency = Urgency.MEDIUM,
            content = "Plan step 2/4: Fetch summary of top result",
            planContext = ai.neopsyke.agent.model.PlanContext(
                planId = "plan-42",
                planGoal = "Identify an interesting topic",
                stepIndex = 1,
                totalSteps = 4,
                stepDescription = "Fetch summary of top result"
            ),
            origin = ai.neopsyke.agent.model.ActionOrigin.id(
                needId = "learn-something",
                rootImpulseId = "impulse-42"
            ),
            conversationContext = ai.neopsyke.agent.model.ConversationContext(
                sessionId = "id:internal",
                interlocutor = ai.neopsyke.agent.model.Interlocutor.UNKNOWN
            )
        )

        planner.decide(
            trigger = continuationTrigger(continuation),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 1, 0),
                conversationContext = continuation.conversationContext
            )
        )

        with(llm.lastOptions.metadata) {
            assertEquals("ego", actor)
            assertEquals("planner", cognitiveRole)
            assertEquals("continuation", callSite)
            assertEquals("continuation", trigger)
            assertEquals("id", originSource)
            assertEquals("learn-something", needId)
            assertEquals("impulse-42", rootImpulseId)
            assertEquals(17L, thoughtId)
            assertEquals("plan-42", planId)
            assertEquals(1, planStepIndex)
            assertEquals(4, planTotalSteps)
            assertEquals("Fetch summary of top result", planStepDescription)
            assertEquals("id:internal", sessionId)
        }
    }

    @Test
    fun `planner accepts structured action payload object by normalizing to json string`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable",
              "urgency":"medium",
              "action_type":"website_fetch",
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
            trigger = continuationTrigger(queuedContinuation(7, Urgency.LOW, "think", 1)),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.WEBSITE_FETCH, action.actionType)
        assertTrue(action.payload.contains("\"url\":\"https://openai.com/pricing\""))
        assertTrue(action.payload.contains("\"max_chars\":1200"))
    }

    @Test
    fun `planner may propose actions that runtime later blocks`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable",
              "urgency":"medium",
              "action_type":"website_fetch",
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "fetch this page")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.CONTACT_USER, ActionType.WEB_SEARCH)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.WEBSITE_FETCH, action.actionType)
    }

    @Test
    fun `planner converts invalid payload and parse failures to noop`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","action_type":"contact_user"}""")
        llm.enqueueRawResponse("not-json")
        llm.enqueueRawResponseForCallSite("general_action_json_retry", "still-not-json")
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )
        val trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(2, "hello"))
        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0)
        )

        val invalidAction = planner.decide(trigger, context)
        assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(invalidAction)
        assertTrue(invalidAction.reason.contains("invalid intention", ignoreCase = true))

        val invalidJson = planner.decide(trigger, context)
        assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(invalidJson)
        assertTrue(invalidJson.reason.contains("non-parseable", ignoreCase = true))
        assertTrue(instrumentation.events.any { it.type == "warning" })
    }

    @Test
    fun `planner retries with strict json prompt and recovers parse failures`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("not-json")
            enqueueRawResponseForCallSite(
                callSite = "general_action_json_retry",
                content = """{"intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"recovered","action_summary":"recovered"}"""
            )
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals("recovered", action.payload)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "general_action_json_retry" })
    }

    @Test
    fun `planner recovers compatibility failures through llm-layer structured output adaptation`() {
        val observedOptions = mutableListOf<ai.neopsyke.llm.ChatRequestOptions>()
        val baseClient = object : ChatModelClient {
            override val modelName: String = "schema-flaky"
            private var calls = 0

            override fun chat(messages: List<ChatMessage>, options: ai.neopsyke.llm.ChatRequestOptions): ai.neopsyke.llm.ChatCompletion {
                calls += 1
                observedOptions += options
                if (calls == 1) {
                    throw IllegalStateException("generated JSON does not match the expected schema")
                }
                return ai.neopsyke.llm.ChatCompletion(
                    content = """{"decision":"noop","reason":"relaxed schema recovered"}""",
                    model = modelName
                )
            }
        }
        val llm = AdaptiveStructuredOutputChatClient(
            delegate = baseClient,
            provider = "groq"
        )
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(llmRetryAttempts = 2)
        )

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("recovered", ignoreCase = true))
        assertEquals(2, observedOptions.size)
        val strictFormat = assertIs<ChatResponseFormat.JsonSchema>(observedOptions[0].responseFormat)
        val relaxedFormat = assertIs<ChatResponseFormat.JsonSchema>(observedOptions[1].responseFormat)
        assertTrue(strictFormat.schemaJson.contains("maxLength"))
        assertFalse(relaxedFormat.schemaJson.contains("maxLength"))
        assertFalse(relaxedFormat.strict)
    }

    @Test
    fun `planner retries truncated completion with larger budget before strict json retry`() {
        val calls = mutableListOf<ai.neopsyke.llm.ChatRequestOptions>()
        val llm = object : ChatModelClient {
            override val modelName: String = "truncation-model"

            override fun chat(messages: List<ChatMessage>, options: ai.neopsyke.llm.ChatRequestOptions): ai.neopsyke.llm.ChatCompletion {
                calls += options
                return when (options.metadata.callSite) {
                    "input_intent_router" -> ai.neopsyke.llm.ChatCompletion(
                        content = """{"route":"general_action","reasoning":"test default"}""",
                        model = modelName
                    )
                    "general_action" -> ai.neopsyke.llm.ChatCompletion(
                        content = """{"decision":"noop","reason":"truncated""",
                        model = modelName,
                        finishReason = "length"
                    )
                    "general_action_truncation_retry" -> ai.neopsyke.llm.ChatCompletion(
                        content = """{"intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"truncation recovered","action_summary":"recovered"}""",
                        model = modelName
                    )
                    else -> throw IllegalStateException("unexpected call site ${options.metadata.callSite}")
                }
            }
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals("truncation recovered", action.payload)
        val initialCall = calls.first { it.metadata.callSite == "general_action" }
        val truncationRetry = calls.first { it.metadata.callSite == "general_action_truncation_retry" }
        assertTrue((truncationRetry.maxTokens ?: 0) > (initialCall.maxTokens ?: 0))
        assertFalse(calls.any { it.metadata.callSite == "general_action_json_retry" })
    }

    @Test
    fun `planner blocks answer_draft proposals outside active plan context`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"resolution_draft","action_payload":"chunk","action_summary":"draft chunk"}
                """.trimIndent()
            )
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "long answer")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.CONTACT_USER, ActionType.RESOLUTION_DRAFT)
            )
        )

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("outside active plan context", ignoreCase = true))
    }

    @Test
    fun `planner repairs invalid json escapes in model output`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"Costs are \${'$'}20 per month","action_summary":"deliver answer"}
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "pricing")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
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
                  "decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable",
                  "urgency":"medium",
                  "action_type":"contact_user",
                  "action_payload":"first useful line\nsecond line"
                }
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxActionSummaryChars = 180),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals("first useful line", action.summary)
        assertEquals(1, repairCount)
        assertTrue(instrumentation.events.any { it.type == "planner_output_repaired" })
    }

    @Test
    fun `planner trims oversized prompt before sending to model`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxLlmPromptTokens = 180)
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
                continuationCount = 4,
                pendingActionCount = 3
            )
        )
        planner.decide(ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "ask")), context)

        assertTrue(llm.lastMessages.isNotEmpty())
        assertTrue(llm.lastMessages.any { it.role == ChatRole.USER && it.content.contains("Trigger:") })
        val estimatedPromptTokens = llm.lastMessages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 180)
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
                continuationCount = 0,
                pendingActionCount = 0
            ),
            shortTermContextSummary = "Short-term context summary:\n- user likes concise answers"
        )

        planner.decide(ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "question")), context)

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
                continuationCount = 0,
                pendingActionCount = 0
            ),
            shortTermContextSummary = "Short-term context summary:\n- user likes concise answers",
            longTermMemoryRecall = "- last week user asked for deploy checklist"
        )

        planner.decide(ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "question")), context)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Relevant long-term memory:"))
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

        planner.decide(continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "think")), context)

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
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )

        planner.decide(
           ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "question")),
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
    fun `planner includes ambient context for Id impulses`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        planner.decide(
           ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "question")),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                ambientContext = ai.neopsyke.agent.model.AmbientContext(
                    activeWorkItems = listOf("Improve the memory subsystem"),
                    unresolvedOpenLoops = listOf("Tidy the planner retries"),
                )
            )
        )

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Background context:"))
        assertTrue(prompt.contains("active_goals:"))
        assertTrue(prompt.contains("Improve the memory subsystem"))
        assertTrue(prompt.contains("unresolved_open_loops:"))
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
           ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "question")),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val systemPrompt = llm.lastMessages.first().content
        assertTrue(systemPrompt.contains("action_summary"))
        assertTrue(systemPrompt.contains("\"action_summary\":\"required"))
    }

    @Test
    fun `planner falls back to noop when model call fails`() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<ChatMessage>,
                options: ai.neopsyke.llm.ChatRequestOptions
            ) = throw IllegalStateException("planner unavailable")
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = failingClient,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("unavailable", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("call failed", ignoreCase = true) == true
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
                options: ai.neopsyke.llm.ChatRequestOptions
            ): ai.neopsyke.llm.ChatCompletion {
                calls += 1
                if (calls < 3) {
                    throw IllegalStateException("temporary failure")
                }
                return ai.neopsyke.llm.ChatCompletion(
                    content = """{"decision":"noop","reason":"ok"}""",
                    model = modelName
                )
            }
        }
        val planner = LlmEgoPlanner(
            modelClient = flakyClient,
            config = AgentConfig(llmRetryAttempts = 3)
        )

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertEquals(3, calls)
    }

    @Test
    fun `planner caps configured retry attempts to three`() {
        var calls = 0
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<ChatMessage>,
                options: ai.neopsyke.llm.ChatRequestOptions
            ): ai.neopsyke.llm.ChatCompletion {
                calls += 1
                throw IllegalStateException("still failing")
            }
        }
        val planner = LlmEgoPlanner(
            modelClient = failingClient,
            config = AgentConfig(llmRetryAttempts = 10)
        )

        val decision = planner.decide(
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
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
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "pricing?")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val plan = assertIs<ai.neopsyke.agent.model.EgoDecision.EnqueuePlan>(decision)
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
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "test")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
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
            trigger = continuationTrigger(queuedContinuation(1, Urgency.MEDIUM, "test")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
    }

    @Test
    fun `planner repairs bare URL fetch payload by wrapping in json`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable",
                  "urgency":"medium",
                  "action_type":"website_fetch",
                  "action_payload":"https://example.com/pricing",
                  "action_summary":"Fetch pricing page"
                }
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "fetch the pricing page")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.WEBSITE_FETCH, action.actionType)
        assertTrue(action.payload.contains("\"url\":\"https://example.com/pricing\""))
        assertEquals(1, repairCount)
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_output_repaired" &&
                    it.data["repair"] == "bare_url_wrapped"
            }
        )
    }
}
