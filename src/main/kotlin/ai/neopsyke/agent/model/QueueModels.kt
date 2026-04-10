package ai.neopsyke.agent.model

import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.goal.GoalRunActivation
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
    val groundingMetadata: GroundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
)

data class PlanContext(
    val planId: String,
    val planGoal: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val stepDescription: String,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = Continuation.PlanStepContinuation::class, name = "plan_step"),
    JsonSubTypes.Type(value = Continuation.RetryAlternative::class, name = "retry_alternative"),
    JsonSubTypes.Type(value = Continuation.ConvergeNow::class, name = "converge_now"),
    JsonSubTypes.Type(value = Continuation.WaitResume::class, name = "wait_resume"),
)
sealed interface Continuation {
    val content: String
    val longTermMemoryRecallQuery: String?
    val allowFallbackExplanation: Boolean
    val originActionType: ActionType?
    val originActionObservedEvidence: Boolean?

    data class PlanStepContinuation(
        override val content: String,
        val planContext: PlanContext,
        override val longTermMemoryRecallQuery: String? = null,
        override val allowFallbackExplanation: Boolean = false,
        override val originActionType: ActionType? = null,
        override val originActionObservedEvidence: Boolean? = null,
    ) : Continuation

    data class RetryAlternative(
        override val content: String,
        val deniedActionType: ActionType? = null,
        val deniedActionPayload: String? = null,
        val denialReason: String? = null,
        val denialReasonCode: String? = null,
        override val longTermMemoryRecallQuery: String? = null,
        override val allowFallbackExplanation: Boolean = false,
        override val originActionType: ActionType? = null,
        override val originActionObservedEvidence: Boolean? = null,
    ) : Continuation

    data class ConvergeNow(
        override val content: String,
        val convergenceReason: String,
        override val longTermMemoryRecallQuery: String? = null,
        override val allowFallbackExplanation: Boolean = false,
        override val originActionType: ActionType? = null,
        override val originActionObservedEvidence: Boolean? = null,
    ) : Continuation

    data class WaitResume(
        override val content: String,
        val waitReason: String,
        override val longTermMemoryRecallQuery: String? = null,
        override val allowFallbackExplanation: Boolean = false,
        override val originActionType: ActionType? = null,
        override val originActionObservedEvidence: Boolean? = null,
    ) : Continuation
}

/**
 * Tracks the origin of a pending continuation or action, distinguishing
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
    val groundingMetadata: GroundingMetadata,
    val isForcedTerminal: Boolean = false,
)

data class PendingFeedback(
    val cue: ActionFeedbackCue,
    val percept: Percept,
    val stimulusId: String,
    val stimulusContent: String,
    val receivedAtMs: Long,
    val resumedFromWaitingThread: Boolean = false,
)

data class QueueSnapshot(
    val pendingInputCount: Int,
    val continuationCount: Int,
    val pendingActionCount: Int,
    val pendingIntentionCount: Int = 0,
    val pendingImpulseCount: Int = 0,
)

data class ClearedPendingWork(
    val continuationsRemoved: Int = 0,
    val actionsRemoved: Int = 0,
)

data class QueueState(
    val inputs: List<PendingInput>,
    val continuations: List<QueuedContinuation>,
    val actions: List<PendingAction>,
    val intentions: List<QueuedIntention> = emptyList(),
)

sealed interface OpportunityTrigger {
    val rootInputId: String
    val conversationContext: ConversationContext
    val receivedAtMs: Long?
    val groundingMetadata: GroundingMetadata

    data class Input(val input: PendingInput) : OpportunityTrigger {
        override val rootInputId: String = input.rootInputId
        override val conversationContext: ConversationContext = input.conversationContext
        override val receivedAtMs: Long = input.receivedAtMs
        override val groundingMetadata: GroundingMetadata = input.groundingMetadata
    }

    data class Impulse(val impulse: PendingImpulse) : OpportunityTrigger {
        override val rootInputId: String = impulse.rootImpulseId
        override val conversationContext: ConversationContext = impulse.conversationContext
        override val receivedAtMs: Long = impulse.receivedAtMs
        override val groundingMetadata: GroundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER
    }

    data class Feedback(val feedback: PendingFeedback) : OpportunityTrigger {
        override val rootInputId: String = feedback.cue.rootInputId
        override val conversationContext: ConversationContext = feedback.cue.conversationContext
        override val receivedAtMs: Long = feedback.receivedAtMs
        override val groundingMetadata: GroundingMetadata = feedback.cue.groundingMetadata
    }

    data class GoalWork(val work: GoalRunActivation) : OpportunityTrigger {
        override val rootInputId: String = work.rootInputId
        override val conversationContext: ConversationContext = work.conversationContext
        override val receivedAtMs: Long? = null
        override val groundingMetadata: GroundingMetadata = work.groundingMetadata
    }
}

sealed interface LoopTask {
    data class AttendOpportunity(val item: ScheduledOpportunity) : LoopTask
    data class ProcessContinuation(val item: QueuedContinuation) : LoopTask
    data class ProcessIntention(val item: QueuedIntention) : LoopTask
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
    val groundingMetadata: GroundingMetadata,
) {
    val rootInputId: String? get() = intention.rootStimulusId
    val conversationContext: ConversationContext get() = intention.conversationContext
}

data class QueuedContinuation(
    val queueId: Long = 0,
    val urgency: Urgency,
    val continuation: Continuation,
    val passes: Int = 0,
    val rootInputId: String? = null,
    val rootInputReceivedAtMs: Long? = null,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val origin: ActionOrigin = ActionOrigin.USER,
    val groundingMetadata: GroundingMetadata,
) {
    @get:JsonIgnore
    val content: String get() = continuation.content
    @get:JsonIgnore
    val longTermMemoryRecallQuery: String? get() = continuation.longTermMemoryRecallQuery
    @get:JsonIgnore
    val allowFallbackExplanation: Boolean get() = continuation.allowFallbackExplanation
    @get:JsonIgnore
    val originActionType: ActionType? get() = continuation.originActionType
    @get:JsonIgnore
    val originActionObservedEvidence: Boolean? get() = continuation.originActionObservedEvidence
    @get:JsonIgnore
    val planContext: PlanContext?
        get() = (continuation as? Continuation.PlanStepContinuation)?.planContext
    @get:JsonIgnore
    val deniedActionType: ActionType?
        get() = (continuation as? Continuation.RetryAlternative)?.deniedActionType
    @get:JsonIgnore
    val deniedActionPayload: String?
        get() = (continuation as? Continuation.RetryAlternative)?.deniedActionPayload
    @get:JsonIgnore
    val denialReason: String?
        get() = (continuation as? Continuation.RetryAlternative)?.denialReason
    @get:JsonIgnore
    val denialReasonCode: String?
        get() = (continuation as? Continuation.RetryAlternative)?.denialReasonCode
}

object RootInputIds {
    fun next(): String = UUID.randomUUID().toString()
}
