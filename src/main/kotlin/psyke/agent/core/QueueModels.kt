package psyke.agent.core

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
)

data class QueueSnapshot(
    val pendingInputCount: Int,
    val pendingThoughtCount: Int,
    val pendingActionCount: Int,
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

sealed interface LoopTask {
    data class ProcessInput(val item: PendingInput) : LoopTask
    data class ProcessThought(val item: PendingThought) : LoopTask
    data class PerformAction(val item: PendingAction) : LoopTask
}

object RootInputIds {
    fun next(): String = UUID.randomUUID().toString()
}
