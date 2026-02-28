package psyke.agent

data class MemoryRecallQuery(
    val cue: String,
    val recentDialogue: List<DialogueTurn> = emptyList(),
    val memorySummary: String = "",
    val maxItems: Int = 4,
    val maxChars: Int = 1200,
)

data class MemoryRecall(
    val provider: String,
    val text: String,
    val hitCount: Int = 0,
    val truncated: Boolean = false,
)

data class MemoryImprint(
    val summary: String,
    val source: String = "ego_memory_consolidation",
    val confidence: Double = 0.5,
    val tags: List<String> = emptyList(),
)

interface Hippocampus : AutoCloseable {
    val providerName: String
    val enabled: Boolean
        get() = true

    fun recall(query: MemoryRecallQuery): MemoryRecall

    fun imprint(imprint: MemoryImprint): Boolean = false

    fun imprint(turn: DialogueTurn): Boolean {
        val normalized = turn.content.trim()
        if (normalized.isBlank()) {
            return false
        }
        return imprint(
            MemoryImprint(
                summary = normalized,
                source = "dialogue_turn_${turn.role.name.lowercase()}"
            )
        )
    }

    override fun close() {}
}

object NoopHippocampus : Hippocampus {
    override val providerName: String = "none"
    override val enabled: Boolean = false

    override fun recall(query: MemoryRecallQuery): MemoryRecall =
        MemoryRecall(provider = providerName, text = "", hitCount = 0, truncated = false)

    override fun imprint(imprint: MemoryImprint): Boolean = false
}
