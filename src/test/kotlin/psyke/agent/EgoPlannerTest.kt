package psyke.agent

import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
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
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(
                    pendingInputCount = 1,
                    pendingThoughtCount = 2,
                    pendingActionCount = 3
                )
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
            context = PlannerContext(
                recentDialogue = listOf(
                    DialogueTurn(DialogueRole.USER, "u"),
                    DialogueTurn(DialogueRole.ASSISTANT, "a")
                ),
                queue = QueueSnapshot(
                    pendingInputCount = 0,
                    pendingThoughtCount = 1,
                    pendingActionCount = 0
                )
            )
        )

        val action = assertIs<EgoDecision.ProposeAction>(decision)
        assertEquals(ActionType.ANSWER, action.actionType)
        assertEquals("payload", action.payload)
        assertEquals("summary-", action.summary)
        assertEquals("thought", llm.lastOptions.metadata.callSite)
    }

    @Test
    fun `planner rejects actions unavailable at runtime`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "decision":"action",
              "urgency":"medium",
              "action_type":"mcp_fetch",
              "action_payload":"{\"url\":\"https://example.com\"}",
              "action_summary":"fetch page"
            }
            """.trimIndent()
        )
        val planner = EgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )

        val decision = planner.decide(
            trigger = EgoTrigger.IncomingInput(PendingInput(1, "fetch this page")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.ANSWER, ActionType.WEB_SEARCH)
            )
        )

        val noop = assertIs<EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("unavailable", ignoreCase = true))
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
        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0)
        )

        val invalidAction = planner.decide(trigger, context)
        assertIs<EgoDecision.Noop>(invalidAction)
        assertTrue(invalidAction.reason.contains("invalid action", ignoreCase = true))

        val invalidJson = planner.decide(trigger, context)
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
            config = AgentConfig(maxPromptTokens = 120)
        )

        val context = PlannerContext(
            recentDialogue = List(15) { idx ->
                DialogueTurn(
                    role = if (idx % 2 == 0) DialogueRole.USER else DialogueRole.ASSISTANT,
                    content = "content-$idx-" + "x".repeat(40)
                )
            },
            queue = QueueSnapshot(
                pendingInputCount = 5,
                pendingThoughtCount = 4,
                pendingActionCount = 3
            )
        )
        planner.decide(EgoTrigger.IncomingInput(PendingInput(1, "ask")), context)

        assertTrue(llm.lastMessages.isNotEmpty())
        assertTrue(llm.lastMessages.any { it.role == ChatRole.USER && it.content.contains("Trigger:") })
        val estimatedPromptTokens = llm.lastMessages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 120)
        assertIs<ChatMessage>(llm.lastMessages.first())
    }

    @Test
    fun `planner includes memory summary in prompt context`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("""{"decision":"noop","reason":"done"}""")
        val planner = EgoPlanner(
            modelClient = llm,
            config = AgentConfig()
        )
        val context = PlannerContext(
            recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "hello")),
            queue = QueueSnapshot(
                pendingInputCount = 1,
                pendingThoughtCount = 0,
                pendingActionCount = 0
            ),
            memorySummary = "Compressed memory:\n- user likes concise answers"
        )

        planner.decide(EgoTrigger.IncomingInput(PendingInput(1, "question")), context)

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Memory summary:"))
        assertTrue(prompt.contains("user likes concise answers"))
    }

    @Test
    fun `planner falls back to noop when model call fails`() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ) = throw IllegalStateException("planner unavailable")
        }
        val instrumentation = RecordingInstrumentation()
        val planner = EgoPlanner(
            modelClient = failingClient,
            config = AgentConfig(),
            instrumentation = instrumentation
        )

        val decision = planner.decide(
            trigger = EgoTrigger.IncomingInput(PendingInput(1, "hello")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0)
            )
        )

        val noop = assertIs<EgoDecision.Noop>(decision)
        assertTrue(noop.reason.contains("unavailable", ignoreCase = true))
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    (it.data["message"] as? String)?.contains("Planner call failed", ignoreCase = true) == true
            }
        )
    }
}
