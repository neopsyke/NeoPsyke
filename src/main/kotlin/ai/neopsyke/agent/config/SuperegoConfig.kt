package ai.neopsyke.agent.config

data class SuperegoConfig(
    val maxCompletionTokens: Int = DEFAULT_MAX_COMPLETION_TOKENS,
    val twoStageReviewEnabled: Boolean = false,
    val twoStageLowConfidenceThreshold: Double = 0.60,
    val twoStageEscalateOnMediumPolicyRisk: Boolean = true,
    val twoStageSkipForContactUserActions: Boolean = true,
    val twoStageSkipForWebSearchActions: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_COMPLETION_TOKENS: Int = 1024
    }
}
