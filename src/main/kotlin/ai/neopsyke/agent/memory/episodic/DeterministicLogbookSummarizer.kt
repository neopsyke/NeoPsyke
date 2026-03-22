package ai.neopsyke.agent.memory.longterm

import ai.neopsyke.agent.config.LogbookConfig

/**
 * Deterministic keyword extraction and input summarization for logbook entries.
 * No LLM calls; extracts keywords by tokenization and stopword filtering.
 */
class DeterministicLogbookSummarizer(
    private val config: LogbookConfig,
) : LogbookSummarizer {

    override fun extractKeywords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text
            .lowercase()
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .split(WHITESPACE_REGEX)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_KEYWORD_LENGTH }
            .filter { it !in STOPWORDS }
            .distinct()
            .take(config.maxKeywordsPerEntry)
            .toList()
    }

    override fun summarizeInput(content: String, maxChars: Int): String {
        val preview = content
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val capped = if (preview.length <= maxChars) preview else preview.take(maxChars)
        return "User: $capped"
    }

    companion object {
        const val MIN_KEYWORD_LENGTH: Int = 3

        val NON_ALPHANUMERIC_REGEX: Regex = Regex("[^a-z0-9]+")
        val WHITESPACE_REGEX: Regex = Regex("\\s+")

        val STOPWORDS: Set<String> = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "can",
            "had", "her", "was", "one", "our", "out", "has", "have", "been",
            "were", "being", "been", "does", "did", "doing", "would", "could",
            "should", "will", "shall", "might", "must", "may", "with", "this",
            "that", "from", "they", "its", "also", "into", "more", "than",
            "then", "them", "these", "some", "what", "when", "where", "which",
            "while", "who", "whom", "how", "about", "each", "other", "just",
            "like", "over", "such", "very", "your", "here", "there",
        )
    }
}
