package ai.neopsyke.agent

import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.actions.websearch.WebSearchResult
import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.support.buildTestEgo
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRoundTripIntegrationTest {
    @Test
    fun `memory is consolidated then recalled on a later turn`() {
        val plannerLlm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Noted. I will remember your preference.","action_summary":"ack preference"}
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {"decision":"action","urgency":"medium","action_type":"contact_user","action_payload":"Your favorite color is teal.","action_summary":"answer from memory"}
                """.trimIndent()
            )
        }
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":true}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val hippocampus = RoundTripHippocampus()
        var consolidationCalls = 0
        val advisor = object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision {
                consolidationCalls += 1
                return LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = "User preference: favorite color is teal.",
                    confidence = 0.95,
                    reason = "durable preference",
                    tags = listOf("preference")
                )
            }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8),
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 100,
                longTermMemoryMinConfidence = 0.5,
                longTermMemoryForceAssessOnAllowedAction = true
            )
        )
        val outputs = mutableListOf<String>()
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

        try {
            runAgentWithInput(
                agent = agent,
                stdinContent = "Remember this: my favorite color is teal.\nWhat is my favorite color?\nexit\n"
            )

            assertTrue(consolidationCalls >= 1)
            assertEquals(1, hippocampus.imprints.size)
            assertTrue(hippocampus.imprints.single().summary.contains("favorite color is teal", ignoreCase = true))
            assertTrue(hippocampus.recallQueries.size >= 2)
            val lastPlannerPrompt = plannerLlm.lastMessages.last().content
            assertTrue(lastPlannerPrompt.contains("Relevant long-term memory:"))
            assertTrue(lastPlannerPrompt.contains("favorite color is teal", ignoreCase = true))
            assertEquals(
                listOf(
                    "ego> Noted. I will remember your preference.",
                    "ego> Your favorite color is teal."
                ),
                outputs
            )
        } finally {
            hippocampus.clear()
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

    private fun buildMotorCortex(output: (String) -> Unit): MotorCortex {
        val webSearchHandler = WebSearchActionHandler(
            engine = object : WebSearchEngine {
                override fun search(query: String, maxResults: Int): WebSearchResult =
                    WebSearchResult(summary = "unused", snippets = emptyList())
            }
        )
        return MotorCortex(
            webSearchActionHandler = webSearchHandler,
            output = output,
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )
    }

    private class RoundTripHippocampus : Hippocampus {
        override val providerName: String = "roundtrip_memory"
        override val capabilities: Set<MemoryCapability> = setOf(
            MemoryCapability.SEMANTIC_RECALL,
            MemoryCapability.NARRATIVE_IMPRINT,
        )
        val recallQueries = mutableListOf<MemoryRecallQuery>()
        val imprints = mutableListOf<MemoryImprint>()
        
        fun clear() {
            recallQueries.clear()
            imprints.clear()
        }

        override fun recall(request: RecallRequest): RecallResult {
            recallQueries += request
            val latest = imprints.lastOrNull()
            return if (latest == null) {
                MemoryRecall(provider = providerName, text = "", hitCount = 0, truncated = false)
            } else {
                MemoryRecall(
                    provider = providerName,
                    text = "- ${latest.summary}",
                    hitCount = 1,
                    truncated = false
                )
            }
        }

        override fun imprint(request: ImprintRequest): ImprintResult {
            val narrative = request as? NarrativeImprint
                ?: return ImprintResult(provider = providerName, accepted = false, detail = "unsupported")
            imprints += narrative
            return ImprintResult(provider = providerName, accepted = true, storedCount = 1)
        }
    }
}
