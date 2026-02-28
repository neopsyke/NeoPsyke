package psyke.agent

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.actions.websearch.WebSearchSource
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
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
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"ok","action_summary":"respond"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2)
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 4),
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
    fun `fallback explanation executes with one grace step when thought limit is reached`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"high","action_type":"answer","action_payload":"blocked attempt","action_summary":"initial answer"}
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
        val config = AgentConfig(
            maxLoopStepsPerInput = 4,
            maxThoughtPasses = 2
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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
                {"decision":"action","urgency":"high","action_type":"answer","action_payload":"blocked attempt","action_summary":"initial answer"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"low","action_type":"answer","action_payload":"queued action","action_summary":"occupy action queue"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"policy denied"}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            maxLoopStepsPerInput = 2,
            maxThoughtPasses = 1,
            maxPendingActions = 1
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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
        val config = AgentConfig(
            maxLoopStepsPerInput = 7,
            maxThoughtPasses = 1
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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
        val config = AgentConfig(
            maxLoopStepsPerInput = 7,
            maxThoughtPasses = 1
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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
    fun `agent keeps loop alive when action execution throws`() {
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
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { throw IllegalStateException("output unavailable") }),
            config = AgentConfig(maxLoopStepsPerInput = 4, maxThoughtPasses = 2),
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
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(maxLoopStepsPerInput = 4),
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, hippocampus.queries.size)
        assertTrue(hippocampus.queries.single().cue.contains("hello"))
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
    fun `thought recall is skipped when planner does not request explicit query`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"thought","urgency":"medium","thought":"consider options"}
                """.trimIndent()
            )
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
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "recall baseline",
                hitCount = 1
            )
        )
        var recallSkipped = 0
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = AgentConfig(maxLoopStepsPerInput = 6),
            hippocampus = hippocampus,
            onLongTermMemoryRecallSkipped = { recallSkipped += 1 },
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, hippocampus.queries.size)
        assertEquals(1, recallSkipped)
        assertTrue(instrumentation.events.any { it.type == "long_term_memory_recall_skipped" })
    }

    @Test
    fun `thought recall runs when planner requests explicit recall query`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"thought","urgency":"medium","thought":"check memory","long_term_memory_recall_query":"project constraints and deadlines"}
                """.trimIndent()
            )
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
        val hippocampus = RecordingHippocampus(
            recall = MemoryRecall(
                provider = "test_memory",
                text = "memory result",
                hitCount = 1
            )
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = AgentConfig(maxLoopStepsPerInput = 6),
            hippocampus = hippocampus,
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(2, hippocampus.queries.size)
        assertTrue(hippocampus.queries.last().cue.contains("project constraints and deadlines"))
        assertTrue(instrumentation.events.any { it.type == "long_term_memory_recall_requested" })
    }

    @Test
    fun `agent continues when hippocampus recall fails`() {
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
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = AgentConfig(), instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(maxLoopStepsPerInput = 4),
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
                {"decision":"action","urgency":"high","action_type":"answer","action_payload":"final answer","action_summary":"deliver final"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val agent = EgoAgent(
            planner = EgoPlanner(
                modelClient = plannerLlm,
                config = AgentConfig(
                    maxLoopStepsPerInput = 6,
                    deliberationPressureAssessmentMinStep = 1,
                    deliberationPressureAssessmentEverySteps = 1
                ),
                instrumentation = instrumentation
            ),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = AgentConfig(),
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = { outputs.add(it) }),
            config = AgentConfig(
                maxLoopStepsPerInput = 6,
                deliberationPressureAssessmentMinStep = 1,
                deliberationPressureAssessmentEverySteps = 1
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
            maxLoopStepsPerInput = 4,
            longTermMemoryAssessEverySteps = 100,
            longTermMemoryMinConfidence = 0.6,
            longTermMemoryForceAssessOnAllowedAction = true
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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
    fun `memory metric callbacks receive recall consolidation and imprint signals`() {
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
        var recallSuccessCount = 0
        var recallFailureCount = 0
        var consolidationCount = 0
        var imprintCount = 0
        var lastRecallHitCount = -1
        var lastImprintSaved = false
        var lastImprintLatencyMs = -1L
        val config = AgentConfig(
            maxLoopStepsPerInput = 4,
            longTermMemoryAssessEverySteps = 100,
            longTermMemoryMinConfidence = 0.5,
            longTermMemoryForceAssessOnAllowedAction = true
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            onMemoryRecall = { hitCount, _, _, _ ->
                recallSuccessCount += 1
                lastRecallHitCount = hitCount
            },
            onMemoryRecallFailure = {
                recallFailureCount += 1
            },
            onLongTermMemoryAssessment = {
                consolidationCount += 1
            },
            onMemoryImprintResult = { saved, _, latencyMs ->
                imprintCount += 1
                lastImprintSaved = saved
                lastImprintLatencyMs = latencyMs
            },
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "hello\nexit\n")

        assertEquals(1, recallSuccessCount)
        assertEquals(0, recallFailureCount)
        assertEquals(2, lastRecallHitCount)
        assertEquals(1, consolidationCount)
        assertEquals(1, imprintCount)
        assertTrue(lastImprintSaved)
        assertTrue(lastImprintLatencyMs >= 0)
    }

    @Test
    fun `memory consolidation action-trigger does not run when superego denies`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"blocked","action_summary":"respond"}
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
            maxLoopStepsPerInput = 3,
            longTermMemoryAssessEverySteps = 100
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
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

        assertTrue(hippocampus.imprints.isEmpty())
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
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a1","action_summary":"s1"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a2","action_summary":"s2"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"a3","action_summary":"s3"}
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
        var parseFailureCount = 0
        val config = AgentConfig(
            maxLoopStepsPerInput = 6,
            longTermMemoryForceAssessOnAllowedAction = true,
            longTermMemoryParseFallbackDisableAfter = 2
        )
        val agent = EgoAgent(
            planner = EgoPlanner(modelClient = plannerLlm, config = config, instrumentation = instrumentation),
            superego = SuperegoGatekeeper(
                modelClient = superegoLlm,
                config = config,
                instrumentation = instrumentation
            ),
            motorCortex = buildMotorCortex(output = {}),
            config = config,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            onLongTermMemoryAssessmentParseFailure = { parseFailureCount += 1 },
            instrumentation = instrumentation
        )

        runAgentWithInput(agent, "one\ntwo\nthree\nexit\n")

        assertEquals(2, parseFailureCount)
        assertTrue(
            instrumentation.events.count { it.type == "long_term_memory_assessment_parse_fallback" } >= 2
        )
        assertEquals(
            1,
            instrumentation.events.count { it.type == "long_term_memory_assessment_temporarily_disabled" }
        )
    }

    private fun runAgentWithInput(agent: EgoAgent, stdinContent: String) {
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
                WebSearchResult(summary = "unused", snippets = emptyList())
        }
    ): MotorCortex {
        val webSearchHandler = WebSearchActionHandler(
            engine = webSearchEngine
        )
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

    private class ThrowingHippocampus : Hippocampus {
        override val providerName: String = "throwing_memory"

        override fun recall(query: MemoryRecallQuery): MemoryRecall {
            throw IllegalStateException("memory offline")
        }
    }
}
