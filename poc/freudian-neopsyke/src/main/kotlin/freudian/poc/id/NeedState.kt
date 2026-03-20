package freudian.poc.id

import freudian.poc.config.NeedConfig

class NeedState(
    val name: String,
    val config: NeedConfig,
) {
    var value: Double = 0.0
        private set

    var consecutiveDenials: Int = 0
        private set

    var backoffRemainingTicks: Int = 0
        private set

    fun grow() {
        value = (value + config.growthRate).coerceIn(0.0, 1.0)
        if (backoffRemainingTicks > 0) {
            backoffRemainingTicks -= 1
        }
    }

    fun isEligible(): Boolean = backoffRemainingTicks <= 0

    fun applyAcceptedFeedback() {
        value = config.resetValue.coerceIn(0.0, 1.0)
        consecutiveDenials = 0
        backoffRemainingTicks = 0
    }

    fun applyDeniedFeedback(maxConsecutiveDenials: Int, baseBackoffTicks: Int) {
        consecutiveDenials += 1
        if (maxConsecutiveDenials > 0 && baseBackoffTicks > 0 && consecutiveDenials % maxConsecutiveDenials == 0) {
            val escalation = (consecutiveDenials / maxConsecutiveDenials).coerceAtMost(MAX_BACKOFF_ESCALATION)
            backoffRemainingTicks = baseBackoffTicks * (1 shl escalation)
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "name" to name,
        "value" to value,
        "consecutive_denials" to consecutiveDenials,
        "backoff_remaining_ticks" to backoffRemainingTicks,
    )

    companion object {
        private const val MAX_BACKOFF_ESCALATION: Int = 4
    }
}
