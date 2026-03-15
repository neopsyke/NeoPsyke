package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import psyke.agent.model.PendingImpulse
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdTest {

    /** Records all impulses enqueued by the Id. */
    private val enqueuedImpulses = mutableListOf<PendingImpulse>()

    /** Simulated Ego busy state. */
    private var egoBusy = false

    /** Records all instrumentation events. */
    private val events = mutableListOf<AgentEvent>()

    private val recordingInstrumentation = object : AgentInstrumentation {
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun buildId(
        config: IdConfig = defaultConfig(),
        enqueueResult: Boolean = true,
    ): Id = Id(
        config = config,
        instrumentation = recordingInstrumentation,
        scope = CoroutineScope(Dispatchers.Unconfined),
        enqueueImpulse = { impulse ->
            enqueuedImpulses.add(impulse)
            enqueueResult
        },
        hasPendingWork = { egoBusy },
    )

    private fun defaultConfig(
        enabled: Boolean = true,
        triggerThreshold: Double = 0.5,
        thresholdOnUrgency: Boolean = true,
        maxInFlightPulses: Int = 20,
        backoffPulses: Int = 10,
        needs: Map<String, NeedConfig> = mapOf(
            "be-useful" to NeedConfig(
                description = "test drive",
                growthRate = 0.1,
                satisfactionDecay = 0.8,
                cooldownPulses = 2,
                prompt = "Be useful!",
                responseCurve = ResponseCurveConfig(type = "linear"),
                activityDecay = mapOf("action_executed" to 0.1),
            ),
        ),
    ) = IdConfig(
        enabled = enabled,
        pulseIntervalMs = 1000,
        triggerThreshold = triggerThreshold,
        thresholdOnUrgency = thresholdOnUrgency,
        maxConsecutiveDenials = 5,
        backoffPulses = backoffPulses,
        maxInFlightPulses = maxInFlightPulses,
        maxPendingImpulses = 1,
        needs = needs,
    )

    // ── Pulse basics ────────────────────────────────────────────────────

    @Test
    fun `pulse grows all needs`() {
        val id = buildId()
        val need = id.needs["be-useful"]!!
        assertEquals(0.0, need.value)

        id.pulse()
        assertEquals(0.1, need.value, 1e-9)

        id.pulse()
        assertEquals(0.2, need.value, 1e-9)
    }

    @Test
    fun `pulse emits id_pulse event`() {
        val id = buildId()
        id.pulse()

        val pulseEvents = events.filter { it.type == "id_pulse" }
        assertEquals(1, pulseEvents.size)
        assertEquals(1L, pulseEvents[0].data["pulse"])
    }

    // ── Idle gate ───────────────────────────────────────────────────────

    @Test
    fun `pulse does not fire impulse when Ego is busy`() {
        val id = buildId(config = defaultConfig(triggerThreshold = 0.0)) // threshold 0 so it fires immediately
        egoBusy = true

        id.pulse()
        assertTrue(enqueuedImpulses.isEmpty(), "Should not fire when Ego busy")
    }

    @Test
    fun `pulse fires impulse when Ego is idle and threshold exceeded`() {
        val id = buildId(config = defaultConfig(triggerThreshold = 0.0))
        egoBusy = false

        id.pulse() // need grows to 0.1, above threshold 0.0
        assertEquals(1, enqueuedImpulses.size)
        assertEquals("be-useful", enqueuedImpulses[0].needId)
    }

    // ── Threshold check ─────────────────────────────────────────────────

    @Test
    fun `pulse does not fire when value below threshold`() {
        val id = buildId(config = defaultConfig(triggerThreshold = 0.5))
        egoBusy = false

        // After 1 pulse: value = 0.1 (linear urgency = 0.1), below 0.5
        id.pulse()
        assertTrue(enqueuedImpulses.isEmpty())

        // After 5 pulses total: value = 0.5, urgency = 0.5, NOT above 0.5 (strict >)
        repeat(4) { id.pulse() }
        assertTrue(enqueuedImpulses.isEmpty(), "Should not fire when exactly at threshold")

        // 6th pulse: value = 0.6, above 0.5
        id.pulse()
        assertEquals(1, enqueuedImpulses.size)
    }

    @Test
    fun `thresholdOnUrgency uses curve-transformed value`() {
        val config = defaultConfig(
            triggerThreshold = 0.3,
            thresholdOnUrgency = true,
            needs = mapOf(
                "power-need" to NeedConfig(
                    description = "power test",
                    growthRate = 0.1,
                    cooldownPulses = 0,
                    prompt = "power!",
                    responseCurve = ResponseCurveConfig(type = "power", exponent = 3.0),
                ),
            ),
        )
        val id = buildId(config = config)

        // After 5 pulses: rawValue = 0.5, urgency = 0.5^3 = 0.125 < 0.3
        repeat(5) { id.pulse() }
        assertTrue(enqueuedImpulses.isEmpty(), "Power curve urgency 0.125 should be below 0.3")

        // After 8 pulses total: rawValue = 0.8, urgency = 0.8^3 = 0.512 > 0.3
        // But need enters cooldown/in-flight after first fire, so we need enough growth
        repeat(3) { id.pulse() }
        assertEquals(1, enqueuedImpulses.size)
    }

    @Test
    fun `thresholdOnUrgency false uses raw value`() {
        // Use 0.25 growth rate so raw value at 2 pulses is exactly 0.5
        val config = defaultConfig(
            triggerThreshold = 0.5,
            thresholdOnUrgency = false,
            needs = mapOf(
                "power-need" to NeedConfig(
                    description = "power test",
                    growthRate = 0.25,
                    cooldownPulses = 0,
                    prompt = "power!",
                    responseCurve = ResponseCurveConfig(type = "power", exponent = 3.0),
                ),
            ),
        )
        val id = buildId(config = config)

        // After 1 pulse: rawValue = 0.25, NOT above 0.5
        id.pulse()
        assertTrue(enqueuedImpulses.isEmpty(), "rawValue 0.25 should not exceed threshold 0.5")

        // After 2 pulses: rawValue = 0.5, NOT above 0.5 (strict >)
        id.pulse()
        assertTrue(enqueuedImpulses.isEmpty(), "rawValue 0.5 should not exceed strict > threshold 0.5")

        // After 3 pulses: rawValue = 0.75 > 0.5 (urgency = 0.75^3 ≈ 0.42, but raw is used)
        id.pulse()
        assertEquals(1, enqueuedImpulses.size, "rawValue 0.75 should fire with thresholdOnUrgency=false")
    }

    // ── Candidate selection (highest urgency wins) ──────────────────────

    @Test
    fun `highest urgency need wins when multiple above threshold`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "slow" to NeedConfig(
                    description = "slow",
                    growthRate = 0.05,
                    cooldownPulses = 0,
                    prompt = "slow!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
                "fast" to NeedConfig(
                    description = "fast",
                    growthRate = 0.2,
                    cooldownPulses = 0,
                    prompt = "fast!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)

        id.pulse()
        assertEquals(1, enqueuedImpulses.size)
        assertEquals("fast", enqueuedImpulses[0].needId, "Higher growth rate need should win")
    }

    @Test
    fun `response curve affects competition - logarithmic beats power at low values`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "power-need" to NeedConfig(
                    description = "power",
                    growthRate = 0.1,
                    cooldownPulses = 0,
                    prompt = "power!",
                    responseCurve = ResponseCurveConfig(type = "power", exponent = 3.0),
                ),
                "log-need" to NeedConfig(
                    description = "log",
                    growthRate = 0.1,
                    cooldownPulses = 0,
                    prompt = "log!",
                    responseCurve = ResponseCurveConfig(type = "logarithmic", scale = 10.0),
                ),
            ),
        )
        val id = buildId(config = config)

        id.pulse() // Both at rawValue 0.1. Power urgency = 0.001, Log urgency ≈ 0.289
        assertEquals(1, enqueuedImpulses.size)
        assertEquals("log-need", enqueuedImpulses[0].needId, "Logarithmic should win at low values")
    }

    // ── Cooldown enforcement ────────────────────────────────────────────

    @Test
    fun `need cannot fire again during cooldown`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "only" to NeedConfig(
                    description = "only need",
                    growthRate = 0.2,
                    cooldownPulses = 3,
                    prompt = "go!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)

        // First pulse fires
        id.pulse()
        assertEquals(1, enqueuedImpulses.size)

        // Complete the impulse to clear in-flight, but cooldown remains
        id.onImpulseCompleted("only", success = true)

        // Next 2 pulses: cooldown decrementing (started at 3, fire set it, each pulse decrements)
        id.pulse()
        id.pulse()
        assertEquals(1, enqueuedImpulses.size, "Should not fire during cooldown")

        // After cooldown expires, should fire again
        id.pulse()
        assertEquals(2, enqueuedImpulses.size, "Should fire after cooldown expires")
    }

    // ── In-flight blocking ──────────────────────────────────────────────

    @Test
    fun `need cannot fire when already in-flight`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "only" to NeedConfig(
                    description = "only need",
                    growthRate = 0.2,
                    cooldownPulses = 0,
                    prompt = "go!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)

        id.pulse() // fires, marks in-flight
        assertEquals(1, enqueuedImpulses.size)

        id.pulse() // in-flight, cannot fire again
        assertEquals(1, enqueuedImpulses.size, "Should not fire while in-flight")
    }

    @Test
    fun `Id does not emit a second impulse while one is pending globally`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "first" to NeedConfig(
                    description = "first",
                    growthRate = 0.4,
                    cooldownPulses = 0,
                    prompt = "first!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
                "second" to NeedConfig(
                    description = "second",
                    growthRate = 0.3,
                    cooldownPulses = 0,
                    prompt = "second!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)

        id.pulse() // fires one impulse
        assertEquals(1, enqueuedImpulses.size)

        // Without lifecycle completion callback, no additional impulse can be emitted.
        repeat(4) { id.pulse() }
        assertEquals(1, enqueuedImpulses.size, "Global pending impulse gate should block additional impulses")

        id.onImpulseDenied(enqueuedImpulses.first().needId)
        id.pulse()
        assertEquals(2, enqueuedImpulses.size, "A new impulse can fire after pending lifecycle is resolved")
    }

    // ── Queue full rejection ────────────────────────────────────────────

    @Test
    fun `impulse rejected when queue full emits pregate_blocked`() {
        val id = buildId(
            config = defaultConfig(triggerThreshold = 0.0),
            enqueueResult = false,
        )

        id.pulse()
        assertTrue(enqueuedImpulses.isNotEmpty(), "enqueueImpulse should be called")

        val blocked = events.filter { it.type == "id_pregate_blocked" }
        assertEquals(1, blocked.size)
        assertEquals("impulse_queue_full", blocked[0].data["reason"])
    }

    @Test
    fun `impulse rejected does not mark need in-flight`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "only" to NeedConfig(
                    description = "only",
                    growthRate = 0.2,
                    cooldownPulses = 0,
                    prompt = "go!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config, enqueueResult = false)

        id.pulse()
        assertFalse(id.needs["only"]!!.inFlight, "Should not be in-flight if queue rejected")
    }

    // ── Ego callbacks ───────────────────────────────────────────────────

    @Test
    fun `onActivity decays matching needs`() {
        val config = defaultConfig(
            needs = mapOf(
                "interact" to NeedConfig(
                    description = "interact",
                    growthRate = 0.5,
                    cooldownPulses = 0,
                    prompt = "interact!",
                    activityDecay = mapOf("input_received" to 0.15),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse() // grow to 0.5

        id.onActivity("input_received")
        assertEquals(0.35, id.needs["interact"]!!.value, 1e-9)
    }

    @Test
    fun `onActivity with compound key matches action type`() {
        val config = defaultConfig(
            needs = mapOf(
                "learn" to NeedConfig(
                    description = "learn",
                    growthRate = 0.5,
                    cooldownPulses = 0,
                    prompt = "learn!",
                    activityDecay = mapOf("action_executed_web_search" to 0.12),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse() // grow to 0.5

        id.onActivity("action_executed", "web_search")
        assertEquals(0.38, id.needs["learn"]!!.value, 1e-9)
    }

    @Test
    fun `onActivity does not decay needs without matching key`() {
        val config = defaultConfig(
            needs = mapOf(
                "learn" to NeedConfig(
                    description = "learn",
                    growthRate = 0.5,
                    cooldownPulses = 0,
                    prompt = "learn!",
                    activityDecay = mapOf("action_executed_web_search" to 0.12),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse() // grow to 0.5

        id.onActivity("input_received") // no match
        assertEquals(0.5, id.needs["learn"]!!.value, 1e-9)
    }

    @Test
    fun `onActivity emits id_activity_decay event`() {
        val config = defaultConfig(
            needs = mapOf(
                "test" to NeedConfig(
                    description = "test",
                    growthRate = 0.5,
                    cooldownPulses = 0,
                    prompt = "test!",
                    activityDecay = mapOf("input_received" to 0.1),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse()
        events.clear()

        id.onActivity("input_received")
        val decayEvents = events.filter { it.type == "id_activity_decay" }
        assertEquals(1, decayEvents.size)
        assertEquals("test", decayEvents[0].data["need_id"])
        assertEquals("input_received", decayEvents[0].data["event_type"])
    }

    @Test
    fun `onImpulseCompleted with success decays need`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "need" to NeedConfig(
                    description = "need",
                    growthRate = 0.5,
                    satisfactionDecay = 0.8,
                    cooldownPulses = 0,
                    prompt = "go!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse() // grows to 0.5, fires impulse
        assertEquals(0.5, id.needs["need"]!!.value, 1e-9)

        id.onImpulseCompleted("need", success = true)
        // 0.5 * (1 - 0.8) = 0.1
        assertEquals(0.1, id.needs["need"]!!.value, 1e-9)
        assertFalse(id.needs["need"]!!.inFlight)
    }

    @Test
    fun `onImpulseCompleted with failure increments denials`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "need" to NeedConfig(
                    description = "need",
                    growthRate = 0.5,
                    cooldownPulses = 0,
                    prompt = "go!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse()

        id.onImpulseCompleted("need", success = false)
        assertEquals(1, id.needs["need"]!!.consecutiveDenials)
        assertFalse(id.needs["need"]!!.inFlight)
    }

    @Test
    fun `onImpulseDenied increments denials and clears in-flight`() {
        val config = defaultConfig(
            triggerThreshold = 0.0,
            needs = mapOf(
                "need" to NeedConfig(
                    description = "need",
                    growthRate = 0.5,
                    cooldownPulses = 0,
                    prompt = "go!",
                    responseCurve = ResponseCurveConfig(type = "linear"),
                ),
            ),
        )
        val id = buildId(config = config)
        id.pulse()
        assertTrue(id.needs["need"]!!.inFlight)

        id.onImpulseDenied("need")
        assertEquals(1, id.needs["need"]!!.consecutiveDenials)
        assertFalse(id.needs["need"]!!.inFlight)
    }

    @Test
    fun `onImpulseDenied for unknown need is no-op`() {
        val id = buildId()
        // Should not throw
        id.onImpulseDenied("nonexistent")
        id.onImpulseCompleted("nonexistent", success = true)
    }

    // ── Impulse content ─────────────────────────────────────────────────

    @Test
    fun `fired impulse carries correct metadata`() {
        val id = buildId(config = defaultConfig(triggerThreshold = 0.0))
        id.pulse()

        val impulse = enqueuedImpulses.first()
        assertEquals("be-useful", impulse.needId)
        assertEquals("Be useful!", impulse.prompt)
        assertTrue(impulse.urgency > 0.0)
        assertTrue(impulse.rawValue > 0.0)
        assertEquals(Id.SESSION_ID, impulse.conversationContext.sessionId)
        assertEquals(Id.INTERLOCUTOR, impulse.conversationContext.interlocutor)
    }

    @Test
    fun `impulse fired event is emitted`() {
        val id = buildId(config = defaultConfig(triggerThreshold = 0.0))
        id.pulse()

        val fired = events.filter { it.type == "id_impulse_fired" }
        assertEquals(1, fired.size)
        assertEquals("be-useful", fired[0].data["need_id"])
    }

    // ── needUrgencies ───────────────────────────────────────────────────

    @Test
    fun `needUrgencies returns snapshot of all needs`() {
        val config = defaultConfig(
            needs = mapOf(
                "a" to NeedConfig(growthRate = 0.1, prompt = "a", responseCurve = ResponseCurveConfig(type = "linear")),
                "b" to NeedConfig(growthRate = 0.2, prompt = "b", responseCurve = ResponseCurveConfig(type = "linear")),
            ),
        )
        val id = buildId(config = config)
        id.pulse()

        val urgencies = id.needUrgencies()
        assertEquals(2, urgencies.size)
        assertEquals(0.1, urgencies["a"]!!, 1e-9)
        assertEquals(0.2, urgencies["b"]!!, 1e-9)
    }

    // ── Conversation context ────────────────────────────────────────────

    @Test
    fun `conversation context uses id internal session`() {
        val id = buildId()
        assertEquals("id:internal", id.conversationContext.sessionId)
        assertEquals("id", id.conversationContext.interlocutor.id)
        assertEquals("Id", id.conversationContext.interlocutor.label)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Test
    fun `start does nothing when disabled`() {
        val id = buildId(config = defaultConfig(enabled = false))
        id.start()
        // No exception, pulse loop not started
        id.close()
    }

    @Test
    fun `start does nothing when no needs configured`() {
        val id = buildId(config = defaultConfig(enabled = true, needs = emptyMap()))
        id.start()
        // No exception, pulse loop not started
        id.close()
    }
}
