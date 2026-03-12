package psyke.agent.id

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import psyke.agent.core.ConversationContext
import psyke.agent.core.Interlocutor
import psyke.agent.core.PendingImpulse
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import java.io.Closeable

private val logger = KotlinLogging.logger {}

/**
 * The Id — autonomous drive module inspired by the Freudian psyche.
 *
 * Maintains a set of [needs][NeedState] that grow over time. Each pulse,
 * the highest-urgency need above threshold can fire an impulse to the Ego
 * (via [enqueueImpulse]). The Ego processes it through its normal pipeline
 * (planner → superego → execution) and reports back via callbacks.
 *
 * The Id itself is primitive: it only grows needs, selects candidates, and
 * reacts to binary outcomes. All intelligence lives in the Ego.
 *
 * Thread safety: the pulse loop runs in a single coroutine. Callbacks from
 * the Ego ([onActivity], [onImpulseCompleted], [onImpulseDenied]) may be
 * called from the Ego's coroutine — these are short, non-blocking mutations
 * guarded by the effectively single-threaded agent scope.
 */
class Id(
    private val config: IdConfig,
    private val instrumentation: AgentInstrumentation,
    private val scope: CoroutineScope,
    /**
     * Callback to enqueue an impulse into the Ego's attention scheduler.
     * Returns true if the impulse was accepted (queue not full).
     */
    private val enqueueImpulse: (PendingImpulse) -> Boolean,
    /**
     * Callback to check whether the Ego has pending work (inputs, thoughts, actions).
     */
    private val hasPendingWork: () -> Boolean,
) : Closeable {

    val needs: Map<String, NeedState> = config.needs.mapValues { (name, needConfig) ->
        NeedState(
            name = name,
            config = needConfig,
            curve = ResponseCurve.fromConfig(needConfig.responseCurve),
        )
    }

    private var pulseJob: Job? = null
    private var pulseCount: Long = 0

    /** The conversation context used for all Id-initiated sessions. */
    val conversationContext: ConversationContext = ConversationContext(
        sessionId = SESSION_ID,
        interlocutor = INTERLOCUTOR,
    )

    // ── Lifecycle ────────────────────────────────────────────────────

    fun start() {
        if (!config.enabled) {
            logger.info { "Id module disabled." }
            return
        }
        if (config.needs.isEmpty()) {
            logger.warn { "Id module enabled but no needs configured." }
            return
        }
        logger.info {
            "Id module starting: ${config.needs.size} need(s), pulse interval ${config.pulseIntervalMs}ms"
        }
        pulseJob = scope.launch(CoroutineName("psyke-id-pulse")) {
            while (isActive) {
                delay(config.pulseIntervalMs)
                pulse()
            }
        }
    }

    override fun close() {
        pulseJob?.cancel()
        pulseJob = null
        logger.info { "Id module stopped." }
    }

    // ── Pulse loop ───────────────────────────────────────────────────

    internal fun pulse() {
        pulseCount++

        // 1. Grow all needs and decrement cooldowns.
        needs.values.forEach { need ->
            need.grow()
            need.decrementCooldowns(config.maxInFlightPulses)
        }

        // 2. Emit pulse instrumentation event.
        emitPulseEvent()

        // 3. Idle gate: don't fire impulses if the Ego is busy.
        if (hasPendingWork()) {
            return
        }

        // 4. Collect eligible candidates above threshold.
        val threshold = config.triggerThreshold
        val candidates = needs.values.filter { need ->
            need.isEligible() && effectiveValue(need) > threshold
        }
        if (candidates.isEmpty()) return

        // 5. Winner = highest effective urgency.
        val winner = candidates.maxBy { it.urgency }

        // 6. Enqueue impulse.
        val impulse = PendingImpulse(
            id = pulseCount,
            needId = winner.name,
            prompt = winner.config.prompt,
            urgency = winner.urgency,
            rawValue = winner.value,
            conversationContext = conversationContext,
        )

        val accepted = enqueueImpulse(impulse)
        if (accepted) {
            winner.markInFlight(config.maxInFlightPulses)
            emitImpulseFired(winner, impulse)
            logger.debug { "Id impulse fired: need='${winner.name}' urgency=${winner.urgency}" }
        } else {
            emitPreGateBlocked(winner.name, "impulse_queue_full")
            logger.debug { "Id impulse rejected (queue full): need='${winner.name}'" }
        }
    }

    /** Returns the urgency or raw value depending on [IdConfig.thresholdOnUrgency]. */
    private fun effectiveValue(need: NeedState): Double =
        if (config.thresholdOnUrgency) need.urgency else need.value

    // ── Ego callbacks ────────────────────────────────────────────────

    /**
     * Called by the Ego when relevant activity occurs (user input, action execution, etc.).
     * Passively decays matching needs based on their [NeedConfig.activityDecay] map.
     *
     * @param eventType the activity type key (e.g., "input_received", "action_executed")
     * @param actionType optional action type (e.g., "web_search") for compound keys
     */
    fun onActivity(eventType: String, actionType: String? = null) {
        val compoundKey = if (actionType != null) "${eventType}_$actionType" else null
        needs.values.forEach { need ->
            val decay = need.config.activityDecay[eventType]
                ?: (compoundKey?.let { need.config.activityDecay[it] })
            if (decay != null && decay > 0.0) {
                val before = need.value
                need.decayOnActivity(decay)
                emitActivityDecay(need.name, eventType, decay, before, need.value)
            }
        }
    }

    /**
     * Called by the Ego when impulse-driven work completes (loop drain for an Id root).
     *
     * @param needId the need that generated the impulse
     * @param success true if at least one action was executed successfully
     */
    fun onImpulseCompleted(needId: String, success: Boolean) {
        val need = needs[needId] ?: return
        need.clearInFlight(success, config.backoffPulses)
        emitImpulseCompleted(needId, success, need.value)
        logger.debug { "Id impulse completed: need='$needId' success=$success newValue=${need.value}" }
    }

    /**
     * Called by the Ego when an impulse is denied before execution starts
     * (planner noop or superego denial).
     */
    fun onImpulseDenied(needId: String) {
        val need = needs[needId] ?: return
        need.onDenied(config.backoffPulses)
        emitImpulseDenied(needId, need.consecutiveDenials)
        logger.debug { "Id impulse denied: need='$needId' denials=${need.consecutiveDenials}" }
    }

    /**
     * Called by the Ego when an impulse is accepted (planner produced a plan/action).
     * The need should already be marked in-flight from [pulse], but this is a
     * confirmation point for instrumentation.
     */
    fun onImpulseAccepted(needId: String) {
        emitImpulseAccepted(needId)
        logger.debug { "Id impulse accepted: need='$needId'" }
    }

    /** Read-only snapshot of all need urgencies for planner context. */
    fun needUrgencies(): Map<String, Double> =
        needs.mapValues { (_, state) -> state.urgency }

    // ── Instrumentation ──────────────────────────────────────────────

    private fun emitPulseEvent() {
        instrumentation.emit(
            AgentEvent(
                type = "id_pulse",
                data = mapOf(
                    "pulse" to pulseCount,
                    "needs" to needs.values.map { it.snapshot() },
                    "ego_busy" to hasPendingWork(),
                ),
            )
        )
    }

    private fun emitImpulseFired(need: NeedState, impulse: PendingImpulse) {
        instrumentation.emit(
            AgentEvent(
                type = "id_impulse_fired",
                data = mapOf(
                    "need_id" to need.name,
                    "urgency" to need.urgency,
                    "raw_value" to need.value,
                    "root_impulse_id" to impulse.rootImpulseId,
                ),
            )
        )
    }

    private fun emitImpulseAccepted(needId: String) {
        instrumentation.emit(
            AgentEvent(type = "id_impulse_accepted", data = mapOf("need_id" to needId))
        )
    }

    private fun emitImpulseCompleted(needId: String, success: Boolean, newValue: Double) {
        instrumentation.emit(
            AgentEvent(
                type = "id_impulse_completed",
                data = mapOf("need_id" to needId, "success" to success, "new_value" to newValue),
            )
        )
    }

    private fun emitImpulseDenied(needId: String, consecutiveDenials: Int) {
        instrumentation.emit(
            AgentEvent(
                type = "id_impulse_denied",
                data = mapOf("need_id" to needId, "consecutive_denials" to consecutiveDenials),
            )
        )
    }

    private fun emitActivityDecay(
        needId: String, eventType: String, decay: Double, before: Double, after: Double
    ) {
        instrumentation.emit(
            AgentEvent(
                type = "id_activity_decay",
                data = mapOf(
                    "need_id" to needId,
                    "event_type" to eventType,
                    "decay" to decay,
                    "before" to before,
                    "after" to after,
                ),
            )
        )
    }

    private fun emitPreGateBlocked(needId: String, reason: String) {
        instrumentation.emit(
            AgentEvent(
                type = "id_pregate_blocked",
                data = mapOf("need_id" to needId, "reason" to reason),
            )
        )
    }

    companion object {
        const val SESSION_ID = "id:internal"
        val INTERLOCUTOR = Interlocutor(id = "id", label = "Id")
    }
}
