package psyke.eval

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.actions.websearch.WebSearchSource
import psyke.agent.core.AgentConfig
import psyke.agent.core.MemoryConfig
import psyke.agent.core.PendingAction
import psyke.agent.core.MetaReasonerConfig
import psyke.agent.core.PlannerConfig
import psyke.agent.core.TaskWorkspaceConfig
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.ego.Ego
import psyke.agent.ego.LlmEgoPlanner
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.MemoryImprint
import psyke.agent.memory.longterm.MemoryRecall
import psyke.agent.memory.longterm.MemoryRecallQuery
import psyke.agent.superego.Superego
import psyke.llm.ChatCompletion
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentScenarioPackTest {
    @Test
    fun scenario_denial_alternative_action() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"answer","action_payload":"bad idea","action_summary":"first answer attempt"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"answer","action_payload":"bad   idea","action_summary":"retrying same action"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"answer","action_payload":"safe alternative","action_summary":"different safe answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy violation"}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 4))
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
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
    fun scenario_fallback_after_external_failures() {
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
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = timingOutSearch),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, outputs.size)
        assertTrue(outputs.first().contains("could not complete external verification", ignoreCase = true))
    }

    @Test
    fun scenario_memory_recall_injected_into_prompt() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"ok","action_summary":"respond"}
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
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4))
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
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
    }

    @Test
    fun scenario_task_workspace_prompt_and_cleanup() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"done","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val search = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Official pricing fetched.",
                    snippets = listOf("Pro plan listed."),
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
                taskWorkspace = TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1, maxPromptTokens = 260)
            )
        )
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = search),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "find pricing\nexit\n")

        val plannerCalls = plannerLlm.calls.filter { it.options.metadata.callSite != "action_verifier" }
        assertTrue(plannerCalls.size >= 2)
        val followUpPrompt = plannerCalls[1].messages.last().content
        assertTrue(followUpPrompt.contains("Task workspace summary:"))
        assertTrue(followUpPrompt.contains("web_search_result"))
        assertEquals(listOf("ego> done"), outputs)
        assertTrue(instrumentation.events.any { it.type == "task_workspace_created" })
        assertTrue(instrumentation.events.any { it.type == "task_workspace_destroyed" })
    }

    @Test
    fun scenario_forced_terminal_after_repeated_model_errors() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing-planner"

            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
                throw IllegalStateException("planner unavailable")
            }
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 24,
                maxThoughtPasses = 20,
                llmRetryAttempts = 1
            ),
            metaReasoner = MetaReasonerConfig(
                deliberationPressureAssessmentMinStep = 1,
                forcedTerminalPressureThreshold = 0.55,
                forcedTerminalStaleStreakThreshold = 2
            )
        )
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = failingClient, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertTrue(outputs.isNotEmpty())
        assertTrue(
            outputs.any {
                it.contains("diminishing returns", ignoreCase = true) ||
                    it.contains("parsing", ignoreCase = true) ||
                    it.contains("model error", ignoreCase = true)
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    ((it.data["message"] as? String)?.contains("Forced terminal answer queued", ignoreCase = true) == true ||
                        (it.data["message"] as? String)?.contains("circuit breaker tripped", ignoreCase = true) == true)
            }
        )
    }

    @Test
    fun scenario_unavailable_action_then_recover_with_answer() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://example.com\"}","action_summary":"fetch docs"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"using available tools only","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6, maxThoughtPasses = 2))
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "fetch this\nexit\n")

        assertEquals(listOf("ego> using available tools only"), outputs)
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" &&
                    it.data["decision_type"] == "noop" &&
                    (it.data["reason"] as? String)?.contains("unavailable action type", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun scenario_action_verifier_repairs_web_search_before_superego_review() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"stale query","action_summary":"search old pricing"}
                """.trimIndent()
            )
            enqueueRawResponseForCallSite(
                callSite = "action_verifier",
                content = """
                {"verdict":"repair","action_type":"web_search","action_payload":"official groq pricing","action_summary":"search official pricing page","reason":"refined query"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val observedQueries = mutableListOf<String>()
        val recordingSearchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult {
                observedQueries += query
                return WebSearchResult(
                    summary = "ok",
                    snippets = listOf("official result"),
                    sources = listOf(
                        WebSearchSource(
                            title = "Groq Pricing",
                            url = "https://groq.com/pricing"
                        )
                    )
                )
            }
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 2, maxThoughtPasses = 2))
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(webSearchEngine = recordingSearchEngine, output = {}),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "check pricing\nexit\n")

        assertEquals(listOf("official groq pricing"), observedQueries)
        assertTrue(
            instrumentation.events.any {
                it.type == "action_verifier_result" && it.data["verdict"] == "repair"
            }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "action_review_requested" &&
                    (it.data["action"] as? PendingAction)?.payload == "official groq pricing"
            }
        )
    }

    @Test
    fun scenario_plan_decomposition_then_execute() {
        val plannerLlm = StubChatModelClient().apply {
            // Input: planner decides to create a plan
            enqueueRawResponse(
                """
                {"decision":"plan","urgency":"medium","plan_goal":"Search and answer pricing question","plan_steps":["Search for official pricing","Synthesize answer from search results"]}
                """.trimIndent()
            )
            // Step-thought 1: planner decides to web_search
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing 2025","action_summary":"search pricing"}
                """.trimIndent()
            )
            // Follow-up thought from search: planner decides to answer
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"Pricing is $20/month based on verified sources.","action_summary":"deliver verified answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 12, maxThoughtPasses = 4))
        val search = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "Pricing verified from official source.",
                    snippets = listOf("Official pricing is 20/month."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Official Pricing",
                            url = "https://example.com/pricing"
                        )
                    )
                )
        }
        val agent = Ego(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = search),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "what is the pricing?\nexit\n")

        assertTrue(outputs.isNotEmpty())
        assertTrue(outputs.first().contains("Pricing is", ignoreCase = true))
        assertTrue(
            instrumentation.events.any { it.type == "plan_created" }
        )
        assertTrue(
            instrumentation.events.any { it.type == "plan_steps_enqueued" }
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" && it.data["decision_type"] == "plan"
            }
        )
    }

    private fun runAgentWithInput(agent: Ego, stdinContent: String) {
        val previousIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream(stdinContent.toByteArray()))
            agent.runInteractive()
        } finally {
            System.setIn(previousIn)
        }
    }

    private fun buildMotorCortex(
        output: (String) -> Unit,
        webSearchEngine: WebSearchEngine = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(summary = "unused", snippets = emptyList(), sources = emptyList())
        }
    ): MotorCortex {
        val webSearchHandler = WebSearchActionHandler(engine = webSearchEngine)
        return MotorCortex(
            webSearchActionHandler = webSearchHandler,
            output = output
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
}
