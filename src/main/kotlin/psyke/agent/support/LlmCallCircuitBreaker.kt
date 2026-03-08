package psyke.agent.support

enum class OnTripBehavior {
    /** Caller should bypass the LLM call entirely (use safe default). */
    BYPASS,
    /** Caller should allow the action (prevents denial loops in gatekeepers). */
    ALLOW,
    /** Caller should disable itself until reset. */
    DISABLE,
}

class LlmCallCircuitBreaker(
    private val tripThreshold: Int,
    val onTripBehavior: OnTripBehavior,
) {
    private var streak: Int = 0

    fun recordSuccess() {
        streak = 0
    }

    fun recordParseFailure(): Boolean {
        streak += 1
        return isTripped()
    }

    fun recordFailure(): Boolean {
        streak += 1
        return isTripped()
    }

    fun isTripped(): Boolean = streak >= tripThreshold

    fun streak(): Int = streak

    fun reset() {
        streak = 0
    }
}
