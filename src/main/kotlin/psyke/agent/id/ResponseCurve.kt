package psyke.agent.id

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Maps a raw need value in [0, 1] to an effective urgency in [0, 1].
 *
 * Different curve shapes encode different behavioural characters:
 * - [Linear]: urgency tracks value 1:1. Baseline / test only.
 * - [Power]: ignorable when low, overwhelming when high. Best for core purpose drives.
 * - [Sigmoid]: tipping-point behaviour. Best for social / interaction drives.
 * - [Logarithmic]: immediately felt but never desperate. Best for curiosity / epistemic drives.
 *
 * All implementations guarantee f(0) ≈ 0 and f(1) ≈ 1 (monotonically non-decreasing).
 */
sealed class ResponseCurve {
    abstract fun urgency(rawValue: Double): Double

    /** f(x) = x */
    data object Linear : ResponseCurve() {
        override fun urgency(rawValue: Double): Double = rawValue.coerceIn(0.0, 1.0)
    }

    /** f(x) = x^[exponent]. Exponent > 1 creates a concave-up curve (slow start, fast finish). */
    data class Power(val exponent: Double) : ResponseCurve() {
        init {
            require(exponent > 0.0) { "Power exponent must be positive, was $exponent" }
        }

        override fun urgency(rawValue: Double): Double =
            rawValue.coerceIn(0.0, 1.0).pow(exponent)
    }

    /**
     * Logistic sigmoid with configurable [steepness] and [midpoint].
     *
     * Raw sigmoid output is normalised so that f(0) ≈ 0 and f(1) ≈ 1.
     */
    data class Sigmoid(val steepness: Double, val midpoint: Double) : ResponseCurve() {
        init {
            require(steepness > 0.0) { "Sigmoid steepness must be positive, was $steepness" }
            require(midpoint in 0.0..1.0) { "Sigmoid midpoint must be in [0, 1], was $midpoint" }
        }

        private val f0: Double = rawSigmoid(0.0)
        private val f1: Double = rawSigmoid(1.0)
        private val range: Double = f1 - f0

        override fun urgency(rawValue: Double): Double {
            val x = rawValue.coerceIn(0.0, 1.0)
            if (range == 0.0) return x // degenerate: fall back to linear
            return ((rawSigmoid(x) - f0) / range).coerceIn(0.0, 1.0)
        }

        private fun rawSigmoid(x: Double): Double =
            1.0 / (1.0 + exp(-steepness * (x - midpoint)))
    }

    /**
     * f(x) = ln(1 + x·[scale]) / ln(1 + [scale]).
     *
     * Rises quickly from zero and flattens gradually — a persistent gentle pull.
     */
    data class Logarithmic(val scale: Double) : ResponseCurve() {
        init {
            require(scale > 0.0) { "Logarithmic scale must be positive, was $scale" }
        }

        private val denominator: Double = ln(1.0 + scale)

        override fun urgency(rawValue: Double): Double {
            val x = rawValue.coerceIn(0.0, 1.0)
            return (ln(1.0 + x * scale) / denominator).coerceIn(0.0, 1.0)
        }
    }

    companion object {
        /** Build a [ResponseCurve] from a [ResponseCurveConfig]. */
        fun fromConfig(config: ResponseCurveConfig): ResponseCurve =
            when (config.type.lowercase()) {
                "linear" -> Linear
                "power" -> Power(exponent = config.exponent ?: 2.0)
                "sigmoid" -> Sigmoid(
                    steepness = config.steepness ?: 10.0,
                    midpoint = config.midpoint ?: 0.5,
                )
                "logarithmic" -> Logarithmic(scale = config.scale ?: 10.0)
                else -> error("Unknown response curve type: '${config.type}'")
            }
    }
}
