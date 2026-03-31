package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.model.*
import java.util.PriorityQueue

class AttentionScheduler(
    private val config: AgentConfig,
) {
    private var idCounter = 0L
    private val opportunities = PriorityQueue<ScheduledOpportunity>(opportunityComparator)
    private val intentions = PriorityQueue<QueuedIntention>(intentionComparator)
    private val actions = PriorityQueue<PendingAction>(actionComparator)
    private var latestQueuedInput: PendingInput? = null

    fun enqueueInput(input: PendingInput, opportunity: Opportunity): Boolean {
        if (pendingInputCount() >= config.maxPendingInputs) {
            return false
        }
        opportunities.add(
            ScheduledOpportunity(
                queueId = nextId(),
                opportunity = opportunity,
                trigger = OpportunityTrigger.Input(input),
            )
        )
        latestQueuedInput = input
        return true
    }

    fun enqueueFeedback(feedback: PendingFeedback, opportunity: Opportunity): Boolean {
        if (pendingInputCount() >= config.maxPendingInputs) {
            return false
        }
        opportunities.add(
            ScheduledOpportunity(
                queueId = nextId(),
                opportunity = opportunity,
                trigger = OpportunityTrigger.Feedback(feedback),
            )
        )
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
        val deferredIntentions = intentions.count { it.intention.kind == IntentionKind.DEFER }
        if (deferredIntentions >= config.maxPendingThoughts) {
            return false
        }
        return enqueueIntention(
            QueuedIntention(
                intention = Intention(
                    id = RootInputIds.next(),
                    cognitiveThreadId = rootInputId ?: RootInputIds.next(),
                    kind = IntentionKind.DEFER,
                    summary = content.take(160),
                    createdAt = java.time.Instant.now(),
                    conversationContext = conversationContext,
                    rootStimulusId = rootInputId,
                ),
                urgency = urgency,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                origin = origin,
                deferredThoughtContent = content,
                deferredThoughtPasses = passes,
                deferredThoughtRecallQuery = longTermMemoryRecallQuery,
                deferredDeniedActionType = deniedActionType,
                deferredDeniedActionPayload = deniedActionPayload,
                deferredDenialReason = denialReason,
                deferredAllowFallbackExplanation = allowFallbackExplanation,
                deferredPlanContext = planContext,
                deferredDenialReasonCode = denialReasonCode,
                deferredOriginActionType = originActionType,
                deferredOriginActionObservedEvidence = originActionObservedEvidence,
            )
        )
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

    fun enqueueIntention(intention: QueuedIntention): Boolean {
        if (actions.size + intentions.size >= config.maxPendingActions) {
            return false
        }
        intentions.add(intention.copy(queueId = nextId()))
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
    fun enqueueImpulse(impulse: PendingImpulse, opportunity: Opportunity, maxPendingImpulses: Int): Boolean {
        if (pendingImpulseCount() >= maxPendingImpulses) return false
        opportunities.add(
            ScheduledOpportunity(
                queueId = nextId(),
                opportunity = opportunity,
                trigger = OpportunityTrigger.Impulse(impulse),
            )
        )
        return true
    }

    /** Remove all pending impulses. */
    fun clearPendingImpulses() {
        opportunities.removeIf { it.trigger is OpportunityTrigger.Impulse }
    }

    fun enqueueGoalWork(work: GoalRunActivation, opportunity: Opportunity): Boolean {
        opportunities.add(
            ScheduledOpportunity(
                queueId = nextId(),
                opportunity = opportunity,
                trigger = OpportunityTrigger.GoalWork(work),
            )
        )
        return true
    }

    fun clearGoalWork() {
        opportunities.removeIf { it.trigger is OpportunityTrigger.GoalWork }
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
        return intentions.any { intention ->
            matchesInputScope(
                itemRootInputId = intention.rootInputId,
                itemConversationContext = intention.conversationContext,
                rootInputId = rootInputId,
                sessionId = sessionId
            ) &&
                intention.intention.kind == IntentionKind.DEFER &&
                intention.deferredPlanContext != null
        }
    }

    fun hasPendingConvergenceThoughtForInput(rootInputId: String?, sessionId: String): Boolean {
        if (rootInputId.isNullOrBlank()) {
            return false
        }
        return intentions.any { intention ->
            matchesInputScope(
                itemRootInputId = intention.rootInputId,
                itemConversationContext = intention.conversationContext,
                rootInputId = rootInputId,
                sessionId = sessionId
            ) &&
                intention.intention.kind == IntentionKind.DEFER &&
                intention.deferredThoughtContent?.startsWith(CONVERGENCE_THOUGHT_PREFIX) == true
        }
    }

    fun clearPendingWorkForInput(rootInputId: String?, sessionId: String): ClearedPendingWork {
        if (rootInputId.isNullOrBlank()) {
            return ClearedPendingWork()
        }
        val intentionBefore = intentions.size
        val actionBefore = actions.size
        intentions.removeIf { intention ->
            matchesInputScope(
                itemRootInputId = intention.rootInputId,
                itemConversationContext = intention.conversationContext,
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
            thoughtsRemoved = intentionBefore - intentions.size,
            actionsRemoved = actionBefore - actions.size
        )
    }

    fun nextTask(): LoopTask? {
        val opportunity = opportunities.poll()
        if (opportunity != null) {
            return LoopTask.AttendOpportunity(opportunity)
        }

        val topIntention = intentions.peek()
        val topAction = actions.peek()
        if (topIntention == null && topAction == null) {
            return null
        }
        if (topIntention != null && topAction == null) {
            return LoopTask.ProcessIntention(intentions.remove())
        }
        if (topIntention != null && topIntention.urgency.priority >= topAction!!.urgency.priority) {
            return LoopTask.ProcessIntention(intentions.remove())
        }
        return LoopTask.PerformAction(actions.remove())
    }

    fun hasPendingWork(): Boolean =
        opportunities.isNotEmpty() || intentions.isNotEmpty() || actions.isNotEmpty()

    /**
     * Returns true when there is queued work (thought/action/impulse) for a specific root.
     * Used by Ego to close Id impulse lifecycles only after all derived work is drained.
     */
    fun hasPendingWorkForRoot(rootInputId: String): Boolean =
        opportunities.any { it.rootInputId == rootInputId } ||
            intentions.any { it.rootInputId == rootInputId } ||
            actions.any { it.rootInputId == rootInputId }

    fun queueSnapshot(): QueueSnapshot =
        QueueSnapshot(
            pendingInputCount = pendingInputCount(),
            pendingThoughtCount = intentions.count { it.intention.kind == IntentionKind.DEFER },
            pendingActionCount = actions.size,
            pendingIntentionCount = intentions.size,
            pendingImpulseCount = pendingImpulseCount(),
        )

    fun queueState(): QueueState =
        QueueState(
            inputs = opportunities
                .mapNotNull { scheduled ->
                    (scheduled.trigger as? OpportunityTrigger.Input)?.input
                }
                .sortedWith(inputComparator),
            intentions = intentions.toList().sortedWith(intentionComparator),
            thoughts = intentions
                .filter { it.intention.kind == IntentionKind.DEFER }
                .map { it.toPendingThought() }
                .sortedWith(thoughtComparator),
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

        internal val intentionComparator = compareByDescending<QueuedIntention> { it.urgency.priority }
            .thenBy { if (it.intention.kind == IntentionKind.DEFER) 1 else 0 }
            .thenBy { it.intention.createdAt }
            .thenBy { it.queueId }

        internal val opportunityComparator =
            compareBy<ScheduledOpportunity> { opportunityRank(it) }
                .thenByDescending { opportunityPriority(it) }
                .thenBy { it.id }

        private fun opportunityRank(item: ScheduledOpportunity): Int =
            when (item.opportunity.kind) {
                OpportunityKind.RESPOND -> 0
                OpportunityKind.INTEGRATE_FEEDBACK -> 0
                OpportunityKind.EXECUTE -> 1
                OpportunityKind.RESUME -> 2
                OpportunityKind.CLARIFY -> 2
                OpportunityKind.FINALIZE -> 2
            }

        private fun opportunityPriority(item: ScheduledOpportunity): Int =
            (item.opportunity.salience * 1000).toInt()
    }

    private fun pendingInputCount(): Int =
        opportunities.count { trigger ->
            trigger.trigger is OpportunityTrigger.Input || trigger.trigger is OpportunityTrigger.Feedback
        }

    private fun pendingImpulseCount(): Int =
        opportunities.count { it.trigger is OpportunityTrigger.Impulse }
}
