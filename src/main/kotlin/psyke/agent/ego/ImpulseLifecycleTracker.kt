package psyke.agent.ego

import psyke.agent.model.ActionOrigin
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

    fun markActionExecuted(action: PendingAction) {
        if (action.origin.source != OriginSource.ID) return
        val rootImpulseId = action.origin.rootImpulseId ?: action.rootInputId ?: return
        val lifecycle = impulseLifecyclesByRoot[rootImpulseId] ?: return
        lifecycle.hadExecutedAction = true
    }

    fun maybeFinalizeLifecycle(rootInputId: String?) {
        val rootImpulseId = rootInputId ?: return
        val lifecycle = impulseLifecyclesByRoot[rootImpulseId] ?: return
        if (scheduler.hasPendingWorkForRoot(rootImpulseId)) return

        if (lifecycle.hadExecutedAction) {
            id?.onImpulseCompleted(lifecycle.needId, success = true)
            instrumentation.emit(
                AgentEvent(
                    type = "impulse_lifecycle_finalized",
                    data = mapOf(
                        "root_impulse_id" to rootImpulseId,
                        "need_id" to lifecycle.needId,
                        "result" to "accepted",
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
        var hadExecutedAction: Boolean = false,
    )
}
