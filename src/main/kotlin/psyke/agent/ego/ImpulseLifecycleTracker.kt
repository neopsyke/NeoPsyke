package psyke.agent.ego

import psyke.agent.id.evaluateSatisfaction
import psyke.agent.model.ActionEffect
import psyke.agent.model.ActionOutcome
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingAction
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation

internal class ImpulseLifecycleTracker(
    private val instrumentation: AgentInstrumentation,
    private val scheduler: AttentionScheduler,
) {
    @Volatile private var id: psyke.agent.id.Id? = null

    fun setId(id: psyke.agent.id.Id) {
        this.id = id
    }

    private val impulseLifecyclesByRoot = mutableMapOf<String, ImpulseLifecycle>()

    fun registerLifecycle(rootImpulseId: String, needId: String) {
        impulseLifecyclesByRoot[rootImpulseId] = ImpulseLifecycle(needId = needId)
    }

    fun recordActionOutcome(action: PendingAction, outcome: ActionOutcome) {
        if (action.origin.source != OriginSource.ID) return
        if (!outcome.successful) return
        val rootImpulseId = action.origin.rootImpulseId ?: action.rootInputId ?: return
        val lifecycle = impulseLifecyclesByRoot[rootImpulseId] ?: return
        lifecycle.successfulActionCount++
        lifecycle.observedEffects.addAll(outcome.effects)
    }

    fun maybeFinalizeLifecycle(rootInputId: String?) {
        val rootImpulseId = rootInputId ?: return
        val lifecycle = impulseLifecyclesByRoot[rootImpulseId] ?: return
        if (scheduler.hasPendingWorkForRoot(rootImpulseId)) return

        val needConfig = id?.needConfig(lifecycle.needId)
        val verdict = needConfig?.evaluateSatisfaction(lifecycle.observedEffects)
        if (verdict?.satisfied == true) {
            id?.onImpulseCompleted(lifecycle.needId, success = true)
            instrumentation.emit(
                AgentEvent(
                    type = "impulse_lifecycle_finalized",
                    data = mapOf(
                        "root_impulse_id" to rootImpulseId,
                        "need_id" to lifecycle.needId,
                        "result" to "accepted",
                        "successful_action_count" to lifecycle.successfulActionCount,
                        "required_effects" to verdict.requiredEffects.map { it.name.lowercase() },
                        "matched_effects" to verdict.matchedEffects.map { it.name.lowercase() },
                        "observed_effects" to verdict.observedEffects.map { it.name.lowercase() },
                    )
                )
            )
        } else {
            id?.onImpulseDenied(lifecycle.needId)
            instrumentation.emit(
                AgentEvent(
                    type = "impulse_lifecycle_finalized",
                    data = mapOf(
                        "root_impulse_id" to rootImpulseId,
                        "need_id" to lifecycle.needId,
                        "result" to "denied",
                        "successful_action_count" to lifecycle.successfulActionCount,
                        "required_effects" to needConfig?.satisfactionEffectsAnyOf?.map { it.name.lowercase() }.orEmpty(),
                        "observed_effects" to lifecycle.observedEffects.map { it.name.lowercase() },
                    )
                )
            )
        }
        impulseLifecyclesByRoot.remove(rootImpulseId)
    }

    fun finalizeAllIdle() {
        val roots = impulseLifecyclesByRoot.keys.toList()
        roots.forEach { rootImpulseId -> maybeFinalizeLifecycle(rootImpulseId) }
    }

    fun forceDenyAll(reason: String) {
        val lifecycles = impulseLifecyclesByRoot.toMap()
        impulseLifecyclesByRoot.clear()
        lifecycles.forEach { (rootImpulseId, lifecycle) ->
            id?.onImpulseDenied(lifecycle.needId)
            instrumentation.emit(
                AgentEvent(
                    type = "impulse_lifecycle_finalized",
                    data = mapOf(
                        "root_impulse_id" to rootImpulseId,
                        "need_id" to lifecycle.needId,
                        "result" to "denied",
                        "reason" to reason,
                    )
                )
            )
        }
    }

    private data class ImpulseLifecycle(
        val needId: String,
        var successfulActionCount: Int = 0,
        val observedEffects: MutableSet<ActionEffect> = linkedSetOf(),
    )
}
