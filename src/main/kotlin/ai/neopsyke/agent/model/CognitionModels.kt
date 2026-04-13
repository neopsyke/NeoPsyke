package ai.neopsyke.agent.model

import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import ai.neopsyke.agent.id.ConvergenceMode
import ai.neopsyke.agent.durablework.DurableWorkActivation
import ai.neopsyke.agent.durablework.StepStatus
import ai.neopsyke.agent.durablework.WorkItemStatus
import java.util.UUID

/**
 * Snapshot of the Id's drive state, injected into [PlannerContext] when the
 * planner is responding to an Id impulse. Enables drive-modulated reasoning.
 */
data class IdStateSnapshot(
    val triggeringNeed: String,
    val triggeringTension: Double,
    val allNeeds: Map<String, Double>,
    val convergence: ConvergenceMode = ConvergenceMode.CONTACT_USER,
    val allowEscalation: Boolean = false,
)

data class AmbientContext(
    val activeWorkItems: List<String> = emptyList(),
    val recentScratchpadThemes: List<String> = emptyList(),
    val recentUsefulActionsOrUpdates: List<String> = emptyList(),
    val unresolvedOpenLoops: List<String> = emptyList(),
    val recentExactLearningTopics: List<String> = emptyList(),
) {
    /**
     * Best-effort snapshot only.
     *
     * Ambient context is advisory prompt context, not execution-critical state.
     * It is intentionally allowed to be stale or incomplete so the main agent
     * loop can read it without blocking on synchronized structures, storage, or
     * cross-thread coordination.
     */
    fun isEmpty(): Boolean =
        activeWorkItems.isEmpty() &&
            recentScratchpadThemes.isEmpty() &&
            recentUsefulActionsOrUpdates.isEmpty() &&
            unresolvedOpenLoops.isEmpty() &&
            recentExactLearningTopics.isEmpty()

    fun render(): String {
        if (isEmpty()) return ""
        return buildString {
            append("Optional relevance signals:\n")
            appendSection("active_goals", activeWorkItems)
            appendSection("recent_scratchpad_themes", recentScratchpadThemes)
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

data class DurableWorkPlanStepSnapshot(
    val id: String,
    val description: String,
    val status: StepStatus,
    val acceptanceCriteria: String,
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
)

data class DurableWorkItemSnapshot(
    val workItemId: String,
    val title: String,
    val instruction: String,
    val completionCriteria: String,
    val status: WorkItemStatus,
    val planRevision: Int,
    val failureCountInWindow: Int = 0,
    val latestArtifactSummary: String? = null,
    val planSteps: List<DurableWorkPlanStepSnapshot> = emptyList(),
)

data class PlannerContext(
    val recentDialogue: List<DialogueTurn>,
    val queue: QueueSnapshot,
    val shortTermContextSummary: String = "",
    val longTermMemoryRecall: String = "",
    val lessons: String = "",
    val episodicRecall: String = "",
    val scratchpadSummary: String = "",
    val sessionScratchpadDigest: String = "",
    val ambientContext: AmbientContext = AmbientContext(),
    val evidenceHints: String = "",
    val deliberation: DeliberationState = DeliberationState(),
    val metaGuidance: String = "",
    val conversationSecuritySummary: String = "",
    val threadSecuritySummary: String = "",
    val triggerProvenanceSummary: String = "",
    val perceptSummary: String = "",
    val perceptFamily: PerceptFamily? = null,
    val cognitiveThreadId: String? = null,
    val cognitiveThreadStatus: CognitiveThreadStatus? = null,
    val opportunitySummary: String = "",
    val opportunityKind: OpportunityKind? = null,
    val allowedIntentions: Set<IntentionKind> = setOf(
        IntentionKind.OBSERVE,
        IntentionKind.PREPARE,
    ),
    val allowedCommitModes: Set<CommitMode> = CommitMode.entries.toSet(),
    val availableActions: Set<ActionType> = ActionType.entries.toSet(),
    val dispatchableActions: Set<ActionType> = availableActions,
    val actionDefinitions: List<ActionPlanningDefinition> = emptyList(),
    val conversationContext: ConversationContext = ConversationContext.default(),
    val idState: IdStateSnapshot? = null,
    val goalWorkSummary: String = "",
    val goalIndex: Map<Int, String> = emptyMap(),
    val goalSnapshots: Map<String, DurableWorkItemSnapshot> = emptyMap(),
    val groundingMetadata: GroundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
)

data class ActionPlanningDefinition(
    val actionType: ActionType,
    val description: String,
    val payloadGuidance: String,
    val payloadSchemaExample: String? = null,
    val effectClass: ActionEffectClass = ActionEffectClass.OBSERVE,
    val directCommitAllowed: Boolean = false,
    val supportsAutonomousCommit: Boolean = false,
    val allowedInstructionTrust: Set<InstructionTrust> = setOf(
        InstructionTrust.TRUSTED_INSTRUCTION,
        InstructionTrust.UNTRUSTED_INSTRUCTION,
    ),
)

data class SuperegoContext(
    val recentDialogue: List<DialogueTurn>,
    val shortTermContextSummary: String = "",
    val origin: ActionOrigin? = null,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val threadSecurityContext: CognitiveThreadSecurityContext =
        CognitiveThreadSecurityContext.fromConversation(ConversationContext.default().security),
)

sealed interface EgoTrigger {
    data class IncomingInput(val input: PendingInput) : EgoTrigger
    data class Continuation(val continuation: QueuedContinuation) : EgoTrigger
    data class ActionFeedback(val feedback: PendingFeedback) : EgoTrigger
    data class IncomingImpulse(val impulse: PendingImpulse) : EgoTrigger
    data class DurableWork(val workUnit: DurableWorkActivation) : EgoTrigger
}

sealed interface EgoDecision {
    data class EnqueueContinuation(
        val urgency: Urgency,
        val continuation: ai.neopsyke.agent.model.Continuation,
    ) : EgoDecision

    data class FormIntention(
        val urgency: Urgency,
        val intentionKind: IntentionKind,
        val commitModePreference: CommitMode = CommitMode.NOT_APPLICABLE,
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
    WAITING,
}

data class ActionOutcome(
    val statusSummary: String,
    val plannerSignal: String = statusSummary,
    val executionStatus: ActionExecutionStatus = ActionExecutionStatus.SUCCESS,
    val effects: Set<ActionEffect> = emptySet(),
    val observedEvidence: Boolean? = null,
    val actionErrorCategory: String? = null,
    val fetchErrorCategory: String? = null,
    val asyncWait: AsyncActionWait? = null,
    val resultArtifacts: List<ExternalContentArtifact> = emptyList(),
) {
    val successful: Boolean
        get() = executionStatus == ActionExecutionStatus.SUCCESS

    val waiting: Boolean
        get() = executionStatus == ActionExecutionStatus.WAITING || asyncWait != null
}

data class ExternalContentArtifact(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val provenance: Provenance,
) {
    val dataTrust: DataTrust
        get() = provenance.dataTrust

    fun taintSourceSummary(): String =
        buildString {
            append(provenance.source.provider)
            append("/")
            append(provenance.source.objectType)
            provenance.source.part?.takeIf { it.isNotBlank() }?.let {
                append(":")
                append(it)
            }
            provenance.source.sourceRef?.takeIf { it.isNotBlank() }?.let {
                append("#")
                append(it)
            }
        }
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
