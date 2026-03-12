package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import psyke.agent.core.PendingImpulse
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the Id's activity decay mechanism.
 *
 * Verifies that Ego activity callbacks ([Id.onActivity]) selectively decay
 * matching needs based on their [NeedConfig.activityDecay] maps, including
 * simple keys (e.g. "input_received") and compound keys (e.g. "action_executed_web_search").
 * Tests multi-need scenarios where different needs respond to different activity types.
 */
class IdActivityDecayTest {

    private val enqueuedImpulses = mutableListOf<PendingImpulse>()
    private val events = mutableListOf<AgentEvent>()

    private val recordingInstrumentation = object : AgentInstrumentation {
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun buildMultiNeedId(
        growthRate: Double = 0.5,
    ): Id = Id(
        config = IdConfig(
            enabled = true,
            pulseIntervalMs = 1000,
            triggerThreshold = 0.5,
            thresholdOnUrgency = true,
            maxInFlightPulses = 20,
            backoffPulses = 10,
            maxPendingImpulses = 1,
            needs = mapOf(
                "be-useful" to NeedConfig(
                    description = "Drive to provide value proactively",
                    growthRate = growthRate,
                    cooldownPulses = 5,
                    prompt = "Be useful!",
                    responseCurve = ResponseCurveConfig(type = "power", exponent = 3.0),
                    activityDecay = mapOf(
                        "action_executed" to 0.10,
                    ),
                ),
                "reach-out" to NeedConfig(
                    description = "Drive to proactively contact the user",
                    growthRate = growthRate,
                    cooldownPulses = 5,
                    prompt = "Reach out!",
                    responseCurve = ResponseCurveConfig(type = "sigmoid", steepness = 10.0, midpoint = 0.5),
                    activityDecay = mapOf(
                        "input_received" to 0.15,
                        "answer_delivered" to 0.10,
                    ),
                ),
                "learn-something" to NeedConfig(
                    description = "Drive to acquire new knowledge",
                    growthRate = growthRate,
                    cooldownPulses = 5,
                    prompt = "Learn!",
                    responseCurve = ResponseCurveConfig(type = "logarithmic", scale = 10.0),
                    activityDecay = mapOf(
                        "action_executed_web_search" to 0.12,
                        "action_executed_website_fetch" to 0.12,
                    ),
                ),
            ),
        ),
        instrumentation = recordingInstrumentation,
        scope = CoroutineScope(Dispatchers.Unconfined),
        enqueueImpulse = { impulse ->
            enqueuedImpulses.add(impulse)
            true
        },
        hasPendingWork = { false },
    )

    // ── Selective decay on different activity types ──────────────────────

    @Test
    fun `user input decays reach-out but not others`() {
        val id = buildMultiNeedId()
        id.pulse() // all needs grow to 0.5

        val reachOutBefore = id.needs["reach-out"]!!.value
        val usefulBefore = id.needs["be-useful"]!!.value
        val learnBefore = id.needs["learn-something"]!!.value

        id.onActivity("input_received")

        assertEquals(reachOutBefore - 0.15, id.needs["reach-out"]!!.value, 1e-9,
            "reach-out should decay by 0.15")
        assertEquals(usefulBefore, id.needs["be-useful"]!!.value, 1e-9,
            "be-useful should not be affected by input_received")
        assertEquals(learnBefore, id.needs["learn-something"]!!.value, 1e-9,
            "learn-something should not be affected by input_received")
    }

    @Test
    fun `action execution decays be-useful but not others`() {
        val id = buildMultiNeedId()
        id.pulse()

        val usefulBefore = id.needs["be-useful"]!!.value
        val reachOutBefore = id.needs["reach-out"]!!.value
        val learnBefore = id.needs["learn-something"]!!.value

        id.onActivity("action_executed")

        assertEquals(usefulBefore - 0.10, id.needs["be-useful"]!!.value, 1e-9,
            "be-useful should decay by 0.10")
        assertEquals(reachOutBefore, id.needs["reach-out"]!!.value, 1e-9,
            "reach-out should not be affected by action_executed")
        assertEquals(learnBefore, id.needs["learn-something"]!!.value, 1e-9,
            "learn-something should not be affected by generic action_executed")
    }

    @Test
    fun `web search action decays learn-something via compound key`() {
        val id = buildMultiNeedId()
        id.pulse()

        val learnBefore = id.needs["learn-something"]!!.value
        val usefulBefore = id.needs["be-useful"]!!.value
        val reachOutBefore = id.needs["reach-out"]!!.value

        id.onActivity("action_executed", "web_search")

        assertEquals(learnBefore - 0.12, id.needs["learn-something"]!!.value, 1e-9,
            "learn-something should decay by 0.12 via compound key action_executed_web_search")
        assertEquals(usefulBefore - 0.10, id.needs["be-useful"]!!.value, 1e-9,
            "be-useful should also decay (matches simple key 'action_executed')")
        assertEquals(reachOutBefore, id.needs["reach-out"]!!.value, 1e-9,
            "reach-out should not be affected")
    }

    @Test
    fun `website fetch action decays learn-something`() {
        val id = buildMultiNeedId()
        id.pulse()

        val learnBefore = id.needs["learn-something"]!!.value

        id.onActivity("action_executed", "website_fetch")

        assertEquals(learnBefore - 0.12, id.needs["learn-something"]!!.value, 1e-9,
            "learn-something should decay from website_fetch compound key")
    }

    @Test
    fun `answer delivery decays reach-out`() {
        val id = buildMultiNeedId()
        id.pulse()

        val reachOutBefore = id.needs["reach-out"]!!.value

        id.onActivity("answer_delivered")

        assertEquals(reachOutBefore - 0.10, id.needs["reach-out"]!!.value, 1e-9,
            "reach-out should decay by 0.10 on answer_delivered")
    }

    // ── Cumulative decay ────────────────────────────────────────────────

    @Test
    fun `multiple activities decay cumulatively`() {
        val id = buildMultiNeedId()
        id.pulse() // all at 0.5

        val reachOutNeed = id.needs["reach-out"]!!
        assertEquals(0.5, reachOutNeed.value, 1e-9)

        id.onActivity("input_received")  // -0.15 -> 0.35
        id.onActivity("answer_delivered") // -0.10 -> 0.25

        assertEquals(0.25, reachOutNeed.value, 1e-9,
            "Multiple decays should accumulate")
    }

    @Test
    fun `decay never goes below zero`() {
        val id = buildMultiNeedId(growthRate = 0.1)
        id.pulse() // all at 0.1

        val reachOutNeed = id.needs["reach-out"]!!
        assertEquals(0.1, reachOutNeed.value, 1e-9)

        // Decay 0.15 from a value of 0.1 -> should floor at 0.0
        id.onActivity("input_received")
        assertEquals(0.0, reachOutNeed.value, 1e-9,
            "Decay should not go below zero")
    }

    @Test
    fun `no decay for unmatched activity type`() {
        val id = buildMultiNeedId()
        id.pulse()

        val usefulBefore = id.needs["be-useful"]!!.value
        val reachOutBefore = id.needs["reach-out"]!!.value
        val learnBefore = id.needs["learn-something"]!!.value

        id.onActivity("some_unknown_event")

        assertEquals(usefulBefore, id.needs["be-useful"]!!.value, 1e-9)
        assertEquals(reachOutBefore, id.needs["reach-out"]!!.value, 1e-9)
        assertEquals(learnBefore, id.needs["learn-something"]!!.value, 1e-9)
    }

    @Test
    fun `no decay for action type with no compound match`() {
        val id = buildMultiNeedId()
        id.pulse()

        val learnBefore = id.needs["learn-something"]!!.value

        // "action_executed" with "send_message" -> compound key "action_executed_send_message"
        // which doesn't match learn-something (only web_search and website_fetch do)
        id.onActivity("action_executed", "send_message")

        assertEquals(learnBefore, id.needs["learn-something"]!!.value, 1e-9,
            "learn-something should not decay for unmatched compound key")
    }

    // ── Instrumentation ─────────────────────────────────────────────────

    @Test
    fun `activity decay emits events for each decayed need`() {
        val id = buildMultiNeedId()
        id.pulse()
        events.clear()

        // web_search decays both be-useful (action_executed) and learn-something (compound key)
        id.onActivity("action_executed", "web_search")

        val decayEvents = events.filter { it.type == "id_activity_decay" }
        assertEquals(2, decayEvents.size, "Should emit decay for both be-useful and learn-something")

        val needIds = decayEvents.map { it.data["need_id"] }.toSet()
        assertTrue("be-useful" in needIds, "be-useful should appear in decay events")
        assertTrue("learn-something" in needIds, "learn-something should appear in decay events")
    }

    @Test
    fun `no events emitted for unmatched activity`() {
        val id = buildMultiNeedId()
        id.pulse()
        events.clear()

        id.onActivity("unknown_event_type")

        val decayEvents = events.filter { it.type == "id_activity_decay" }
        assertEquals(0, decayEvents.size, "No events for unmatched activity")
    }

    // ── Decay affects urgency and threshold triggering ───────────────────

    @Test
    fun `decay lowers urgency below threshold preventing impulse`() {
        val id = Id(
            config = IdConfig(
                enabled = true,
                pulseIntervalMs = 1000,
                triggerThreshold = 0.5,
                thresholdOnUrgency = false, // use raw value for simplicity
                maxInFlightPulses = 20,
                backoffPulses = 10,
                maxPendingImpulses = 1,
                needs = mapOf(
                    "test" to NeedConfig(
                        growthRate = 0.25,
                        cooldownPulses = 0,
                        prompt = "test!",
                        responseCurve = ResponseCurveConfig(type = "linear"),
                        activityDecay = mapOf("input_received" to 0.5),
                    ),
                ),
            ),
            instrumentation = recordingInstrumentation,
            scope = CoroutineScope(Dispatchers.Unconfined),
            enqueueImpulse = { enqueuedImpulses.add(it); true },
            hasPendingWork = { false },
        )

        // Grow to 0.75 (3 pulses * 0.25)
        repeat(3) { id.pulse() }
        // Would have fired at pulse 3 (0.75 > 0.5), let's check
        assertTrue(enqueuedImpulses.isNotEmpty(), "Should have fired at 0.75")
        enqueuedImpulses.clear()

        // Complete to clear in-flight
        id.onImpulseCompleted("test", success = true)

        // Decay heavily: value was decayed by satisfaction -> 0.75*(1-0.8)=0.15
        // Now give input_received decay to further reduce
        id.onActivity("input_received") // 0.15 - 0.5 = 0.0 (floored)

        // Next pulse grows to 0.25 (below threshold 0.5), should NOT fire
        id.pulse()
        assertTrue(enqueuedImpulses.isEmpty(), "Should not fire when below threshold after heavy decay")
    }
}
