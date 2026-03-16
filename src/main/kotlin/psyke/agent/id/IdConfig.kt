package psyke.agent.id

/**
 * How an Id impulse should converge once the Ego processes it.
 *
 * - [ANSWER]      – the planner may address the user directly (e.g. reach-out).
 * - [INTERNALIZE] – the planner should research/reflect internally; user output
 *                   is only permitted when [NeedConfig.allowEscalation] is true.
 */
enum class ConvergenceMode {
    ANSWER,
    INTERNALIZE;

    companion object {
        fun fromYaml(raw: String?): ConvergenceMode =
            when (raw?.trim()?.lowercase()) {
                "internalize" -> INTERNALIZE
                "answer" -> ANSWER
                else -> ANSWER
            }
    }
}

/**
 * Top-level Id module configuration, loaded from `id-runtime.yaml`.
 */
data class IdConfig(
    val enabled: Boolean = false,
    val pulseIntervalMs: Long = 30_000,
    val triggerThreshold: Double = 0.7,
    val thresholdOnUrgency: Boolean = true,
    val maxConsecutiveDenials: Int = 5,
    val backoffPulses: Int = 10,
    val maxInFlightPulses: Int = 20,
    val maxPendingImpulses: Int = 1,
    val needs: Map<String, NeedConfig> = emptyMap(),
) {
    init {
        require(pulseIntervalMs > 0) { "pulseIntervalMs must be positive" }
        require(triggerThreshold in 0.0..1.0) { "triggerThreshold must be in [0, 1]" }
        require(maxConsecutiveDenials >= 0) { "maxConsecutiveDenials must be non-negative" }
        require(backoffPulses > 0) { "backoffPulses must be positive" }
        require(maxInFlightPulses > 0) { "maxInFlightPulses must be positive" }
        require(maxPendingImpulses > 0) { "maxPendingImpulses must be positive" }
    }
}

/**
 * Configuration for a single need (drive).
 */
data class NeedConfig(
    val enabled: Boolean = true,
    val description: String = "",
    val growthRate: Double = 0.005,
    val satisfactionDecay: Double = 0.8,
    val resetFloor: Double = 0.0,
    val cooldownPulses: Int = 5,
    val prompt: String = "",
    val convergence: ConvergenceMode = ConvergenceMode.ANSWER,
    val allowEscalation: Boolean = false,
    val responseCurve: ResponseCurveConfig = ResponseCurveConfig(),
    val activityDecay: Map<String, Double> = emptyMap(),
) {
    init {
        require(growthRate >= 0.0) { "growthRate must be non-negative" }
        require(satisfactionDecay in 0.0..1.0) { "satisfactionDecay must be in [0, 1]" }
        require(resetFloor in 0.0..1.0) { "resetFloor must be in [0, 1]" }
        require(cooldownPulses >= 0) { "cooldownPulses must be non-negative" }
    }
}

/**
 * YAML-friendly response curve configuration.
 *
 * Only the parameters relevant to the chosen [type] need to be set:
 * - `power` → [exponent]
 * - `sigmoid` → [steepness], [midpoint]
 * - `logarithmic` → [scale]
 * - `linear` → (no parameters)
 */
data class ResponseCurveConfig(
    val type: String = "linear",
    val exponent: Double? = null,
    val steepness: Double? = null,
    val midpoint: Double? = null,
    val scale: Double? = null,
)
