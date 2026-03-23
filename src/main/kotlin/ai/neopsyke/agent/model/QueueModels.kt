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
    val urgency: Double,
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
    val origin: ActionOrigin = ActionOrigin.USER,
)

data class QueueSnapshot(
    val pendingInputCount: Int,
    val pendingThoughtCount: Int,
    val pendingActionCount: Int,
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
)

sealed interface OpportunityWorkItem {
    val id: Long
    val rootInputId: String
    val conversationContext: ConversationContext

    data class InputOpportunity(val input: PendingInput) : OpportunityWorkItem {
        override val id: Long = input.id
        override val rootInputId: String = input.rootInputId
        override val conversationContext: ConversationContext = input.conversationContext
    }

    data class ImpulseOpportunity(val impulse: PendingImpulse) : OpportunityWorkItem {
        override val id: Long = impulse.id
        override val rootInputId: String = impulse.rootImpulseId
        override val conversationContext: ConversationContext = impulse.conversationContext
    }

    data class GoalWorkOpportunity(val workUnit: ai.neopsyke.agent.goal.GoalRunActivation) : OpportunityWorkItem {
        override val id: Long = workUnit.rootInputId.hashCode().toLong()
        override val rootInputId: String = workUnit.rootInputId
        override val conversationContext: ConversationContext = ConversationContext.default()
    }
}

sealed interface LoopTask {
    data class AttendOpportunity(val item: OpportunityWorkItem) : LoopTask
    data class ProcessThought(val item: PendingThought) : LoopTask
    data class PerformAction(val item: PendingAction) : LoopTask
}

object RootInputIds {
    fun next(): String = UUID.randomUUID().toString()
}
