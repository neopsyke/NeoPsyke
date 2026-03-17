package psyke.agent.model

import psyke.agent.id.ConvergenceMode

/**
 * Snapshot of the Id's drive state, injected into [PlannerContext] when the
 * planner is responding to an Id impulse. Enables drive-modulated reasoning.
 */
data class IdStateSnapshot(
    val triggeringNeed: String,
    val triggeringUrgency: Double,
    val allNeeds: Map<String, Double>,
    val convergence: ConvergenceMode = ConvergenceMode.CONTACT_USER,
    val allowEscalation: Boolean = false,
)

data class AmbientContext(
    val activeProjects: List<String> = emptyList(),
    val recentWorkspaceThemes: List<String> = emptyList(),
    val recentUsefulActionsOrUpdates: List<String> = emptyList(),
    val unresolvedOpenLoops: List<String> = emptyList(),
    val recentExactLearningTopics: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean =
        activeProjects.isEmpty() &&
            recentWorkspaceThemes.isEmpty() &&
            recentUsefulActionsOrUpdates.isEmpty() &&
            unresolvedOpenLoops.isEmpty() &&
            recentExactLearningTopics.isEmpty()

    fun render(): String {
        if (isEmpty()) return ""
        return buildString {
            append("Optional relevance signals:\n")
            appendSection("active_projects", activeProjects)
            appendSection("recent_workspace_themes", recentWorkspaceThemes)
            appendSection("recent_useful_actions_updates", recentUsefulActionsOrUpdates)
            appendSection("unresolved_open_loops", unresolvedOpenLoops)
            appendSection("recent_exact_learning_topics", recentExactLearningTopics)
        }.trim()
    }

    private fun StringBuilder.appendSection(title: String, items: List<String>) {
        if (items.isEmpty()) return
        append(title)
        append(":\n")
        items.forEachIndexed { index, item ->
            append("${index + 1}. ")
            append(item)
            append('\n')
        }
    }
}

data class PlannerContext(
    val recentDialogue: List<DialogueTurn>,
    val queue: QueueSnapshot,
    val shortTermContextSummary: String = "",
    val longTermMemoryRecall: String = "",
    val lessons: String = "",
    val episodicRecall: String = "",
    val taskWorkspaceSummary: String = "",
    val sessionWorkspaceDigest: String = "",
    val ambientContext: AmbientContext = AmbientContext(),
    val evidenceHints: String = "",
    val deliberation: DeliberationState = DeliberationState(),
    val metaGuidance: String = "",
    val availableActions: Set<ActionType> = ActionType.entries.toSet(),
    val dispatchableActions: Set<ActionType> = availableActions,
    val actionDefinitions: List<ActionPlanningDefinition> = emptyList(),
    val conversationContext: ConversationContext = ConversationContext.default(),
    val idState: IdStateSnapshot? = null,
)

data class ActionPlanningDefinition(
    val actionType: ActionType,
    val description: String,
    val payloadGuidance: String,
    val payloadSchemaExample: String? = null,
)

data class SuperegoContext(
    val recentDialogue: List<DialogueTurn>,
    val shortTermContextSummary: String = "",
    val origin: ActionOrigin? = null,
)

sealed interface EgoTrigger {
    data class IncomingInput(val input: PendingInput) : EgoTrigger
    data class PendingThoughtInput(val thought: PendingThought) : EgoTrigger
    data class IncomingImpulse(val impulse: PendingImpulse) : EgoTrigger
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

    data class Noop(
        val reason: String,
        val parseFailureShortCircuit: Boolean = false,
        val deniedActionType: ActionType? = null,
        val deniedActionPayload: String? = null,
        val denialReasonCode: String? = null,
    ) : EgoDecision

    data class EnqueuePlan(
        val urgency: Urgency,
        val goal: String,
        val steps: List<String>,
    ) : EgoDecision
}

data class GateDecision(
    val allow: Boolean,
    val reason: String,
    val reasonCode: String? = null,
)

enum class ActionExecutionStatus {
    SUCCESS,
    FAILED,
    NO_EFFECT,
}

data class ActionOutcome(
    val statusSummary: String,
    val assistantOutput: String? = null,
    val plannerSignal: String = statusSummary,
    val executionStatus: ActionExecutionStatus = ActionExecutionStatus.SUCCESS,
    val effects: Set<ActionEffect> = emptySet(),
    val observedEvidence: Boolean? = null,
    val actionErrorCategory: String? = null,
    val fetchErrorCategory: String? = null,
) {
    val successful: Boolean
        get() = executionStatus == ActionExecutionStatus.SUCCESS
}

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
