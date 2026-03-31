package ai.neopsyke.agent.model

import java.util.UUID

data class PendingInput(
    val id: Long,
    val content: String,
    val priority: InputPriority = InputPriority.MEDIUM,
    val source: String = "external",
    val rootInputId: String = RootInputIds.next(),
    val receivedAtMs: Long = System.currentTimeMillis(),
    val conversationContext: ConversationContext = ConversationContext.default(),
    val percept: Percept? = null,
    val cognitiveThreadId: String? = null,
)

data class PlanContext(
    val planId: String,
    val planGoal: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val stepDescription: String,
)

data class PendingThought(
    val id: Long,
    val urgency: Urgency,
    val content: String,
    val passes: Int = 0,
    val longTermMemoryRecallQuery: String? = null,
    val rootInputId: String? = null,
    val rootInputReceivedAtMs: Long? = null,
    val deniedActionType: ActionType? = null,
    val deniedActionPayload: String? = null,
    val denialReason: String? = null,
    val allowFallbackExplanation: Boolean = false,
    val planContext: PlanContext? = null,
    val denialReasonCode: String? = null,
    val originActionType: ActionType? = null,
    val originActionObservedEvidence: Boolean? = null,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val origin: ActionOrigin = ActionOrigin.USER,
)

/**
 * Tracks the origin of a pending thought or action, distinguishing
 * user-initiated work from Id-driven (internal drive) work.
 */
data class ActionOrigin(
    val source: OriginSource = OriginSource.USER,
    val needId: String? = null,
    val rootImpulseId: String? = null,
) {
    companion object {
        val USER = ActionOrigin(OriginSource.USER)
        val SYSTEM = ActionOrigin(OriginSource.SYSTEM)
        fun id(needId: String, rootImpulseId: String) =
            ActionOrigin(OriginSource.ID, needId, rootImpulseId)
    }
}

enum class OriginSource { USER, ID, SYSTEM, GOAL }

data class PendingImpulse(
    val id: Long,
    val needId: String,
    val prompt: String,
    val tension: Double,
    val rawValue: Double,
    val rootImpulseId: String = RootInputIds.next(),
    val receivedAtMs: Long = System.currentTimeMillis(),
    val conversationContext: ConversationContext,
)

data class PendingAction(
    val id: Long,
    val urgency: Urgency,
    val type: ActionType,
    val payload: String,
    val summary: String,
    val attempts: Int = 0,
    val isFallbackExplanation: Boolean = false,
    val rootInputId: String? = null,
    val rootInputReceivedAtMs: Long? = null,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val requiresFollowUpThought: Boolean = false,
    val followUpPrefix: String = "Action completed.",
    val argumentDataTrust: DataTrust = DataTrust.TRUSTED_DATA,
    val origin: ActionOrigin = ActionOrigin.USER,
    val intentionId: String? = null,
    val intentionKind: IntentionKind = IntentionKind.PREPARE,
    val requestedCommitMode: CommitMode = CommitMode.NOT_APPLICABLE,
)

data class QueueSnapshot(
    val pendingInputCount: Int,
    val pendingThoughtCount: Int,
    val pendingActionCount: Int,
    val pendingIntentionCount: Int = 0,
    val pendingImpulseCount: Int = 0,
)

data class ClearedPendingWork(
    val thoughtsRemoved: Int = 0,
    val actionsRemoved: Int = 0,
)

data class QueueState(
    val inputs: List<PendingInput>,
    val thoughts: List<PendingThought>,
    val actions: List<PendingAction>,
    val intentions: List<QueuedIntention> = emptyList(),
)

data class ThreadContinuation(
    val rootInputId: String,
    val conversationContext: ConversationContext,
    val reason: String,
    val receivedAtMs: Long? = null,
)

sealed interface OpportunityTrigger {
    val rootInputId: String
    val conversationContext: ConversationContext
    val receivedAtMs: Long?

    data class Input(val input: PendingInput) : OpportunityTrigger {
        override val rootInputId: String = input.rootInputId
        override val conversationContext: ConversationContext = input.conversationContext
        override val receivedAtMs: Long = input.receivedAtMs
    }

    data class Impulse(val impulse: PendingImpulse) : OpportunityTrigger {
        override val rootInputId: String = impulse.rootImpulseId
        override val conversationContext: ConversationContext = impulse.conversationContext
        override val receivedAtMs: Long = impulse.receivedAtMs
    }

    data class ThreadWork(val continuation: ThreadContinuation) : OpportunityTrigger {
        override val rootInputId: String = continuation.rootInputId
        override val conversationContext: ConversationContext = continuation.conversationContext
        override val receivedAtMs: Long? = continuation.receivedAtMs
    }
}

sealed interface LoopTask {
    data class AttendOpportunity(val item: ScheduledOpportunity) : LoopTask
    data class ProcessIntention(val item: QueuedIntention) : LoopTask
    data class ProcessThought(val item: PendingThought) : LoopTask
    data class PerformAction(val item: PendingAction) : LoopTask
}

data class ScheduledOpportunity(
    val queueId: Long,
    val opportunity: Opportunity,
    val trigger: OpportunityTrigger,
) {
    val id: Long get() = queueId
    val rootInputId: String get() = trigger.rootInputId
    val conversationContext: ConversationContext get() = trigger.conversationContext
    val receivedAtMs: Long? get() = trigger.receivedAtMs
}

data class QueuedIntention(
    val queueId: Long = 0,
    val intention: Intention,
    val urgency: Urgency,
    val rootInputReceivedAtMs: Long? = null,
    val proposedActionType: ActionType? = null,
    val proposedActionPayload: String? = null,
    val proposedActionSummary: String? = null,
    val argumentDataTrust: DataTrust = DataTrust.TRUSTED_DATA,
    val origin: ActionOrigin = ActionOrigin.USER,
    val deferredThoughtContent: String? = null,
    val deferredThoughtPasses: Int = 0,
    val deferredThoughtRecallQuery: String? = null,
    val deferredDeniedActionType: ActionType? = null,
    val deferredDeniedActionPayload: String? = null,
    val deferredDenialReason: String? = null,
    val deferredAllowFallbackExplanation: Boolean = false,
    val deferredPlanContext: PlanContext? = null,
    val deferredDenialReasonCode: String? = null,
    val deferredOriginActionType: ActionType? = null,
    val deferredOriginActionObservedEvidence: Boolean? = null,
) {
    val rootInputId: String? get() = intention.rootStimulusId
    val conversationContext: ConversationContext get() = intention.conversationContext
}

object RootInputIds {
    fun next(): String = UUID.randomUUID().toString()
}
