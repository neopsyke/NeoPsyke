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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hi")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(
                    pendingInputCount = 1,
                    pendingThoughtCount = 2,
                    pendingActionCount = 3
                )
            )
        )

        val thought = assertIs<ai.neopsyke.agent.model.EgoDecision.EnqueueThought>(decision)
        assertEquals(Urgency.HIGH, thought.urgency)
        assertEquals("abcde", thought.content)
        assertEquals("ego", llm.lastOptions.metadata.actor)
        assertEquals("input", llm.lastOptions.metadata.callSite)
        assertEquals(88, llm.lastOptions.maxTokens)
        val plannerFormat = assertIs<ChatResponseFormat.JsonSchema>(llm.lastOptions.responseFormat)
        assertTrue(plannerFormat.strict)
        assertTrue(instrumentation.events.any { it.type == "planner_start" })
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" && it.data["decision_type"] == "thought"
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "prompt_budget_allocation" &&
                    it.data["call_site"] == "planner_prompt"
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(PendingThought(7, Urgency.LOW, "think", 1)),
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

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertEquals("payload", action.payload)
        assertEquals("summary-", action.summary)
        assertEquals("thought", llm.lastOptions.metadata.callSite)
    }

    @Test
    fun `planner routes recurring reminder requests to dedicated goal creation branch`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"create_goal",
                  "title":"Weather reminder",
                  "instruction":"Check the current weather and send the user an update for this scheduled run.",
                  "completion_criteria":"A weather update is delivered to the user for the current scheduled run.",
                  "priority":"medium"
                }
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(
                PendingInput(8, "I would like to set a goal for you: remind me of the current weather every 5 minutes.")
            ),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.CONTACT_USER, ActionType.GOAL_OPERATION),
                dispatchableActions = setOf(ActionType.CONTACT_USER, ActionType.GOAL_OPERATION)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.GOAL_OPERATION, action.actionType)
        assertTrue(action.payload.contains(""""operation":"create""""))
        assertTrue(action.payload.contains(""""cron_expression":"*/5 * * * *""""))
        assertTrue(action.payload.contains("Weather reminder"))
        assertEquals("input_goal_create", llm.lastOptions.metadata.callSite)
        assertEquals(1, llm.calls.size)
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_branch_selected" && it.data["branch"] == "goal_creation"
            }
        )
    }

    @Test
    fun `planner explains when recurring goals are unavailable for reminder requests`() {
        val llm = StubChatModelClient()
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(
                PendingInput(9, "monitor the weather and remind me every 5 minutes")
            ),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.CONTACT_USER)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertTrue(action.payload.contains("goals are unavailable", ignoreCase = true))
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `planner attaches thought and plan context metadata to llm calls`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"ok"}""")
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )
        val thought = PendingThought(
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(thought),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 1, 0),
                conversationContext = thought.conversationContext
            )
        )

        with(llm.lastOptions.metadata) {
            assertEquals("ego", actor)
            assertEquals("planner", cognitiveRole)
            assertEquals("thought", callSite)
            assertEquals("thought", trigger)
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
              "decision":"action",
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(PendingThought(7, Urgency.LOW, "think", 1)),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.WEBSITE_FETCH, action.actionType)
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

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `planner converts invalid payload and parse failures to noop`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"action","action_type":"contact_user"}""")
        llm.enqueueRawResponse("not-json")
        llm.enqueueRawResponseForCallSite("input_json_retry", "still-not-json")
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
        assertTrue(invalidAction.reason.contains("invalid action", ignoreCase = true))

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
                callSite = "input_json_retry",
                content = """{"decision":"noop","reason":"recovered"}"""
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

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertEquals("recovered", noop.reason)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "input_json_retry" })
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
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
                    "input" -> ai.neopsyke.llm.ChatCompletion(
                        content = """{"decision":"noop","reason":"truncated""",
                        model = modelName,
                        finishReason = "length"
                    )
                    "input_truncation_retry" -> ai.neopsyke.llm.ChatCompletion(
                        content = """{"decision":"noop","reason":"truncation recovered"}""",
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

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("truncation recovered", ignoreCase = true))
        val initialCall = calls.first { it.metadata.callSite == "input" }
        val truncationRetry = calls.first { it.metadata.callSite == "input_truncation_retry" }
        assertTrue((truncationRetry.maxTokens ?: 0) > (initialCall.maxTokens ?: 0))
        assertFalse(calls.any { it.metadata.callSite == "input_json_retry" })
    }

    @Test
    fun `planner blocks answer_draft proposals outside active plan context`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"resolution_draft","action_payload":"chunk","action_summary":"draft chunk"}
                """.trimIndent()
            )
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "long answer")),
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
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Costs are \${'$'}20 per month","action_summary":"deliver answer"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
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

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
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
                  "decision":"action",
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

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals("first useful line", action.summary)
        assertEquals(1, repairCount)
        assertTrue(instrumentation.events.any { it.type == "planner_output_repaired" })
    }

    @Test
    fun `planner ignores semantic-changing contact_user repair from action verifier`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"18","action_summary":"Return the computed integer"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"contact_user","action_payload":"20","action_summary":"Return the computed integer","reason":"The computed result is 20."}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "what is 2+2?")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertEquals("18", action.payload)
        assertEquals("Return the computed integer", action.summary)
        assertEquals(0, repairCount)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier" })
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("meaning-changing repair", ignoreCase = true) == true
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" &&
                    it.data["verdict"] == "approve" &&
                    it.data["repaired"] == false &&
                    (it.data["reason"] as? String)?.contains("alter action meaning", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `planner allows surface-only contact_user repair from action verifier`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"  hello, world  ","action_summary":"reply"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"contact_user","action_payload":"Hello world.","action_summary":"reply","reason":"surface cleanup only"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "say hello world")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertEquals("Hello world.", action.payload)
        assertEquals(1, repairCount)
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
    fun `planner ignores repair back to origin evidence action when follow-up already has successful evidence`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"The current time in Hamburg is 12:18 PM.","action_summary":"Provide current time answer"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"mcp_time","action_payload":{"timezone":"Europe/Berlin"},"action_summary":"Run another time lookup","reason":"verify recency"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(
                PendingThought(
                    id = 9,
                    urgency = Urgency.MEDIUM,
                    content = """
                    MCP time lookup completed.
                    UNTRUSTED_EXTERNAL_DATA_BEGIN
                    MCP time result: {
                      "timezone": "Europe/Berlin",
                      "datetime": "2026-03-09T12:18:19+01:00",
                      "day_of_week": "Monday",
                      "is_dst": false
                    }
                    UNTRUSTED_EXTERNAL_DATA_END
                    Produce the next planner decision as one raw JSON object only.
                    Do not use tool or function wrappers.
                    """.trimIndent(),
                    passes = 1,
                    originActionType = ActionType.MCP_TIME,
                    originActionObservedEvidence = true
                )
            ),
            context = PlannerContext(
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "what is the current time in hamburg?")),
                queue = QueueSnapshot(0, 1, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier" })
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" &&
                    it.data["verdict"] == "approve" &&
                    (it.data["reason"] as? String)?.contains("repair ignored", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `planner ignores action type changing verifier repair even when user requested refresh`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"The current time in Hamburg is 12:18 PM.","action_summary":"Provide current time answer"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"mcp_time","action_payload":{"timezone":"Europe/Berlin"},"action_summary":"Run another time lookup","reason":"user asked to refresh"}
                """.trimIndent()
            )
        }
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true))
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(
                PendingThought(
                    id = 10,
                    urgency = Urgency.MEDIUM,
                    content = "follow-up",
                    passes = 1,
                    originActionType = ActionType.MCP_TIME,
                    originActionObservedEvidence = true
                )
            ),
            context = PlannerContext(
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "refresh and check again please")),
                queue = QueueSnapshot(0, 1, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertEquals("The current time in Hamburg is 12:18 PM.", action.payload)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier" })
    }

    @Test
    fun `planner treats no-op action verifier repair as approve`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"mcp_time","action_payload":"{\"timezone\":\"Europe/Berlin\"}","action_summary":"Retrieve current time for Hamburg"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"mcp_time","action_payload":{"timezone":"Europe/Berlin"},"action_summary":"Retrieve current time for Hamburg","reason":"same action wording update"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        var repairCount = 0
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation,
            onPlannerOutputRepaired = { repairCount += 1 }
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "what time is it?")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.MCP_TIME, action.actionType)
        assertEquals("""{"timezone":"Europe/Berlin"}""", action.payload)
        assertEquals(0, repairCount)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier" })
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" &&
                    it.data["verdict"] == "approve" &&
                    it.data["repaired"] == false &&
                    (it.data["reason"] as? String)?.contains("No-op repair ignored", ignoreCase = true) == true
            }
        )
        assertFalse(
            instrumentation.events.any {
                it.type == "planner_output_repaired" && it.data["repair"] == "action_verifier_repair"
            }
        )
    }

    @Test
    fun `planner ignores meaning-changing non-contact action verifier repair`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://example.com\"}","action_summary":"fetch"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"website_fetch","action_payload":{"url":"https://openai.com/pricing","max_chars":900},"action_summary":"fetch pricing"}
                """.trimIndent()
            )
        }
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true))
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "pricing")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.WEBSITE_FETCH, action.actionType)
        assertEquals("""{"url":"https://example.com"}""", action.payload)
    }

    @Test
    fun `planner ignores action type changing verifier repair`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://example.com\"}","action_summary":"fetch"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"web_search","action_payload":"OpenAI pricing","action_summary":"search pricing","reason":"different tool would be better"}
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "pricing")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.WEBSITE_FETCH, action.actionType)
        assertEquals("""{"url":"https://example.com"}""", action.payload)
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("meaning-changing repair", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `planner converts verifier reject into noop`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"unsafe","action_summary":"respond"}
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
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "safe response only")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("inconsistent", ignoreCase = true))
        assertEquals(ActionType.CONTACT_USER, noop.deniedActionType)
        assertEquals("unsafe", noop.deniedActionPayload)
        assertEquals("ACTION_VERIFIER_REJECT", noop.denialReasonCode)
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" &&
                    it.data["verdict"] == "reject"
            }
        )
    }

    @Test
    fun `planner overrides repeated identical answer reject from action verifier`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Omar","action_summary":"respond"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """{"verdict":"reject","reason":"The answer 'Omar' is incorrect based on the provided information."}"""
            )
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(
                PendingThought(
                    id = 1,
                    urgency = Urgency.LOW,
                    content = "Retry previous answer",
                    deniedActionType = ActionType.CONTACT_USER,
                    deniedActionPayload = "Omar",
                    denialReason = "The answer 'Omar' is incorrect based on the provided information.",
                    denialReasonCode = "ACTION_VERIFIER_REJECT"
                )
            ),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.CONTACT_USER, action.actionType)
        assertEquals("Omar", action.payload)
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("repeated a non-technical reject", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `planner retries action verifier with strict json and applies retry verdict`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"unsafe","action_summary":"respond"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "not-json")
            enqueueRawResponseForCallSite(
                callSite = "action_verifier_json_retry",
                content = """{"verdict":"reject","reason":"retry parsed and rejected"}"""
            )
        }
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true))
        )

        val decision = planner.decide(
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "safe response only")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("rejected", ignoreCase = true))
        assertTrue(llm.calls.any { it.options.metadata.callSite == "action_verifier_json_retry" })
        val verifierCall = llm.calls.firstOrNull { it.options.metadata.callSite == "action_verifier" }
        assertNotNull(verifierCall)
        val verifierFormat = assertIs<ChatResponseFormat.JsonSchema>(verifierCall.options.responseFormat)
        assertTrue(verifierFormat.strict)
    }

    @Test
    fun `planner trips action verifier parse-failure circuit breaker and bypasses one decision`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a1","action_summary":"s1"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a2","action_summary":"s2"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a3","action_summary":"s3"}""")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-1")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-1-retry")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-2")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-2-retry")
        }
        val instrumentation = RecordingInstrumentation()
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true)),
            instrumentation = instrumentation
        )
        val trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "test"))
        val context = PlannerContext(recentDialogue = emptyList(), queue = QueueSnapshot(0, 0, 0))

        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(trigger, context))
        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(trigger, context))
        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(trigger, context))

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
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a1","action_summary":"s1"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a2","action_summary":"s2"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a3","action_summary":"s3"}""")
            enqueueRawResponse("""{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"b1","action_summary":"s4"}""")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-1")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-1-retry")
            enqueueRawResponseForCallSite(callSite = "action_verifier", content = "bad-2")
            enqueueRawResponseForCallSite(callSite = "action_verifier_json_retry", content = "bad-2-retry")
        }
        val planner = LlmEgoPlanner(
            modelClient = llm,
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true))
        )
        val context = PlannerContext(recentDialogue = emptyList(), queue = QueueSnapshot(0, 0, 0))
        val triggerA = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(
            PendingInput(id = 1, content = "test-a", receivedAtMs = 1L)
        )
        val triggerB = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(
            PendingInput(id = 2, content = "test-b", receivedAtMs = 2L)
        )

        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(triggerA, context))
        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(triggerA, context))
        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(triggerA, context)) // bypassed
        assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(planner.decide(triggerB, context)) // verifier active again

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
                pendingThoughtCount = 4,
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
                pendingThoughtCount = 0,
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
                pendingThoughtCount = 0,
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

        planner.decide(ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput(PendingThought(1, Urgency.MEDIUM, "think")), context)

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
            config = AgentConfig(planner = PlannerConfig(actionVerifierEnabled = true))
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
           ai.neopsyke.agent.model.EgoTrigger.IncomingImpulse(
               ai.neopsyke.agent.model.PendingImpulse(
                    id = 1,
                    needId = "learn-something",
                    prompt = "I feel curious and want to learn something new.",
                    tension = 0.8,
                    rawValue = 0.8,
                    conversationContext = ai.neopsyke.agent.model.ConversationContext.default(),
                )
            ),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                ambientContext = ai.neopsyke.agent.model.AmbientContext(
                    activeGoals = listOf("Improve the memory subsystem"),
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
        assertTrue(systemPrompt.contains("Do not return decision=action without both action_payload and action_summary."))
        assertTrue(systemPrompt.contains("\"action_summary\":\"required when decision=action"))
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "hello")),
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "pricing?")),
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "test")),
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
            trigger = ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "test")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        assertIs<ai.neopsyke.agent.model.EgoDecision.Noop>(decision)
    }

    @Test
    fun `planner prompt includes plan decision type in schema`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val planner = LlmEgoPlanner(modelClient = llm, config = AgentConfig())

        planner.decide(
           ai.neopsyke.agent.model.EgoTrigger.IncomingInput(PendingInput(1, "test")),
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

    @Test
    fun `planner repairs bare URL fetch payload by wrapping in json`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"action",
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

        val action = assertIs<ai.neopsyke.agent.model.EgoDecision.ProposeAction>(decision)
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
