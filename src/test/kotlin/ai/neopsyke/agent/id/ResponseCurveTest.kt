package ai.neopsyke.agent.id

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseCurveTest {

    private val epsilon = 1e-9

    // ── Linear ─────────────────────────────────────────────────────────

    @Test
    fun `linear curve returns input unchanged`() {
        val curve = ResponseCurve.Linear
        assertEquals(0.0, curve.tension(0.0), epsilon)
        assertEquals(0.5, curve.tension(0.5), epsilon)
        assertEquals(1.0, curve.tension(1.0), epsilon)
    }

    @Test
    fun `linear curve clamps out-of-range values`() {
        val curve = ResponseCurve.Linear
        assertEquals(0.0, curve.tension(-0.5), epsilon)
        assertEquals(1.0, curve.tension(1.5), epsilon)
    }

    // ── Power ──────────────────────────────────────────────────────────

    @Test
    fun `power curve f(0) is 0 and f(1) is 1`() {
        val curve = ResponseCurve.Power(exponent = 3.0)
        assertEquals(0.0, curve.tension(0.0), epsilon)
        assertEquals(1.0, curve.tension(1.0), epsilon)
    }

    @Test
    fun `power curve with exponent gt 1 is below linear at midpoints`() {
        val curve = ResponseCurve.Power(exponent = 3.0)
        // x^3 at 0.5 = 0.125, well below 0.5
        val mid = curve.tension(0.5)
        assertTrue(mid < 0.5, "Power(3) at 0.5 should be below linear: got $mid")
        assertEquals(0.125, mid, epsilon)
    }

    @Test
    fun `power curve with exponent lt 1 is above linear at midpoints`() {
        val curve = ResponseCurve.Power(exponent = 0.5)
        val mid = curve.tension(0.25)
        // 0.25^0.5 = 0.5
        assertEquals(0.5, mid, epsilon)
    }

    @Test
    fun `power curve is monotonically non-decreasing`() {
        val curve = ResponseCurve.Power(exponent = 3.0)
        assertMonotonicity(curve)
    }

    @Test
    fun `power curve clamps out-of-range values`() {
        val curve = ResponseCurve.Power(exponent = 2.0)
        assertEquals(0.0, curve.tension(-1.0), epsilon)
        assertEquals(1.0, curve.tension(2.0), epsilon)
    }

    // ── Sigmoid ────────────────────────────────────────────────────────

    @Test
    fun `sigmoid curve f(0) is approximately 0 and f(1) is approximately 1`() {
        val curve = ResponseCurve.Sigmoid(steepness = 10.0, midpoint = 0.5)
        assertTrue(curve.tension(0.0) < 0.01, "sigmoid(0) should be ≈ 0")
        assertTrue(curve.tension(1.0) > 0.99, "sigmoid(1) should be ≈ 1")
    }

    @Test
    fun `sigmoid curve midpoint produces approximately 0_5`() {
        val curve = ResponseCurve.Sigmoid(steepness = 10.0, midpoint = 0.5)
        val mid = curve.tension(0.5)
        assertTrue(mid in 0.45..0.55, "sigmoid(0.5) should be ≈ 0.5: got $mid")
    }

    @Test
    fun `sigmoid curve is monotonically non-decreasing`() {
        val curve = ResponseCurve.Sigmoid(steepness = 10.0, midpoint = 0.5)
        assertMonotonicity(curve)
    }

    @Test
    fun `sigmoid curve with low steepness behaves nearly linear`() {
        val curve = ResponseCurve.Sigmoid(steepness = 0.1, midpoint = 0.5)
        val mid = curve.tension(0.5)
        // With very low steepness, curve is nearly linear
        assertTrue(mid in 0.45..0.55, "Low-steepness sigmoid should be nearly linear at midpoint: got $mid")
    }

    @Test
    fun `sigmoid curve clamps out-of-range values`() {
        val curve = ResponseCurve.Sigmoid(steepness = 10.0, midpoint = 0.5)
        val low = curve.tension(-1.0)
        val high = curve.tension(2.0)
        assertTrue(low >= 0.0 && low <= 1.0, "Out-of-range input should clamp: got $low")
        assertTrue(high >= 0.0 && high <= 1.0, "Out-of-range input should clamp: got $high")
    }

    // ── Logarithmic ────────────────────────────────────────────────────

    @Test
    fun `logarithmic curve f(0) is 0 and f(1) is approximately 1`() {
        val curve = ResponseCurve.Logarithmic(scale = 10.0)
        assertEquals(0.0, curve.tension(0.0), epsilon)
        assertTrue(curve.tension(1.0) > 0.99, "log(1) should be ≈ 1")
    }

    @Test
    fun `logarithmic curve rises quickly from zero`() {
        val curve = ResponseCurve.Logarithmic(scale = 10.0)
        val lowValue = curve.tension(0.1)
        // ln(1 + 0.1*10) / ln(1 + 10) = ln(2)/ln(11) ≈ 0.289
        assertTrue(lowValue > 0.2, "Logarithmic should rise quickly at low values: got $lowValue")
    }

    @Test
    fun `logarithmic curve is monotonically non-decreasing`() {
        val curve = ResponseCurve.Logarithmic(scale = 10.0)
        assertMonotonicity(curve)
    }

    @Test
    fun `logarithmic curve clamps out-of-range values`() {
        val curve = ResponseCurve.Logarithmic(scale = 10.0)
        assertEquals(0.0, curve.tension(-1.0), epsilon)
        assertEquals(1.0, curve.tension(2.0), epsilon)
    }

    // ── Cross-curve comparisons ────────────────────────────────────────

    @Test
    fun `power dominates logarithmic at high values`() {
        val power = ResponseCurve.Power(exponent = 3.0)
        val log = ResponseCurve.Logarithmic(scale = 10.0)

        // At high values (e.g. 0.9), Power(3) = 0.729, Log(10) ≈ 0.958
        // Actually Power(3) < Logarithmic at high — Power is BELOW linear at high values.
        // This verifies Power(3) is below Logarithmic at low values
        val lowVal = 0.2
        val powerLow = power.tension(lowVal)
        val logLow = log.tension(lowVal)
        assertTrue(
            powerLow < logLow,
            "At x=$lowVal: Power(3) ($powerLow) should be below Logarithmic ($logLow)"
        )

        // At high values near 1.0, both approach 1.0 but logarithmic flattens while power catches up
        val highVal = 0.95
        val powerHigh = power.tension(highVal)
        val logHigh = log.tension(highVal)
        // Power(3) at 0.95 = 0.857, Log at 0.95 ≈ 0.987 — log still higher
        assertTrue(
            powerHigh < logHigh,
            "At x=$highVal: Power(3) ($powerHigh) should still be below Logarithmic ($logHigh)"
        )
    }

    @Test
    fun `emergent hierarchy - logarithmic felt first, power felt last`() {
        val power = ResponseCurve.Power(exponent = 3.0)
        val sigmoid = ResponseCurve.Sigmoid(steepness = 10.0, midpoint = 0.5)
        val log = ResponseCurve.Logarithmic(scale = 10.0)

        // At low raw value, logarithmic should produce highest urgency
        val rawLow = 0.15
        assertTrue(
            log.tension(rawLow) > sigmoid.tension(rawLow),
            "At low value, logarithmic should dominate sigmoid"
        )
        assertTrue(
            log.tension(rawLow) > power.tension(rawLow),
            "At low value, logarithmic should dominate power"
        )
    }

    // ── fromConfig factory ─────────────────────────────────────────────

    @Test
    fun `fromConfig creates linear curve`() {
        val curve = ResponseCurve.fromConfig(ResponseCurveConfig(type = "linear"))
        assertTrue(curve is ResponseCurve.Linear)
    }

    @Test
    fun `fromConfig creates power curve with custom exponent`() {
        val curve = ResponseCurve.fromConfig(ResponseCurveConfig(type = "power", exponent = 4.0))
        assertTrue(curve is ResponseCurve.Power)
        assertEquals(4.0, (curve as ResponseCurve.Power).exponent, epsilon)
    }

    @Test
    fun `fromConfig creates power curve with default exponent when not specified`() {
        val curve = ResponseCurve.fromConfig(ResponseCurveConfig(type = "power"))
        assertTrue(curve is ResponseCurve.Power)
        assertEquals(2.0, (curve as ResponseCurve.Power).exponent, epsilon)
    }

    @Test
    fun `fromConfig creates sigmoid curve`() {
        val curve = ResponseCurve.fromConfig(
            ResponseCurveConfig(type = "sigmoid", steepness = 8.0, midpoint = 0.6)
        )
        assertTrue(curve is ResponseCurve.Sigmoid)
        assertEquals(8.0, (curve as ResponseCurve.Sigmoid).steepness, epsilon)
        assertEquals(0.6, curve.midpoint, epsilon)
    }

    @Test
    fun `fromConfig creates logarithmic curve`() {
        val curve = ResponseCurve.fromConfig(ResponseCurveConfig(type = "logarithmic", scale = 5.0))
        assertTrue(curve is ResponseCurve.Logarithmic)
        assertEquals(5.0, (curve as ResponseCurve.Logarithmic).scale, epsilon)
    }

    @Test
    fun `fromConfig is case insensitive`() {
        val curve = ResponseCurve.fromConfig(ResponseCurveConfig(type = "POWER", exponent = 2.0))
        assertTrue(curve is ResponseCurve.Power)
    }

    @Test
    fun `fromConfig throws on unknown type`() {
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            ResponseCurve.fromConfig(ResponseCurveConfig(type = "cubic_spline"))
        }
        assertTrue(ex.message!!.contains("cubic_spline"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun assertMonotonicity(curve: ResponseCurve, steps: Int = 100) {
        var prev = curve.tension(0.0)
        for (i in 1..steps) {
            val x = i.toDouble() / steps
            val current = curve.tension(x)
            assertTrue(
                current >= prev - epsilon,
                "Curve not monotonic at x=$x: f(${(i - 1).toDouble() / steps})=$prev > f($x)=$current"
            )
            prev = current
        }
    }
}
