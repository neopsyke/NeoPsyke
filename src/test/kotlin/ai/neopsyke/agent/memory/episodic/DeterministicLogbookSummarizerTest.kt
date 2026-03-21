package ai.neopsyke.agent.memory.episodic

import ai.neopsyke.agent.config.LogbookConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeterministicLogbookSummarizerTest {
    private val summarizer = DeterministicLogbookSummarizer(LogbookConfig())

    @Test
    fun `extractKeywords filters stopwords and short tokens`() {
        val keywords = summarizer.extractKeywords("the user asked about weather forecast today")
        assertTrue("weather" in keywords)
        assertTrue("forecast" in keywords)
        assertTrue("today" in keywords)
        assertTrue("the" !in keywords)
        assertTrue("about" !in keywords)
    }

    @Test
    fun `extractKeywords caps at configured limit`() {
        val config = LogbookConfig(maxKeywordsPerEntry = 3)
        val constrained = DeterministicLogbookSummarizer(config)
        val keywords = constrained.extractKeywords("alpha bravo charlie delta echo foxtrot golf")
        assertEquals(3, keywords.size)
    }

    @Test
    fun `extractKeywords returns empty for blank text`() {
        assertEquals(emptyList(), summarizer.extractKeywords(""))
        assertEquals(emptyList(), summarizer.extractKeywords("   "))
    }

    @Test
    fun `extractKeywords deduplicates tokens`() {
        val keywords = summarizer.extractKeywords("search search search results results")
        assertEquals(1, keywords.count { it == "search" })
        assertEquals(1, keywords.count { it == "results" })
    }

    @Test
    fun `summarizeInput prefixes with User and caps length`() {
        val summary = summarizer.summarizeInput("Hello world, this is a test input", maxChars = 20)
        assertTrue(summary.startsWith("User: "))
        assertTrue(summary.length <= "User: ".length + 20)
    }

    @Test
    fun `summarizeInput collapses whitespace`() {
        val summary = summarizer.summarizeInput("hello    world\n\ttesting", maxChars = 200)
        assertEquals("User: hello world testing", summary)
    }
}
