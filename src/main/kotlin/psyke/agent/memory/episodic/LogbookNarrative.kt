package psyke.agent.memory.episodic

internal object LogbookNarrative {
    fun normalizeSummary(eventType: EpisodicEventType, rawSummary: String): String {
        val summary = rawSummary.trim()
        if (summary.isBlank()) return ""
        return when (eventType) {
            EpisodicEventType.INPUT_RECEIVED -> normalizeUserInput(summary)
            EpisodicEventType.MEMORY_IMPRINT -> normalizeMemoryImprint(summary)
            EpisodicEventType.SELF_INITIATED -> normalizeSelfInitiated(summary)
            else -> summary
        }
    }

    private fun normalizeUserInput(summary: String): String {
        val body = USER_PREFIX_REGEX.replace(summary, "").trim()
        return "$USER_PREFIX$body"
    }

    private fun normalizeMemoryImprint(summary: String): String {
        if (LESSON_PREFIX_REGEX.containsMatchIn(summary)) {
            val lessonBody = LESSON_PREFIX_REGEX.replace(summary, "").trim()
            return if (lessonBody.isBlank()) {
                FIRST_PERSON_LESSON_FALLBACK
            } else {
                "$FIRST_PERSON_LESSON_PREFIX$lessonBody"
            }
        }
        if (FIRST_PERSON_PREFIX_REGEX.containsMatchIn(summary)) return summary
        return "$FIRST_PERSON_MEMORY_PREFIX$summary"
    }

    private fun normalizeSelfInitiated(summary: String): String {
        if (FIRST_PERSON_PREFIX_REGEX.containsMatchIn(summary)) return summary
        return "$FIRST_PERSON_MEMORY_PREFIX$summary"
    }

    private const val USER_PREFIX: String = "User: "
    private const val FIRST_PERSON_MEMORY_PREFIX: String = "I learned: "
    private const val FIRST_PERSON_LESSON_PREFIX: String = "I learned a lesson: "
    private const val FIRST_PERSON_LESSON_FALLBACK: String = "I learned a lesson."
    private val USER_PREFIX_REGEX = Regex("^user\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val LESSON_PREFIX_REGEX = Regex("^lesson\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val FIRST_PERSON_PREFIX_REGEX = Regex("^i\\b", RegexOption.IGNORE_CASE)
}
