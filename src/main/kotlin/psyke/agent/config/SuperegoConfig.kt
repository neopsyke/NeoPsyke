package psyke.agent.config

data class SuperegoConfig(
    val maxCompletionTokens: Int = 192,
    val dynamicCompletionEnabled: Boolean = true,
    val dynamicCompletionHardMaxTokens: Int = 640,
    val dynamicPromptToCompletionRatio: Double = 0.10,
    val dynamicCompletionMinPromptTokens: Int = 160,
    val twoStageReviewEnabled: Boolean = false,
    val twoStageLowConfidenceThreshold: Double = 0.60,
    val twoStageEscalateOnMediumPolicyRisk: Boolean = true,
    val twoStageSkipForAnswerActions: Boolean = true,
    val twoStageSkipForWebSearchActions: Boolean = true,
)
