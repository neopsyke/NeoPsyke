package psyke.agent.ego

import psyke.agent.config.*
import psyke.agent.model.*
import java.util.PriorityQueue

class AttentionScheduler(
    private val config: AgentConfig,
) {
    private var idCounter = 0L
    private val inputs = PriorityQueue<PendingInput>(inputComparator)
    private val thoughts = PriorityQueue<PendingThought>(thoughtComparator)
    private val actions = PriorityQueue<PendingAction>(actionComparator)
    private val impulses = ArrayDeque<PendingImpulse>()
    private var latestQueuedInput: PendingInput? = null

    fun enqueueInput(
        content: String,
        priority: InputPriority = InputPriority.MEDIUM,
        source: String = "external",
        conversationContext: ConversationContext = ConversationContext.default(),
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
        rootInputId: String? = null,
        rootInputReceivedAtMs: Long? = null,
        deniedActionType: ActionType? = null,
        deniedActionPayload: String? = null,
        denialReason: String? = null,
        allowFallbackExplanation: Boolean = false,
        planContext: PlanContext? = null,
        denialReasonCode: String? = null,
        originActionType: ActionType? = null,
        originActionObservedEvidence: Boolean? = null,
        conversationContext: ConversationContext = ConversationContext.default(),
        origin: ActionOrigin = ActionOrigin.USER,
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
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                deniedActionType = deniedActionType,
                deniedActionPayload = deniedActionPayload,
                denialReason = denialReason,
                allowFallbackExplanation = allowFallbackExplanation,
                planContext = planContext,
                denialReasonCode = denialReasonCode,
                originActionType = originActionType,
                originActionObservedEvidence = originActionObservedEvidence,
                conversationContext = conversationContext,
                origin = origin,
            )
        )
        return true
    }

    fun enqueueAction(
        type: ActionType,
        payload: String,
        summary: String,
        urgency: Urgency,
        requiresFollowUpThought: Boolean = false,
        followUpPrefix: String = "Action completed.",
        attempts: Int = 0,
        isFallbackExplanation: Boolean = false,
        rootInputId: String? = null,
        rootInputReceivedAtMs: Long? = null,
        conversationContext: ConversationContext = ConversationContext.default(),
        origin: ActionOrigin = ActionOrigin.USER,
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
                requiresFollowUpThought = requiresFollowUpThought,
                followUpPrefix = followUpPrefix,
                attempts = attempts,
                isFallbackExplanation = isFallbackExplanation,
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                conversationContext = conversationContext,
                origin = origin,
            )
        )
        return true
    }

    /**
     * Enqueue an impulse from the Id module.
     * Impulses are processed after inputs but before actions/thoughts (Ego idle).
     *
     * @param impulse the impulse to enqueue
     * @param maxPendingImpulses maximum impulse queue depth (from [IdConfig])
     * @return true if enqueued, false if at capacity
     */
    fun enqueueImpulse(impulse: PendingImpulse, maxPendingImpulses: Int): Boolean {
        if (impulses.size >= maxPendingImpulses) return false
        impulses.addLast(impulse)
        return true
    }

    /** Remove all pending impulses. */
    fun clearPendingImpulses() {
        impulses.clear()
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

    fun hasPendingFallbackExplanationAction(rootInputId: String?, sessionId: String): Boolean {
        if (rootInputId.isNullOrBlank()) {
            return false
        }
        return actions.any { action ->
            action.isFallbackExplanation &&
                matchesInputScope(
                    itemRootInputId = action.rootInputId,
                    itemConversationContext = action.conversationContext,
                    rootInputId = rootInputId,
                    sessionId = sessionId
                )
        }
    }

    fun hasPendingPlanThoughtsForInput(rootInputId: String?, sessionId: String): Boolean {
        if (rootInputId.isNullOrBlank()) {
            return false
        }
        return thoughts.any { thought ->
            matchesInputScope(
                itemRootInputId = thought.rootInputId,
                itemConversationContext = thought.conversationContext,
                rootInputId = rootInputId,
                sessionId = sessionId
            ) &&
                thought.planContext != null
        }
    }

    fun hasPendingConvergenceThoughtForInput(rootInputId: String?, sessionId: String): Boolean {
        if (rootInputId.isNullOrBlank()) {
            return false
        }
        return thoughts.any { thought ->
            matchesInputScope(
                itemRootInputId = thought.rootInputId,
                itemConversationContext = thought.conversationContext,
                rootInputId = rootInputId,
                sessionId = sessionId
            ) &&
                thought.content.startsWith(CONVERGENCE_THOUGHT_PREFIX)
        }
    }

    fun clearPendingWorkForInput(rootInputId: String?, sessionId: String): ClearedPendingWork {
        if (rootInputId.isNullOrBlank()) {
            return ClearedPendingWork()
        }
        val thoughtBefore = thoughts.size
        val actionBefore = actions.size
        thoughts.removeIf { thought ->
            matchesInputScope(
                itemRootInputId = thought.rootInputId,
                itemConversationContext = thought.conversationContext,
                rootInputId = rootInputId,
                sessionId = sessionId
            )
        }
        actions.removeIf { action ->
            matchesInputScope(
                itemRootInputId = action.rootInputId,
                itemConversationContext = action.conversationContext,
                rootInputId = rootInputId,
                sessionId = sessionId
            )
        }
        return ClearedPendingWork(
            thoughtsRemoved = thoughtBefore - thoughts.size,
            actionsRemoved = actionBefore - actions.size
        )
    }

    fun nextTask(): LoopTask? {
        // 1. User inputs always take top priority.
        val input = inputs.poll()
        if (input != null) {
            return LoopTask.ProcessInput(input)
        }

        // 2. Id impulses come second (when Ego is otherwise idle).
        val impulse = impulses.removeFirstOrNull()
        if (impulse != null) {
            return LoopTask.ProcessImpulse(impulse)
        }

        // 3. Actions and thoughts by urgency priority.
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

    fun hasPendingWork(): Boolean =
        inputs.isNotEmpty() || impulses.isNotEmpty() || thoughts.isNotEmpty() || actions.isNotEmpty()

    /**
     * Returns true when there is queued work (thought/action/impulse) for a specific root.
     * Used by Ego to close Id impulse lifecycles only after all derived work is drained.
     */
    fun hasPendingWorkForRoot(rootInputId: String): Boolean =
        impulses.any { it.rootImpulseId == rootInputId } ||
            thoughts.any { it.rootInputId == rootInputId } ||
            actions.any { it.rootInputId == rootInputId }

    fun queueSnapshot(): QueueSnapshot =
        QueueSnapshot(
            pendingInputCount = inputs.size,
            pendingThoughtCount = thoughts.size,
            pendingActionCount = actions.size,
            pendingImpulseCount = impulses.size,
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

    private fun matchesInputScope(
        itemRootInputId: String?,
        itemConversationContext: ConversationContext,
        rootInputId: String?,
        sessionId: String,
    ): Boolean {
        if (itemRootInputId != rootInputId) {
            return false
        }
        return itemConversationContext.sessionId == sessionId
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
