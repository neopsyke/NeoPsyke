package ai.neopsyke.agent.config

data class LogbookConfig(
    val enabled: Boolean = true,
    val maxSummaryChars: Int = 200,
    val maxKeywordsPerEntry: Int = 12,
    val maxEntriesPerQuery: Int = 20,
    val retentionDays: Int = 90,
    val dbPath: String = "",
    val episodicRecallMaxChars: Int = DEFAULT_EPISODIC_RECALL_MAX_CHARS,
    val episodicRecallMaxResults: Int = DEFAULT_EPISODIC_RECALL_MAX_RESULTS,
    val useLlmSummarizer: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_SUMMARY_CHARS: Int = 200
        const val DEFAULT_MAX_KEYWORDS_PER_ENTRY: Int = 12
        const val DEFAULT_MAX_ENTRIES_PER_QUERY: Int = 20
        const val DEFAULT_RETENTION_DAYS: Int = 90
        const val DEFAULT_EPISODIC_RECALL_MAX_CHARS: Int = 1200
        const val DEFAULT_EPISODIC_RECALL_MAX_RESULTS: Int = 15
    }
}
