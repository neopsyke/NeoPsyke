package ai.neopsyke.agent.ego.planner.runtime

import ai.neopsyke.llm.ChatCompletion

/**
 * Truncation detection heuristic and token-bump calculation.
 * Extracted from LlmEgoPlanner's truncation retry logic.
 */
object TruncationRetry {

    const val TRUNCATION_RETRY_MIN_TOKEN_BUMP: Int = 96
    const val TRUNCATION_RETRY_DIVISOR: Int = 2
    const val PLANNER_TRUNCATION_RETRY_HARD_MAX_TOKENS: Int = 1_600

    /**
     * Returns true if the completion appears truncated (finish_reason=length/max_tokens
     * or JSON object started but not closed).
     */
    fun isLikelyTruncated(response: ChatCompletion): Boolean {
        val finishReason = response.finishReason?.trim()?.lowercase().orEmpty()
        if (finishReason == "length" || finishReason == "max_tokens") {
            return true
        }
        val trimmed = response.content.trim()
        if (trimmed.isBlank()) return false
        return trimmed.startsWith("{") && !trimmed.endsWith("}")
    }

    /**
     * Calculate bumped completion budget for truncation retry.
     * Returns a value > baseMaxTokens, or baseMaxTokens if already at hard max.
     */
    fun bumpCompletionBudget(
        baseMaxTokens: Int,
        hardMaxTokens: Int = PLANNER_TRUNCATION_RETRY_HARD_MAX_TOKENS,
    ): Int =
        minOf(
            hardMaxTokens,
            baseMaxTokens + maxOf(
                TRUNCATION_RETRY_MIN_TOKEN_BUMP,
                baseMaxTokens / TRUNCATION_RETRY_DIVISOR,
            )
        )
}
