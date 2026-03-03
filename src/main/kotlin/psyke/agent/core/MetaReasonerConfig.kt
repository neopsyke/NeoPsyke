package psyke.agent.core

data class MetaReasonerConfig(
    val deliberationPressureAssessmentMinStep: Int = 16,
    val deliberationPressureAssessmentEverySteps: Int = 8,
    val deliberationPressureAssessmentThreshold: Double = 0.68,
    val cooldownSteps: Int = 6,
    val maxTokens: Int = 200,
    val forcedTerminalPressureThreshold: Double = 0.98,
    val forcedTerminalStaleStreakThreshold: Int = 8,
)
