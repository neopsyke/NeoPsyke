package psyke.agent.core

data class MemoryConfig(
    val maxShortTermContextChars: Int = 20_000,
    val maxShortTermContextPromptTokens: Int = 384,
    val longTermMemoryRecallMaxItems: Int = 4,
    val longTermMemoryRecallMaxChars: Int = 1_200,
    val longTermMemoryAssessEverySteps: Int = 16,
    val longTermMemoryAssessCooldownSteps: Int = 8,
    val longTermMemoryMinConfidence: Double = 0.65,
    val longTermMemoryMaxTokens: Int = 180,
    val longTermMemoryMaxSummaryChars: Int = 320,
    val longTermMemoryForceAssessOnAllowedAction: Boolean = false,
    val longTermMemoryParseFallbackDisableAfter: Int = 2,
    val mcpMemoryCallTimeoutMs: Long = 8_000,
)
