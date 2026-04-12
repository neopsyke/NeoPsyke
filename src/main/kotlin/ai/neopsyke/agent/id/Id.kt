package ai.neopsyke.agent.id

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import java.io.Closeable

private val logger = KotlinLogging.logger {}

/**
 * The Id — autonomous drive module inspired by the Freudian psyche.
 *
 * Maintains a set of [needs][NeedState] that grow over time. Each pulse,
 * the highest-tension need above threshold can fire an impulse to the Ego
 * (via [enqueueImpulse]). The Ego processes it through its normal pipeline
 * (planner → superego → execution) and reports back via callbacks.
 *
 * The Id itself is primitive: it only grows needs, selects candidates, and
 * reacts to binary outcomes. All intelligence lives in the Ego.
 *
 * Thread safety: pulse updates and Ego callbacks can run on different threads.
 * All mutable Id state is guarded by [stateLock].
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
     * Callback to check whether the Ego has pending work (inputs, continuations, actions).
     */
    private val hasPendingWork: () -> Boolean,
    /**
     * Callback to wake the Ego loop after an impulse is enqueued.
     * Injects an Id cue stimulus into the sensory channel so
     * the Ego processes the impulse without waiting for user input.
     */
    private val notifyEgo: () -> Unit = {},
) : Closeable {

    val needs: Map<String, NeedState> = config.needs
        .filterValues { it.enabled }
        .mapValues { (name, needConfig) ->
            NeedState(
                name = name,
                config = needConfig,
                curve = ResponseCurve.fromConfig(needConfig.responseCurve),
                maxConsecutiveDenials = config.maxConsecutiveDenials,
            )
        }

    private val stateLock = Any()
    private var pulseJob: Job? = null
    private var pulseCount: Long = 0
    private var pendingImpulse: PendingImpulseLifecycle? = null

    /** The conversation context used for all Id-initiated sessions. */
    val conversationContext: ConversationContext = ConversationContext(
        sessionId = SESSION_ID,
        interlocutor = INTERLOCUTOR,
        security = ConversationSecurityContexts.internalAutomation(
            provider = "id",
            channelId = SESSION_ID,
        ),
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
        pulseJob = scope.launch(CoroutineName("neopsyke-id-pulse")) {
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

    internal fun pulse() = synchronized(stateLock) {
        pulseCount++

        // 1. Grow all needs and decrement cooldowns.
        needs.values.forEach { need ->
            need.grow()
            need.decrementCooldowns(config.maxInFlightPulses)
        }
        // If an in-flight impulse timed out during cooldown decrement, release pending lock.
        pendingImpulse?.let { active ->
            val pendingNeed = needs[active.needId]
            if (pendingNeed == null || !pendingNeed.inFlight) {
                pendingImpulse = null
                if (pendingNeed != null) {
                    emitImpulseDenied(active.needId, pendingNeed.consecutiveDenials)
                }
            }
        }

        // 2. Emit pulse instrumentation event.
        emitPulseEvent()

        // 3. Do not fire new impulses while one lifecycle is still pending.
        pendingImpulse?.let { active ->
            emitPreGateBlocked(active.needId, "impulse_pending")
            return
        }

        // 4. Idle gate: don't fire impulses if the Ego is busy.
        if (hasPendingWork()) {
            return
        }

        // 5. Collect eligible candidates above threshold.
        val threshold = config.triggerThreshold
        val candidates = needs.values.filter { need ->
            need.isEligible() && effectiveValue(need) > threshold
        }
        if (candidates.isEmpty()) return

        // 6. Winner = highest effective tension.
        val winner = candidates.maxBy { it.tension }

        // 7. Enqueue impulse.
        val impulse = PendingImpulse(
            id = pulseCount,
            needId = winner.name,
            prompt = winner.config.prompt,
            tension = winner.tension,
            rawValue = winner.value,
            conversationContext = conversationContext,
        )

        val accepted = enqueueImpulse(impulse)
        if (accepted) {
            winner.markInFlight(config.maxInFlightPulses)
            pendingImpulse = PendingImpulseLifecycle(
                needId = winner.name,
                rootImpulseId = impulse.rootImpulseId
            )
            emitImpulseFired(winner, impulse)
            notifyEgo()
            logger.debug { "Id impulse fired: need='${winner.name}' tension=${winner.tension}" }
        } else {
            emitPreGateBlocked(winner.name, "impulse_queue_full")
            logger.debug { "Id impulse rejected (queue full): need='${winner.name}'" }
        }
    }

    /** Returns the tension or raw value depending on [IdConfig.thresholdOnTension]. */
    private fun effectiveValue(need: NeedState): Double =
        if (config.thresholdOnTension) need.tension else need.value

    // ── Ego callbacks ────────────────────────────────────────────────

    /**
     * Called by the Ego when relevant activity occurs (user input, action execution, etc.).
     * Passively decays matching needs based on their [NeedConfig.activityDecay] map.
     *
     * @param eventType the activity type key (e.g., "input_received", "action_executed")
     * @param actionType optional action type (e.g., "web_search") for compound keys
     */
    fun onActivity(eventType: String, actionType: String? = null) {
        synchronized(stateLock) {
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
    }

    /**
     * Called by the Ego when impulse-driven work completes (loop drain for an Id root).
     *
     * @param needId the need that generated the impulse
     * @param success true if at least one action was executed successfully
     */
    fun onImpulseCompleted(needId: String, success: Boolean) {
        synchronized(stateLock) {
            val need = needs[needId] ?: return
            need.clearInFlight(success, config.backoffPulses)
            if (pendingImpulse?.needId == needId) {
                pendingImpulse = null
            }
            emitImpulseCompleted(needId, success, need.value)
            logger.debug { "Id impulse completed: need='$needId' success=$success newValue=${need.value}" }
        }
    }

    /**
     * Called by the Ego when an impulse is denied before execution starts
     * (planner noop or superego denial).
     */
    fun onImpulseDenied(needId: String) {
        synchronized(stateLock) {
            val need = needs[needId] ?: return
            need.onDenied(config.backoffPulses)
            if (pendingImpulse?.needId == needId) {
                pendingImpulse = null
            }
            emitImpulseDenied(needId, need.consecutiveDenials)
            logger.debug { "Id impulse denied: need='$needId' denials=${need.consecutiveDenials}" }
        }
    }

    /**
     * Called by the Ego when an impulse is accepted (planner produced a plan/action).
     * The need should already be marked in-flight from [pulse], but this is a
     * confirmation point for instrumentation.
     */
    fun onImpulseAccepted(needId: String) {
        synchronized(stateLock) {
            emitImpulseAccepted(needId)
            logger.debug { "Id impulse accepted: need='$needId'" }
        }
    }

    /** Read-only snapshot of all need tensions for planner context. */
    fun needTensions(): Map<String, Double> =
        synchronized(stateLock) {
            needs.mapValues { (_, state) -> state.tension }
        }

    /** Look up the config for a specific need. */
    fun needConfig(needId: String): NeedConfig? = config.needs[needId]

    // ── Instrumentation ──────────────────────────────────────────────

    private fun emitPulseEvent() {
        instrumentation.emit(
            AgentEvent(
                type = "id_pulse",
                data = mapOf(
                    "pulse" to pulseCount,
                    "needs" to needs.values.map { it.snapshot() },
                    "ego_busy" to hasPendingWork(),
                    "trigger_threshold" to config.triggerThreshold,
                    "pulse_interval_ms" to config.pulseIntervalMs,
                    "threshold_on_tension" to config.thresholdOnTension,
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
                    "tension" to need.tension,
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

    private data class PendingImpulseLifecycle(
        val needId: String,
        val rootImpulseId: String,
    )
}
