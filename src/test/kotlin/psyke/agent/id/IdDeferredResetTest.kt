package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import psyke.agent.core.PendingImpulse
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the Id's deferred reset mechanism.
 *
 * Verifies that a need's value stays elevated while an impulse is in-flight
 * (not decayed on denial/timeout), only decaying on verified successful completion.
 * Also verifies the in-flight timeout mechanism.
 */
class IdDeferredResetTest {

    private val enqueuedImpulses = mutableListOf<PendingImpulse>()
    private var egoBusy = false
    private val events = mutableListOf<AgentEvent>()

    private val recordingInstrumentation = object : AgentInstrumentation {
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun buildId(
        growthRate: Double = 0.1,
        satisfactionDecay: Double = 0.8,
        cooldownPulses: Int = 0,
        maxInFlightPulses: Int = 5,
        backoffPulses: Int = 10,
        enqueueResult: Boolean = true,
    ): Id = Id(
        config = IdConfig(
            enabled = true,
            pulseIntervalMs = 1000,
            triggerThreshold = 0.0,
            thresholdOnUrgency = true,
            maxConsecutiveDenials = 5,
            backoffPulses = backoffPulses,
            maxInFlightPulses = maxInFlightPulses,
            maxPendingImpulses = 1,
            needs = mapOf(
                "test-need" to NeedConfig(
                    description = "test",
                    growthRate = growthRate,
                    satisfactionDecay = satisfactionDecay,
                    cooldownPulses = cooldownPulses,
                    prompt = "test!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        ),
        instrumentation = recordingInstrumentation,
        scope = CoroutineScope(Dispatchers.Unconfined),
        enqueueImpulse = { impulse ->
            enqueuedImpulses.add(impulse)
            enqueueResult
        },
        hasPendingWork = { egoBusy },
    )

    // ── Need stays elevated during in-flight ────────────────────────────

    @Test
    fun `need value continues to grow during in-flight`() {
        val id = buildId(growthRate = 0.1)
        val need = id.needs["test-need"]!!

        // First pulse fires impulse; need is in-flight at 0.1
        id.pulse()
        assertTrue(need.inFlight)
        assertEquals(0.1, need.value, 1e-9)

        // Subsequent pulses still grow the need even while in-flight
        id.pulse()
        assertEquals(0.2, need.value, 1e-9)
        assertTrue(need.inFlight, "Should remain in-flight")

        id.pulse()
        assertEquals(0.3, need.value, 1e-9)
        assertTrue(need.inFlight, "Should remain in-flight")
    }

    @Test
    fun `need does not fire second impulse while in-flight`() {
        // Use large maxInFlightPulses to avoid timeout during the test
        val id = buildId(growthRate = 0.2, maxInFlightPulses = 50)
        val need = id.needs["test-need"]!!

        id.pulse() // fires, in-flight
        assertEquals(1, enqueuedImpulses.size)

        // Even though value keeps growing above threshold, no new impulse fires
        // (well within the 50-pulse timeout)
        repeat(5) { id.pulse() }
        assertEquals(1, enqueuedImpulses.size, "Should not fire while in-flight")
        assertTrue(need.inFlight)
    }

    // ── Deferred reset on successful completion ─────────────────────────

    @Test
    fun `successful completion decays need from elevated value`() {
        val id = buildId(growthRate = 0.1, satisfactionDecay = 0.8)
        val need = id.needs["test-need"]!!

        // Fire and let need grow while in-flight
        id.pulse() // value = 0.1, fires
        id.pulse() // value = 0.2, still in-flight
        id.pulse() // value = 0.3, still in-flight
        assertTrue(need.inFlight)
        assertEquals(0.3, need.value, 1e-9)

        // Complete with success - decay from 0.3
        id.onImpulseCompleted("test-need", success = true)
        // 0.3 * (1 - 0.8) = 0.06
        assertEquals(0.06, need.value, 1e-9)
        assertFalse(need.inFlight)
        assertEquals(0, need.consecutiveDenials)
    }

    @Test
    fun `successful completion clears consecutive denials`() {
        val id = buildId(growthRate = 0.5)
        val need = id.needs["test-need"]!!

        // Accumulate denials first
        id.pulse()
        id.onImpulseDenied("test-need")
        assertEquals(1, need.consecutiveDenials)

        id.pulse() // fires again since not in-flight after denial
        id.onImpulseDenied("test-need")
        assertEquals(2, need.consecutiveDenials)

        // Now fire and succeed
        id.pulse()
        id.onImpulseCompleted("test-need", success = true)
        assertEquals(0, need.consecutiveDenials, "Success should clear denial counter")
    }

    // ── Failure does not decay ──────────────────────────────────────────

    @Test
    fun `failure completion does not decay need value`() {
        val id = buildId(growthRate = 0.1, satisfactionDecay = 0.8)
        val need = id.needs["test-need"]!!

        id.pulse() // fires, value = 0.1
        id.pulse() // grows, value = 0.2
        val valueBeforeCompletion = need.value

        id.onImpulseCompleted("test-need", success = false)
        assertEquals(valueBeforeCompletion, need.value, 1e-9, "Failure should not decay value")
        assertFalse(need.inFlight)
        assertEquals(1, need.consecutiveDenials)
    }

    @Test
    fun `denial does not decay need value`() {
        val id = buildId(growthRate = 0.1)
        val need = id.needs["test-need"]!!

        id.pulse() // fires, value = 0.1
        id.pulse() // grows, value = 0.2
        val valueBeforeDenial = need.value

        id.onImpulseDenied("test-need")
        assertEquals(valueBeforeDenial, need.value, 1e-9, "Denial should not decay value")
        assertFalse(need.inFlight)
    }

    // ── In-flight timeout ───────────────────────────────────────────────

    @Test
    fun `in-flight times out after maxInFlightPulses`() {
        // Use a high threshold so the need doesn't re-fire after timeout in the same pulse.
        // We build a custom Id with threshold = 0.99 initially, but manually set up the first fire.
        val customId = Id(
            config = IdConfig(
                enabled = true,
                pulseIntervalMs = 1000,
                triggerThreshold = 0.0,
                maxInFlightPulses = 3,
                backoffPulses = 10,
                maxPendingImpulses = 1,
                needs = mapOf(
                    "test-need" to NeedConfig(
                        growthRate = 0.1,
                        cooldownPulses = 0,
                        prompt = "test!",
                        responseCurve = ResponseCurveConfig(type = "linear"),
                    ),
                ),
            ),
            instrumentation = recordingInstrumentation,
            scope = CoroutineScope(Dispatchers.Unconfined),
            enqueueImpulse = { enqueuedImpulses.add(it); true },
            hasPendingWork = { egoBusy },
        )
        val need = customId.needs["test-need"]!!

        customId.pulse() // fires, inFlightPulsesRemaining = 3
        assertTrue(need.inFlight)
        assertEquals(3, need.inFlightPulsesRemaining)

        customId.pulse() // decrements to 2
        assertTrue(need.inFlight)
        assertEquals(2, need.inFlightPulsesRemaining)

        customId.pulse() // decrements to 1
        assertTrue(need.inFlight)
        assertEquals(1, need.inFlightPulsesRemaining)

        // On the 4th pulse, timeout triggers clearInFlight(success=false).
        // The need may re-fire in the same pulse (value > threshold, eligible again).
        // We verify the timeout happened by checking consecutiveDenials.
        customId.pulse()
        assertEquals(1, need.consecutiveDenials, "Timeout should count as failure (increments denials)")
    }

    @Test
    fun `need can fire again after in-flight timeout`() {
        val id = buildId(growthRate = 0.2, maxInFlightPulses = 2)
        val need = id.needs["test-need"]!!

        id.pulse() // fires impulse #1
        assertEquals(1, enqueuedImpulses.size)

        id.pulse() // inFlightPulsesRemaining: 2 -> 1
        // On next pulse: timeout fires clearInFlight(false), and then the same pulse
        // re-evaluates candidates — need is above threshold and eligible again, so fires #2
        id.pulse() // inFlightPulsesRemaining: 1 -> 0, timeout + immediate re-fire

        assertEquals(2, enqueuedImpulses.size, "Should fire again after timeout (in same pulse)")
        assertEquals(1, need.consecutiveDenials, "Timeout counted as failure before re-fire")
    }

    @Test
    fun `explicit completion before timeout prevents timeout side-effects`() {
        // Use Ego busy to prevent re-firing after completion, so we can isolate denial counting
        val id = buildId(growthRate = 0.1, maxInFlightPulses = 10)
        val need = id.needs["test-need"]!!

        id.pulse() // fires
        assertTrue(need.inFlight)

        // Complete before timeout
        id.onImpulseCompleted("test-need", success = true)
        assertFalse(need.inFlight)
        assertEquals(0, need.consecutiveDenials)

        // Block re-firing so we can check denials cleanly
        egoBusy = true

        // Subsequent pulses should not trigger timeout behavior (in-flight was cleared)
        repeat(15) { id.pulse() }
        assertEquals(0, need.consecutiveDenials, "No timeout failure after explicit success")
    }

    // ── Reset floor ─────────────────────────────────────────────────────

    @Test
    fun `satisfaction decay respects reset floor`() {
        val id = Id(
            config = IdConfig(
                enabled = true,
                pulseIntervalMs = 1000,
                triggerThreshold = 0.0,
                maxInFlightPulses = 20,
                backoffPulses = 10,
                maxPendingImpulses = 1,
                needs = mapOf(
                    "floored" to NeedConfig(
                        growthRate = 0.5,
                        satisfactionDecay = 0.99,
                        resetFloor = 0.1,
                        cooldownPulses = 0,
                        prompt = "test!",
                        responseCurve = ResponseCurveConfig(type = "linear"),
                    ),
                ),
            ),
            instrumentation = recordingInstrumentation,
            scope = CoroutineScope(Dispatchers.Unconfined),
            enqueueImpulse = { enqueuedImpulses.add(it); true },
            hasPendingWork = { egoBusy },
        )

        val need = id.needs["floored"]!!
        id.pulse() // grows to 0.5, fires
        assertEquals(0.5, need.value, 1e-9)

        id.onImpulseCompleted("floored", success = true)
        // 0.5 * (1 - 0.99) = 0.005, but floor is 0.1
        assertEquals(0.1, need.value, 1e-9, "Value should be clamped to resetFloor")
    }

    // ── Multi-pulse deferred cycle ──────────────────────────────────────

    @Test
    fun `full deferred reset cycle - grow, fire, grow more, complete`() {
        // Use cooldownPulses=10 so cooldown survives the in-flight period (4 pulses).
        // maxInFlightPulses=50 to avoid timeout during the test.
        val id = buildId(growthRate = 0.1, satisfactionDecay = 0.8, cooldownPulses = 10, maxInFlightPulses = 50)
        val need = id.needs["test-need"]!!

        // Phase 1: Grow and fire
        id.pulse() // value = 0.1, fires. cooldownRemaining=10, inFlightPulsesRemaining=50
        assertEquals(1, enqueuedImpulses.size)
        assertTrue(need.inFlight)

        // Phase 2: Grow while in-flight (4 more pulses). Cooldown also decrements: 10→6
        repeat(4) { id.pulse() }
        assertEquals(0.5, need.value, 1e-9)
        assertTrue(need.inFlight)
        assertEquals(1, enqueuedImpulses.size, "No second impulse while in-flight")
        assertEquals(6, need.cooldownRemaining, "Cooldown decrements during in-flight: 10 - 4 = 6")

        // Phase 3: Complete with success (after 5 total pulses → cooldown = 6 remaining)
        id.onImpulseCompleted("test-need", success = true)
        // 0.5 * (1 - 0.8) = 0.1
        assertEquals(0.1, need.value, 1e-9)
        assertFalse(need.inFlight)

        // Phase 4: Cooldown still active (6 pulses remain). Need grows each pulse.
        // After 5 pulses, cooldown = 1 (still blocking)
        repeat(5) { id.pulse() }
        assertEquals(1, enqueuedImpulses.size, "Should not fire during remaining cooldown")
        assertEquals(1, need.cooldownRemaining, "One cooldown pulse remaining")

        // Phase 5: Cooldown expires on this pulse AND need fires in the same pulse.
        // Note: markInFlight resets cooldownRemaining, so we only check impulse count.
        id.pulse() // cooldown 1→0, eligible, fires → markInFlight resets cooldown
        assertEquals(2, enqueuedImpulses.size, "Should fire when cooldown expires")
    }
}
