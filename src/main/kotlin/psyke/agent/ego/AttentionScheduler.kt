package psyke.agent.ego

import psyke.agent.core.*
import java.util.PriorityQueue

class AttentionScheduler(
    private val config: AgentConfig,
) {
    private var idCounter = 0L
    private val inputs = PriorityQueue<PendingInput>(inputComparator)
    private val thoughts = PriorityQueue<PendingThought>(thoughtComparator)
    private val actions = PriorityQueue<PendingAction>(actionComparator)
    private var latestQueuedInput: PendingInput? = null

    fun enqueueInput(
        content: String,
        priority: InputPriority = InputPriority.MEDIUM,
        source: String = "external",
        conversationContext: ConversationContext? = null,
    ): Boolean {
        if (inputs.size >= config.maxPendingInputs) {
            return false
        }
        val input = PendingInput(
            id = nextId(),
            content = content,
            priority = priority,
            source = source,
            conversationContext = conversationContext
        )
        inputs.add(input)
        latestQueuedInput = input
        return true
    }

    fun latestQueuedInput(): PendingInput? = latestQueuedInput

    fun enqueueThought(
        content: String,
        urgency: Urgency,
        passes: Int = 0,
        longTermMemoryRecallQuery: String? = null,
        rootInputEnqueuedAtMs: Long? = null,
        deniedActionType: ActionType? = null,
        deniedActionPayload: String? = null,
        denialReason: String? = null,
        allowFallbackExplanation: Boolean = false,
        planContext: PlanContext? = null,
        denialReasonCode: String? = null,
        conversationContext: ConversationContext? = null,
    ): Boolean {
        if (thoughts.size >= config.maxPendingThoughts) {
            return false
        }
        thoughts.add(
            PendingThought(
                id = nextId(),
                urgency = urgency,
                content = content,
                passes = passes,
                longTermMemoryRecallQuery = longTermMemoryRecallQuery,
                rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                deniedActionType = deniedActionType,
                deniedActionPayload = deniedActionPayload,
                denialReason = denialReason,
                allowFallbackExplanation = allowFallbackExplanation,
                planContext = planContext,
                denialReasonCode = denialReasonCode,
                conversationContext = conversationContext,
            )
        )
        return true
    }

    fun enqueueAction(
        type: ActionType,
        payload: String,
        summary: String,
        urgency: Urgency,
        attempts: Int = 0,
        isFallbackExplanation: Boolean = false,
        rootInputEnqueuedAtMs: Long? = null,
        conversationContext: ConversationContext? = null,
    ): Boolean {
        if (actions.size >= config.maxPendingActions) {
            return false
        }
        actions.add(
            PendingAction(
                id = nextId(),
                urgency = urgency,
                type = type,
                payload = payload,
                summary = summary,
                attempts = attempts,
                isFallbackExplanation = isFallbackExplanation,
                rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                conversationContext = conversationContext
            )
        )
        return true
    }

    fun dequeueFallbackExplanationAction(): PendingAction? {
        val candidate = actions.toList()
            .filter { it.isFallbackExplanation }
            .sortedWith(actionComparator)
            .firstOrNull()
            ?: return null
        actions.remove(candidate)
        return candidate
    }

    fun hasPendingFallbackExplanationAction(rootInputEnqueuedAtMs: Long?): Boolean {
        if (rootInputEnqueuedAtMs == null) {
            return false
        }
        return actions.any { action ->
            action.isFallbackExplanation &&
                action.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs
        }
    }

    fun hasPendingPlanThoughtsForInput(rootInputEnqueuedAtMs: Long?): Boolean {
        if (rootInputEnqueuedAtMs == null) {
            return false
        }
        return thoughts.any { thought ->
            thought.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs &&
                thought.planContext != null
        }
    }

    fun hasPendingConvergenceThoughtForInput(rootInputEnqueuedAtMs: Long?): Boolean {
        if (rootInputEnqueuedAtMs == null) {
            return false
        }
        return thoughts.any { thought ->
            thought.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs &&
                thought.content.startsWith(CONVERGENCE_THOUGHT_PREFIX)
        }
    }

    fun clearPendingWorkForInput(rootInputEnqueuedAtMs: Long?): ClearedPendingWork {
        if (rootInputEnqueuedAtMs == null) {
            return ClearedPendingWork()
        }
        val thoughtBefore = thoughts.size
        val actionBefore = actions.size
        thoughts.removeIf { it.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs }
        actions.removeIf { it.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs }
        return ClearedPendingWork(
            thoughtsRemoved = thoughtBefore - thoughts.size,
            actionsRemoved = actionBefore - actions.size
        )
    }

    fun nextTask(): LoopTask? {
        val input = inputs.poll()
        if (input != null) {
            return LoopTask.ProcessInput(input)
        }

        val topAction = actions.peek()
        val topThought = thoughts.peek()
        if (topAction == null && topThought == null) {
            return null
        }
        if (topAction == null) {
            return LoopTask.ProcessThought(thoughts.remove())
        }
        if (topThought == null) {
            return LoopTask.PerformAction(actions.remove())
        }

        return if (topAction.urgency.priority >= topThought.urgency.priority) {
            LoopTask.PerformAction(actions.remove())
        } else {
            LoopTask.ProcessThought(thoughts.remove())
        }
    }

    fun hasPendingWork(): Boolean = inputs.isNotEmpty() || thoughts.isNotEmpty() || actions.isNotEmpty()

    fun queueSnapshot(): QueueSnapshot =
        QueueSnapshot(
            pendingInputCount = inputs.size,
            pendingThoughtCount = thoughts.size,
            pendingActionCount = actions.size
        )

    fun queueState(): QueueState =
        QueueState(
            inputs = inputs.toList().sortedWith(inputComparator),
            thoughts = thoughts.toList().sortedWith(thoughtComparator),
            actions = actions.toList().sortedWith(actionComparator)
        )

    private fun nextId(): Long {
        idCounter += 1
        return idCounter
    }

    companion object {
        const val CONVERGENCE_THOUGHT_PREFIX: String = "[convergence] "

        internal val inputComparator = compareByDescending<PendingInput> { it.priority.level }
            .thenBy { it.id }

        internal val thoughtComparator = compareByDescending<PendingThought> { it.urgency.priority }
            .thenBy { it.id }

        internal val actionComparator = compareByDescending<PendingAction> { it.urgency.priority }
            .thenBy { it.id }
    }
}
