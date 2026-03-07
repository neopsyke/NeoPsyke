package psyke.agent

import psyke.support.StubChatModelClient
import psyke.support.RecordingInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongTermMemoryAdvisorTest {
    @Test
    fun `advisor parses save decision and summary`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"save":true,"summary":"User prefers concise answers.","confidence":0.88,"reason":"durable preference","tags":["preference","style"]}
                """.trimIndent()
            )
        }
        val advisor = LlmLongTermMemoryAdvisor(
            modelClient = llm,
            config = AgentConfig(
                memory = MemoryConfig(
                    longTermMemoryMaxTokens = 111,
                    longTermMemoryDynamicCompletionEnabled = false
                )
            )
        )

        val decision = advisor.assess(
            LongTermMemoryAssessmentContext(
                trigger = "interval",
                deliberation = DeliberationState(stepIndex = 8, decisionPressure = 0.42),
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "be concise")),
                shortTermContextSummary = "",
                longTermMemoryRecall = "",
                metaGuidance = ""
            )
        )

        assertTrue(decision.shouldSave)
        assertEquals("User prefers concise answers.", decision.summary)
        assertTrue(decision.confidence > 0.8)
        assertEquals("long_term_memory_assessment", llm.lastOptions.metadata.callSite)
        assertEquals(111, llm.lastOptions.maxTokens)
    }

    @Test
    fun `advisor compresses long dialogue and recall blocks before model call`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"save":false,"summary":"","confidence":0.9,"reason":"transient","tags":[]}""")
        }
        val instrumentation = RecordingInstrumentation()
        val advisor = LlmLongTermMemoryAdvisor(
            modelClient = llm,
            config = AgentConfig(
                memory = MemoryConfig(
                    longTermMemoryDynamicCompletionEnabled = false,
                    longTermMemoryPromptCompressionEnabled = true,
                    longTermMemoryPromptDialogueMaxChars = 220,
                    longTermMemoryPromptRecallMaxChars = 180
                )
            ),
            instrumentation = instrumentation
        )

        advisor.assess(
            LongTermMemoryAssessmentContext(
                trigger = "interval",
                deliberation = DeliberationState(stepIndex = 12, decisionPressure = 0.66),
                recentDialogue = listOf(
                    DialogueTurn(DialogueRole.USER, "u".repeat(600)),
                    DialogueTurn(DialogueRole.ASSISTANT, "a".repeat(600))
                ),
                shortTermContextSummary = "summary",
                longTermMemoryRecall = "r".repeat(1200),
                metaGuidance = ""
            )
        )

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("[...compressed for memory advisor;"))
        assertTrue(
            instrumentation.events.any { it.type == "memory_advisor_prompt_compressed" }
        )
    }
}
