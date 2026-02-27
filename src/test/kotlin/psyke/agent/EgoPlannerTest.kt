package psyke.agent

import psyke.llm.ChatMessage
import psyke.llm.ChatRole
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EgoPlannerTest {
    @Test
    fun `planner returns clamped thought decision and emits events`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"thought","urgency":"high","thought":"abcdefghi"}""")
        val instrumentation = RecordingInstrumentation()
        val planner = EgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxThoughtChars = 5, maxCompletionTokens = 88),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = EgoTrigger.IncomingInput(PendingInput(1, "hi")),
            snapshot = AgentSnapshot(
                recentDialogue = emptyList(),
                pendingInputCount = 1,
                pendingThoughtCount = 2,
                pendingActionCount = 3
            )
        )

        val thought = assertIs<EgoDecision.EnqueueThought>(decision)
        assertEquals(Urgency.HIGH, thought.urgency)
        assertEquals("abcde", thought.content)
        assertEquals("ego", llm.lastOptions.metadata.actor)
        assertEquals("input", llm.lastOptions.metadata.callSite)
        assertEquals(88, llm.lastOptions.maxTokens)
        assertTrue(instrumentation.events.any { it.type == "planner_start" })
        assertTrue(
            instrumentation.events.any {
                it.type == "planner_decision" && it.data["decision_type"] == "thought"
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
              "action_type":"answer",
              "action_payload":"payload-too-long",
              "action_summary":"summary-too-long"
            }
            """.trimIndent()
        )
        val planner = EgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxActionPayloadChars = 7, maxActionSummaryChars = 8)
        )

        val decision = planner.decide(
            trigger = EgoTrigger.PendingThoughtInput(PendingThought(7, Urgency.LOW, "think", 1)),
            snapshot = AgentSnapshot(
                recentDialogue = listOf(
                    DialogueTurn(DialogueRole.USER, "u"),
                    DialogueTurn(DialogueRole.ASSISTANT, "a")
                ),
                pendingInputCount = 0,
                pendingThoughtCount = 1,
                pendingActionCount = 0
            )
        )

        val action = assertIs<EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.ANSWER, action.actionType)
        assertEquals("payload", action.payload)
        assertEquals("summary-", action.summary)
        assertEquals("thought", llm.lastOptions.metadata.callSite)
    }

    @Test
    fun `planner converts invalid payload and parse failures to noop`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"action","action_type":"answer"}""")
        llm.enqueueRawResponse("not-json")
        val instrumentation = RecordingInstrumentation()
        val planner = EgoPlanner(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation
        )
        val trigger = EgoTrigger.IncomingInput(PendingInput(2, "hello"))
        val snapshot = AgentSnapshot(emptyList(), 0, 0, 0)

        val invalidAction = planner.decide(trigger, snapshot)
        assertIs<EgoDecision.Noop>(invalidAction)
        assertTrue(invalidAction.reason.contains("invalid action", ignoreCase = true))

        val invalidJson = planner.decide(trigger, snapshot)
        assertIs<EgoDecision.Noop>(invalidJson)
        assertTrue(invalidJson.reason.contains("non-parseable", ignoreCase = true))
        assertTrue(instrumentation.events.any { it.type == "warning" })
    }

    @Test
    fun `planner trims oversized prompt before sending to model`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = EgoPlanner(
            modelClient = llm,
            config = AgentConfig(maxPromptTokens = 1)
        )

        val snapshot = AgentSnapshot(
            recentDialogue = List(15) { idx ->
                DialogueTurn(
                    role = if (idx % 2 == 0) DialogueRole.USER else DialogueRole.ASSISTANT,
                    content = "content-$idx-" + "x".repeat(40)
                )
            },
            pendingInputCount = 5,
            pendingThoughtCount = 4,
            pendingActionCount = 3
        )
        planner.decide(EgoTrigger.IncomingInput(PendingInput(1, "ask")), snapshot)

        assertTrue(llm.lastMessages.isNotEmpty())
        assertEquals(2, llm.lastMessages.size)
        assertTrue(llm.lastMessages.all { it.role == ChatRole.SYSTEM })
        assertIs<ChatMessage>(llm.lastMessages.first())
    }
}
