package psyke.agent

import psyke.support.StubChatModelClient
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
            config = AgentConfig(memory = MemoryConfig(longTermMemoryMaxTokens = 111))
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
}
