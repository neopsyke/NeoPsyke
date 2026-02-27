package psyke.agent

import psyke.llm.ChatRole
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
    private val snapshot = AgentSnapshot(
        recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "last user message")),
        pendingInputCount = 0,
        pendingThoughtCount = 1,
        pendingActionCount = 1
    )

    @Test
    fun `gatekeeper accepts action with empty reason and emits events`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"allow":true,"reason":"unused"}""")
        val instrumentation = RecordingInstrumentation()
        val gatekeeper = SuperegoGatekeeper(
            modelClient = llm,
            config = AgentConfig(maxPromptTokens = 1),
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
        assertEquals(2, llm.lastMessages.size)
        assertTrue(llm.lastMessages.all { it.role == ChatRole.SYSTEM })
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
}
