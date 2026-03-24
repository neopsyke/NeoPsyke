package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.goal.GoalRunActivation
import java.util.PriorityQueue

class AttentionScheduler(
    private val config: AgentConfig,
) {
    private var idCounter = 0L
    private val opportunities = PriorityQueue<OpportunityWorkItem>(opportunityComparator)
    private val thoughts = PriorityQueue<PendingThought>(thoughtComparator)
    private val actions = PriorityQueue<PendingAction>(actionComparator)
    private var latestQueuedInput: PendingInput? = null

    fun enqueueInput(
        content: String,
        priority: InputPriority = InputPriority.MEDIUM,
        source: String = "external",
        conversationContext: ConversationContext = ConversationContext.default(),
    ): Boolean {
        if (pendingInputCount() >= config.maxPendingInputs) {
            return false
        }
        val input = PendingInput(
            id = nextId(),
            content = content,
            priority = priority,
            source = source,
            conversationContext = conversationContext
        )
        opportunities.add(OpportunityWorkItem.InputOpportunity(input))
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
        argumentDataTrust: DataTrust = DataTrust.TRUSTED_DATA,
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
                argumentDataTrust = argumentDataTrust,
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
        if (pendingImpulseCount() >= maxPendingImpulses) return false
        opportunities.add(OpportunityWorkItem.ImpulseOpportunity(impulse))
        return true
    }

    /** Remove all pending impulses. */
    fun clearPendingImpulses() {
        opportunities.removeIf { it is OpportunityWorkItem.ImpulseOpportunity }
    }

    fun enqueueProjectWork(workUnit: GoalRunActivation): Boolean {
        opportunities.add(OpportunityWorkItem.GoalWorkOpportunity(workUnit))
        return true
    }

    fun clearProjectWork() {
        opportunities.removeIf { it is OpportunityWorkItem.GoalWorkOpportunity }
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
        val opportunity = opportunities.poll()
        if (opportunity != null) {
            return LoopTask.AttendOpportunity(opportunity)
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

    fun hasPendingWork(): Boolean =
        opportunities.isNotEmpty() || thoughts.isNotEmpty() || actions.isNotEmpty()

    /**
     * Returns true when there is queued work (thought/action/impulse) for a specific root.
     * Used by Ego to close Id impulse lifecycles only after all derived work is drained.
     */
    fun hasPendingWorkForRoot(rootInputId: String): Boolean =
        opportunities.any { it.rootInputId == rootInputId } ||
            thoughts.any { it.rootInputId == rootInputId } ||
            actions.any { it.rootInputId == rootInputId }

    fun queueSnapshot(): QueueSnapshot =
        QueueSnapshot(
            pendingInputCount = pendingInputCount(),
            pendingThoughtCount = thoughts.size,
            pendingActionCount = actions.size,
            pendingImpulseCount = pendingImpulseCount(),
        )

    fun queueState(): QueueState =
        QueueState(
            inputs = opportunities
                .filterIsInstance<OpportunityWorkItem.InputOpportunity>()
                .map { it.input }
                .sortedWith(inputComparator),
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

        internal val opportunityComparator =
            compareBy<OpportunityWorkItem> { opportunityRank(it) }
                .thenByDescending { opportunityPriority(it) }
                .thenBy { it.id }

        private fun opportunityRank(item: OpportunityWorkItem): Int =
            when (item) {
                is OpportunityWorkItem.InputOpportunity -> 0
                is OpportunityWorkItem.ImpulseOpportunity -> 1
                is OpportunityWorkItem.GoalWorkOpportunity -> 2
            }

        private fun opportunityPriority(item: OpportunityWorkItem): Int =
            when (item) {
                is OpportunityWorkItem.InputOpportunity -> item.input.priority.level
                is OpportunityWorkItem.ImpulseOpportunity -> (item.impulse.urgency * 1000).toInt()
                is OpportunityWorkItem.GoalWorkOpportunity -> 0
            }
    }

    private fun pendingInputCount(): Int =
        opportunities.count { it is OpportunityWorkItem.InputOpportunity }

    private fun pendingImpulseCount(): Int =
        opportunities.count { it is OpportunityWorkItem.ImpulseOpportunity }
}
