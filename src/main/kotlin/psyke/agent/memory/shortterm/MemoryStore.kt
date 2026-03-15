package psyke.agent.memory.shortterm

import psyke.agent.model.DialogueTurn
import psyke.agent.support.TextSecurity
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
    companion object {
        /** Maximum chars stored per turn when adding to short-term memory. */
        const val TURN_CONTENT_MAX_CHARS: Int = 700
        /** Max chars per turn when folding old turns into the rolled summary. */
        const val FOLDED_TURN_PREVIEW_CHARS: Int = 90
        /** Minimum chars the rolled summary is allowed to occupy. */
        const val SUMMARY_MIN_CHARS: Int = 240
        /** Max chars per turn when building the recent-turns section of the prompt. */
        const val RECENT_TURN_PREVIEW_CHARS: Int = 140
        /** Fraction of maxChars used as the rolled-summary capacity cap. */
        const val SUMMARY_CAP_RATIO: Double = 0.60
    }
    private val recentTurns = ArrayDeque<DialogueTurn>()
    private var rolledSummary: String = ""
    private var recentTurnsCharCount: Int = 0

    init {
        require(maxChars >= 512) { "maxChars must be at least 512." }
    }

    @Synchronized
    fun remember(turn: DialogueTurn) {
        val normalized = TextSecurity.preview(turn.content, TURN_CONTENT_MAX_CHARS)
        if (normalized.isBlank()) {
            return
        }

        val newTurn = DialogueTurn(
            role = turn.role,
            content = normalized,
            sessionId = turn.sessionId,
            interlocutor = turn.interlocutor,
            timestamp = turn.timestamp
        )
        recentTurns.addLast(newTurn)
        recentTurnsCharCount += normalized.length + 12
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
            val removed = recentTurns.removeFirst()
            recentTurnsCharCount -= removed.content.length + 12
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
                val removed = recentTurns.removeFirst()
                recentTurnsCharCount -= removed.content.length + 12
                chunk.add(removed)
            }
        }
        if (chunk.isEmpty()) {
            return
        }

        val compacted = chunk.joinToString(separator = " | ") {
            "${it.role.name.lowercase()}: ${TextSecurity.preview(it.content, FOLDED_TURN_PREVIEW_CHARS)}"
        }
        val entry = "- $compacted"
        rolledSummary = if (rolledSummary.isBlank()) entry else "$rolledSummary\n$entry"
        val summaryCap = max(SUMMARY_MIN_CHARS, (maxChars * SUMMARY_CAP_RATIO).toInt())
        rolledSummary = trimFromStart(rolledSummary, summaryCap)
    }

    private fun buildMemoryText(): String {
        if (rolledSummary.isBlank() && recentTurns.isEmpty()) {
            return ""
        }

        val recent = recentTurns.takeLast(8).joinToString(separator = "\n") {
            "- ${it.role.name.lowercase()}: ${TextSecurity.preview(it.content, RECENT_TURN_PREVIEW_CHARS)}"
        }
        return buildString {
            if (rolledSummary.isNotBlank()) {
                append("Short-term context summary:\n")
                append(rolledSummary)
                append('\n')
            }
            if (recent.isNotBlank()) {
                append("Recent short-term context:\n")
                append(recent)
            }
        }.trim()
    }

    private fun totalChars(): Int = rolledSummary.length + recentTurnsChars()

    private fun recentTurnsChars(): Int = recentTurnsCharCount

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
