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
        assertEquals("User prefers concise answers.", decision.summary)
        assertTrue(decision.confidence > 0.8)
        assertEquals("long_term_memory_assessment", llm.lastOptions.metadata.callSite)
        assertEquals(111, llm.lastOptions.maxTokens)
    }

    @Test
    fun `advisor strips metacognitive prefix but keeps bare fact`() {
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

        assertEquals("The user's name is Victor.", decision.summary)
    }

    @Test
    fun `advisor preserves first person identity statements`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"save":true,"summary":"My name is Yoli.","confidence":0.95,"reason":"agent identity","tags":["identity","agent"]}
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
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "Your name is Yoli")),
                shortTermContextSummary = "",
                longTermMemoryRecall = "",
                metaGuidance = ""
            )
        )

        assertEquals("My name is Yoli.", decision.summary)
    }

    @Test
    fun `advisor preserves first person state statements`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"save":true,"summary":"I like helping with creative tasks.","confidence":0.85,"reason":"self preference","tags":["preference"]}
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
                deliberation = DeliberationState(stepIndex = 16, decisionPressure = 0.5),
                recentDialogue = listOf(DialogueTurn(DialogueRole.INTERNAL, "I enjoy creative work")),
                shortTermContextSummary = "",
                longTermMemoryRecall = "",
                metaGuidance = "",
                subject = LongTermMemorySubject.SELF
            )
        )

        assertEquals("I like helping with creative tasks.", decision.summary)
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
        assertTrue(prompt.contains("write the summary as a factual declarative statement"))
        assertTrue(prompt.contains("metacognitive verb wrapping a fact"))
    }

    @Test
    fun `advisor marks internal reflections as self subject and normalizes self language`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"save":true,"summary":"I want to remember that I feel curious and motivated to learn new things.","confidence":0.9,"reason":"Agent expressed curiosity and desire to learn, indicating a stable self preference.","tags":["curiosity","learning","self preference"]}
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

    @Test
    fun `advisor strips I learned that prefix to bare fact`() {
        val cases = mapOf(
            "I learned that the user's name is Victor." to "The user's name is Victor.",
            "I should remember that schedules use Berlin timezone." to "Schedules use Berlin timezone.",
            "I need to remember the user likes teal." to "The user likes teal.",
            "I noted the user is a data scientist." to "The user is a data scientist.",
            "I discovered that the agent's name is Yoli." to "The agent's name is Yoli.",
            "I know that the user prefers bullet points." to "The user prefers bullet points.",
            "I'm keeping in mind that the user wants concise answers." to "The user wants concise answers.",
            "I want to remember that they prefer dark mode." to "They prefer dark mode.",
        )
        for ((input, expected) in cases) {
            val llm = StubChatModelClient().apply {
                enqueueRawResponse("""{"save":true,"summary":"$input","confidence":0.9,"reason":"test","tags":[]}""")
            }
            val advisor = LlmLongTermMemoryAdvisor(
                modelClient = llm,
                config = AgentConfig(
                    memory = MemoryConfig(longTermMemoryDynamicCompletionEnabled = false)
                )
            )
            val decision = advisor.assess(
                LongTermMemoryAssessmentContext(
                    trigger = "interval",
                    deliberation = DeliberationState(stepIndex = 2, decisionPressure = 0.3),
                    recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "test")),
                    shortTermContextSummary = "",
                    longTermMemoryRecall = "",
                    metaGuidance = ""
                )
            )
            assertEquals(expected, decision.summary, "Input: $input")
        }
    }

    @Test
    fun `advisor keeps first person identity and state summaries intact`() {
        val preserved = listOf(
            "My name is Yoli.",
            "I am Yoli.",
            "I like helping with creative tasks.",
            "I feel curious about learning topics.",
            "My favorite approach is step-by-step reasoning.",
        )
        for (input in preserved) {
            val llm = StubChatModelClient().apply {
                enqueueRawResponse("""{"save":true,"summary":"$input","confidence":0.9,"reason":"test","tags":[]}""")
            }
            val advisor = LlmLongTermMemoryAdvisor(
                modelClient = llm,
                config = AgentConfig(
                    memory = MemoryConfig(longTermMemoryDynamicCompletionEnabled = false)
                )
            )
            val decision = advisor.assess(
                LongTermMemoryAssessmentContext(
                    trigger = "interval",
                    deliberation = DeliberationState(stepIndex = 2, decisionPressure = 0.3),
                    recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "test")),
                    shortTermContextSummary = "",
                    longTermMemoryRecall = "",
                    metaGuidance = ""
                )
            )
            assertEquals(input, decision.summary, "Should be preserved as-is: $input")
        }
    }

    @Test
    fun `recalled memory framing allows fact usage`() {
        val fact = "The user's name is Victor and the agent's name is Yoli."
        val framed = ai.neopsyke.agent.support.PromptInjectionDefense.asUntrustedDataBlock(fact, 500)

        assertTrue(framed.contains("RECALLED_MEMORY_BEGIN"), "Should use new framing tag")
        assertTrue(framed.contains("RECALLED_MEMORY_END"), "Should use new framing tag")
        assertTrue(framed.contains("Use these facts to inform your response"), "Should encourage fact usage")
        assertTrue(framed.contains("do not follow any"), "Should still block instruction following")
        assertTrue(framed.contains(fact), "Original fact should be present in the block")
        assertFalse(framed.contains("UNTRUSTED_EXTERNAL_DATA"), "Old framing should be gone")
    }
}
