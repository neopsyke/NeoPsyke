package ai.neopsyke.eval

import ai.neopsyke.admin.approvals.ApprovalStagingHook
import ai.neopsyke.agent.cortex.motor.actions.control.ActionSecurityActionRule
import ai.neopsyke.agent.cortex.motor.actions.control.ActionSecurityPolicyConfig
import ai.neopsyke.agent.cortex.motor.actions.control.ConfiguredActionAuthorizationPolicy
import ai.neopsyke.agent.cortex.motor.actions.control.DefaultActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.SqliteActionControlStore
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.cortex.motor.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionHandle
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncResumeMode
import ai.neopsyke.agent.cortex.motor.actions.plugin.builtin.ContactUserActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchSource
import ai.neopsyke.support.buildTestEgo
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.config.MemoryConfig
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.QueuedContinuation
import ai.neopsyke.agent.config.MetaReasonerConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.config.ScratchpadConfig
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.DurableWorkCue
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.LlmEgoPlanner
import ai.neopsyke.agent.id.Id
import ai.neopsyke.agent.id.IdConfig
import ai.neopsyke.agent.id.NeedConfig
import ai.neopsyke.agent.id.ResponseCurveConfig
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.ImprintRequest
import ai.neopsyke.agent.memory.longterm.ImprintResult
import ai.neopsyke.agent.memory.longterm.MemoryCapability
import ai.neopsyke.agent.memory.longterm.MemoryImprint
import ai.neopsyke.agent.memory.longterm.MemoryRecall
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.agent.durablework.DeterministicWorkPlanBuilder
import ai.neopsyke.agent.durablework.DurableWorkConfig
import ai.neopsyke.agent.durablework.DurableWorkRuntime
import ai.neopsyke.agent.durablework.WorkItemPriority
import ai.neopsyke.agent.durablework.WorkItemStatus
import ai.neopsyke.agent.durablework.WorkItemStore
import ai.neopsyke.agent.durablework.DurableWorkGateway
import ai.neopsyke.agent.durablework.NoopDurableWorkGateway
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.buildTestHierarchicalPlanner
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentScenarioPackTest {
    @Test
    fun scenario_direct_answer_single_step() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond directly"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 4, maxContinuationPasses = 1))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(listOf("ego> ok"), outputs)
        val plannerCalls = plannerLlm.calls.filter {
            it.options.metadata.callSite != "input_intent_router" &&
                it.options.metadata.callSite != "grounding_classifier"
        }
        assertEquals(1, plannerCalls.size)
        assertTrue(instrumentation.events.none { it.type == "plan_created" })
    }

    @Test
    fun scenario_denial_alternative_action() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"high","action_type":"contact_user","action_payload":"bad idea","action_summary":"first answer attempt"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"high","action_type":"contact_user","action_payload":"bad   idea","action_summary":"retrying same action"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"high","action_type":"contact_user","action_payload":"safe alternative","action_summary":"different safe answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy violation"}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxContinuationPasses = 4))
        val agent = buildTestEgo(
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
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"latest pricing","action_summary":"search 1"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"latest pricing retry","action_summary":"search 2"}
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
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 7, maxContinuationPasses = 1))
        val agent = buildTestEgo(
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
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"ok","action_summary":"respond"}
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
        val agent = buildTestEgo(
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
        assertTrue(prompt.contains("Relevant long-term memory:"))
        assertTrue(prompt.contains("prior preference: concise responses"))
        assertEquals(listOf("ego> ok"), outputs)
    }

    @Test
    fun scenario_scratchpad_prompt_and_cleanup() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"official pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"done","action_summary":"respond"}
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
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxContinuationPasses = 3),
            memory = MemoryConfig(
                scratchpad = ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, maxPromptTokens = 260)
            )
        )
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = search),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "find pricing\nexit\n")

        val plannerCalls = plannerLlm.calls.filter {
            it.options.metadata.callSite != "input_intent_router" &&
                it.options.metadata.callSite != "grounding_classifier"
        }
        assertTrue(plannerCalls.size >= 2)
        val followUpPrompt = plannerCalls[1].messages.last().content
        assertTrue(followUpPrompt.contains("Working notes for this request:"))
        assertTrue(
            followUpPrompt.contains("web_search_result") ||
                followUpPrompt.contains("Official pricing fetched.")
        )
        assertEquals(listOf("ego> done"), outputs)
        assertTrue(instrumentation.events.any { it.type == "scratchpad_created" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_destroyed" })
    }

    @Test
    fun scenario_unavailable_action_then_recover_with_answer() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"website_fetch","action_payload":"{\"url\":\"https://example.com\"}","action_summary":"fetch docs"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"using available tools only","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6, maxContinuationPasses = 2))
        val agent = buildTestEgo(
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
                it.type == "planner_decision_blocked" &&
                    it.data["action_type"] == "website_fetch"
            }
        )
    }

    @Test
    fun scenario_plan_decomposition_then_execute() {
        val plannerLlm = StubChatModelClient().apply {
            // Route to multi_step_task via input_intent_router
            enqueueRawResponseForCallSite("input_intent_router", """{"route":"multi_step_task","reasoning":"test"}""")
            // Task decomposition planner returns plan
            enqueueRawResponseForCallSite("task_decomposition",
                """
                {"goal":"Search and answer pricing question","steps":["Search for official pricing","Synthesize answer from search results"],"urgency":"medium"}
                """.trimIndent()
            )
            // Continuation step 1: planner decides to web_search
            enqueueRawResponseForCallSite("continuation",
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"official pricing 2025","action_summary":"search pricing"}
                """.trimIndent()
            )
            // Continuation step 2: planner synthesizes the answer
            enqueueRawResponseForCallSite("continuation",
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"Pricing is $20/month based on verified sources.","action_summary":"deliver verified answer"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 12, maxContinuationPasses = 4))
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
        val agent = buildTestEgo(
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

    @Test
    fun scenario_search_then_answer() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"official pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"Pricing is available on the official site.","action_summary":"respond with result"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val observedQueries = mutableListOf<String>()
        val search = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult {
                observedQueries += query
                return WebSearchResult(
                    summary = "Official pricing page located.",
                    snippets = listOf("Pricing information published."),
                    sources = listOf(
                        WebSearchSource(
                            title = "Pricing",
                            url = "https://example.com/pricing"
                        )
                    )
                )
            }
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6, maxContinuationPasses = 2))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = search),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "check pricing\nexit\n")

        assertEquals(listOf("official pricing"), observedQueries)
        assertEquals(listOf("ego> Pricing is available on the official site."), outputs)
        val plannerCalls = plannerLlm.calls
        assertTrue(plannerCalls.size >= 2)
    }

    @Test
    fun scenario_id_impulse_parallel_lifecycle() {
        val instrumentation = RecordingInstrumentation()
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.EnqueuePlan(
                        urgency = Urgency.HIGH,
                        goal = "Evaluate impulse branches",
                        steps = listOf("discard branch", "execute branch")
                    )
                    is EgoTrigger.ActionFeedback -> EgoDecision.Noop("ignore feedback in test")
                    is EgoTrigger.Continuation -> decideThought(trigger.continuation)
                    is EgoTrigger.DurableWork -> EgoDecision.Noop("ignore goal work")
                }

            private fun decideThought(thought: QueuedContinuation): EgoDecision =
                when {
                    thought.planContext?.stepIndex == 0 -> EgoDecision.Noop("discard this branch")
                    thought.planContext?.stepIndex == 1 -> EgoDecision.FormIntention(
                        urgency = Urgency.HIGH,
                        intentionKind = IntentionKind.OBSERVE,
                        actionType = ai.neopsyke.agent.model.ActionType.WEB_SEARCH,
                        payload = "official pricing",
                        summary = "gather evidence"
                    )
                    else -> EgoDecision.Noop("done")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 24, maxContinuationPasses = 1)
        )
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val agent = buildTestEgo(
            planner = planner,
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            instrumentation = instrumentation
        )
        val idModule = Id(
            config = IdConfig(
                enabled = true,
                pulseIntervalMs = 1000,
                triggerThreshold = 0.0,
                maxConsecutiveDenials = 5,
                backoffPulses = 10,
                maxInFlightPulses = 20,
                maxPendingImpulses = 1,
                needs = mapOf(
                    "be-useful" to NeedConfig(
                        description = "test",
                        growthRate = 0.1,
                        cooldownPulses = 0,
                        prompt = "be useful",
                        responseCurve = ResponseCurveConfig(type = "linear")
                    )
                )
            ),
            instrumentation = instrumentation,
            scope = CoroutineScope(Dispatchers.Unconfined),
            enqueueImpulse = { false },
            hasPendingWork = { false },
        )
        agent.setId(idModule)
        val rootImpulseId = "scenario-id-lifecycle-root"
        val queued = agent.enqueueImpulse(
            PendingImpulse(
                id = 1,
                needId = "be-useful",
                prompt = "Try something useful.",
                tension = 0.9,
                rawValue = 0.9,
                rootImpulseId = rootImpulseId,
                conversationContext = idModule.conversationContext,
            ),
            maxPendingImpulses = 1
        )
        assertTrue(queued)

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, instrumentation.events.count { it.type == "id_impulse_accepted" })
        assertEquals(1, instrumentation.events.count { it.type == "id_impulse_completed" && it.data["success"] == true })
        assertEquals(0, instrumentation.events.count { it.type == "id_impulse_denied" })
        assertTrue(
            instrumentation.events.any {
                it.type == "impulse_lifecycle_finalized" &&
                    it.data["root_impulse_id"] == rootImpulseId &&
                    it.data["result"] == "accepted"
            }
        )
    }

    @Test
    fun scenario_goal_async_wait_resume() = runBlocking {
        val root = Files.createTempDirectory("neopsyke-scenario-goal-async")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxContinuationPasses = 2),
            durableWork = DurableWorkConfig(
                enabled = true,
                workspaceRoot = root,
                actionsPerCycle = 2,
                conditionCheckIntervalMs = 25,
                allowRuntimePlanFallback = true,
            ),
        )
        val provider = StubAsyncOperationProvider().apply {
            enqueue(
                operationId = "scenario-op-1",
                statuses = listOf(
                    AsyncOperationStatus.Pending("download queued", nextPollAfterMs = 25),
                    AsyncOperationStatus.Succeeded("download finished"),
                )
            )
        }
        val manager = DurableWorkRuntime(
            config = config.durableWork,
            store = WorkItemStore(root),
            planner = DeterministicWorkPlanBuilder(),
            asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            instrumentation = instrumentation,
            cueEmitter = source::offer,
        )
        manager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        var startedAsync = false
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.DurableWork -> {
                        if (!startedAsync) {
                            startedAsync = true
                            EgoDecision.FormIntention(
                                urgency = Urgency.MEDIUM,
                                intentionKind = IntentionKind.PREPARE,
                                commitModePreference = CommitMode.APPROVAL_BACKED,
                                actionType = ActionType("async_test"),
                                payload = """{"operation_id":"scenario-op-1"}""",
                                summary = "start async scenario operation"
                            )
                        } else {
                            EgoDecision.FormIntention(
                                urgency = Urgency.MEDIUM,
                                intentionKind = IntentionKind.PREPARE,
                                commitModePreference = CommitMode.APPROVAL_BACKED,
                                actionType = ActionType.CONTACT_USER,
                                payload = "scenario async goal done",
                                summary = "report scenario completion"
                            )
                        }
                    }

                    else -> EgoDecision.Noop("ignore non-goal work")
                }
        }
        val agent = buildTestEgo(
            planner = planner,
            superego = Superego(
                modelClient = StubChatModelClient().apply {
                    repeat(4) { enqueueRawResponse("""{"allow":true}""") }
                },
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildAsyncMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
            durableWorkGateway = manager,
        )

        val loop = launch { agent.runInteractive() }
        try {
            val workItemId = manager.createWorkItem(
                instruction = "Complete async scenario goal",
                title = "Async Scenario",
                priority = WorkItemPriority.HIGH,
            )
            waitForWorkItemStatus(manager, workItemId, WorkItemStatus.COMPLETED)
            source.offer(RuntimeControlSignal.ExitRequested("scenario test"))
            loop.join()

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state.workItem.status)
            assertTrue(state.workItem.plan.steps.first().notes.contains("async_status=succeeded"))
            assertEquals(listOf("ego> scenario async goal done"), outputs)
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun scenario_natural_language_recurring_goal_creation() = runBlocking {
        val root = Files.createTempDirectory("neopsyke-scenario-goal-create")
        var manager: DurableWorkRuntime? = null
        var actionControlStore: SqliteActionControlStore? = null
        try {
            val plannerLlm = StubChatModelClient().apply {
                // Route to goal via input_intent_router
                enqueueRawResponseForCallSite("input_intent_router", """{"route":"goal","reasoning":"test"}""")
                enqueueRawResponseForCallSite("goal",
                    """
                    {
                      "operation":"create","work_item_reference":null,
                      "title":"Weather reminder",
                      "instruction":"Check the current weather and send the user an update for this scheduled run.",
                      "completion_criteria":"A weather update is delivered to the user for the current scheduled run.",
                      "priority":"medium",
                      "cron_expression":"*/5 * * * *",
                      "assistant_response":null,
                      "reason":null
                    }
                    """.trimIndent()
                )
            }
            val instrumentation = RecordingInstrumentation()
            val outputs = mutableListOf<String>()
            val config = AgentConfig(
                planner = PlannerConfig(maxLoopStepsPerInput = 4, maxContinuationPasses = 2),
                durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, allowRuntimePlanFallback = true),
            )
            manager = DurableWorkRuntime(
                config = config.durableWork,
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                instrumentation = instrumentation,
            )
            manager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val actionControlDb = root.resolve("action-control.db")
            actionControlStore = SqliteActionControlStore(actionControlDb.toString())
            val motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                config = config,
                durableWorkGateway = manager,
            )
            val agent = buildTestEgo(
                planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
                superego = Superego(
                    modelClient = StubChatModelClient().apply { enqueueRawResponse("""{"allow":true}""") },
                    config = config,
                    instrumentation = instrumentation
                ),
                motorCortex = motorCortex,
                config = config,
                instrumentation = instrumentation,
                durableWorkGateway = manager,
                actionControlService = DefaultActionControlService(
                    config = config.actionControl.copy(dbPath = actionControlDb.toString()),
                    store = actionControlStore,
                ) { action, authorization ->
                    motorCortex.execute(action, config.searchResultCount, authorization)
                },
            )
            val approvalPrompts = mutableListOf<String>()
            agent.setApprovalStagingHook(
                object : ApprovalStagingHook {
                    override suspend fun onApprovalStaged(
                        actionSummary: String,
                        stagedAction: ai.neopsyke.agent.model.StagedAction,
                        reason: String,
                        reasonCode: String?,
                        conversationContext: ai.neopsyke.agent.model.ConversationContext,
                    ) {
                        approvalPrompts += "$reason | $actionSummary | ${stagedAction.actionType.id}"
                    }
                }
            )

            runAgentWithInput(agent, "I would like to set a goal for you: Remind me of the current weather every 5 minutes.\nexit\n")

            assertTrue(manager.allWorkItems().isEmpty())
            val staged = actionControlStore.listStagedActions(limit = 10)
                .firstOrNull { it.actionType == ActionType.DURABLE_WORK_OPERATION }
            assertNotNull(staged)
            assertEquals(ActionType.DURABLE_WORK_OPERATION, staged.actionType)
            assertEquals(StagedActionStatus.WAITING_AUTHORIZATION, staged.status)
            assertTrue(staged.payload.contains("\"cron_expression\":\"*/5 * * * *\""))
            assertTrue(outputs.isEmpty())
            assertEquals(1, approvalPrompts.size)
            assertTrue(approvalPrompts.single().contains("approval", ignoreCase = true))
            assertTrue(approvalPrompts.single().contains(ActionType.DURABLE_WORK_OPERATION.id, ignoreCase = true))
            assertTrue(plannerLlm.calls.any { it.options.metadata.callSite == "goal" })
        } finally {
            actionControlStore?.close()
            manager?.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun scenario_llm_driven_daily_cron_goal_creation() = runBlocking {
        val root = Files.createTempDirectory("neopsyke-scenario-daily-goal")
        var manager: DurableWorkRuntime? = null
        var actionControlStore: SqliteActionControlStore? = null
        try {
            val plannerLlm = StubChatModelClient().apply {
                // Route to goal via input_intent_router
                enqueueRawResponseForCallSite("input_intent_router", """{"route":"goal","reasoning":"test"}""")
                enqueueRawResponseForCallSite("goal",
                    """
                    {
                      "operation":"create","work_item_reference":null,
                      "title":"Daily weather forecast",
                      "instruction":"Fetch the weather forecast for Hamburg and send the user a summary for this scheduled run.",
                      "completion_criteria":"The weather forecast is delivered to the user for the current scheduled run.",
                      "priority":"medium",
                      "cron_expression":"5 5 * * *",
                      "assistant_response":null,
                      "reason":null
                    }
                    """.trimIndent()
                )
            }
            val instrumentation = RecordingInstrumentation()
            val outputs = mutableListOf<String>()
            val config = AgentConfig(
                planner = PlannerConfig(maxLoopStepsPerInput = 4, maxContinuationPasses = 2),
                durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, allowRuntimePlanFallback = true),
            )
            manager = DurableWorkRuntime(
                config = config.durableWork,
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                instrumentation = instrumentation,
            )
            manager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val actionControlDb = root.resolve("action-control.db")
            actionControlStore = SqliteActionControlStore(actionControlDb.toString())
            val motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                config = config,
                durableWorkGateway = manager,
            )
            val agent = buildTestEgo(
                planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
                superego = Superego(
                    modelClient = StubChatModelClient().apply { enqueueRawResponse("""{"allow":true}""") },
                    config = config,
                    instrumentation = instrumentation
                ),
                motorCortex = motorCortex,
                config = config,
                instrumentation = instrumentation,
                durableWorkGateway = manager,
                actionControlService = DefaultActionControlService(
                    config = config.actionControl.copy(dbPath = actionControlDb.toString()),
                    store = actionControlStore,
                ) { action, authorization ->
                    motorCortex.execute(action, config.searchResultCount, authorization)
                },
            )
            val approvalPrompts = mutableListOf<String>()
            agent.setApprovalStagingHook(
                object : ApprovalStagingHook {
                    override suspend fun onApprovalStaged(
                        actionSummary: String,
                        stagedAction: ai.neopsyke.agent.model.StagedAction,
                        reason: String,
                        reasonCode: String?,
                        conversationContext: ai.neopsyke.agent.model.ConversationContext,
                    ) {
                        approvalPrompts += "$reason | $actionSummary | ${stagedAction.actionType.id}"
                    }
                }
            )

            runAgentWithInput(agent, "Create a new goal. It's purpose should be to remind me of the weather forecast for the day, every day at 5:05 am hamburg time.\nexit\n")

            // Goal not yet created (waiting for approval)
            assertTrue(manager.allWorkItems().isEmpty())
            val staged = actionControlStore.listStagedActions(limit = 10)
                .firstOrNull { it.actionType == ActionType.DURABLE_WORK_OPERATION }
            assertNotNull(staged, "Expected a staged durable_work_operation action")
            assertEquals(ActionType.DURABLE_WORK_OPERATION, staged.actionType)
            assertEquals(StagedActionStatus.WAITING_AUTHORIZATION, staged.status)
            // Cron comes from the LLM, not regex
            assertTrue(staged.payload.contains("\"cron_expression\":\"5 5 * * *\""),
                "Expected daily 5:05 cron from LLM; payload=${staged.payload}")
            assertTrue(staged.payload.contains("Daily weather forecast"))
            // Planner was invoked via the goal creation lane
            assertTrue(plannerLlm.calls.any { it.options.metadata.callSite == "goal" })
        } finally {
            actionControlStore?.close()
            manager?.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun scenario_goal_creation_bad_cron_triggers_feedback_retry() = runBlocking {
        val root = Files.createTempDirectory("neopsyke-scenario-goal-retry")
        var manager: DurableWorkRuntime? = null
        try {
            val plannerLlm = StubChatModelClient().apply {
                // Route to goal via input_intent_router
                enqueueRawResponseForCallSite("input_intent_router", """{"route":"goal","reasoning":"test"}""")
                // First call: goal creation lane returns an invalid cron
                enqueueRawResponseForCallSite("goal",
                    """
                    {
                      "operation":"create","work_item_reference":null,
                      "title":"Weekly standup",
                      "instruction":"Remind the user about their weekly standup meeting.",
                      "completion_criteria":"Standup reminder delivered.",
                      "priority":"medium",
                      "cron_expression":"bad-cron",
                      "assistant_response":null,
                      "reason":null
                    }
                    """.trimIndent()
                )
                // Second call: feedback triggers progression lane — planner responds to the user
                enqueueRawResponse(
                    """
                    {
                      "urgency":"medium",
                      "intention_kind":"observe",
                      "commit_mode_preference":"not_applicable",
                      "action_type":"contact_user",
                      "action_payload":"I corrected the schedule. Your weekly standup reminder is set for Mondays at 9 AM.",
                      "action_summary":"Confirm corrected goal schedule",
                      "long_term_memory_recall_query":null,
                      "reason":"Retrying with corrected cron expression"
                    }
                    """.trimIndent()
                )
            }
            val instrumentation = RecordingInstrumentation()
            val outputs = mutableListOf<String>()
            val config = AgentConfig(
                planner = PlannerConfig(maxLoopStepsPerInput = 10, maxContinuationPasses = 5),
                durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, allowRuntimePlanFallback = true),
            )
            manager = DurableWorkRuntime(
                config = config.durableWork,
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                instrumentation = instrumentation,
            )
            manager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val motorCortex = buildMotorCortex(
                output = { outputs.add(it) },
                config = config,
                durableWorkGateway = manager,
            )
            val superEgoLlm = StubChatModelClient().apply {
                // Enqueue enough superego approvals for all action reviews
                repeat(10) { enqueueRawResponse("""{"allow":true}""") }
            }
            // Use a policy that allows autonomous commit for recurring goals so the bad cron
            // reaches DurableWorkRuntime.executeOperation() and triggers the feedback loop.
            val autoCommitPolicy = ConfiguredActionAuthorizationPolicy(
                ActionSecurityPolicyConfig(
                    actions = mapOf(
                        ActionType.DURABLE_WORK_OPERATION.id to ActionSecurityActionRule(
                            directCommitEnabled = true,
                            autonomousCommitEnabled = true,
                            recurringRequiresApproval = false,
                        ),
                        ActionType.CONTACT_USER.id to ActionSecurityActionRule(
                            directCommitEnabled = true,
                            autonomousCommitEnabled = true,
                        ),
                    ),
                )
            )
            val agent = buildTestEgo(
                planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
                superego = Superego(
                    modelClient = superEgoLlm,
                    config = config,
                    instrumentation = instrumentation,
                    authorizationPolicy = autoCommitPolicy,
                ),
                motorCortex = motorCortex,
                config = config,
                instrumentation = instrumentation,
                durableWorkGateway = manager,
            )

            runAgentWithInput(agent, "Create a goal to remind me every Monday at 9am about our standup.\nexit\n")

            // A feedback-triggered planner call should have occurred after bad cron execution failure.
            // The feedback planner decides what to tell the user — the raw validation error flows
            // through statusSummary/plannerSignal, not through a bypass delivery path.
            val feedbackPlannerCalls = instrumentation.events.filter {
                it.type == "planner_start" && it.data["trigger"] == "feedback"
            }
            assertTrue(feedbackPlannerCalls.isNotEmpty(), "Expected planner re-invocation via feedback trigger after bad cron")

            // The feedback planner call should go through the progression lane
            assertTrue(
                instrumentation.events.any {
                    it.type == "planner_lane_selected" && it.data["lane"] == "progression" && it.data["trigger"] == "feedback"
                },
                "Expected feedback to route through progression lane"
            )

            // The planner should have been called at least twice: router + goal_creation + feedback
            assertTrue(plannerLlm.calls.size >= 2,
                "Expected at least 2 planner calls (creation + feedback), got ${plannerLlm.calls.size}")
        } finally {
            manager?.stop()
            root.toFile().deleteRecursively()
        }
    }

    // --- AC 25: Goal creation confirmation grounding ---
    @Test
    fun scenario_goal_creation_confirmation_grounding() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("input_intent_router", """{"route":"goal","reasoning":"user wants a goal"}""")
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"durable_work_operation","action_payload":"{\"command\":\"create\",\"title\":\"Daily Weather\",\"description\":\"Fetch weather\",\"priority\":\"medium\",\"cron_expression\":\"5 6 * * *\",\"step_descriptions\":[\"Check weather\"]}","action_summary":"create weather goal"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"Goal created: Daily Weather. Recurs on cron '5 6 * * *'.","action_summary":"confirm goal creation"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxContinuationPasses = 3))
        val agent = buildTestEgo(
            planner = LlmEgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "create a daily weather goal at 06:05\nexit\n")

        // Confirmation message must be delivered without denial.
        assertTrue(outputs.any { it.contains("Goal created") }, "Confirmation should be delivered: $outputs")
        // grounding_gate_review must show allow=true, grounding_required=false for the confirmation.
        val gateEvents = instrumentation.events.filter { it.type == "grounding_gate_review" }
        assertTrue(gateEvents.any { it.data["allow"] == true && it.data["grounding_required"] == false },
            "Expected grounding_gate_review with allow=true and grounding_required=false: $gateEvents")
    }

    // --- AC 26: Volatile-fact grounding enforcement ---
    @Test
    fun scenario_volatile_fact_grounding_enforcement() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("input_intent_router", """{"route":"direct_response","reasoning":"weather question"}""")
            enqueueRawResponseForCallSite("grounding_classifier", """{"grounding_required":true}""")
            // First planner call (DirectResponsePlanner): tries to answer without evidence.
            enqueueRawResponseForCallSite("direct_response",
                """{"answer":"The weather in Hamburg is sunny.","summary":"answer weather"}""")
            // After denial requeue (ContinuationPlanner): planner gathers evidence.
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"Hamburg weather today","action_summary":"search weather"}
                """.trimIndent()
            )
            // After evidence feedback (ContinuationPlanner or FeedbackPlanner): planner answers.
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"Based on search results, Hamburg is 15C and cloudy.","action_summary":"grounded answer"}
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
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 12, maxContinuationPasses = 4))
        val agent = buildTestEgo(
            planner = buildTestHierarchicalPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "what is the weather in Hamburg right now\nexit\n")

        // The grounded answer should be delivered.
        val classifierEvents = instrumentation.events.filter { it.type == "grounding_classification_resolved" }
        val gateEvents = instrumentation.events.filter { it.type == "grounding_gate_review" }
        val propagationEvents = instrumentation.events.filter { it.type == "grounding_metadata_propagated" }
        val propagationPairs = propagationEvents.mapNotNull { event ->
            val from = event.data["from_envelope_type"]?.toString()
            val to = event.data["to_envelope_type"]?.toString()
            if (from != null && to != null) from to to else null
        }.toSet()
        assertTrue(outputs.any { it.contains("15C") || it.contains("cloudy") },
            "Expected grounded answer in outputs: $outputs\nClassifier events: $classifierEvents\nGate events: $gateEvents")
        assertTrue(propagationEvents.isNotEmpty(), "Expected grounding_metadata_propagated events: $propagationEvents")
        assertTrue(
            setOf(
                "grounding_classifier" to "pending_input",
                "pending_input" to "planner_context",
                "planner_context" to "queued_intention",
                "queued_intention" to "pending_action",
                "pending_action" to "queued_continuation",
                "pending_action" to "action_feedback_cue",
                "action_feedback_cue" to "pending_action",
                "action_feedback_cue" to "queued_continuation",
            ).all { it in propagationPairs },
            "Expected full grounding propagation chain, got: $propagationPairs"
        )
        // First gate review must deny (no evidence).
        assertTrue(gateEvents.any {
            it.data["allow"] == false &&
                it.data["grounding_required"] == true &&
                it.data["evidence_gathered"] == false &&
                it.data["reason_code"] == "GROUNDING_EVIDENCE_REQUIRED"
        }, "Expected deny gate event.\nClassifier events: $classifierEvents\nGate events: $gateEvents")
        // Final gate review must allow the contact_user (evidence gathered).
        assertTrue(gateEvents.any {
            it.data["allow"] == true &&
                it.data["action_type"] == "contact_user" &&
                it.data["grounding_required"] == true &&
                it.data["evidence_gathered"] == true
        }, "Expected allow gate event for contact_user after evidence: $gateEvents")
    }

    // --- AC 27: Technical evidence retry ---
    @Test
    fun scenario_technical_evidence_retry() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("input_intent_router", """{"route":"general_action","reasoning":"weather question"}""")
            enqueueRawResponseForCallSite("grounding_classifier", """{"grounding_required":true}""")
            // First planner call dispatches web_search.
            enqueueRawResponseForCallSite(
                "general_action",
                """{"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"Hamburg weather","action_summary":"search weather"}"""
            )
            // After technical failure feedback: planner retries web_search.
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"web_search","action_payload":"Hamburg current weather","action_summary":"retry search"}
                """.trimIndent()
            )
            // After successful evidence: planner answers.
            enqueueRawResponse(
                """
                {"decision":"intend","intention_kind":"observe","commit_mode_preference":"not_applicable","urgency":"medium","action_type":"contact_user","action_payload":"Hamburg is 12C with rain.","action_summary":"grounded answer"}
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
        var searchAttempts = 0
        val search = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult {
                searchAttempts++
                if (searchAttempts == 1) {
                    throw RuntimeException("Simulated search timeout")
                }
                return WebSearchResult(
                    summary = "Hamburg weather: 12C, rain.",
                    snippets = listOf("Hamburg 12C rain."),
                    sources = listOf(WebSearchSource(title = "Weather", url = "https://example.com/weather"))
                )
            }
        }
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 12, maxContinuationPasses = 4))
        val agent = buildTestEgo(
            planner = buildTestHierarchicalPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }, webSearchEngine = search),
            config = config,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "what is the weather in Hamburg\nexit\n")

        // Answer should be delivered.
        assertTrue(outputs.any { it.contains("12C") || it.contains("rain") },
            "Expected grounded answer: $outputs")
        // Evidence retry was not suppressed: search was attempted at least twice.
        assertTrue(searchAttempts >= 2, "Expected at least 2 search attempts (first failed, second succeeded)")
        // Final gate must allow the contact_user with evidence gathered.
        val gateEvents = instrumentation.events.filter { it.type == "grounding_gate_review" }
        assertTrue(gateEvents.any {
            it.data["allow"] == true &&
                it.data["action_type"] == "contact_user" &&
                it.data["grounding_required"] == true &&
                it.data["evidence_gathered"] == true
        }, "Expected allow gate event after retry: $gateEvents")
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
                WebSearchResult(
                    summary = "default result",
                    snippets = listOf("default snippet"),
                    sources = listOf(WebSearchSource(title = "Default", url = "https://example.com/default"))
                )
        },
        config: AgentConfig = AgentConfig(),
        durableWorkGateway: DurableWorkGateway = NoopDurableWorkGateway,
    ): MotorCortex {
        val webSearchHandler = WebSearchActionHandler(engine = webSearchEngine)
        return MotorCortex(
            webSearchActionHandler = webSearchHandler,
            output = output,
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
            config = config,
            durableWorkGateway = durableWorkGateway,
        )
    }

    private fun buildAsyncMotorCortex(outputs: MutableList<String>): MotorCortex {
        val asyncPlugin = object : AgentActionPlugin {
            override val descriptor: ActionDescriptor = ActionDescriptor(
                actionType = ActionType("async_test"),
                dispatchable = true,
                plannerDescription = "async_test: start a test async operation and return a durable handle.",
                payloadGuidance = """JSON: {"operation_id":"scenario-op-1"}""",
                requiresFollowUpThought = false,
            )

            override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
                ActionOutcome(
                    statusSummary = "Async operation started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                    asyncWait = AsyncActionWait(
                        handles = listOf(
                            AsyncActionHandle(
                                providerType = "test_async",
                                providerId = "provider-1",
                                operationId = "scenario-op-1",
                                resumeMode = AsyncResumeMode.POLL,
                                pollAfterMs = 25,
                                timeoutAt = Instant.now().plusSeconds(5),
                            )
                        ),
                        summary = "Waiting for async scenario provider.",
                    ),
                )
        }
        return MotorCortex(
            ActionRegistry.fromPlugins(
                listOf(
                    ContactUserActionPlugin(
                        conversationOutput = RoutedConversationOutputGateway(
                            fallbackOutput = { text -> outputs += text }
                        )
                    ),
                    asyncPlugin,
                )
            )
        )
    }

    private suspend fun waitForWorkItemStatus(
        manager: DurableWorkRuntime,
        workItemId: String,
        status: WorkItemStatus,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.workItemStatus(workItemId)?.workItem?.status == status) {
                return
            }
            delay(10)
        }
        val state = manager.workItemStatus(workItemId)
        fail("Timed out waiting for status=$status, actual=${state?.workItem?.status}")
    }

    private class QueueSignalSource : SignalSource {
        private val signals = Channel<Signal>(Channel.UNLIMITED)

        fun offer(signal: Signal) {
            signals.trySend(signal).getOrThrow()
        }

        fun offer(cue: DurableWorkCue) {
            signals.trySend(CognitiveSignal.StimulusReceived(cue.toStimulus())).getOrThrow()
        }

        override suspend fun nextSignal(): Signal = signals.receive()
    }

    private class StubAsyncOperationProvider : AsyncOperationProvider {
        override val providerType: String = "test_async"
        private val statusesByOperation = mutableMapOf<String, ArrayDeque<AsyncOperationStatus>>()

        fun enqueue(operationId: String, statuses: List<AsyncOperationStatus>) {
            statusesByOperation[operationId] = ArrayDeque(statuses)
        }

        override suspend fun poll(handle: AsyncActionHandle): AsyncOperationStatus {
            val queue = statusesByOperation[handle.operationId]
                ?: return AsyncOperationStatus.Pending("still pending", nextPollAfterMs = 25)
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private class RecordingHippocampus(
        private val recall: MemoryRecall,
    ) : Hippocampus {
        override val providerName: String = recall.provider
        override val capabilities: Set<MemoryCapability> = setOf(
            MemoryCapability.SEMANTIC_RECALL,
            MemoryCapability.NARRATIVE_IMPRINT,
        )
        val queries = mutableListOf<MemoryRecallQuery>()
        val imprints = mutableListOf<MemoryImprint>()

        override fun recall(request: MemoryRecallQuery): MemoryRecall {
            queries += request
            return recall
        }

        override fun imprint(request: ImprintRequest): ImprintResult {
            val narrative = request as? MemoryImprint
                ?: return ImprintResult(provider = providerName, accepted = false, detail = "unsupported")
            imprints += narrative
            return ImprintResult(provider = providerName, accepted = true, storedCount = 1)
        }
    }
}
