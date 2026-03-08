package psyke.agent

import psyke.support.StubChatModelClient
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MetaReasonerTest {

    private fun buildReasoner(response: String, maxTokens: Int = 88): Pair<LlmMetaReasoner, StubChatModelClient> {
        val llm = StubChatModelClient().apply { enqueueRawResponse(response) }
        val reasoner = LlmMetaReasoner(
            modelClient = llm,
            config = AgentConfig(
                metaReasoner = MetaReasonerConfig(
                    maxTokens = maxTokens,
                    dynamicCompletionEnabled = false
                )
            )
        )
        return reasoner to llm
    }

    private fun defaultContext() = PlannerContext(
        recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "question")),
        queue = QueueSnapshot(0, 1, 0),
        deliberation = DeliberationState(
            stepIndex = 20,
            decisionPressure = 0.75,
            staleStreak = 3,
            progressScore = 0.4,
            denialCount = 0,
            stepsSinceNewEvidence = 4,
            repeatSignatureHits = 1,
            noopStreak = 1
        )
    )

    private fun thoughtTrigger() = psyke.agent.core.EgoTrigger.PendingThoughtInput(
        PendingThought(id = 1, urgency = Urgency.MEDIUM, content = "keep thinking")
    )

    private fun inputTrigger() = psyke.agent.core.EgoTrigger.IncomingInput(
        PendingInput(id = 1, content = "what is 2+2?")
    )

    @Test
    fun `meta reasoner parses finalize verdict`() {
        val (reasoner, llm) = buildReasoner(
            """{"verdict":"finalize_now","confidence":0.87,"reason":"stale loop and high pressure"}"""
        )

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.FINALIZE_NOW, assessment.verdict)
        assertTrue(assessment.confidence > 0.8)
        assertEquals("meta_reasoner", llm.lastOptions.metadata.callSite)
        assertEquals(88, llm.lastOptions.maxTokens)
        assertIs<psyke.llm.ChatResponseFormat.JsonSchema>(llm.lastOptions.responseFormat)
    }

    @Test
    fun `meta reasoner parses continue verdict`() {
        val (reasoner, _) = buildReasoner(
            """{"verdict":"continue","confidence":0.9,"reason":"reasoning still productive"}"""
        )

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
        assertTrue(assessment.confidence >= 0.8)
    }

    @Test
    fun `meta reasoner parses continue_with_constraints verdict`() {
        val (reasoner, _) = buildReasoner(
            """{"verdict":"continue_with_constraints","confidence":0.7,"reason":"loop degrading, constrain next steps"}"""
        )

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE_WITH_CONSTRAINTS, assessment.verdict)
        assertTrue(assessment.confidence >= 0.6)
        assertTrue(assessment.reason.isNotBlank())
    }

    @Test
    fun `meta reasoner parses request_tool_then_finalize verdict`() {
        val (reasoner, _) = buildReasoner(
            """{"verdict":"request_tool_then_finalize","confidence":0.8,"reason":"one external lookup will resolve the question"}"""
        )

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE, assessment.verdict)
        assertTrue(assessment.confidence >= 0.7)
    }

    @Test
    fun `meta reasoner falls back to CONTINUE on malformed JSON`() {
        val (reasoner, _) = buildReasoner("this is not json at all {{{")

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
        assertTrue(assessment.confidence < 0.5, "Low confidence expected on parse fallback, got ${assessment.confidence}")
        assertTrue(assessment.reason.isNotBlank())
    }

    @Test
    fun `meta reasoner falls back to CONTINUE on empty response`() {
        val (reasoner, _) = buildReasoner("")

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
    }

    @Test
    fun `meta reasoner falls back to CONTINUE on unknown verdict string`() {
        val (reasoner, _) = buildReasoner(
            """{"verdict":"do_something_weird","confidence":0.5,"reason":"unknown"}"""
        )

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
    }

    @Test
    fun `meta reasoner confidence is clamped to valid range`() {
        val (reasoner, _) = buildReasoner(
            """{"verdict":"finalize_now","confidence":9.99,"reason":"over the limit"}"""
        )

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertTrue(assessment.confidence <= 1.0, "Confidence must be clamped to 1.0, got ${assessment.confidence}")
        assertTrue(assessment.confidence >= 0.0, "Confidence must be non-negative")
    }

    @Test
    fun `meta reasoner works with IncomingInput trigger`() {
        val (reasoner, _) = buildReasoner(
            """{"verdict":"continue","confidence":0.95,"reason":"fresh input"}"""
        )

        val assessment = reasoner.assess(trigger = inputTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
    }

    @Test
    fun `meta reasoner actor is always ego`() {
        val (reasoner, llm) = buildReasoner(
            """{"verdict":"continue","confidence":0.9,"reason":"ok"}"""
        )

        reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals("ego", llm.lastOptions.metadata.actor)
    }

    // ── Truncation-tolerant parsing tests ──

    @Test
    fun `meta reasoner salvages finalize_now from truncated JSON`() {
        val truncated = """{"verdict":"finalize_now","confidence":0.97,"reason":"Sufficient info to"""
        val (reasoner, _) = buildReasoner(truncated)

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.FINALIZE_NOW, assessment.verdict)
        assertTrue(assessment.confidence >= 0.9, "Should extract confidence from truncated JSON, got ${assessment.confidence}")
    }

    @Test
    fun `meta reasoner salvages request_tool_then_finalize from truncated JSON`() {
        val truncated = """{"verdict":"request_tool_then_finalize","confidence":0.85,"rea"""
        val (reasoner, _) = buildReasoner(truncated)

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE, assessment.verdict)
        assertTrue(assessment.confidence >= 0.8)
    }

    @Test
    fun `meta reasoner does not salvage truncated continue verdict`() {
        // A truncated "continue" is worthless — fallback to standard parse fallback
        val truncated = """{"verdict":"continue","confidence":0.8,"reason":"still produc"""
        val (reasoner, _) = buildReasoner(truncated)

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
        // Should still be low confidence because it's a fallback, not a salvage
        assertTrue(assessment.confidence <= 0.3, "Should be fallback confidence, got ${assessment.confidence}")
    }

    @Test
    fun `meta reasoner salvages verdict even without confidence field`() {
        val truncated = """{"verdict":"finalize_now","rea"""
        val (reasoner, _) = buildReasoner(truncated)

        val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.FINALIZE_NOW, assessment.verdict)
        assertEquals(0.6, assessment.confidence, 0.01) // default when confidence not extractable
    }

    @Test
    fun `meta reasoner empty-content transport failures trip circuit breaker`() {
        var callCount = 0
        val failing = object : ChatModelClient {
            override val modelName: String = "failing-model"
            override fun chat(messages: List<psyke.llm.ChatMessage>, options: ChatRequestOptions): psyke.llm.ChatCompletion {
                callCount += 1
                throw IllegalStateException(
                    "Groq chat returned empty message content (finish_reason=length, content_chars=0)."
                )
            }
        }
        val reasoner = LlmMetaReasoner(
            modelClient = failing,
            config = AgentConfig(
                planner = PlannerConfig(llmRetryAttempts = 1),
                metaReasoner = MetaReasonerConfig(dynamicCompletionEnabled = false)
            )
        )

        repeat(3) {
            val assessment = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())
            assertEquals(MetaReasonerVerdict.CONTINUE, assessment.verdict)
        }
        val tripped = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertTrue(tripped.reason.contains("circuit breaker tripped", ignoreCase = true))
        assertEquals(3, callCount, "Once tripped, meta reasoner should bypass LLM calls.")
    }

    @Test
    fun `meta reasoner retries with fallback model after repeated empty-content failures`() {
        var primaryCalls = 0
        val primary = object : ChatModelClient {
            override val modelName: String = "primary-model"
            override fun chat(messages: List<psyke.llm.ChatMessage>, options: ChatRequestOptions): psyke.llm.ChatCompletion {
                primaryCalls += 1
                throw IllegalStateException(
                    "OpenAI chat returned empty message content (finish_reason=length, content_chars=0)."
                )
            }
        }
        val fallback = StubChatModelClient(modelName = "fallback-model").apply {
            enqueueRawResponse("""{"verdict":"finalize_now","confidence":0.9,"reason":"fallback resolved decision"}""")
        }
        val reasoner = LlmMetaReasoner(
            modelClient = primary,
            config = AgentConfig(
                planner = PlannerConfig(llmRetryAttempts = 1),
                metaReasoner = MetaReasonerConfig(dynamicCompletionEnabled = false)
            ),
            fallbackModelClient = fallback
        )

        val first = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())
        val second = reasoner.assess(trigger = thoughtTrigger(), context = defaultContext())

        assertEquals(MetaReasonerVerdict.CONTINUE, first.verdict)
        assertEquals(MetaReasonerVerdict.FINALIZE_NOW, second.verdict)
        assertEquals(2, primaryCalls)
        assertEquals("meta_reasoner_fallback", fallback.lastOptions.metadata.callSite)
    }
}
