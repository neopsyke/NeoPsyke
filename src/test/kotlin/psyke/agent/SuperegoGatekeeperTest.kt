package psyke.agent

import psyke.llm.ChatRole
import psyke.llm.ChatModelClient
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperegoGatekeeperTest {
    private val action = PendingAction(
        id = 42,
        urgency = Urgency.HIGH,
        type = ActionType.ANSWER,
        payload = "sample payload",
        summary = "sample summary"
    )
    private val snapshot = SuperegoContext(
        recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "last user message")),
    )

    @Test
    fun `gatekeeper accepts action with empty reason and emits events`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":true,"reason":"unused"}""")
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = SuperegoGatekeeper(
            modelClient = llm,
            config = AgentConfig(maxPromptTokens = 100),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)
        assertTrue(decision.allow)
        assertEquals("", decision.reason)
        assertEquals("superego", llm.lastOptions.metadata.actor)
        assertEquals("action_review", llm.lastOptions.metadata.callSite)
        assertEquals("answer", llm.lastOptions.metadata.actionType)
        assertEquals(80, llm.lastOptions.maxTokens)
        assertTrue(instrumentation.events.any { it.type == "superego_input" })
        assertTrue(
            instrumentation.events.any {
                it.type == "superego_output" && it.data["allow"] == true
            }
        )
        assertTrue(llm.lastMessages.any { it.role == ChatRole.USER && it.content.contains("Candidate action:") })
        val estimatedPromptTokens = llm.lastMessages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 100)
    }

    @Test
    fun `gatekeeper denies and clamps reason`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":false,"reason":"${"n".repeat(220)}"}""")
        val gatekeeper = SuperegoGatekeeper(modelClient = llm, config = AgentConfig())

        val decision = gatekeeper.review(action, snapshot)
        assertFalse(decision.allow)
        assertEquals(180, decision.reason.length)
    }

    @Test
    fun `gatekeeper denies when response cannot be parsed`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("n/a")
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = SuperegoGatekeeper(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)
        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("could not be parsed", ignoreCase = true))
        assertTrue(instrumentation.events.any { it.type == "warning" })
    }

    @Test
    fun `gatekeeper includes memory summary in review prompt`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":true}""")
        val gatekeeper = SuperegoGatekeeper(modelClient = llm, config = AgentConfig())
        val memorySnapshot = snapshot.copy(memorySummary = "Compressed memory: prefer neutral tone.")

        gatekeeper.review(action, memorySnapshot)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Memory summary:"))
        assertTrue(prompt.contains("prefer neutral tone"))
    }

    @Test
    fun `gatekeeper denies when model call fails`() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<psyke.llm.ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ) = throw IllegalStateException("superego unavailable")
        }
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = SuperegoGatekeeper(
            modelClient = failingClient,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = gatekeeper.review(action, snapshot)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("unavailable", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("Superego call failed", ignoreCase = true) == true
            }
        )
    }
}
