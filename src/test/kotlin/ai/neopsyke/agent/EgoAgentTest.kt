package ai.neopsyke.agent

import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.actions.websearch.WebSearchResult
import ai.neopsyke.agent.actions.websearch.WebSearchSource
import ai.neopsyke.support.buildTestEgo
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EgoAgentTest {
    @Test
    fun `queue snapshots are emitted after task processing instead of immediate dequeue`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        val queueSnapshots = instrumentation.events.filter { it.type == "queue_snapshot" }
        assertTrue(queueSnapshots.any { it.data["source"] == "input_enqueued" })
        assertTrue(queueSnapshots.any { it.data["source"] == "task_processed" })
        assertTrue(queueSnapshots.none { it.data["source"] == "task_dequeued" })

        val nonEmptyTaskProcessed = queueSnapshots
            .filter { it.data["source"] == "task_processed" }
            .any { snapshot ->
                val queues = snapshot.data["queues"] as QueueState
                queues.inputs.isNotEmpty() || queues.thoughts.isNotEmpty() || queues.actions.isNotEmpty()
            }
        assertTrue(nonEmptyTaskProcessed)
        assertEquals(listOf("ego> ok"), outputs)
    }

    @Test
    fun `denied action chain requests a different action and blocks repeats`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"bad idea","action_summary":"first answer attempt"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"bad   idea","action_summary":"retrying same action"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"safe alternative","action_summary":"different safe answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy violation"}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 4)),
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(listOf("ego> safe alternative"), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("repeated a denied action", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `technical denial allows one repeated action without repeat blocking`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"retryable answer","action_summary":"first answer attempt"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"retryable  answer","action_summary":"retrying same action"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"blocked by temporary issue","reason_code":"TECH_TIMEOUT"}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 4)),
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("retryable"))
        assertTrue(
            instrumentation.events.none {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("repeated a denied action", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `verifier style denied action context is preserved through noop and next thought`() {
        var sawVerifierDeniedThought = false
        val planner = object : ai.neopsyke.agent.ego.Ego.Planner {
            override fun decide(
                trigger: ai.neopsyke.agent.model.EgoTrigger,
                context: PlannerContext
            ): ai.neopsyke.agent.model.EgoDecision =
                when (trigger) {
                    is ai.neopsyke.agent.model.EgoTrigger.IncomingInput -> ai.neopsyke.agent.model.EgoDecision.Noop(
                        reason = "The answer 'Omar' is incorrect based on the provided information.",
                        deniedActionType = ActionType.CONTACT_USER,
                        deniedActionPayload = "Omar",
                        denialReasonCode = "ACTION_VERIFIER_REJECT"
                    )

                    is ai.neopsyke.agent.model.EgoTrigger.PendingThoughtInput -> {
                        val thought = trigger.thought
                        sawVerifierDeniedThought =
                            thought.deniedActionType == ActionType.CONTACT_USER &&
                                thought.deniedActionPayload == "Omar" &&
                                thought.denialReasonCode == "ACTION_VERIFIER_REJECT"
                       ai.neopsyke.agent.model.EgoDecision.ProposeAction(
                            urgency = Urgency.HIGH,
                            actionType = ActionType.CONTACT_USER,
                            payload = "Omar",
                            summary = "deliver answer"
                        )
                    }

                    is ai.neopsyke.agent.model.EgoTrigger.IncomingImpulse ->
                       ai.neopsyke.agent.model.EgoDecision.Noop("unexpected impulse")
                    is ai.neopsyke.agent.model.EgoTrigger.GoalWork ->
                        ai.neopsyke.agent.model.EgoDecision.Noop("unexpected goal work")
                }
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 4))
        val agent = buildTestEgo(
            planner = planner,
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "Who arrived last?\nexit\n")

        assertTrue(sawVerifierDeniedThought)
        assertEquals(listOf("ego> Omar"), outputs)
    }

    @Test
    fun `task verifier blocks verification-sensitive answer until evidence exists`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"The current price is 20.","action_summary":"answer quickly"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"web_search","action_payload":"current product price official source","action_summary":"verify current price"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"Latest verified price from source is 20.","action_summary":"deliver verified answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val searchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Official source confirms price is 20.",
                    snippets = listOf("Official source: current price is 20."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Official pricing",
                            url = "https://example.com/pricing"
                        )
                    )
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 10, maxThoughtPasses = 4))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = searchEngine),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "What is the latest price?\nexit\n")

        assertEquals(listOf("ego> Latest verified price from source is 20."), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "task_verifier_review" &&
                    it.data["allow"] == false &&
                    it.data["reason_code"] == "TASK_EVIDENCE_REQUIRED"
            }
        )
    }

    @Test
    fun `task verifier allows graceful volatile answer when evidence actions are unavailable`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"Current price appears to be 20.","action_summary":"answer directly"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val unavailableSearchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(summary = "offline", snippets = emptyList())

            override fun healthCheck(): ai.neopsyke.agent.actions.websearch.WebSearchEngineHealth =
               ai.neopsyke.agent.actions.websearch.WebSearchEngineHealth(
                    available = false,
                    detail = "search offline"
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6, maxThoughtPasses = 3))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = unavailableSearchEngine),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "What is the latest price today?\nexit\n")

        assertEquals(listOf("ego> Current price appears to be 20."), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "task_verifier_review" &&
                    it.data["allow"] == true &&
                    it.data["reason_code"] == "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL"
            }
        )
    }

    @Test
    fun `session workspace digest persists across turns when queues drain`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"first done","action_summary":"respond first"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"second done","action_summary":"respond second"}"""
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 3),
            memory = MemoryConfig(
                scratchpad = ScratchpadConfig(
                    enabled = true,
                    activationMinPlanSteps = 1,
                    digestMaxEntries = 4
                )
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "digest sentinel one\ndigest sentinel two\nexit\n")

        assertEquals(listOf("ego> first done", "ego> second done"), outputs)
        val plannerInputCalls = plannerLlm.calls.filter { it.options.metadata.callSite == "input" }
        assertTrue(plannerInputCalls.size >= 2)
        val secondPrompt = plannerInputCalls[1].messages.joinToString("\\n\\n") { it.content }
        assertTrue(secondPrompt.contains("Prior workspace digests"))
        assertTrue(secondPrompt.contains("digest sentinel one"))
        assertTrue(
            instrumentation.events.any { it.type == "scratchpad_digest_captured" },
            "Expected digest capture event after first resolved answer."
        )
    }

    @Test
    fun `non technical denial stores lesson in long term memory`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"unsafe","action_summary":"attempt"}
                """.trimIndent()
            )
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"unsafe action for policy reasons","reason_code":"POLICY_CUSTOM"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(
            hippocampus.imprints.any {
                it.source == "ego_lesson" && it.summary.startsWith("LESSON:")
            }
        )
    }

    @Test
    fun `technical denial does not store lesson`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"quick answer","action_summary":"attempt"}
                """.trimIndent()
            )
            enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"model response could not be parsed","reason_code":"TECH_PARSE_ERROR"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.imprints.none { it.source == "ego_lesson" })
    }

    @Test
    fun `repeated external action after successful evidence emits redundancy soft signal`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing page","action_summary":"first search"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing page","action_summary":"repeat search"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"pricing compiled","action_summary":"final answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val searchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Official pricing page with current rates.",
                    snippets = listOf("Rates updated this week."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Pricing",
                            url = "https://example.com/pricing"
                        )
                    )
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 10, maxThoughtPasses = 4))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = searchEngine),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "find pricing\nexit\n")

        assertEquals(1, outputs.size)
        val redundancySignals = instrumentation.events.filter {
            it.type == "external_action_redundancy_signal" && it.data["action_type"] == "web_search"
        }
        assertTrue(redundancySignals.isNotEmpty())
        assertTrue(
            redundancySignals.any {
                it.data["redundant_risk"] == true &&
                    ((it.data["signature_hits"] as? Int) ?: 0) >= 2
            }
        )
    }

    @Test
    fun `fallback explanation executes with one grace step when thought limit is reached`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"blocked attempt","action_summary":"initial answer"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"noop","reason":"no safe alternative found"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy denied"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("blocked by policy", ignoreCase = true))
        assertTrue(outputs.first().contains("safe alternative", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "loop_step" && it.data["task_type"] == "action_fallback"
            }
        )
    }

    @Test
    fun `fallback explanation executes immediately when action queue is full`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"blocked attempt","action_summary":"initial answer"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"low","action_type":"contact_user","action_payload":"queued action","action_summary":"occupy action queue"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy denied"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 2, maxThoughtPasses = 1),
            maxPendingActions = 1
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nagain\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("blocked by policy", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("Executing immediately", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `web search timeouts eventually return fallback answer instead of silent drop`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"latest pricing","action_summary":"search 1"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"latest pricing retry","action_summary":"search 2"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val timingOutSearch = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Groq web search unavailable: timeout",
                    snippets = emptyList(),
                    sources = emptyList()
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 7, maxThoughtPasses = 1))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                webSearchEngine = timingOutSearch
            ),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("could not complete external verification", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "queue_snapshot" && it.data["source"] == "fallback_explanation_enqueued"
            }
        )
    }

    @Test
    fun `fallback answer uses gathered evidence when planner output remains non parseable`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"latest groq pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse("not-json")
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val successfulSearch = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Groq official pricing page lists current model rates.",
                    snippets = listOf("Use official pricing pages over community estimates."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Groq Pricing",
                            url = "https://groq.com/pricing"
                        )
                    )
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 7, maxThoughtPasses = 1))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                webSearchEngine = successfulSearch
            ),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("completed external verification", ignoreCase = true))
        assertTrue(outputs.first().contains("web_search result:", ignoreCase = true))
        assertFalse(outputs.first().contains("could not complete external verification", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "queue_snapshot" && it.data["source"] == "fallback_explanation_enqueued"
            }
        )
    }

    @Test
    fun `fallback answer resolves pending work for same input and prevents continued cycling`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"plan",
                  "urgency":"medium",
                  "plan_goal":"Fetch official pricing and answer",
                  "plan_steps":["Search pricing page","Finalize response"]
                }
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"web_search","action_payload":"official pricing page","action_summary":"search pricing"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val successfulSearch = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Official pricing page with current rates.",
                    snippets = listOf("Use official pricing pages."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Pricing",
                            url = "https://example.com/pricing"
                        )
                    )
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 10, maxThoughtPasses = 1))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                webSearchEngine = successfulSearch
            ),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, outputs.size)
        val answerEvents = instrumentation.events.filter {
            it.type == "action_executed" &&
                ((it.data["action"] as? PendingAction)?.type == ActionType.CONTACT_USER)
        }
        assertEquals(1, answerEvents.size)
        val cleanup = instrumentation.events.firstOrNull { it.type == "input_resolution_cleanup" }
        assertTrue(cleanup != null)
        assertTrue((cleanup?.data?.get("removed_thoughts") as? Int ?: 0) >= 1)
        val answerEventId = answerEvents.first().id
        assertFalse(
            instrumentation.events.any {
                it.id > answerEventId &&
                    (it.type == "thought_processing" || it.type == "action_review_requested")
            }
        )
    }

    @Test
    fun `duplicate plan emission is suppressed when identical plan hash is emitted`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"plan",
                  "urgency":"medium",
                  "plan_goal":"Get pricing",
                  "plan_steps":["step one","step two"]
                }
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {
                  "decision":"plan",
                  "urgency":"medium",
                  "plan_goal":"Get pricing",
                  "plan_steps":["step one","step two"]
                }
                """.trimIndent()
            )
        }
        val instrumentation = RecordingInstrumentation()
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(
                modelClient = plannerLlm,
                config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 2, maxThoughtPasses = 3)),
                instrumentation = instrumentation
            ),
            superego = Superego(
                modelClient = StubChatModelClient(),
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 2, maxThoughtPasses = 3)),
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, instrumentation.events.count { it.type == "plan_created" })
        assertTrue(
            instrumentation.events.any {
                it.type == "queue_snapshot" && it.data["source"] == "decision_plan_suppressed_hash"
            }
        )
    }

    @Test
    fun `plan suppression recovery enqueues convergence thought instead of silently ending input`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "decision":"plan",
                  "urgency":"medium",
                  "plan_goal":"Initial plan",
                  "plan_steps":["step one"]
                }
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {
                  "decision":"plan",
                  "urgency":"medium",
                  "plan_goal":"Duplicate plan after first step",
                  "plan_steps":["step again"]
                }
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"final after suppression","action_summary":"converged"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 8,
                maxThoughtPasses = 3,
                maxPlansPerInput = 1
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(listOf("ego> final after suppression"), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "duplicate_plan_suppressed" && it.data["reason"] == "budget_exhausted"
            }
        )
        assertTrue(instrumentation.events.any { it.type == "convergence_thought_enqueued" })
    }

    @Test
    fun `agent keeps loop alive when action execution throws`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { throw IllegalStateException("output unavailable") }),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2)),
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("Action execution failed", ignoreCase = true) == true
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "loop_status" && it.data["status"] == "stopped"
            }
        )
    }

    @Test
    fun `agent injects hippocampus recall into planner context`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "- prior preference: concise responses",
                hitCount = 1
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4)),
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.queries.isNotEmpty())
        assertTrue(hippocampus.queries.any { it.cue.contains("hello") })
        val prompt = plannerLlm.lastMessages.last().content
        assertTrue(prompt.contains("Long-term memory recall:"))
        assertTrue(prompt.contains("prior preference: concise responses"))
        assertEquals(listOf("ego> ok"), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "memory_recall_result" &&
                    it.data["provider"] == "test_memory" &&
                    it.data["hit_count"] == 1
            }
        )
    }

    @Test
    fun `scratchpad summary is injected and lifecycle is scoped to resolved input`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Final answer from planner","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val searchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Official pricing page confirms current Pro plan rate.",
                    snippets = listOf("Use official pricing pages for current rates."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Pricing",
                            url = "https://example.com/pricing"
                        )
                    )
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 3),
            memory = MemoryConfig(
                scratchpad = ScratchpadConfig(
                    enabled = true,
                    activationMinPlanSteps = 1,
                    maxPromptTokens = 280,
                    debugCaptureEnabled = true
                )
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = searchEngine),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "find current pricing\nexit\n")

        val plannerCalls = plannerLlm.calls.filter { it.options.metadata.callSite != "action_verifier" }
        assertTrue(plannerCalls.size >= 2)
        val followUpPrompt = plannerCalls[1].messages.last().content
        assertTrue(followUpPrompt.contains("Scratchpad summary:"))
        assertTrue(followUpPrompt.contains("Request"))
        assertTrue(followUpPrompt.contains("web_search_result"))
        assertEquals(listOf("ego> Final answer from planner"), outputs)
        assertTrue(instrumentation.events.any { it.type == "scratchpad_created" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_updated" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_head" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_debug_snapshot" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_final_pass" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_destroyed" })
    }

    @Test
    fun `scratchpad final pass applies rewritten answer when confidence gates pass`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Draft answer from planner","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        var finalizerCalls = 0
        val finalizer = object : ScratchpadFinalizer {
            override fun finalize(request: ScratchpadFinalizerRequest): ScratchpadFinalizerResult {
                finalizerCalls += 1
                return ScratchpadFinalizerResult(
                    rewrittenPayload = "Rewritten grounded final answer",
                    confidence = 0.92,
                    reason = "direct_answer"
                )
            }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 3),
            memory = MemoryConfig(
                scratchpad = ScratchpadConfig(
                    enabled = true,
                    activationMinPlanSteps = 1,
                    finalPassMinWorkspaceConfidence = 0.30,
                    finalPassMinModelConfidence = 0.60
                )
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                webSearchEngine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult(
                            summary = "Official pricing confirms the rate.",
                            snippets = listOf("Verified from official pricing page.")
                        )
                }
            ),
            config = config,
            scratchpadFinalizer = finalizer,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "find current pricing\nexit\n")

        assertEquals(listOf("ego> Rewritten grounded final answer"), outputs)
        assertEquals(1, finalizerCalls)
        assertTrue(instrumentation.events.any { it.type == "scratchpad_final_pass_applied" })
    }

    @Test
    fun `scratchpad final pass skips rewrite when no evidence gathered`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Planner answer","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        var finalizerCalls = 0
        val finalizer = object : ScratchpadFinalizer {
            override fun finalize(request: ScratchpadFinalizerRequest): ScratchpadFinalizerResult {
                finalizerCalls += 1
                return ScratchpadFinalizerResult(
                    rewrittenPayload = "Should never apply",
                    confidence = 0.95,
                    reason = "direct_answer"
                )
            }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2),
            memory = MemoryConfig(
                scratchpad = ScratchpadConfig(
                    enabled = true,
                    activationMinPlanSteps = 1,
                    finalPassMinWorkspaceConfidence = 0.90,
                    finalPassMinModelConfidence = 0.50
                )
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            scratchpadFinalizer = finalizer,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "answer directly\nexit\n")

        assertEquals(listOf("ego> Planner answer"), outputs)
        assertEquals(0, finalizerCalls)
        assertTrue(
            instrumentation.events.any {
                it.type == "scratchpad_final_pass_skipped" &&
                    it.data["reason"] == "no_evidence_or_insufficient_drafts"
            }
        )
    }

    @Test
    fun `scratchpad final pass can finalize from answer drafts without external evidence`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"plan","urgency":"medium","plan_goal":"Build final response in chunks","plan_steps":["Synthesize chunk one","Synthesize chunk two","Finalize answer"]}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"resolution_draft","action_payload":"Draft chunk one","action_summary":"capture chunk one"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"resolution_draft","action_payload":"Draft chunk two","action_summary":"capture chunk two"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Final draft answer","action_summary":"deliver final answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        var finalizerCalls = 0
        val finalizer = object : ScratchpadFinalizer {
            override fun finalize(request: ScratchpadFinalizerRequest): ScratchpadFinalizerResult {
                finalizerCalls += 1
                return ScratchpadFinalizerResult(
                    rewrittenPayload = "Final answer synthesized from drafts",
                    confidence = 0.91,
                    reason = "direct_answer"
                )
            }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 10, maxThoughtPasses = 3),
            memory = MemoryConfig(
                scratchpad = ScratchpadConfig(
                    enabled = true,
                    activationMinPlanSteps = 2,
                    finalPassMinWorkspaceConfidence = 0.30,
                    finalPassMinModelConfidence = 0.60
                )
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            scratchpadFinalizer = finalizer,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "compose long answer\nexit\n")

        assertEquals(listOf("ego> Final answer synthesized from drafts"), outputs)
        assertEquals(1, finalizerCalls)
        assertTrue(
            instrumentation.events.count {
                it.type == "scratchpad_updated" &&
                    it.data["update_type"] == "resolution_draft_recorded"
            } >= 2
        )
        assertTrue(instrumentation.events.any { it.type == "scratchpad_final_pass_applied" })
    }

    @Test
    fun `thought recall is skipped when planner does not request explicit query`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"thought","urgency":"medium","thought":"consider options"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "recall baseline",
                hitCount = 1
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6)),
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.queries.isNotEmpty())
        assertEquals(1, instrumentation.events.count { it.type == "long_term_memory_recall_skipped" })
    }

    @Test
    fun `thought recall runs when planner requests explicit recall query`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"thought","urgency":"medium","thought":"check memory","long_term_memory_recall_query":"goal constraints and deadlines"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "memory result",
                hitCount = 1
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6)),
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.queries.size >= 2)
        assertTrue(hippocampus.queries.any { it.cue.contains("goal constraints and deadlines") })
        assertTrue(instrumentation.events.any { it.type == "long_term_memory_recall_requested" })
    }

    @Test
    fun `agent continues when hippocampus recall fails`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4)),
            hippocampus = ThrowingHippocampus(),
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(listOf("ego> ok"), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "memory_recall_failure" &&
                    (it.data["reason"] as? String)?.contains("offline", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `meta reasoner can push convergence when chain is stale`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"noop","reason":"still thinking"}""")
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"contact_user","action_payload":"final answer","action_summary":"deliver final"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(
                modelClient = plannerLlm,
                config = AgentConfig(
                    planner = PlannerConfig(maxLoopStepsPerInput = 6),
                    metaReasoner = MetaReasonerConfig(
                        deliberationPressureAssessmentMinStep = 1,
                        deliberationPressureAssessmentEverySteps = 1
                    )
                ),
                instrumentation = instrumentation
            ),
            superego = Superego(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(
                planner = PlannerConfig(maxLoopStepsPerInput = 6),
                metaReasoner = MetaReasonerConfig(
                    deliberationPressureAssessmentMinStep = 1,
                    deliberationPressureAssessmentEverySteps = 1
                )
            ),
            metaReasoner = object : MetaReasoner {
                override fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment =
                    MetaReasonerAssessment(
                        verdict = MetaReasonerVerdict.FINALIZE_NOW,
                        confidence = 0.9,
                        reason = "stale loop"
                    )
            },
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(listOf("ego> final answer"), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "meta_reasoner_assessment" &&
                    it.data["verdict"] == "finalize_now"
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("MetaReasoner requested faster convergence", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `memory consolidation runs after allowed action and writes imprint`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User prefers concise answers for future interactions.",
                    confidence = 0.9,
                    reason = "stable preference",
                    tags = listOf("preference")
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryMinConfidence = 0.6,
                longTermMemoryForceAssessOnAllowedAction = true
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(listOf("ego> ok"), outputs)
        assertEquals(1, hippocampus.imprints.size)
        assertTrue(hippocampus.imprints.single().summary.contains("prefers concise"))
        assertTrue(instrumentation.events.any { it.type == "long_term_memory_assessment" })
        assertTrue(
            instrumentation.events.any {
                it.type == "memory_imprint_result" && it.data["saved"] == true
            }
        )
    }

    @Test
    fun `terminal answer force hook runs consolidation when allowed-action force is disabled`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User name is Victor.",
                    confidence = 0.95,
                    reason = "identity fact"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryForceAssessOnAllowedAction = false,
                longTermMemoryForceAssessOnTerminalAnswer = true
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "remember my name is Victor\nexit\n")

        assertEquals(listOf("ego> ok"), outputs)
        assertEquals(1, hippocampus.imprints.size)
        assertTrue(
            instrumentation.events.any {
                it.type == "long_term_memory_assessment" &&
                    it.data["trigger"] == "post_terminal_answer"
            }
        )
        assertFalse(
            instrumentation.events.any {
                it.type == "long_term_memory_assessment" &&
                    it.data["trigger"] == "post_allowed_action"
            }
        )
    }

    @Test
    fun `explicit remember intent forces consolidation even when force hooks are disabled`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"noted","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User name is Victor.",
                    confidence = 0.95,
                    reason = "explicit remember request"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryForceAssessOnAllowedAction = false,
                longTermMemoryForceAssessOnTerminalAnswer = false
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "Please remember my name is Victor\nexit\n")

        assertEquals(listOf("ego> noted"), outputs)
        assertEquals(1, hippocampus.imprints.size)
        assertTrue(
            instrumentation.events.any {
                it.type == "long_term_memory_explicit_intent_detected"
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "long_term_memory_assessment" &&
                    it.data["trigger"] == "explicit_remember_intent"
            }
        )
        assertFalse(
            instrumentation.events.any {
                it.type == "long_term_memory_assessment" &&
                    it.data["trigger"] == "post_terminal_answer"
            }
        )
        assertFalse(
            instrumentation.events.any {
                it.type == "long_term_memory_assessment" &&
                    it.data["trigger"] == "post_allowed_action"
            }
        )
    }

    @Test
    fun `memory consolidation skips imprint when summary echoes recalled memory`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Your name is Victor.","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "Known fact: user name is Victor."
            )
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User name is Victor.",
                    confidence = 0.95,
                    reason = "identity fact"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryForceAssessOnAllowedAction = false,
                longTermMemoryForceAssessOnTerminalAnswer = true
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "what is my name?\nexit\n")

        assertEquals(listOf("ego> Your name is Victor."), outputs)
        assertTrue(hippocampus.imprints.none { it.source == "ego_long_term_memory_assessment" })
        val skipEvent = instrumentation.events.firstOrNull { it.type == "long_term_memory_persistence_skipped" }
        assertTrue(skipEvent != null)
        assertEquals("recall_echo_suppression", skipEvent?.data?.get("reason_code"))
        assertTrue((skipEvent?.data?.get("reason_detail") as? String)?.contains("min_summary_chars=") == true)
    }

    @Test
    fun `memory consolidation recall-echo suppression can be relaxed via config knobs`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Your name is Victor.","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "Known fact: user name is Victor."
            )
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User name is Victor.",
                    confidence = 0.95,
                    reason = "identity fact"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryForceAssessOnAllowedAction = false,
                longTermMemoryForceAssessOnTerminalAnswer = true,
                longTermMemoryRecallEchoMinSummaryChars = 100
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "what is my name?\nexit\n")

        assertEquals(listOf("ego> Your name is Victor."), outputs)
        assertEquals(1, hippocampus.imprints.size)
    }

    @Test
    fun `memory consolidation emits exact skip reason when confidence is below threshold`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User likes short responses.",
                    confidence = 0.40,
                    reason = "stable preference"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryMinConfidence = 0.80,
                longTermMemoryForceAssessOnAllowedAction = true
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.imprints.none { it.source == "ego_long_term_memory_assessment" })
        val skipEvent = instrumentation.events.firstOrNull { it.type == "long_term_memory_persistence_skipped" }
        assertTrue(skipEvent != null)
        assertEquals("confidence_below_threshold", skipEvent?.data?.get("reason_code"))
        assertTrue((skipEvent?.data?.get("reason_detail") as? String)?.contains("below configured minimum") == true)
    }

    @Test
    fun `memory consolidation emits exact skip reason when advisor declines save`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = false,
                    summary = "",
                    confidence = 0.95,
                    reason = "not durable"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryForceAssessOnAllowedAction = true
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.imprints.none { it.source == "ego_long_term_memory_assessment" })
        val skipEvent = instrumentation.events.firstOrNull { it.type == "long_term_memory_persistence_skipped" }
        assertTrue(skipEvent != null)
        assertEquals("advisor_declined_save", skipEvent?.data?.get("reason_code"))
        assertTrue((skipEvent?.data?.get("reason_detail") as? String)?.contains("not durable") == true)
    }

    @Test
    fun `memory metric callbacks receive recall consolidation and imprint signals`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "preference: concise",
                hitCount = 2
            )
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User prefers concise answers.",
                    confidence = 0.95,
                    reason = "stable preference"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 4),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryMinConfidence = 0.5,
                longTermMemoryForceAssessOnAllowedAction = true
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, instrumentation.events.count { it.type == "memory_recall_result" })
        assertEquals(0, instrumentation.events.count { it.type == "memory_recall_failure" })
        val recallEvent = instrumentation.events.first { it.type == "memory_recall_result" }
        assertEquals(2, recallEvent.data["hit_count"] as Int)
        assertEquals(1, instrumentation.events.count { it.type == "long_term_memory_assessment" })
        val imprintEvent = instrumentation.events.first { it.type == "memory_imprint_result" }
        assertEquals(1, instrumentation.events.count { it.type == "memory_imprint_result" })
        assertTrue(imprintEvent.data["saved"] as Boolean)
        assertTrue((imprintEvent.data["latency_ms"] as Long) >= 0)
    }

    @Test
    fun `memory consolidation action-trigger does not run when superego denies`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"blocked","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "")
        )
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "should not save on denied action trigger",
                    confidence = 0.95,
                    reason = "test"
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 3),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryForceAssessOnTerminalAnswer = false
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(hippocampus.imprints.none { it.source == "ego_long_term_memory_assessment" })
        assertFalse(
            instrumentation.events.any {
                it.type == "long_term_memory_assessment" &&
                    it.data["trigger"] == "post_allowed_action"
            }
        )
    }

    @Test
    fun `long-term memory assessment parse fallback disables further assessments in run`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a1","action_summary":"s1"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a2","action_summary":"s2"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"a3","action_summary":"s3"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = false,
                    summary = "",
                    confidence = 0.0,
                    reason = "parse fallback",
                    parseFallback = true
                )
        }
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(provider = "test_memory", text = "", hitCount = 0)
        )
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 6),
            memory = MemoryConfig(
                longTermMemoryForceAssessOnAllowedAction = true,
                longTermMemoryParseFallbackDisableAfter = 2
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "one\ntwo\nthree\nexit\n")

        assertTrue(
            instrumentation.events.count { it.type == "long_term_memory_assessment_parse_fallback" } >= 2
        )
        assertEquals(
            1,
            instrumentation.events.count { it.type == "long_term_memory_assessment_temporarily_disabled" }
        )
    }

    @Test
    fun `fetch circuit breaker disables action after repeated non retryable failures`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://blocked.example.com\"}","action_summary":"fetch page"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://blocked.example.com\"}","action_summary":"retry fetch"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://blocked.example.com\"}","action_summary":"retry fetch 2"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Could not fetch the page.","action_summary":"fallback answer"}"""
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val failingFetchTool = object : FetchTool {
            override suspend fun fetch(payload: String): String = "unused"
            override suspend fun fetchWithOutcome(payload: String): FetchOutcome =
                FetchOutcome(
                    message = "Fetch tool returned an error: 403 Forbidden",
                    errorCategory = FetchErrorCategory.NON_RETRYABLE
                )
        }
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 16,
                maxThoughtPasses = 5,
                actionRetryBudgetNonRetryableFailures = 1,
                actionRetryCooldownSteps = 8
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, fetchTool = failingFetchTool),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "fetch this page\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("Could not fetch"))
        assertTrue(
            instrumentation.events.any {
                it.type == "action_type_temporarily_disabled" &&
                    it.data["action_type"] == "website_fetch"
            },
            "Expected action_type_temporarily_disabled event for fetch"
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "action_type_circuit_breaker_tripped"
            },
            "Expected action_type_circuit_breaker_tripped event"
        )
    }

    @Test
    fun `fetch malformed request does not trip circuit breaker`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"bad json","action_summary":"fetch 1"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"still bad","action_summary":"fetch 2"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"nope","action_summary":"fetch 3"}"""
            )
            enqueueRawResponse(
                """{"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"answering from context","action_summary":"fallback"}"""
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val malformedTool = object : FetchTool {
            override suspend fun fetch(payload: String): String = "unused"
            override suspend fun fetchWithOutcome(payload: String): FetchOutcome =
                FetchOutcome(
                    message = "Fetch payload is invalid.",
                    errorCategory = FetchErrorCategory.MALFORMED_REQUEST
                )
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 16, maxThoughtPasses = 5))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, fetchTool = malformedTool),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "fetch this\nexit\n")

        assertEquals(1, outputs.size)
        assertFalse(
            instrumentation.events.any { it.type == "action_type_temporarily_disabled" },
            "MALFORMED_REQUEST should NOT trigger action_type_temporarily_disabled"
        )
        assertFalse(
            instrumentation.events.any { it.type == "action_type_circuit_breaker_tripped" },
            "MALFORMED_REQUEST should NOT trigger circuit breaker"
        )
    }

    private fun runAgentWithInput(agent: Ego, stdinContent: String) {
        val previousIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream(stdinContent.toByteArray()))
            kotlinx.coroutines.runBlocking { agent.runInteractive() }
        } finally {
            System.setIn(previousIn)
        }
    }

    private fun buildMotorCortex(
        output: (String) -> Unit,
        webSearchEngine: WebSearchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(summary = "unused", snippets = emptyList())
        },
        fetchTool: FetchTool? = null,
    ): MotorCortex {
        val webSearchHandler = WebSearchActionHandler(
            engine = webSearchEngine
        )
        return MotorCortex(
            webSearchActionHandler = webSearchHandler,
            output = output,
            fetchTool = fetchTool,
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )
    }

    private class RecordingHippocampus(
        private val recall: MemoryRecall,
    ) : Hippocampus {
        override val providerName: String = recall.provider
        val queries = mutableListOf<MemoryRecallQuery>()
        val imprints = mutableListOf<MemoryImprint>()

        override fun recall(query: MemoryRecallQuery): MemoryRecall {
            queries += query
            return recall
        }

        override fun imprint(imprint: MemoryImprint): Boolean {
            imprints += imprint
            return true
        }
    }

    private class ThrowingHippocampus : Hippocampus {
        override val providerName: String = "throwing_memory"

        override fun recall(query: MemoryRecallQuery): MemoryRecall {
            throw IllegalStateException("memory offline")
        }
    }
}
