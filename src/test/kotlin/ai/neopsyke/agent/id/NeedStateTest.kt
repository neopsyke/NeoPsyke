package ai.neopsyke.agent.id

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedStateTest {

    private val epsilon = 1e-9

    private fun defaultNeedConfig(
        growthRate: Double = 0.01,
        satisfactionDecay: Double = 0.8,
        resetFloor: Double = 0.0,
        cooldownPulses: Int = 3,
        activityDecay: Map<String, Double> = emptyMap(),
    ) = NeedConfig(
        description = "test need",
        growthRate = growthRate,
        satisfactionDecay = satisfactionDecay,
        resetFloor = resetFloor,
        cooldownPulses = cooldownPulses,
        prompt = "test prompt",
        activityDecay = activityDecay,
    )

    private fun needState(config: NeedConfig = defaultNeedConfig()) =
        NeedState(name = "test", config = config)

    // ── Growth ──────────────────────────────────────────────────────────

    @Test
    fun `grow increases value by growth rate`() {
        val state = needState()
        assertEquals(0.0, state.value, epsilon)
        state.grow()
        assertEquals(0.01, state.value, epsilon)
        state.grow()
        assertEquals(0.02, state.value, epsilon)
    }

    @Test
    fun `grow caps value at 1_0`() {
        val state = needState(defaultNeedConfig(growthRate = 0.6))
        state.grow() // 0.6
        state.grow() // 1.0 (capped)
        assertEquals(1.0, state.value, epsilon)
    }

    @Test
    fun `grow tracks previous value for delta`() {
        val state = needState()
        assertEquals(0.0, state.delta, epsilon)
        state.grow()
        assertEquals(0.01, state.delta, epsilon) // 0.01 - 0.0
        state.grow()
        assertEquals(0.01, state.delta, epsilon) // 0.02 - 0.01
    }

    @Test
    fun `urgency is computed through response curve`() {
        val config = defaultNeedConfig(growthRate = 0.5)
        val state = NeedState(
            name = "power-test",
            config = config,
            curve = ResponseCurve.Power(exponent = 2.0),
        )
        state.grow() // value = 0.5
        // urgency = 0.5^2 = 0.25
        assertEquals(0.25, state.tension, epsilon)
    }

    // ── Cooldown ────────────────────────────────────────────────────────

    @Test
    fun `cooldown decrements each pulse`() {
        val state = needState(defaultNeedConfig(cooldownPulses = 5))
        state.markInFlight(maxInFlightPulses = 20) // sets cooldown = 5
        assertEquals(5, state.cooldownRemaining)

        state.decrementCooldowns(maxInFlightPulses = 20)
        assertEquals(4, state.cooldownRemaining)

        state.decrementCooldowns(maxInFlightPulses = 20)
        assertEquals(3, state.cooldownRemaining)
    }

    @Test
    fun `cooldown does not go below zero`() {
        val state = needState(defaultNeedConfig(cooldownPulses = 1))
        state.markInFlight(maxInFlightPulses = 20)
        state.decrementCooldowns(maxInFlightPulses = 20) // cooldown: 1 → 0
        state.decrementCooldowns(maxInFlightPulses = 20) // stays at 0
        assertEquals(0, state.cooldownRemaining)
    }

    // ── Activity decay ──────────────────────────────────────────────────

    @Test
    fun `decayOnActivity reduces value`() {
        val state = needState()
        // Grow to 0.5
        repeat(50) { state.grow() }
        assertEquals(0.5, state.value, epsilon)

        state.decayOnActivity(0.15)
        assertEquals(0.35, state.value, epsilon)
    }

    @Test
    fun `decayOnActivity does not go below zero`() {
        val state = needState()
        state.grow() // 0.01
        state.decayOnActivity(0.5) // would be -0.49, clamped to 0
        assertEquals(0.0, state.value, epsilon)
    }

    @Test
    fun `decayOnActivity tracks delta correctly`() {
        val state = needState()
        repeat(10) { state.grow() } // value = 0.10
        state.decayOnActivity(0.03) // value = 0.07
        assertEquals(-0.03, state.delta, epsilon) // 0.07 - 0.10
    }

    // ── Impulse lifecycle ───────────────────────────────────────────────

    @Test
    fun `markInFlight sets state correctly`() {
        val state = needState(defaultNeedConfig(cooldownPulses = 4))
        assertFalse(state.inFlight)
        assertEquals(0, state.inFlightPulsesRemaining)

        state.markInFlight(maxInFlightPulses = 15)
        assertTrue(state.inFlight)
        assertEquals(15, state.inFlightPulsesRemaining)
        assertEquals(4, state.cooldownRemaining)
    }

    @Test
    fun `decayOnSatisfaction reduces value and clears state`() {
        val state = needState(defaultNeedConfig(
            growthRate = 0.5,
            satisfactionDecay = 0.8,
        ))
        state.grow() // value = 0.5
        state.markInFlight(maxInFlightPulses = 20)

        // Simulate some denials first to verify they get cleared
        state.onDenied(backoffPulses = 10)
        state.markInFlight(maxInFlightPulses = 20) // re-mark

        state.decayOnSatisfaction()
        // value = 0.5 * (1 - 0.8) = 0.1
        assertEquals(0.1, state.value, epsilon)
        assertFalse(state.inFlight)
        assertEquals(0, state.inFlightPulsesRemaining)
        assertEquals(0, state.consecutiveDenials) // cleared!
    }

    @Test
    fun `decayOnSatisfaction respects resetFloor`() {
        val state = needState(defaultNeedConfig(
            growthRate = 0.1,
            satisfactionDecay = 0.99,
            resetFloor = 0.05,
        ))
        state.grow() // value = 0.1
        state.decayOnSatisfaction()
        // value = 0.1 * (1 - 0.99) = 0.001, but floor is 0.05
        assertEquals(0.05, state.value, epsilon)
    }

    @Test
    fun `clearInFlight with success delegates to decayOnSatisfaction`() {
        val state = needState(defaultNeedConfig(
            growthRate = 0.5,
            satisfactionDecay = 0.8,
        ))
        state.grow() // value = 0.5
        state.markInFlight(maxInFlightPulses = 20)
        state.clearInFlight(success = true, backoffPulses = 10)

        assertEquals(0.1, state.value, epsilon) // 0.5 * 0.2
        assertFalse(state.inFlight)
        assertEquals(0, state.consecutiveDenials)
    }

    @Test
    fun `clearInFlight with failure increments denials`() {
        val state = needState()
        state.markInFlight(maxInFlightPulses = 20)
        state.clearInFlight(success = false, backoffPulses = 10)

        assertFalse(state.inFlight)
        assertEquals(1, state.consecutiveDenials)
    }

    // ── Denial & backoff ────────────────────────────────────────────────

    @Test
    fun `onDenied increments consecutive denials`() {
        val state = needState()
        assertEquals(0, state.consecutiveDenials)

        state.onDenied(backoffPulses = 10)
        assertEquals(1, state.consecutiveDenials)

        state.onDenied(backoffPulses = 10)
        assertEquals(2, state.consecutiveDenials)
    }

    @Test
    fun `backoff triggers after MAX_DENIALS_BEFORE_BACKOFF consecutive denials`() {
        val state = needState()
        val backoffBase = 10

        // First 4 denials: no backoff
        repeat(4) {
            state.onDenied(backoffPulses = backoffBase)
        }
        assertEquals(0, state.backoffPulsesRemaining)

        // 5th denial triggers backoff: 10 * 2^1 = 20
        state.onDenied(backoffPulses = backoffBase)
        assertEquals(NeedState.MAX_DENIALS_BEFORE_BACKOFF, state.consecutiveDenials)
        assertEquals(20, state.backoffPulsesRemaining) // 10 * 2^1
    }

    @Test
    fun `backoff escalates with more denials`() {
        val state = needState()
        val backoffBase = 10

        // 5 denials → backoff = 10 * 2^1 = 20
        repeat(5) { state.onDenied(backoffPulses = backoffBase) }
        assertEquals(20, state.backoffPulsesRemaining)

        // Clear backoff for next test
        repeat(20) { state.decrementCooldowns(maxInFlightPulses = 20) }
        assertEquals(0, state.backoffPulsesRemaining)

        // 5 more denials (total 10) → backoff = 10 * 2^2 = 40
        repeat(5) { state.onDenied(backoffPulses = backoffBase) }
        assertEquals(10, state.consecutiveDenials)
        assertEquals(40, state.backoffPulsesRemaining) // 10 * 2^2
    }

    @Test
    fun `backoff escalation is capped at MAX_BACKOFF_ESCALATION`() {
        val state = needState()
        val backoffBase = 5

        // Push way past the cap: 25 denials → escalation = min(5, MAX_BACKOFF_ESCALATION) = 4
        // Max backoff = 5 * 2^4 = 80
        repeat(25) { state.onDenied(backoffPulses = backoffBase) }
        // At 25 denials, escalation = min(25/5, 4) = min(5, 4) = 4
        assertTrue(
            state.backoffPulsesRemaining <= backoffBase * (1 shl NeedState.MAX_BACKOFF_ESCALATION),
            "Backoff should be capped: got ${state.backoffPulsesRemaining}"
        )
    }

    @Test
    fun `successful completion resets denial counter`() {
        val state = needState(defaultNeedConfig(growthRate = 0.5))
        state.grow() // value = 0.5

        // Accumulate denials
        repeat(3) { state.onDenied(backoffPulses = 10) }
        assertEquals(3, state.consecutiveDenials)

        // Successful completion clears denials
        state.markInFlight(maxInFlightPulses = 20)
        state.clearInFlight(success = true, backoffPulses = 10)
        assertEquals(0, state.consecutiveDenials)
    }

    @Test
    fun `backoff pulses decrement each pulse`() {
        val state = needState()

        // Trigger backoff
        repeat(5) { state.onDenied(backoffPulses = 10) }
        val initialBackoff = state.backoffPulsesRemaining
        assertTrue(initialBackoff > 0)

        state.decrementCooldowns(maxInFlightPulses = 20)
        assertEquals(initialBackoff - 1, state.backoffPulsesRemaining)
    }

    // ── In-flight timeout ──────────────────────────────────────────────

    @Test
    fun `in-flight timeout triggers after max pulses`() {
        val state = needState()
        state.markInFlight(maxInFlightPulses = 3)
        assertTrue(state.inFlight)

        state.decrementCooldowns(maxInFlightPulses = 3) // remaining: 2
        assertTrue(state.inFlight)
        state.decrementCooldowns(maxInFlightPulses = 3) // remaining: 1
        assertTrue(state.inFlight)
        state.decrementCooldowns(maxInFlightPulses = 3) // remaining: 0, timeout → clearInFlight(failure)
        assertFalse(state.inFlight, "Should have timed out")
        assertEquals(1, state.consecutiveDenials, "Timeout should count as a denial")
    }

    // ── Eligibility ─────────────────────────────────────────────────────

    @Test
    fun `isEligible returns true when all conditions met`() {
        val state = needState()
        assertTrue(state.isEligible())
    }

    @Test
    fun `isEligible returns false when in-flight`() {
        val state = needState()
        state.markInFlight(maxInFlightPulses = 20)
        assertFalse(state.isEligible())
    }

    @Test
    fun `isEligible returns false when cooldown active`() {
        val state = needState(defaultNeedConfig(cooldownPulses = 3))
        state.markInFlight(maxInFlightPulses = 20)
        // Clear in-flight but cooldown remains
        state.clearInFlight(success = true, backoffPulses = 0)
        assertFalse(state.inFlight)
        assertTrue(state.cooldownRemaining > 0)
        assertFalse(state.isEligible())
    }

    @Test
    fun `isEligible returns false when in backoff`() {
        val state = needState()
        repeat(5) { state.onDenied(backoffPulses = 10) }
        assertTrue(state.backoffPulsesRemaining > 0)
        assertFalse(state.isEligible())
    }

    // ── Snapshot ─────────────────────────────────────────────────────────

    @Test
    fun `snapshot captures all fields`() {
        val state = needState()
        state.grow()

        val snap = state.snapshot()
        assertEquals("test", snap["name"])
        assertEquals(state.value, snap["rawValue"])
        assertEquals(state.tension, snap["tension"])
        assertEquals(state.delta, snap["delta"])
        assertEquals(state.cooldownRemaining, snap["cooldownRemaining"])
        assertEquals(state.inFlight, snap["inFlight"])
        assertEquals(state.inFlightPulsesRemaining, snap["inFlightPulsesRemaining"])
        assertEquals(state.consecutiveDenials, snap["consecutiveDenials"])
        assertEquals(state.backoffPulsesRemaining, snap["backoffPulsesRemaining"])
    }

    @Test
    fun `snapshot includes growthRate from config`() {
        val config = defaultNeedConfig(growthRate = 0.07)
        val state = needState(config)

        val snap = state.snapshot()
        assertEquals(0.07, snap["growthRate"])
    }
}
