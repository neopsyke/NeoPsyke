package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.EvidenceArtifactStore
import ai.neopsyke.agent.cortex.motor.actions.NoopEvidenceArtifactStore
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.CognitiveThreadSnapshot
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation

private val logger = KotlinLogging.logger {}

/**
 * Manages deliberation state, meta-reasoning pressure, and external evidence tracking
 * for the Ego agent loop. Extracted from Ego to separate deliberation concerns.
 */
internal class DeliberationEngine(
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val metaReasoner: MetaReasoner,
    private val cognitiveThreads: CognitiveThreadStore = CognitiveThreadStore(),
    private val evidenceArtifactStore: EvidenceArtifactStore = NoopEvidenceArtifactStore,
    private val isEvidenceActionType: (ActionType) -> Boolean = { false },
) {
    private data class SessionDeliberationState(
        val monitor: DeliberationProgressMonitor = DeliberationProgressMonitor(),
        var guidance: String = "",
        var lastAssessmentStep: Int = 0,
    )

    private data class InputScope(
        val rootInputId: String,
        val sessionId: String,
    )

    private val sessionStates: MutableMap<String, SessionDeliberationState> =
        object : LinkedHashMap<String, SessionDeliberationState>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionDeliberationState>): Boolean =
                size > MAX_TRACKED_SESSIONS
        }
    private var activeSessionId: String = ConversationContext.DEFAULT_SESSION_ID
    private val forcedTerminalAnswerQueuedByInput = linkedSetOf<InputScope>()

    fun setActiveSession(sessionId: String) {
        activeSessionId = sessionId
    }

    private val externalEvidence: MutableMap<InputScope, ExternalEvidenceProgress> =
        object : LinkedHashMap<InputScope, ExternalEvidenceProgress>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InputScope, ExternalEvidenceProgress>): Boolean =
                size > MAX_EVIDENCE_ENTRIES
        }
    private val actionCooldownByScope: MutableMap<InputScope, MutableMap<ActionType, ActionCooldownState>> =
        object : LinkedHashMap<InputScope, MutableMap<ActionType, ActionCooldownState>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InputScope, MutableMap<ActionType, ActionCooldownState>>): Boolean =
                size > MAX_EVIDENCE_ENTRIES
        }
    private fun activeState(): SessionDeliberationState =
        sessionStates.getOrPut(activeSessionId) { SessionDeliberationState() }

    private fun inputScope(rootInputId: String?, sessionId: String): InputScope? {
        if (rootInputId.isNullOrBlank()) return null
        return InputScope(rootInputId = rootInputId, sessionId = sessionId)
    }

    private fun trimForcedTerminalQueue() {
        while (forcedTerminalAnswerQueuedByInput.size > MAX_FORCED_TERMINAL_SCOPES) {
            val iterator = forcedTerminalAnswerQueuedByInput.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    // --- Deliberation monitor delegation ---

    fun snapshot(): DeliberationState = activeState().monitor.snapshot()

    fun startStep(): DeliberationState = activeState().monitor.startStep()

    fun onPlannerDecision(decision: EgoDecision) = activeState().monitor.onPlannerDecision(decision)

    fun onActionExecuted(action: PendingAction, observedEvidence: Boolean) =
        activeState().monitor.onActionExecuted(action, observedEvidence)

    fun onActionDenied() = activeState().monitor.onActionDenied()

    fun onRepeatedDeniedAction() = activeState().monitor.onRepeatedDeniedAction()

    fun onTaskFailure() = activeState().monitor.onTaskFailure()

    // --- Guidance ---

    fun guidance(): String = activeState().guidance

    // --- Meta-reasoning ---

    /**
     * Runs meta-reasoner assessment if due, updates latest guidance, and returns the assessment
     * (or null if not triggered).
     */
    fun maybeAssessAndUpdateGuidance(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment? {
        val assessment = maybeAssessDeliberation(trigger, context) ?: return null
        activeState().guidance = buildMetaGuidance(assessment)
        return assessment
    }

    /**
     * Applies meta-pressure override to [decision] if [assessment] demands finalization.
     */
    fun maybeApplyPressureOverride(decision: EgoDecision, assessment: MetaReasonerAssessment?): EgoDecision {
        if (assessment == null) return decision
        val needsFinalization = assessment.verdict == MetaReasonerVerdict.FINALIZE_NOW ||
            assessment.verdict == MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE
        if (!needsFinalization) return decision
        if (decision is EgoDecision.FormIntention) return decision
        val pressuredThought = TextSecurity.clamp(
            "Decision pressure is high. Stop looping and provide a concise best-effort final answer now. " +
                "If one decisive tool action is strictly necessary, do only one then answer.",
            config.planner.maxThoughtChars
        )
        instrumentation.emit(AgentEvents.warning("MetaReasoner requested faster convergence; overriding non-action decision."))
        return EgoDecision.EnqueueThought(urgency = Urgency.HIGH, content = pressuredThought)
    }

    /**
     * If pressure thresholds are exceeded and no forced answer is queued, enqueues one via [scheduler].
     */
    fun maybeForceTerminalAnswer(
        scheduler: AttentionScheduler,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        groundingMetadata: GroundingMetadata? = null,
    ) {
        val scope = inputScope(rootInputId, conversationContext.sessionId) ?: return
        if (scope in forcedTerminalAnswerQueuedByInput) return
        val state = activeState().monitor.snapshot()
        if (state.stepIndex < config.metaReasoner.deliberationPressureAssessmentMinStep) return
        val circularPressureHigh = state.decisionPressure >= config.metaReasoner.forcedTerminalPressureThreshold &&
            state.staleStreak >= config.metaReasoner.forcedTerminalStaleStreakThreshold
        val repeatedModelErrors = state.modelErrorStreak >= MODEL_ERROR_STREAK_THRESHOLD &&
            state.decisionPressure >= MODEL_ERROR_PRESSURE_THRESHOLD &&
            state.stepIndex >= MODEL_ERROR_MIN_STEP_INDEX
        if (!circularPressureHigh && !repeatedModelErrors) return
        val basePayload = "I have reached diminishing returns in internal reasoning. " +
            "Here is the best concise answer I can provide now with current evidence."
        val payload = if (groundingMetadata?.requirement == GroundingRequirement.REQUIRED) {
            val evidence = evidenceFor(rootInputId, conversationContext.sessionId)
            if (evidence?.hadExternalFailures == true && evidence.hadSuccessfulEvidence != true) {
                basePayload + FORCED_TERMINAL_EVIDENCE_FAILURE_DISCLAIMER
            } else {
                basePayload
            }
        } else {
            basePayload
        }
        val queued = scheduler.enqueueAction(
            type = ActionType.CONTACT_USER,
            payload = TextSecurity.clamp(payload, config.maxActionPayloadChars),
            summary = "Forced terminal answer due to high decision pressure.",
            urgency = Urgency.HIGH,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext,
            groundingMetadata = groundingMetadata,
            isForcedTerminal = true,
        )
        if (queued) {
            forcedTerminalAnswerQueuedByInput.add(scope)
            trimForcedTerminalQueue()
            instrumentation.emit(AgentEvents.warning("Forced terminal answer queued due to persistent circular deliberation pressure."))
            activeState().guidance = "Finalize immediately due to persistent circular reasoning pressure."
        }
    }

    // --- External evidence tracking ---

    fun observedEvidence(action: PendingAction, outcome: ActionOutcome): Boolean {
        if (!isEvidenceAction(action)) return true
        if (outcome.waiting) return false
        outcome.observedEvidence?.let { return it }
        // Generic fallback: check plannerSignal for common failure keywords.
        val summary = outcome.plannerSignal.lowercase(java.util.Locale.ROOT)
        return !summary.contains("unavailable") &&
            !summary.contains("timeout") &&
            !summary.contains("not configured") &&
            !summary.contains("failed") &&
            !summary.contains("error")
    }

    fun recordEvidenceProgress(action: PendingAction, outcome: ActionOutcome, observed: Boolean) {
        if (!isEvidenceAction(action)) return
        val scope = inputScope(action.rootInputId, action.conversationContext.sessionId) ?: return
        val current = externalEvidence[scope] ?: ExternalEvidenceProgress()
        val updatedSignals = if (observed) {
            val signal = TextSecurity.clamp(outcome.plannerSignal, 280)
            val newList = current.successfulEvidenceSignals.toMutableList()
            if (newList.size < MAX_EVIDENCE_SIGNALS_PER_INPUT) {
                newList.add(signal)
            }
            newList
        } else {
            current.successfulEvidenceSignals
        }
        externalEvidence[scope] = current.copy(
            hadSuccessfulEvidence = current.hadSuccessfulEvidence || observed,
            hadExternalFailures = current.hadExternalFailures || !observed,
            latestPlannerSignal = if (observed) TextSecurity.clamp(outcome.plannerSignal, 420) else current.latestPlannerSignal,
            successfulEvidenceSignals = updatedSignals
        )
    }

    fun markEvidenceFailure(action: PendingAction) {
        if (!isEvidenceAction(action)) return
        val scope = inputScope(action.rootInputId, action.conversationContext.sessionId) ?: return
        val current = externalEvidence[scope] ?: ExternalEvidenceProgress()
        val actionId = action.id.takeIf { it > 0L }
        val alreadyCounted = actionId != null && actionId in current.technicalFailureActionIds
        val updatedIds = if (actionId != null && !alreadyCounted) {
            if (current.technicalFailureActionIds.size >= MAX_TRACKED_TECHNICAL_FAILURE_ACTION_IDS) {
                current.technicalFailureActionIds
            } else {
                current.technicalFailureActionIds + actionId
            }
        } else {
            current.technicalFailureActionIds
        }
        val nextCount = if (alreadyCounted) {
            current.technicalFailureCount
        } else {
            current.technicalFailureCount + 1
        }
        externalEvidence[scope] = current.copy(
            hadExternalFailures = true,
            technicalFailureCount = nextCount,
            technicalFailureActionIds = updatedIds,
        )
    }

    fun clearEvidenceForInput(rootInputId: String?, sessionId: String) {
        val scope = inputScope(rootInputId, sessionId) ?: return
        externalEvidence.remove(scope)
    }

    fun evidenceFor(rootInputId: String?, sessionId: String): ExternalEvidenceProgress? {
        val scope = inputScope(rootInputId, sessionId) ?: return null
        return externalEvidence[scope]
    }

    fun isGroundingTechnicalFailureBudgetExceeded(rootInputId: String?, sessionId: String): Boolean {
        val scope = inputScope(rootInputId, sessionId) ?: return false
        val evidence = externalEvidence[scope] ?: return false
        return evidence.technicalFailureCount >= GROUNDING_TECHNICAL_FAILURE_BUDGET
    }

    fun threadSecurityContext(
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): CognitiveThreadSecurityContext =
        cognitiveThreads.threadSecurityContext(rootInputId, conversationContext)

    fun recordIntention(rootInputId: String?, conversationContext: ConversationContext, intention: Intention) {
        cognitiveThreads.recordIntention(rootInputId, conversationContext, intention)
    }

    fun threadSnapshot(rootInputId: String?, conversationContext: ConversationContext): CognitiveThreadSnapshot? =
        cognitiveThreads.snapshot(rootInputId, conversationContext)

    // --- Action retry budget / cooldown ---

    fun recordActionArtifacts(action: PendingAction, outcome: ActionOutcome) {
        updateThreadSecurityContext(action, outcome)
    }

    fun recordActionOutcome(action: PendingAction, outcome: ActionOutcome, observed: Boolean) {
        if (!isEvidenceAction(action)) return
        val scope = inputScope(action.rootInputId, action.conversationContext.sessionId) ?: return
        val failureBudget = config.planner.actionRetryBudgetNonRetryableFailures
        val cooldownSteps = config.planner.actionRetryCooldownSteps
        if (failureBudget <= 0 || cooldownSteps <= 0) {
            return
        }

        val category = normalizeActionErrorCategory(outcome)
        val byAction = actionCooldownByScope.getOrPut(scope) { mutableMapOf() }
        val state = byAction[action.type] ?: ActionCooldownState()

        if (observed || category == ACTION_ERROR_CATEGORY_NONE) {
            byAction.remove(action.type)
            if (byAction.isEmpty()) {
                actionCooldownByScope.remove(scope)
            }
            return
        }
        if (category != ACTION_ERROR_CATEGORY_NON_RETRYABLE) {
            return
        }

        val nextCount = state.nonRetryableFailureCount + 1
        val nowStep = activeState().monitor.snapshot().stepIndex
        if (nextCount >= failureBudget) {
            val cooldownUntilStepExclusive = nowStep + cooldownSteps
            byAction[action.type] = ActionCooldownState(
                nonRetryableFailureCount = nextCount,
                cooldownUntilStepExclusive = cooldownUntilStepExclusive
            )
            instrumentation.emit(
                AgentEvent(
                    type = "action_type_circuit_breaker_tripped",
                    data = mapOf(
                        "action_type" to action.type.id,
                        "root_input_id" to scope.rootInputId,
                        "session_id" to scope.sessionId,
                        "non_retryable_failure_count" to nextCount,
                        "threshold" to failureBudget,
                        "cooldown_steps" to cooldownSteps,
                        "cooldown_until_step_exclusive" to cooldownUntilStepExclusive
                    )
                )
            )
            instrumentation.emit(
                AgentEvents.actionTypeTemporarilyDisabled(
                    actionType = action.type.id,
                    reason = "retry_budget_cooldown_non_retryable_failures",
                    rootInputId = scope.rootInputId
                )
            )
        } else {
            byAction[action.type] = state.copy(nonRetryableFailureCount = nextCount)
        }
    }

    fun disabledActionTypes(rootInputId: String?, sessionId: String): Set<ActionType> {
        val scope = inputScope(rootInputId, sessionId) ?: return emptySet()
        val byAction = actionCooldownByScope[scope] ?: return emptySet()
        if (byAction.isEmpty()) {
            actionCooldownByScope.remove(scope)
            return emptySet()
        }
        val nowStep = activeState().monitor.snapshot().stepIndex
        byAction.entries.removeIf { (_, state) -> state.cooldownUntilStepExclusive <= nowStep }
        if (byAction.isEmpty()) {
            actionCooldownByScope.remove(scope)
            return emptySet()
        }
        return byAction.keys.toSet()
    }

    // --- Reset ---

    fun hasForcedTerminalForInput(rootInputId: String?, sessionId: String): Boolean {
        val scope = inputScope(rootInputId, sessionId) ?: return false
        return scope in forcedTerminalAnswerQueuedByInput
    }

    fun clearForInput(rootInputId: String?, sessionId: String, retainThreadContinuity: Boolean = false) {
        val scope = inputScope(rootInputId, sessionId) ?: return
        if (!retainThreadContinuity) {
            forcedTerminalAnswerQueuedByInput.remove(scope)
        }
        if (!retainThreadContinuity) {
            externalEvidence.remove(scope)
            actionCooldownByScope.remove(scope)
            cognitiveThreads.clearForInput(rootInputId, sessionId)
            evidenceArtifactStore.clear(
                rootInputId,
                ConversationContext(
                    sessionId = sessionId,
                    interlocutor = ai.neopsyke.agent.model.Interlocutor.UNKNOWN,
                )
            )
        }
    }

    fun reset() {
        reset(retainThreadContinuity = false)
    }

    fun reset(retainThreadContinuity: Boolean) {
        sessionStates.clear()
        forcedTerminalAnswerQueuedByInput.clear()
        externalEvidence.clear()
        actionCooldownByScope.clear()
        if (!retainThreadContinuity) {
            cognitiveThreads.reset()
        }
    }

    // --- Private helpers ---

    private fun maybeAssessDeliberation(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment? {
        if (!metaReasoner.enabled) return null
        val stateHolder = activeState()
        val state = stateHolder.monitor.snapshot()
        val minStepReached = state.stepIndex >= config.metaReasoner.deliberationPressureAssessmentMinStep
        if (!minStepReached) return null
        val stepsSinceLast = state.stepIndex - stateHolder.lastAssessmentStep
        val dueByInterval = stepsSinceLast >= config.metaReasoner.deliberationPressureAssessmentEverySteps
        val dueByPressure = state.decisionPressure >= config.metaReasoner.deliberationPressureAssessmentThreshold &&
            stepsSinceLast >= config.metaReasoner.cooldownSteps
        if (!dueByInterval && !dueByPressure) return null
        val assessment = try {
            metaReasoner.assess(trigger, context)
        } catch (ex: Exception) {
            logger.warn(ex) { "MetaReasoner assessment failed; continuing without override." }
            instrumentation.emit(AgentEvents.warning("MetaReasoner call failed; continuing default deliberation."))
            return null
        }
        stateHolder.lastAssessmentStep = state.stepIndex
        instrumentation.emit(
            AgentEvent(
                type = "meta_reasoner_assessment",
                data = mapOf(
                    "step_index" to state.stepIndex,
                    "decision_pressure" to state.decisionPressure,
                    "verdict" to assessment.verdict.name.lowercase(),
                    "confidence" to assessment.confidence,
                    "reason" to assessment.reason,
                    "session_id" to activeSessionId
                )
            )
        )
        return assessment
    }

    private fun buildMetaGuidance(assessment: MetaReasonerAssessment): String =
        when (assessment.verdict) {
            MetaReasonerVerdict.CONTINUE ->
                "Continue current reasoning. Reason: ${assessment.reason}"
            MetaReasonerVerdict.CONTINUE_WITH_CONSTRAINTS ->
                "Reasoning is degrading. Avoid repeated thoughts/actions. Converge to one concrete next step in <=2 iterations. Reason: ${assessment.reason}"
            MetaReasonerVerdict.FINALIZE_NOW ->
                "Finalize now. Prefer action=answer with a concise best-effort response and explicit uncertainty if needed. Reason: ${assessment.reason}"
            MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE ->
                "At most one decisive external action is allowed, then finalize with action=answer immediately. Reason: ${assessment.reason}"
        }

    data class ExternalEvidenceProgress(
        val hadSuccessfulEvidence: Boolean = false,
        val hadExternalFailures: Boolean = false,
        val latestPlannerSignal: String = "",
        val successfulEvidenceSignals: List<String> = emptyList(),
        val technicalFailureCount: Int = 0,
        val technicalFailureActionIds: Set<Long> = emptySet(),
    )

    data class ActionCooldownState(
        val nonRetryableFailureCount: Int = 0,
        val cooldownUntilStepExclusive: Int = Int.MIN_VALUE,
    )

    private companion object {
        private const val MODEL_ERROR_STREAK_THRESHOLD: Int = 3
        private const val MODEL_ERROR_PRESSURE_THRESHOLD: Double = 0.72
        private const val MODEL_ERROR_MIN_STEP_INDEX: Int = 6
        private const val MAX_EVIDENCE_ENTRIES: Int = 64
        private const val MAX_EVIDENCE_SIGNALS_PER_INPUT: Int = 6
        private const val MAX_TRACKED_SESSIONS: Int = 128
        private const val MAX_FORCED_TERMINAL_SCOPES: Int = 256
        private const val ACTION_ERROR_CATEGORY_NONE: String = "none"
        private const val ACTION_ERROR_CATEGORY_NON_RETRYABLE: String = "non_retryable"
        private const val GROUNDING_TECHNICAL_FAILURE_BUDGET: Int = 2
        private const val MAX_TRACKED_TECHNICAL_FAILURE_ACTION_IDS: Int = 128
        private const val FORCED_TERMINAL_EVIDENCE_FAILURE_DISCLAIMER: String =
            "\n\nNote: I was unable to verify this information with external sources due to " +
                "technical failures. This answer is based on available knowledge and may not " +
                "reflect the latest data."
    }

    private fun normalizeActionErrorCategory(outcome: ActionOutcome): String {
        val explicit = outcome.actionErrorCategory?.trim()?.lowercase().orEmpty()
        if (explicit.isNotBlank()) {
            return explicit
        }
        val legacyFetch = outcome.fetchErrorCategory?.trim()?.lowercase().orEmpty()
        return when (legacyFetch) {
            "none" -> ACTION_ERROR_CATEGORY_NONE
            "non_retryable" -> ACTION_ERROR_CATEGORY_NON_RETRYABLE
            else -> "retryable"
        }
    }

    private fun isEvidenceAction(action: PendingAction): Boolean =
        action.requiresFollowUpThought || isEvidenceActionType(action.type)

    private fun updateThreadSecurityContext(action: PendingAction, outcome: ActionOutcome) {
        if (outcome.resultArtifacts.isEmpty()) return
        cognitiveThreads.observeArtifacts(
            rootInputId = action.rootInputId,
            conversationContext = action.conversationContext,
            artifacts = outcome.resultArtifacts,
        )
        evidenceArtifactStore.record(action.rootInputId, action.conversationContext, outcome.resultArtifacts)
    }
}
