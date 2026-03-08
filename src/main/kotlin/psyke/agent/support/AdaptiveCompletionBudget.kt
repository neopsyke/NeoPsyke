package psyke.agent.support

import psyke.llm.ChatMessage
import kotlin.math.roundToInt

object AdaptiveCompletionBudget {
    data class Request(
        val messages: List<ChatMessage>,
        val baseMaxTokens: Int,
        val hardMaxTokens: Int,
        val promptToCompletionRatio: Double,
        val minPromptTokensForScaling: Int,
        val modelTokenWeight: Double,
        val modelContextWindow: Int? = null,
    )

    data class Resolution(
        val budget: Int,
        val contextClamped: Boolean,
        val promptEstimate: Int,
    )

    fun resolve(request: Request): Int = resolveDetailed(request).budget

    fun resolveDetailed(request: Request): Resolution {
        val base = request.baseMaxTokens.coerceAtLeast(MIN_BASE_TOKENS)
        val hardMax = request.hardMaxTokens.coerceAtLeast(base)
        val promptEstimate = estimatePromptTokens(request.messages)

        val desired = if (promptEstimate < request.minPromptTokensForScaling.coerceAtLeast(1)) {
            base
        } else {
            val normalizedRatio = request.promptToCompletionRatio.coerceIn(MIN_RATIO, MAX_RATIO)
            val normalizedWeight = request.modelTokenWeight.coerceIn(MIN_MODEL_WEIGHT, MAX_MODEL_WEIGHT)
            val scaledExtra = ((promptEstimate * normalizedRatio) / normalizedWeight).roundToInt()
            (base + scaledExtra).coerceIn(base, hardMax)
        }

        val contextWindow = request.modelContextWindow
        if (contextWindow != null && contextWindow > 0) {
            val available = contextWindow - promptEstimate - CONTEXT_SAFETY_MARGIN
            if (available < MIN_VIABLE_COMPLETION_TOKENS) {
                return Resolution(
                    budget = MIN_VIABLE_COMPLETION_TOKENS,
                    contextClamped = true,
                    promptEstimate = promptEstimate
                )
            }
            if (desired > available) {
                return Resolution(
                    budget = available,
                    contextClamped = true,
                    promptEstimate = promptEstimate
                )
            }
        }

        return Resolution(
            budget = desired,
            contextClamped = false,
            promptEstimate = promptEstimate
        )
    }

    fun estimatePromptTokens(messages: List<ChatMessage>): Int {
        if (messages.isEmpty()) return MIN_PROMPT_ESTIMATE_TOKENS
        val estimate = messages.sumOf { msg ->
            TextSecurity.estimateTokens(msg.content) + PER_MESSAGE_OVERHEAD_TOKENS
        }
        return estimate.coerceAtLeast(MIN_PROMPT_ESTIMATE_TOKENS)
    }

    private const val MIN_BASE_TOKENS: Int = 32
    private const val PER_MESSAGE_OVERHEAD_TOKENS: Int = 4
    private const val MIN_PROMPT_ESTIMATE_TOKENS: Int = 24
    private const val MIN_RATIO: Double = 0.0
    private const val MAX_RATIO: Double = 0.75
    private const val MIN_MODEL_WEIGHT: Double = 0.25
    private const val MAX_MODEL_WEIGHT: Double = 4.0
    private const val CONTEXT_SAFETY_MARGIN: Int = 64
    private const val MIN_VIABLE_COMPLETION_TOKENS: Int = 16
}
