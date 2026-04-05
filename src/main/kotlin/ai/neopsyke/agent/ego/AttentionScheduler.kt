package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.model.*
import java.util.PriorityQueue

class AttentionScheduler(
    private val config: AgentConfig,
) {
    // enqueueImpulse() can be called from the Id thread while the runLoop
    // coroutine reads these structures, so cross-thread fields are @Volatile.
    @Volatile private var idCounter = 0L
    private val opportunities = PriorityQueue<ScheduledOpportunity>(opportunityComparator)
    private val intentions = PriorityQueue<QueuedIntention>(intentionComparator)
    private val actions = PriorityQueue<PendingAction>(actionComparator)
    @Volatile private var latestQueuedInput: PendingInput? = null

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

    /**
     * Build a deferred [Intention] + [QueuedIntention] and enqueue it.
     * Returns the [Intention] if successfully queued, null if the queue is full.
     *
     * Shared entry point for [DecisionDispatcher] and [FallbackHandler].
     */
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
    ): Intention? {
        val deferredIntentions = intentions.count { it.intention.kind == IntentionKind.DEFER }
        if (deferredIntentions >= config.maxPendingThoughts) {
            return null
        }
        val intention = Intention(
            id = RootInputIds.next(),
            cognitiveThreadId = rootInputId ?: RootInputIds.next(),
            kind = IntentionKind.DEFER,
            summary = content.take(160),
            createdAt = java.time.Instant.now(),
            conversationContext = conversationContext,
            rootStimulusId = rootInputId,
        )
        val queued = enqueueIntention(
            QueuedIntention(
                intention = intention,
                urgency = urgency,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                origin = origin,
                deferredContent = content,
                deferredPasses = passes,
                deferredRecallQuery = longTermMemoryRecallQuery,
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
        return if (queued) intention else null
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
                intention.deferredContent?.startsWith(CONVERGENCE_THOUGHT_PREFIX) == true
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

    fun nextTask(
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean = { _, _ -> false },
    ): LoopTask? {
        val opportunity = pollNextOpportunity(isBlocked)
        if (opportunity != null) {
            return LoopTask.AttendOpportunity(opportunity)
        }

        val topIntention = peekNextIntention(isBlocked)
        val topAction = peekNextAction(isBlocked)
        if (topIntention == null && topAction == null) {
            return null
        }
        if (topIntention != null && topAction == null) {
            return LoopTask.ProcessIntention(removeNextIntention(isBlocked) ?: return null)
        }
        if (topIntention != null && topIntention.urgency.priority >= topAction!!.urgency.priority) {
            return LoopTask.ProcessIntention(removeNextIntention(isBlocked) ?: return null)
        }
        return LoopTask.PerformAction(removeNextAction(isBlocked) ?: return null)
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
            deferredIntentionCount = intentions.count { it.intention.kind == IntentionKind.DEFER },
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

    private fun pollNextOpportunity(
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
    ): ScheduledOpportunity? {
        val deferred = mutableListOf<ScheduledOpportunity>()
        var selected: ScheduledOpportunity? = null
        while (true) {
            val next = opportunities.poll() ?: break
            if (isBlocked(next.rootInputId, next.conversationContext)) {
                deferred += next
                continue
            }
            selected = next
            break
        }
        deferred.forEach(opportunities::add)
        return selected
    }

    private fun peekNextIntention(
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
    ): QueuedIntention? =
        peekAvailableFromQueue(
            queue = intentions,
            isBlocked = isBlocked,
            rootInputId = { it.rootInputId },
            conversationContext = { it.conversationContext },
        )

    private fun peekNextAction(
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
    ): PendingAction? =
        peekAvailableFromQueue(
            queue = actions,
            isBlocked = isBlocked,
            rootInputId = { it.rootInputId },
            conversationContext = { it.conversationContext },
        )

    private fun removeNextIntention(
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
    ): QueuedIntention? =
        removeAvailableFromQueue(
            queue = intentions,
            isBlocked = isBlocked,
            rootInputId = { it.rootInputId },
            conversationContext = { it.conversationContext },
        )

    private fun removeNextAction(
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
    ): PendingAction? =
        removeAvailableFromQueue(
            queue = actions,
            isBlocked = isBlocked,
            rootInputId = { it.rootInputId },
            conversationContext = { it.conversationContext },
        )

    private fun <T> peekAvailableFromQueue(
        queue: PriorityQueue<T>,
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
        rootInputId: (T) -> String?,
        conversationContext: (T) -> ConversationContext,
    ): T? {
        val deferred = mutableListOf<T>()
        var selected: T? = null
        while (true) {
            val next = queue.poll() ?: break
            if (isBlocked(rootInputId(next), conversationContext(next))) {
                deferred += next
                continue
            }
            selected = next
            break
        }
        selected?.let(queue::add)
        deferred.forEach(queue::add)
        return selected
    }

    private fun <T> removeAvailableFromQueue(
        queue: PriorityQueue<T>,
        isBlocked: (rootInputId: String?, conversationContext: ConversationContext) -> Boolean,
        rootInputId: (T) -> String?,
        conversationContext: (T) -> ConversationContext,
    ): T? {
        val deferred = mutableListOf<T>()
        var selected: T? = null
        while (true) {
            val next = queue.poll() ?: break
            if (isBlocked(rootInputId(next), conversationContext(next))) {
                deferred += next
                continue
            }
            selected = next
            break
        }
        deferred.forEach(queue::add)
        return selected
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
