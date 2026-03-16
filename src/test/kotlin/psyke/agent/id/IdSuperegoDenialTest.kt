package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import psyke.agent.model.ActionOrigin
import psyke.agent.model.ActionType
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingImpulse
import psyke.agent.superego.SuperegoPolicy
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the Superego denial → Id backoff cycle.
 *
 * Verifies that repeated denials (via [Id.onImpulseDenied]) increment the
 * consecutive denial counter, trigger exponential backoff after the threshold,
 * and that recovery is possible. Also tests that [SuperegoPolicy] produces
 * stricter directives when actions originate from the Id.
 */
class IdSuperegoDenialTest {

    private val enqueuedImpulses = mutableListOf<PendingImpulse>()
    private var egoBusy = false
    private val events = mutableListOf<AgentEvent>()

    private val recordingInstrumentation = object : AgentInstrumentation {
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun buildId(
        growthRate: Double = 0.5,
        cooldownPulses: Int = 0,
        backoffPulses: Int = 3,
        maxConsecutiveDenials: Int = 5,
        maxInFlightPulses: Int = 20,
        enqueueResult: Boolean = true,
    ): Id = Id(
        config = IdConfig(
            enabled = true,
            pulseIntervalMs = 1000,
            triggerThreshold = 0.0,
            thresholdOnUrgency = true,
            maxConsecutiveDenials = maxConsecutiveDenials,
            backoffPulses = backoffPulses,
            maxInFlightPulses = maxInFlightPulses,
            maxPendingImpulses = 1,
            needs = mapOf(
                "test-need" to NeedConfig(
                    description = "test",
                    growthRate = growthRate,
                    satisfactionDecay = 0.8,
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

    // ── Consecutive denial tracking ─────────────────────────────────────

    @Test
    fun `each denial increments consecutive denial counter`() {
        val id = buildId()
        val need = id.needs["test-need"]!!

        for (i in 1..4) {
            id.pulse()
            id.onImpulseDenied("test-need")
            assertEquals(i, need.consecutiveDenials, "Denial $i should increment counter")
            assertFalse(need.inFlight, "Should clear in-flight on denial")
        }
    }

    @Test
    fun `denial emits id_impulse_denied event`() {
        val id = buildId()
        id.pulse()
        events.clear()

        id.onImpulseDenied("test-need")

        val deniedEvents = events.filter { it.type == "id_impulse_denied" }
        assertEquals(1, deniedEvents.size)
        assertEquals("test-need", deniedEvents[0].data["need_id"])
        assertEquals(1, deniedEvents[0].data["consecutive_denials"])
    }

    // ── Backoff triggers after MAX_DENIALS_BEFORE_BACKOFF (5) ───────────

    @Test
    fun `backoff triggers after 5 consecutive denials`() {
        val id = buildId(backoffPulses = 3)
        val need = id.needs["test-need"]!!

        // Deny 5 times (MAX_DENIALS_BEFORE_BACKOFF = 5)
        for (i in 1..5) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }

        assertEquals(5, need.consecutiveDenials)
        // backoffPulsesRemaining = backoffPulses * 2^(5/5) = 3 * 2^1 = 6
        assertEquals(6, need.backoffPulsesRemaining, "Should have backoff after 5 denials")
        assertFalse(need.isEligible(), "Should not be eligible during backoff")
    }

    @Test
    fun `backoff threshold honors maxConsecutiveDenials config`() {
        val id = buildId(backoffPulses = 3, maxConsecutiveDenials = 3)
        val need = id.needs["test-need"]!!

        repeat(2) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(0, need.backoffPulsesRemaining, "No backoff before configured threshold")

        id.pulse()
        id.onImpulseDenied("test-need")
        assertEquals(3, need.consecutiveDenials)
        assertEquals(6, need.backoffPulsesRemaining, "Backoff should trigger at configured denial threshold")
    }

    @Test
    fun `no backoff before reaching 5 denials`() {
        val id = buildId(backoffPulses = 3)
        val need = id.needs["test-need"]!!

        // Deny 4 times
        for (i in 1..4) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }

        assertEquals(4, need.consecutiveDenials)
        assertEquals(0, need.backoffPulsesRemaining, "No backoff at 4 denials")
        assertTrue(need.isEligible(), "Should still be eligible")
    }

    // ── Backoff decrement and recovery ──────────────────────────────────

    @Test
    fun `backoff decrements each pulse and allows firing after expiry`() {
        val id = buildId(backoffPulses = 3)
        val need = id.needs["test-need"]!!

        // Trigger backoff (5 denials)
        for (i in 1..5) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }

        val backoff = need.backoffPulsesRemaining
        assertTrue(backoff > 0, "Should have positive backoff")

        // Pulse through the backoff period (each pulse decrements by 1)
        val impulsesBefore = enqueuedImpulses.size
        for (i in 1..backoff) {
            id.pulse()
            assertFalse(need.isEligible() && need.backoffPulsesRemaining > 0,
                "Should not be eligible during backoff")
        }

        // After backoff expired, next pulse should allow firing
        // Need to pulse once more after backoff expires
        id.pulse()
        val impulsesAfter = enqueuedImpulses.size
        assertTrue(impulsesAfter > impulsesBefore, "Should fire after backoff expires")
    }

    // ── Exponential backoff escalation ───────────────────────────────────

    @Test
    fun `backoff escalates exponentially with more denial rounds`() {
        val id = buildId(backoffPulses = 3, growthRate = 0.5)
        val need = id.needs["test-need"]!!

        // Round 1: 5 denials -> backoff = 3 * 2^1 = 6
        for (i in 1..5) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(6, need.backoffPulsesRemaining, "First backoff: 3 * 2^1 = 6")

        // Wait out the backoff
        repeat(need.backoffPulsesRemaining + 1) { id.pulse() }

        // Round 2: 5 more denials (total 10) -> backoff = 3 * 2^2 = 12
        for (i in 1..5) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(10, need.consecutiveDenials)
        assertEquals(12, need.backoffPulsesRemaining, "Second backoff: 3 * 2^2 = 12")
    }

    @Test
    fun `backoff escalation is capped at MAX_BACKOFF_ESCALATION`() {
        val id = buildId(backoffPulses = 2, growthRate = 0.5)
        val need = id.needs["test-need"]!!

        // Need to reach denial count 20 for escalation 4 (20/5 = 4)
        // MAX_BACKOFF_ESCALATION = 4, so backoff = 2 * 2^4 = 32
        // To accumulate 20 denials, we need to fire-deny in cycles
        // Since we're testing the cap, let's just simulate with direct onDenied calls
        for (round in 1..4) {
            // Each round: 5 denials + wait out backoff
            for (i in 1..5) {
                id.pulse()
                id.onImpulseDenied("test-need")
            }
            // Wait out backoff if we're not on the last round
            if (round < 4) {
                repeat(need.backoffPulsesRemaining + 1) { id.pulse() }
            }
        }

        assertEquals(20, need.consecutiveDenials)
        // backoff = 2 * 2^4 = 32 (capped at MAX_BACKOFF_ESCALATION = 4)
        assertEquals(32, need.backoffPulsesRemaining, "Backoff capped at 2 * 2^4 = 32")

        // Round 5: even more denials, still capped
        repeat(need.backoffPulsesRemaining + 1) { id.pulse() }
        for (i in 1..5) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(25, need.consecutiveDenials)
        // escalation = min(25/5, 4) = 4, backoff = 2 * 2^4 = 32 (still capped)
        assertEquals(32, need.backoffPulsesRemaining, "Backoff still capped at 32")
    }

    // ── Success resets denial counter and backoff ────────────────────────

    @Test
    fun `successful completion resets denial counter and prevents future backoff`() {
        val id = buildId(backoffPulses = 3)
        val need = id.needs["test-need"]!!

        // Accumulate 4 denials (just below threshold)
        for (i in 1..4) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(4, need.consecutiveDenials)

        // Fire and succeed
        id.pulse()
        id.onImpulseCompleted("test-need", success = true)
        assertEquals(0, need.consecutiveDenials, "Success resets denial counter")

        // Next 4 denials should not trigger backoff
        for (i in 1..4) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(4, need.consecutiveDenials)
        assertEquals(0, need.backoffPulsesRemaining, "No backoff with only 4 denials after reset")
    }

    // ── SuperegoPolicy Id-origin directives (structural only) ───────────

    @Test
    fun `SuperegoPolicy includes extra directives when origin is ID`() {
        val origin = ActionOrigin(source = OriginSource.ID, needId = "be-useful")
        val withId = SuperegoPolicy.forAction(actionType = ActionType.WEB_SEARCH, origin = origin)
        val withoutId = SuperegoPolicy.forAction(actionType = ActionType.WEB_SEARCH, origin = null)

        assertTrue(withId.general.size > withoutId.general.size,
            "Id-origin should inject additional directives beyond the general set")
    }

    @Test
    fun `SuperegoPolicy does not add Id directives for USER origin`() {
        val userOrigin = ActionOrigin(source = OriginSource.USER)
        val withUser = SuperegoPolicy.forAction(actionType = ActionType.WEB_SEARCH, origin = userOrigin)
        val withNull = SuperegoPolicy.forAction(actionType = ActionType.WEB_SEARCH, origin = null)

        assertEquals(withNull.general.size, withUser.general.size,
            "USER and null origin should produce the same general directive set")
    }

    // ── Denial from failure vs planner noop ─────────────────────────────

    @Test
    fun `failure completion and denial both increment denials`() {
        val id = buildId()
        val need = id.needs["test-need"]!!

        // Denial via planner noop
        id.pulse()
        id.onImpulseDenied("test-need")
        assertEquals(1, need.consecutiveDenials)

        // Denial via execution failure
        id.pulse()
        id.onImpulseCompleted("test-need", success = false)
        assertEquals(2, need.consecutiveDenials)

        // Another denial
        id.pulse()
        id.onImpulseDenied("test-need")
        assertEquals(3, need.consecutiveDenials)
    }

    @Test
    fun `mixed denials and failures still reach backoff threshold`() {
        val id = buildId(backoffPulses = 2)
        val need = id.needs["test-need"]!!

        // 3 denials + 2 failures = 5 total
        id.pulse(); id.onImpulseDenied("test-need")     // 1
        id.pulse(); id.onImpulseDenied("test-need")     // 2
        id.pulse(); id.onImpulseCompleted("test-need", success = false)  // 3
        id.pulse(); id.onImpulseDenied("test-need")     // 4
        id.pulse(); id.onImpulseCompleted("test-need", success = false)  // 5

        assertEquals(5, need.consecutiveDenials)
        assertTrue(need.backoffPulsesRemaining > 0, "Should trigger backoff at 5 denials")
    }

    // ── Full denial → backoff → recovery → success cycle ────────────────

    @Test
    fun `full cycle - denial, backoff, recovery, success`() {
        val id = buildId(backoffPulses = 2, growthRate = 0.5)
        val need = id.needs["test-need"]!!

        // Phase 1: Deny 5 times
        for (i in 1..5) {
            id.pulse()
            id.onImpulseDenied("test-need")
        }
        assertEquals(5, need.consecutiveDenials)
        val backoff = need.backoffPulsesRemaining
        assertTrue(backoff > 0, "Should be in backoff")

        // Phase 2: Wait out backoff
        val impulseCountBeforeRecovery = enqueuedImpulses.size
        repeat(backoff) { id.pulse() }
        assertEquals(0, need.backoffPulsesRemaining, "Backoff should have expired")

        // Phase 3: Recovery - need fires again
        id.pulse()
        assertTrue(enqueuedImpulses.size > impulseCountBeforeRecovery,
            "Should fire after backoff recovery")

        // Phase 4: This time, succeed
        id.onImpulseCompleted("test-need", success = true)
        assertEquals(0, need.consecutiveDenials, "Success resets denial counter")
        assertFalse(need.inFlight)

        // Phase 5: Next impulse starts fresh (no backoff)
        id.pulse()
        id.onImpulseDenied("test-need")
        assertEquals(1, need.consecutiveDenials, "Counter starts fresh after success")
        assertEquals(0, need.backoffPulsesRemaining, "No backoff for 1 denial")
    }
}
