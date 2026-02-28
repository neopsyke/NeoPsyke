package psyke.eval

enum class ReasoningEvalMode(val id: String) {
    LOGIC("logic"),
    MODEL("model");

    companion object {
        fun parse(raw: String?): ReasoningEvalMode? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) {
                return null
            }
            return entries.firstOrNull { it.id == normalized }
        }
    }
}
