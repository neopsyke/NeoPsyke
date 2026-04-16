package ai.neopsyke.agent.ego.planner

/**
 * Structured output mode for a lane's LLM response format.
 * - STRICT: send strict JSON schema; fall back to RELAXED on first parse failure.
 * - RELAXED: send relaxed JSON schema (no maxLength constraints).
 * - OFF: no responseFormat sent (free-text response expected).
 */
enum class StructuredOutputMode { STRICT, RELAXED, OFF }

/**
 * Per-lane LLM configuration. Null fields inherit from lane_defaults,
 * then from hardcoded fallbacks.
 */
data class LaneConfig(
    val provider: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxCompletionTokens: Int? = null,
    val retryAttempts: Int? = null,
    val structuredOutput: StructuredOutputMode? = null,
)

/**
 * Fully resolved lane configuration with no nulls.
 */
data class ResolvedLaneConfig(
    val provider: String?,
    val model: String?,
    val temperature: Double,
    val maxCompletionTokens: Int,
    val retryAttempts: Int,
    val structuredOutput: StructuredOutputMode,
) {
    companion object {
        const val DEFAULT_TEMPERATURE: Double = 0.2
        const val DEFAULT_MAX_COMPLETION_TOKENS: Int = 4096
        const val DEFAULT_RETRY_ATTEMPTS: Int = 2
        val DEFAULT_STRUCTURED_OUTPUT: StructuredOutputMode = StructuredOutputMode.STRICT
    }
}
