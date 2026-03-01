package psyke.agent.core

data class PlannerContext(
    val recentDialogue: List<DialogueTurn>,
    val queue: QueueSnapshot,
    val shortTermContextSummary: String = "",
    val longTermMemoryRecall: String = "",
    val deliberation: DeliberationState = DeliberationState(),
    val metaGuidance: String = "",
    val availableActions: Set<ActionType> = ActionType.entries.toSet(),
)

data class SuperegoContext(
    val recentDialogue: List<DialogueTurn>,
    val shortTermContextSummary: String = "",
)

sealed interface EgoTrigger {
    data class IncomingInput(val input: PendingInput) : EgoTrigger
    data class PendingThoughtInput(val thought: PendingThought) : EgoTrigger
}

sealed interface EgoDecision {
    data class EnqueueThought(
        val urgency: Urgency,
        val content: String,
        val longTermMemoryRecallQuery: String? = null,
    ) : EgoDecision

    data class ProposeAction(
        val urgency: Urgency,
        val actionType: ActionType,
        val payload: String,
        val summary: String,
    ) : EgoDecision

    data class Noop(val reason: String) : EgoDecision

    data class EnqueuePlan(
        val urgency: Urgency,
        val goal: String,
        val steps: List<String>,
    ) : EgoDecision
}

data class GateDecision(
    val allow: Boolean,
    val reason: String,
)

data class ActionOutcome(
    val statusSummary: String,
    val assistantOutput: String? = null,
    val plannerSignal: String = statusSummary,
    val observedEvidence: Boolean? = null,
)

data class DeliberationState(
    val stepIndex: Int = 0,
    val decisionPressure: Double = 0.0,
    val staleStreak: Int = 0,
    val progressScore: Double = 0.0,
    val denialCount: Int = 0,
    val stepsSinceNewEvidence: Int = 0,
    val repeatSignatureHits: Int = 0,
    val noopStreak: Int = 0,
    val modelErrorStreak: Int = 0,
)
