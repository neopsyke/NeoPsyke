package ai.neopsyke.agent.config

data class MetaReasonerConfig(
    val deliberationPressureAssessmentMinStep: Int = 16,
    val deliberationPressureAssessmentEverySteps: Int = 8,
    val deliberationPressureAssessmentThreshold: Double = 0.68,
    val cooldownSteps: Int = 6,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val forcedTerminalPressureThreshold: Double = 0.98,
    val forcedTerminalStaleStreakThreshold: Int = 8,
    val forcedTerminalNoopStreakThreshold: Int = 6,
    val reasonMaxChars: Int = 500,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS: Int = 2048
    }
}
