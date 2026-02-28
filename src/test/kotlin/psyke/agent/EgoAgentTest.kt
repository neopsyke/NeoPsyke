package psyke.agent

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun runAgentWithInput(agent: EgoAgent, stdinContent: String) {
        val previousIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream(stdinContent.toByteArray()))
            agent.runInteractive()
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
            output = output
        )
    }
}
