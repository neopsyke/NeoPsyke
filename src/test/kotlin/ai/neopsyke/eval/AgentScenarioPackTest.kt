package ai.neopsyke.eval

import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.actions.websearch.WebSearchResult
import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.actions.async.AsyncActionHandle
import ai.neopsyke.agent.actions.async.AsyncActionWait
import ai.neopsyke.agent.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.actions.async.AsyncResumeMode
import ai.neopsyke.agent.actions.builtin.ContactUserActionPlugin
import ai.neopsyke.agent.actions.websearch.WebSearchSource
import ai.neopsyke.support.buildTestEgo
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.MemoryConfig
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingThought
import ai.neopsyke.agent.config.MetaReasonerConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.config.TaskWorkspaceConfig
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.cortex.sensory.SensorySignal
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.LlmEgoPlanner
import ai.neopsyke.agent.id.Id
import ai.neopsyke.agent.id.IdConfig
import ai.neopsyke.agent.id.NeedConfig
import ai.neopsyke.agent.id.ResponseCurveConfig
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.MemoryImprint
import ai.neopsyke.agent.memory.longterm.MemoryRecall
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.agent.project.DeterministicProjectPlanner
import ai.neopsyke.agent.project.ProjectConfig
import ai.neopsyke.agent.project.ProjectManager
import ai.neopsyke.agent.project.ProjectPriority
import ai.neopsyke.agent.project.ProjectStatus
import ai.neopsyke.agent.project.ProjectStore
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
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
    fun scenario_denial_alternative_action() {
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
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 4))
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
        assertTrue(prompt.contains("Long-term memory recall:"))
        assertTrue(prompt.contains("prior preference: concise responses"))
        assertEquals(listOf("ego> ok"), outputs)
    }

    @Test
    fun scenario_scratchpad_prompt_and_cleanup() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"web_search","action_payload":"official pricing","action_summary":"search pricing"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"done","action_summary":"respond"}
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
        val agent = buildTestEgo(
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
        assertTrue(followUpPrompt.contains("Scratchpad summary:"))
        assertTrue(followUpPrompt.contains("web_search_result"))
        assertEquals(listOf("ego> done"), outputs)
        assertTrue(instrumentation.events.any { it.type == "scratchpad_created" })
        assertTrue(instrumentation.events.any { it.type == "scratchpad_destroyed" })
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
                maxThoughtPasses = 20
            ),
            llmRetryAttempts = 1,
            metaReasoner = MetaReasonerConfig(
                deliberationPressureAssessmentMinStep = 1,
                forcedTerminalPressureThreshold = 0.55,
                forcedTerminalStaleStreakThreshold = 2
            )
        )
        val agent = buildTestEgo(
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
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"using available tools only","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(planner = PlannerConfig(maxLoopStepsPerInput = 6, maxThoughtPasses = 2))
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
        val agent = buildTestEgo(
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
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Pricing is $20/month based on verified sources.","action_summary":"deliver verified answer"}
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
                    is EgoTrigger.PendingThoughtInput -> decideThought(trigger.thought)
                    is EgoTrigger.ProjectWork -> EgoDecision.Noop("ignore project work")
                }

            private fun decideThought(thought: PendingThought): EgoDecision =
                when {
                    thought.planContext?.stepIndex == 0 -> EgoDecision.Noop("discard this branch")
                    thought.planContext?.stepIndex == 1 -> EgoDecision.ProposeAction(
                        urgency = Urgency.HIGH,
                        actionType = ai.neopsyke.agent.model.ActionType.WEB_SEARCH,
                        payload = "official pricing",
                        summary = "gather evidence"
                    )
                    else -> EgoDecision.Noop("done")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 24, maxThoughtPasses = 1)
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
                urgency = 0.9,
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
    fun scenario_project_async_wait_resume() = runBlocking {
        val root = Files.createTempDirectory("neopsyke-scenario-project-async")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
            projects = ProjectConfig(
                enabled = true,
                workspaceRoot = root,
                actionsPerCycle = 2,
                conditionCheckIntervalMs = 25,
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
        val manager = ProjectManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            instrumentation = instrumentation,
            signalEmitter = source::offer,
        )
        manager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        var startedAsync = false
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.ProjectWork -> {
                        if (!startedAsync) {
                            startedAsync = true
                            EgoDecision.ProposeAction(
                                urgency = Urgency.MEDIUM,
                                actionType = ActionType("async_test"),
                                payload = """{"operation_id":"scenario-op-1"}""",
                                summary = "start async scenario operation"
                            )
                        } else {
                            EgoDecision.ProposeAction(
                                urgency = Urgency.MEDIUM,
                                actionType = ActionType.CONTACT_USER,
                                payload = "scenario async project done",
                                summary = "report scenario completion"
                            )
                        }
                    }

                    else -> EgoDecision.Noop("ignore non-project work")
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
            projectsGateway = manager,
        )

        val loop = launch { agent.runInteractive() }
        try {
            val projectId = manager.createProject(
                instruction = "Complete async scenario project",
                title = "Async Scenario",
                priority = ProjectPriority.HIGH,
            )
            waitForProjectStatus(manager, projectId, ProjectStatus.COMPLETED)
            source.offer(SensorySignal.ExitRequested("scenario test"))
            loop.join()

            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertEquals(ProjectStatus.COMPLETED, state.project.status)
            assertTrue(state.project.plan.steps.first().notes.contains("async_status=succeeded"))
            assertEquals(listOf("ego> scenario async project done"), outputs)
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
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
                WebSearchResult(summary = "unused", snippets = emptyList(), sources = emptyList())
        }
    ): MotorCortex {
        val webSearchHandler = WebSearchActionHandler(engine = webSearchEngine)
        return MotorCortex(
            webSearchActionHandler = webSearchHandler,
            output = output,
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
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
                listOf(ContactUserActionPlugin(output = { outputs += it }), asyncPlugin)
            )
        )
    }

    private suspend fun waitForProjectStatus(
        manager: ProjectManager,
        projectId: String,
        status: ProjectStatus,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.projectStatus(projectId)?.project?.status == status) {
                return
            }
            delay(10)
        }
        val state = manager.projectStatus(projectId)
        fail("Timed out waiting for status=$status, actual=${state?.project?.status}")
    }

    private class QueueSignalSource : SignalSource {
        private val signals = Channel<Signal>(Channel.UNLIMITED)

        fun offer(signal: Signal) {
            signals.trySend(signal).getOrThrow()
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
