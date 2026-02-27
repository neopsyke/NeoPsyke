package psyke.agent

import kotlin.math.max

data class MemoryStats(
    val totalChars: Int,
    val summaryChars: Int,
    val recentTurns: Int,
)

class MemoryStore(
    private val maxChars: Int,
    private val compactThresholdRatio: Double = 0.85,
    private val targetRatio: Double = 0.65,
) {
    private val recentTurns = ArrayDeque<DialogueTurn>()
    private var rolledSummary: String = ""

    init {
        require(maxChars >= 512) { "maxChars must be at least 512." }
    }

    @Synchronized
    fun remember(turn: DialogueTurn) {
        val normalized = TextSecurity.preview(turn.content, 700)
        if (normalized.isBlank()) {
            return
        }

        recentTurns.addLast(
            DialogueTurn(
                role = turn.role,
                content = normalized
            )
        )
        compactIfNeeded()
    }

    @Synchronized
    fun summaryForPrompt(maxTokens: Int): String {
        if (maxTokens <= 0) {
            return ""
        }
        return TextSecurity.clampToTokenBudget(buildMemoryText(), maxTokens)
    }

    @Synchronized
    fun stats(): MemoryStats =
        MemoryStats(
            totalChars = totalChars(),
            summaryChars = rolledSummary.length,
            recentTurns = recentTurns.size
        )

    private fun compactIfNeeded() {
        val compactThresholdChars = (maxChars * compactThresholdRatio).toInt()
        if (totalChars() < compactThresholdChars) {
            return
        }

        val targetChars = (maxChars * targetRatio).toInt()
        while (totalChars() > targetChars && recentTurns.size > 8) {
            foldOldestTurns(chunkSize = minOf(6, recentTurns.size - 8))
        }

        while (totalChars() > maxChars && recentTurns.size > 4) {
            recentTurns.removeFirst()
        }

        val remainingForSummary = max(0, maxChars - recentTurnsChars())
        rolledSummary = trimFromStart(rolledSummary, remainingForSummary)
    }

    private fun foldOldestTurns(chunkSize: Int) {
        if (chunkSize <= 0 || recentTurns.isEmpty()) {
            return
        }

        val chunk = mutableListOf<DialogueTurn>()
        repeat(chunkSize) {
            if (recentTurns.isNotEmpty()) {
                chunk.add(recentTurns.removeFirst())
            }
        }
        if (chunk.isEmpty()) {
            return
        }

        val compacted = chunk.joinToString(separator = " | ") {
            "${it.role.name.lowercase()}: ${TextSecurity.preview(it.content, 90)}"
        }
        val entry = "- $compacted"
        rolledSummary = if (rolledSummary.isBlank()) entry else "$rolledSummary\n$entry"
        val summaryCap = max(240, (maxChars * 0.60).toInt())
        rolledSummary = trimFromStart(rolledSummary, summaryCap)
    }

    private fun buildMemoryText(): String {
        if (rolledSummary.isBlank() && recentTurns.isEmpty()) {
            return ""
        }

        val recent = recentTurns.takeLast(8).joinToString(separator = "\n") {
            "- ${it.role.name.lowercase()}: ${TextSecurity.preview(it.content, 140)}"
        }
        return buildString {
            if (rolledSummary.isNotBlank()) {
                append("Compressed memory:\n")
                append(rolledSummary)
                append('\n')
            }
            if (recent.isNotBlank()) {
                append("Recent memory:\n")
                append(recent)
            }
        }.trim()
    }

    private fun totalChars(): Int = rolledSummary.length + recentTurnsChars()

    private fun recentTurnsChars(): Int = recentTurns.sumOf { it.content.length + 12 }

    private fun trimFromStart(text: String, maxChars: Int): String {
        if (maxChars <= 0) {
            return ""
        }
        if (text.length <= maxChars) {
            return text
        }
        val marker = "...(older memory omitted)\n"
        if (maxChars <= marker.length) {
            return text.takeLast(maxChars)
        }
        return marker + text.takeLast(maxChars - marker.length)
    }
}
