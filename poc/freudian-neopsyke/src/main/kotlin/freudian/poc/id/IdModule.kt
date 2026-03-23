package freudian.poc.id

import freudian.poc.config.IdConfig
import freudian.poc.instrumentation.EventLogger
import freudian.poc.instrumentation.RuntimeEvent
import freudian.poc.model.IdImpulse
import freudian.poc.model.ImpulseFeedback
import freudian.poc.model.ImpulseResult
import java.util.Random
import java.util.UUID

class IdModule(
    private val config: IdConfig,
    private val random: Random,
    private val eventLogger: EventLogger,
) {
    private val needs: Map<String, NeedState> = config.needs.mapValues { (needName, needConfig) ->
        NeedState(name = needName, config = needConfig)
    }

    private var pendingImpulse: IdImpulse? = null

    fun tick(tick: Int): IdImpulse? {
        needs.values.forEach { it.grow() }

        eventLogger.log(
            RuntimeEvent(
                tick = tick,
                type = "id_pulse",
                attributes = mapOf(
                    "needs" to needs.values.map { it.snapshot() },
                    "pending_impulse" to (pendingImpulse != null)
                )
            )
        )

        if (!config.enabled) {
            return null
        }

        if (pendingImpulse != null) {
            eventLogger.log(
                RuntimeEvent(
                    tick = tick,
                    type = "id_impulse_not_emitted",
                    attributes = mapOf("reason" to "pending_impulse")
                )
            )
            return null
        }

        val candidates = needs.values
            .asSequence()
            .filter { it.isEligible() }
            .filter { it.value >= config.triggerThreshold }
            .toList()

        if (candidates.isEmpty()) {
            return null
        }

        val probabilityRoll = random.nextDouble()
        if (probabilityRoll > config.triggerProbability) {
            eventLogger.log(
                RuntimeEvent(
                    tick = tick,
                    type = "id_impulse_not_emitted",
                    attributes = mapOf(
                        "reason" to "probability_gate",
                        "roll" to probabilityRoll,
                        "trigger_probability" to config.triggerProbability,
                    )
                )
            )
            return null
        }

        val selectedNeed = candidates.maxBy { it.value }
        val impulse = IdImpulse(
            rootImpulseId = UUID.randomUUID().toString(),
            needName = selectedNeed.name,
            message = selectedNeed.config.impulseMessage,
            urgency = selectedNeed.value,
            rawNeedValue = selectedNeed.value,
        )
        pendingImpulse = impulse

        eventLogger.log(
            RuntimeEvent(
                tick = tick,
                type = "id_impulse_fired",
                attributes = mapOf(
                    "root_impulse_id" to impulse.rootImpulseId,
                    "need_name" to impulse.needName,
                    "urgency" to impulse.urgency,
                    "raw_need_value" to impulse.rawNeedValue,
                )
            )
        )
        return impulse
    }

    fun onImpulseFeedback(tick: Int, feedback: ImpulseFeedback) {
        val currentPendingImpulse = pendingImpulse ?: return
        if (currentPendingImpulse.rootImpulseId != feedback.rootImpulseId) {
            return
        }

        val need = needs[feedback.needName] ?: return
        when (feedback.result) {
            ImpulseResult.ACCEPTED -> need.applyAcceptedFeedback()
            ImpulseResult.DENIED -> need.applyDeniedFeedback(
                maxConsecutiveDenials = config.maxConsecutiveDenials,
                baseBackoffTicks = config.backoffTicks,
            )
        }
        pendingImpulse = null

        eventLogger.log(
            RuntimeEvent(
                tick = tick,
                type = "id_impulse_feedback",
                attributes = mapOf(
                    "root_impulse_id" to feedback.rootImpulseId,
                    "need_name" to feedback.needName,
                    "result" to feedback.result.name.lowercase(),
                    "need_state" to need.snapshot(),
                )
            )
        )
    }

    fun needsSnapshot(): Map<String, Double> =
        needs.mapValues { (_, needState) -> needState.value }
}
