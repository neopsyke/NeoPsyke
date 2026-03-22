package ai.neopsyke.agent.memory.longterm

/**
 * Extracts keywords from text content for episodic logbook entries.
 */
interface LogbookSummarizer {
    fun extractKeywords(text: String): List<String>
    fun summarizeInput(content: String, maxChars: Int): String
}
