package psyke.agent.core

data class MetaReasonerConfig(
    val deliberationPressureAssessmentMinStep: Int = 16,
    val deliberationPressureAssessmentEverySteps: Int = 8,
    val deliberationPressureAssessmentThreshold: Double = 0.68,
    val cooldownSteps: Int = 6,
    val maxTokens: Int = 512,
    val dynamicCompletionEnabled: Boolean = true,
    val dynamicCompletionHardMaxTokens: Int = 640,
    val dynamicPromptToCompletionRatio: Double = 0.10,
    val dynamicCompletionMinPromptTokens: Int = 160,
    val forcedTerminalPressureThreshold: Double = 0.98,
    val forcedTerminalStaleStreakThreshold: Int = 8,
)
