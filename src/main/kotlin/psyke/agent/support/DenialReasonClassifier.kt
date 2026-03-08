package psyke.agent.support

object DenialReasonClassifier {
    private const val TECHNICAL_REASON_CODE_PREFIX: String = "TECH_"
    private const val POLICY_REASON_CODE_PREFIX: String = "POLICY_"
    private val technicalSignals = listOf(
        "could not be parsed",
        "parse",
        "json",
        "timeout",
        "timed out",
        "unavailable",
        "model error",
        "transport",
        "http_",
        "empty response",
        "retry"
    )

    fun isLikelyTechnical(reasonCode: String?, reason: String?): Boolean {
        val normalizedCode = reasonCode?.trim()?.uppercase().orEmpty()
        if (normalizedCode.startsWith(TECHNICAL_REASON_CODE_PREFIX)) {
            return true
        }
        // Deterministic policy denials (POLICY_*) are hard validation errors, never transient.
        // Skip the text heuristic which can misclassify them (e.g. "json" in reason text).
        if (normalizedCode.startsWith(POLICY_REASON_CODE_PREFIX)) {
            return false
        }
        return isLikelyTechnical(reason)
    }

    fun isLikelyTechnical(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return false
        }
        return technicalSignals.any { normalized.contains(it) }
    }
}
