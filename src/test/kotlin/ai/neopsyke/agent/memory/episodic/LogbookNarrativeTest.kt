package ai.neopsyke.agent.memory.longterm

import kotlin.test.Test
import kotlin.test.assertEquals

class LogbookNarrativeTest {
    @Test
    fun `input received summaries keep canonical user prefix`() {
        assertEquals(
            "User: tell me about Kotlin",
            LogbookNarrative.normalizeSummary(EpisodicEventType.INPUT_RECEIVED, "user: tell me about Kotlin")
        )
    }

    @Test
    fun `memory imprint summaries preserve first person`() {
        assertEquals(
            "I should remember that the user's name is Victor.",
            LogbookNarrative.normalizeSummary(
                EpisodicEventType.MEMORY_IMPRINT,
                "I should remember that the user's name is Victor."
            )
        )
    }

    @Test
    fun `memory imprint summaries normalize lesson wrappers to first person`() {
        assertEquals(
            "I learned a lesson: Avoid repeating denied web_search actions.",
            LogbookNarrative.normalizeSummary(
                EpisodicEventType.MEMORY_IMPRINT,
                "LESSON: Avoid repeating denied web_search actions."
            )
        )
    }

    @Test
    fun `self initiated summaries are normalized to first person when missing`() {
        assertEquals(
            "I learned: Kotlin coroutines use structured concurrency by default.",
            LogbookNarrative.normalizeSummary(
                EpisodicEventType.SELF_INITIATED,
                "Kotlin coroutines use structured concurrency by default."
            )
        )
    }
}
