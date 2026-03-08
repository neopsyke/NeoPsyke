package psyke.agent.support

object LlmFailureClassifier {
    fun isEmptyContentTransportFailure(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val normalized = current.message.orEmpty().trim().lowercase()
            if (EMPTY_CONTENT_MARKERS.any { normalized.contains(it) }) {
                return true
            }
            current = current.cause
            depth += 1
        }
        return false
    }

    private const val MAX_CAUSE_DEPTH: Int = 4
    private val EMPTY_CONTENT_MARKERS: Set<String> = setOf(
        "empty message content",
        "empty response body",
        "returned no choices"
    )
}
