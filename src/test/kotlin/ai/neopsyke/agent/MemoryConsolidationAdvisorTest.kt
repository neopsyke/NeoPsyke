package ai.neopsyke.agent

import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.RecordingInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals("I learned: User prefers concise answers.", decision.summary)
        assertTrue(decision.confidence > 0.8)
        assertEquals("long_term_memory_assessment", llm.lastOptions.metadata.callSite)
        assertEquals(111, llm.lastOptions.maxTokens)
    }

    @Test
    fun `advisor preserves first person memory summaries`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"save":true,"summary":"I should remember that the user's name is Victor.","confidence":0.91,"reason":"durable identity fact","tags":["identity"]}
                """.trimIndent()
            )
        }
        val advisor = LlmLongTermMemoryAdvisor(
            modelClient = llm,
            config = AgentConfig(
                memory = MemoryConfig(
                    longTermMemoryDynamicCompletionEnabled = false
                )
            )
        )

        val decision = advisor.assess(
            LongTermMemoryAssessmentContext(
                trigger = "explicit_remember_intent",
                deliberation = DeliberationState(stepIndex = 1, decisionPressure = 0.15),
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "remember my name is Victor")),
                shortTermContextSummary = "",
                longTermMemoryRecall = "",
                metaGuidance = ""
            )
        )

        assertEquals("I should remember that the user's name is Victor.", decision.summary)
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

    @Test
    fun `advisor prompt requires first person saved summaries`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"save":false,"summary":"","confidence":0.2,"reason":"skip","tags":[]}""")
        }
        val advisor = LlmLongTermMemoryAdvisor(
            modelClient = llm,
            config = AgentConfig(
                memory = MemoryConfig(
                    longTermMemoryDynamicCompletionEnabled = false
                )
            )
        )

        advisor.assess(
            LongTermMemoryAssessmentContext(
                trigger = "interval",
                deliberation = DeliberationState(stepIndex = 2, decisionPressure = 0.20),
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "remember that I like concise answers")),
                shortTermContextSummary = "",
                longTermMemoryRecall = "",
                metaGuidance = ""
            )
        )

        val prompt = llm.lastMessages.first().content
        assertTrue(prompt.contains("write the summary in first person from the agent's perspective"))
        assertTrue(prompt.contains("Bad: \"User prefers concise answers.\""))
    }

    @Test
    fun `advisor marks internal reflections as self subject and normalizes self language`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"save":true,"summary":"I want to remember that I feel curious and motivated to learn new things.","confidence":0.9,"reason":"User expressed curiosity and desire to learn, indicating a stable preference.","tags":["curiosity","learning","user preference"]}
                """.trimIndent()
            )
        }
        val advisor = LlmLongTermMemoryAdvisor(
            modelClient = llm,
            config = AgentConfig(
                memory = MemoryConfig(
                    longTermMemoryDynamicCompletionEnabled = false
                )
            )
        )

        val decision = advisor.assess(
            LongTermMemoryAssessmentContext(
                trigger = "interval",
                deliberation = DeliberationState(stepIndex = 16, decisionPressure = 0.62),
                recentDialogue = listOf(DialogueTurn(DialogueRole.INTERNAL, "I want to explore engaging learning topics.")),
                shortTermContextSummary = "",
                longTermMemoryRecall = "",
                metaGuidance = "",
                subject = LongTermMemorySubject.SELF
            )
        )

        val prompt = llm.lastMessages.last().content
        assertTrue(prompt.contains("Memory subject:"))
        assertTrue(prompt.contains("subject=self"))
        assertTrue(decision.shouldSave)
        assertTrue(decision.reason.contains("self", ignoreCase = true) || decision.reason.contains("agent", ignoreCase = true))
        assertFalse(decision.reason.contains("User", ignoreCase = true))
        assertTrue(decision.tags.contains("self preference"))
        assertFalse(decision.tags.any { it.equals("user preference", ignoreCase = true) })
    }
}
