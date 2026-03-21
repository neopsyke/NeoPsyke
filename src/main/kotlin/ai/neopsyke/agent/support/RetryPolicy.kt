package ai.neopsyke.agent.support

object RetryPolicy {
    const val MAX_LLM_RETRY_ATTEMPTS: Int = 3

    fun boundedLlmRetryAttempts(configuredAttempts: Int): Int =
        configuredAttempts.coerceIn(1, MAX_LLM_RETRY_ATTEMPTS)
}

