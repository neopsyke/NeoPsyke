package psyke.llm

import psyke.metrics.MetricsRuntime
import kotlin.math.max

data class LlmTokenBudgetConfig(
    val maxRunTotalTokens: Int = 0,
    val maxRunTokensPerProvider: Int = 0,
    val maxRunTokensPerRole: Int = 0,
)

class LlmTokenBudgetExceededException(
    message: String,
) : RuntimeException(message)

class LlmTokenBudgetGate(
    private val metricsRuntime: MetricsRuntime,
    private val config: LlmTokenBudgetConfig,
) {
    fun enforceChatCall(
        provider: String,
        role: String,
        messages: List<ChatMessage>,
        requestedMaxCompletionTokens: Int?,
    ) {
        enforceEstimatedCall(
            provider = provider,
            role = role,
            estimatedPromptTokens = estimatePromptTokens(messages),
            requestedMaxCompletionTokens = requestedMaxCompletionTokens
        )
    }

    fun enforceEstimatedCall(
        provider: String,
        role: String,
        estimatedPromptTokens: Int,
        requestedMaxCompletionTokens: Int?,
    ) {
        val snapshot = metricsRuntime.snapshot() ?: return
        if (config.maxRunTotalTokens <= 0 && config.maxRunTokensPerProvider <= 0 && config.maxRunTokensPerRole <= 0) {
            return
        }

        val estimatedCompletionTokens = max(
            MIN_COMPLETION_ESTIMATE_TOKENS,
            requestedMaxCompletionTokens ?: DEFAULT_COMPLETION_ESTIMATE_TOKENS
        )
        val estimatedTotalTokens = estimatedPromptTokens.coerceAtLeast(1) + estimatedCompletionTokens
        val normalizedProvider = provider.trim().ifBlank { "unknown" }
        val normalizedRole = role.trim().ifBlank { LlmRoleLabels.UNKNOWN }

        if (config.maxRunTotalTokens > 0) {
            assertWithinLimit(
                scope = "run_total",
                current = snapshot.runTotals.totalTokens,
                estimated = estimatedTotalTokens.toLong(),
                limit = config.maxRunTotalTokens.toLong(),
                provider = normalizedProvider,
                role = normalizedRole
            )
        }
        if (config.maxRunTokensPerProvider > 0) {
            val currentProvider = snapshot.runTokensByProvider[normalizedProvider] ?: 0L
            assertWithinLimit(
                scope = "run_provider",
                current = currentProvider,
                estimated = estimatedTotalTokens.toLong(),
                limit = config.maxRunTokensPerProvider.toLong(),
                provider = normalizedProvider,
                role = normalizedRole
            )
        }
        if (config.maxRunTokensPerRole > 0) {
            val currentRole = snapshot.runTokensByRole[normalizedRole] ?: 0L
            assertWithinLimit(
                scope = "run_role",
                current = currentRole,
                estimated = estimatedTotalTokens.toLong(),
                limit = config.maxRunTokensPerRole.toLong(),
                provider = normalizedProvider,
                role = normalizedRole
            )
        }
    }

    private fun assertWithinLimit(
        scope: String,
        current: Long,
        estimated: Long,
        limit: Long,
        provider: String,
        role: String,
    ) {
        if (current + estimated <= limit) {
            return
        }
        throw LlmTokenBudgetExceededException(
            "Token budget exceeded for scope=$scope provider=$provider role=$role " +
                "(current=$current estimated_next=$estimated limit=$limit)."
        )
    }

    private fun estimatePromptTokens(messages: List<ChatMessage>): Int {
        if (messages.isEmpty()) {
            return MIN_PROMPT_ESTIMATE_TOKENS
        }
        val estimate = messages.sumOf { message ->
            max(1, message.content.length / PROMPT_ESTIMATE_DIVISOR) + PER_MESSAGE_OVERHEAD_TOKENS
        }
        return max(MIN_PROMPT_ESTIMATE_TOKENS, estimate)
    }

    companion object {
        private const val PROMPT_ESTIMATE_DIVISOR: Int = 3
        private const val PER_MESSAGE_OVERHEAD_TOKENS: Int = 8
        private const val MIN_PROMPT_ESTIMATE_TOKENS: Int = 24
        private const val DEFAULT_COMPLETION_ESTIMATE_TOKENS: Int = 256
        private const val MIN_COMPLETION_ESTIMATE_TOKENS: Int = 64
    }
}

class TokenBudgetGuardedChatClient(
    private val delegate: ChatModelClient,
    private val budgetGate: LlmTokenBudgetGate,
    private val provider: String,
    private val role: String,
) : ChatModelClient {
    override val modelName: String
        get() = delegate.modelName

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        budgetGate.enforceChatCall(
            provider = provider,
            role = role,
            messages = messages,
            requestedMaxCompletionTokens = options.maxTokens
        )
        return delegate.chat(messages, options)
    }

    override fun close() {
        delegate.close()
    }
}
