package freudian.poc.ego

import freudian.poc.model.ImpulseFeedback
import freudian.poc.model.ImpulseResult

class ImpulseLifecycleTracker {
    private val lifecyclesByRootImpulseId = mutableMapOf<String, ImpulseLifecycleState>()

    fun start(rootImpulseId: String, needName: String, initialThoughtCount: Int) {
        lifecyclesByRootImpulseId[rootImpulseId] = ImpulseLifecycleState(
            needName = needName,
            pendingThoughtCount = initialThoughtCount,
            pendingActionCount = 0,
            hadExecutedAction = false,
        )
    }

    fun registerAction(rootImpulseId: String): ImpulseFeedback? {
        val state = lifecyclesByRootImpulseId[rootImpulseId] ?: return null
        state.pendingActionCount += 1
        return finalizeIfPossible(rootImpulseId, state)
    }

    fun completeThought(rootImpulseId: String): ImpulseFeedback? {
        val state = lifecyclesByRootImpulseId[rootImpulseId] ?: return null
        state.pendingThoughtCount = (state.pendingThoughtCount - 1).coerceAtLeast(0)
        return finalizeIfPossible(rootImpulseId, state)
    }

    fun completeAction(rootImpulseId: String, executed: Boolean): ImpulseFeedback? {
        val state = lifecyclesByRootImpulseId[rootImpulseId] ?: return null
        state.pendingActionCount = (state.pendingActionCount - 1).coerceAtLeast(0)
        state.hadExecutedAction = state.hadExecutedAction || executed
        return finalizeIfPossible(rootImpulseId, state)
    }

    fun forceDenyAll(): List<ImpulseFeedback> {
        val deniedFeedback = lifecyclesByRootImpulseId.map { (rootImpulseId, state) ->
            ImpulseFeedback(
                rootImpulseId = rootImpulseId,
                needName = state.needName,
                result = ImpulseResult.DENIED,
            )
        }
        lifecyclesByRootImpulseId.clear()
        return deniedFeedback
    }

    private fun finalizeIfPossible(rootImpulseId: String, state: ImpulseLifecycleState): ImpulseFeedback? {
        val hasPendingWork = state.pendingThoughtCount > 0 || state.pendingActionCount > 0
        if (hasPendingWork) {
            return null
        }

        lifecyclesByRootImpulseId.remove(rootImpulseId)
        val result = if (state.hadExecutedAction) ImpulseResult.ACCEPTED else ImpulseResult.DENIED
        return ImpulseFeedback(
            rootImpulseId = rootImpulseId,
            needName = state.needName,
            result = result,
        )
    }

    private data class ImpulseLifecycleState(
        val needName: String,
        var pendingThoughtCount: Int,
        var pendingActionCount: Int,
        var hadExecutedAction: Boolean,
    )
}
