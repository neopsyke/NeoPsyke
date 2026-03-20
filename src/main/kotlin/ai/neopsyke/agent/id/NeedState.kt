package ai.neopsyke.agent.id

/**
 * Mutable runtime state for a single need (drive).
 *
 * [value] is the raw need intensity (0.0–1.0), growing by [NeedConfig.growthRate]
 * each pulse. Effective urgency for competition is computed via the [curve].
 */
class NeedState(
    val name: String,
    val config: NeedConfig,
    val curve: ResponseCurve = ResponseCurve.fromConfig(config.responseCurve),
    private val maxConsecutiveDenials: Int = DEFAULT_MAX_CONSECUTIVE_DENIALS,
) {
    /** Raw need value in [0.0, 1.0]. */
    var value: Double = 0.0
        private set

    /** Previous value, for derivative (pleasure/distress) tracking. */
    var previousValue: Double = 0.0
        private set

    /** Pulses remaining before this need is eligible to trigger again. */
    var cooldownRemaining: Int = 0
        private set

    /** True when an impulse has been accepted and is awaiting completion. */
    var inFlight: Boolean = false
        private set

    /** Pulses remaining before in-flight timeout. */
    var inFlightPulsesRemaining: Int = 0
        private set

    /** Count of consecutive denial events (superego deny, planner noop, or completion failure). */
    var consecutiveDenials: Int = 0
        private set

    /** Pulses remaining in exponential backoff (after too many consecutive denials). */
    var backoffPulsesRemaining: Int = 0
        private set

    /** The change in value since the last pulse (positive = growing, negative = decaying). */
    val delta: Double get() = value - previousValue

    /** Curve-transformed urgency in [0.0, 1.0]. */
    val urgency: Double get() = curve.urgency(value)

    // ── Pulse lifecycle ──────────────────────────────────────────────

    /**
     * Grow the need by [NeedConfig.growthRate], capped at 1.0.
     * Records previous value for delta tracking.
     */
    fun grow() {
        previousValue = value
        value = (value + config.growthRate).coerceAtMost(1.0)
    }

    /** Decrement cooldown, backoff, and in-flight timeout counters. */
    fun decrementCooldowns(maxInFlightPulses: Int) {
        if (cooldownRemaining > 0) cooldownRemaining--
        if (backoffPulsesRemaining > 0) backoffPulsesRemaining--
        if (inFlight) {
            if (inFlightPulsesRemaining > 0) {
                inFlightPulsesRemaining--
            }
            if (inFlightPulsesRemaining <= 0) {
                // In-flight timeout: treat as failure
                clearInFlight(success = false, backoffPulses = 0)
            }
        }
    }

    // ── Activity decay ───────────────────────────────────────────────

    /**
     * Passively reduce need value when relevant agent activity occurs.
     * Called by Ego via direct callback (not instrumentation bus).
     */
    fun decayOnActivity(amount: Double) {
        previousValue = value
        value = (value - amount).coerceAtLeast(0.0)
    }

    // ── Impulse lifecycle ────────────────────────────────────────────

    /** Mark this need as having an impulse in-flight (accepted by Ego). */
    fun markInFlight(maxInFlightPulses: Int) {
        inFlight = true
        inFlightPulsesRemaining = maxInFlightPulses
        cooldownRemaining = config.cooldownPulses
    }

    /**
     * Decay on satisfaction after successful impulse completion.
     * Reduces value by [NeedConfig.satisfactionDecay] fraction, clears denial counter.
     */
    fun decayOnSatisfaction() {
        previousValue = value
        value = (value * (1.0 - config.satisfactionDecay)).coerceAtLeast(config.resetFloor)
        consecutiveDenials = 0
        inFlight = false
        inFlightPulsesRemaining = 0
    }

    /**
     * Clear in-flight state after completion or timeout.
     *
     * @param success true if the Ego's work succeeded, false on failure/timeout.
     * @param backoffPulses base backoff pulse count from [IdConfig.backoffPulses].
     */
    fun clearInFlight(success: Boolean, backoffPulses: Int) {
        inFlight = false
        inFlightPulsesRemaining = 0
        if (success) {
            decayOnSatisfaction()
        } else {
            consecutiveDenials++
            applyBackoffIfNeeded(backoffPulses)
        }
    }

    /**
     * Called when the Ego (planner noop) or Superego denies an impulse before execution starts.
     * The need was never truly in-flight — just clear and apply denial logic.
     */
    fun onDenied(backoffPulses: Int) {
        inFlight = false
        inFlightPulsesRemaining = 0
        consecutiveDenials++
        applyBackoffIfNeeded(backoffPulses)
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun applyBackoffIfNeeded(backoffPulses: Int) {
        if (maxConsecutiveDenials <= 0) return
        if (backoffPulses > 0 && consecutiveDenials > 0 && consecutiveDenials % maxConsecutiveDenials == 0) {
            val escalation = (consecutiveDenials / maxConsecutiveDenials).coerceAtMost(MAX_BACKOFF_ESCALATION)
            backoffPulsesRemaining = backoffPulses * (1 shl escalation)
        }
    }

    /** Whether this need can be considered for triggering (passes all pre-gate checks). */
    fun isEligible(): Boolean =
        !inFlight && cooldownRemaining <= 0 && backoffPulsesRemaining <= 0

    /** Snapshot for instrumentation. */
    fun snapshot(): Map<String, Any?> = mapOf(
        "name" to name,
        "rawValue" to value,
        "urgency" to urgency,
        "delta" to delta,
        "growthRate" to config.growthRate,
        "cooldownRemaining" to cooldownRemaining,
        "inFlight" to inFlight,
        "inFlightPulsesRemaining" to inFlightPulsesRemaining,
        "consecutiveDenials" to consecutiveDenials,
        "backoffPulsesRemaining" to backoffPulsesRemaining,
    )

    companion object {
        /** Default denial threshold before backoff when config does not override it. */
        internal const val DEFAULT_MAX_CONSECUTIVE_DENIALS = 5
        /** Backward-compatible alias used by existing tests/docs. */
        internal const val MAX_DENIALS_BEFORE_BACKOFF = DEFAULT_MAX_CONSECUTIVE_DENIALS
        /** Cap the exponential backoff escalation factor (2^N). */
        internal const val MAX_BACKOFF_ESCALATION = 4
    }
}
