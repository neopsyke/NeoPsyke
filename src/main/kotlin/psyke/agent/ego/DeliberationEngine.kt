package psyke.agent.ego

import mu.KotlinLogging
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.ConversationContext
import psyke.agent.core.DeliberationState
import psyke.agent.core.EgoDecision
import psyke.agent.core.EgoTrigger
import psyke.agent.core.ActionOutcome
import psyke.agent.core.PendingAction
import psyke.agent.core.PlannerContext
import psyke.agent.core.Urgency
import psyke.agent.support.TextSecurity
import psyke.agent.tools.mcp.FetchErrorCategory
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation

private val logger = KotlinLogging.logger {}

/**
 * Manages deliberation state, meta-reasoning pressure, and external evidence tracking
 * for the Ego agent loop. Extracted from Ego to separate deliberation concerns.
 */
internal class DeliberationEngine(
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val metaReasoner: MetaReasoner,
) {
    private val monitor = DeliberationProgressMonitor()
    private val guidanceBySession = mutableMapOf<String, String>()
    private var activeSessionId: String = ConversationContext.DEFAULT_SESSION_ID
    private var lastAssessmentStep: Int = 0
    private val forcedTerminalAnswerQueuedByInput = mutableSetOf<String>()

    fun setActiveSession(sessionId: String) {
        activeSessionId = sessionId
    }
    private val externalEvidence: MutableMap<String, ExternalEvidenceProgress> =
        object : LinkedHashMap<String, ExternalEvidenceProgress>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExternalEvidenceProgress>): Boolean =
                size > MAX_EVIDENCE_ENTRIES
        }
    private val fetchCircuitBreaker: MutableMap<String, Int> =
        object : LinkedHashMap<String, Int>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
                size > MAX_EVIDENCE_ENTRIES
        }

    // --- Deliberation monitor delegation ---

    fun snapshot(): DeliberationState = monitor.snapshot()

    fun startStep(): DeliberationState = monitor.startStep()

    fun onPlannerDecision(decision: EgoDecision) = monitor.onPlannerDecision(decision)

    fun onActionExecuted(action: PendingAction, observedEvidence: Boolean) =
        monitor.onActionExecuted(action, observedEvidence)

    fun onActionDenied() = monitor.onActionDenied()

    fun onRepeatedDeniedAction() = monitor.onRepeatedDeniedAction()

    fun onTaskFailure() = monitor.onTaskFailure()

    // --- Guidance ---

    fun guidance(): String = guidanceBySession[activeSessionId] ?: ""

    // --- Meta-reasoning ---

    /**
     * Runs meta-reasoner assessment if due, updates [latestGuidance], and returns the assessment
     * (or null if not triggered).
     */
    fun maybeAssessAndUpdateGuidance(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment? {
        val assessment = maybeAssessDeliberation(trigger, context) ?: return null
        guidanceBySession[activeSessionId] = buildMetaGuidance(assessment)
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
        if (decision is EgoDecision.ProposeAction) return decision
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
    ) {
        val effectiveRootInputId = rootInputId ?: ""
        if (effectiveRootInputId in forcedTerminalAnswerQueuedByInput) return
        val state = monitor.snapshot()
        if (state.stepIndex < config.metaReasoner.deliberationPressureAssessmentMinStep) return
        val circularPressureHigh = state.decisionPressure >= config.metaReasoner.forcedTerminalPressureThreshold &&
            state.staleStreak >= config.metaReasoner.forcedTerminalStaleStreakThreshold
        val repeatedModelErrors = state.modelErrorStreak >= MODEL_ERROR_STREAK_THRESHOLD &&
            state.decisionPressure >= MODEL_ERROR_PRESSURE_THRESHOLD &&
            state.stepIndex >= MODEL_ERROR_MIN_STEP_INDEX
        if (!circularPressureHigh && !repeatedModelErrors) return
        val queued = scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = TextSecurity.clamp(
                "I have reached diminishing returns in internal reasoning. " +
                    "Here is the best concise answer I can provide now with current evidence.",
                config.planner.maxActionPayloadChars
            ),
            summary = "Forced terminal answer due to high decision pressure.",
            urgency = Urgency.HIGH,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext
        )
        if (queued) {
            forcedTerminalAnswerQueuedByInput.add(effectiveRootInputId)
            instrumentation.emit(AgentEvents.warning("Forced terminal answer queued due to persistent circular deliberation pressure."))
            guidanceBySession[activeSessionId] = "Finalize immediately due to persistent circular reasoning pressure."
        }
    }

    // --- External evidence tracking ---

    fun observedEvidence(action: PendingAction, outcome: ActionOutcome): Boolean {
        if (!action.type.requiresFollowUpThought()) return true
        outcome.observedEvidence?.let { return it }
        val summary = outcome.plannerSignal.lowercase(java.util.Locale.ROOT)
        return when (action.type) {
            ActionType.WEB_SEARCH -> {
                !summary.contains("unavailable") &&
                    !summary.contains("timeout") &&
                    !summary.contains("sources: none") &&
                    !summary.contains("snippets: no snippets")
            }
            ActionType.MCP_FETCH, ActionType.MCP_TIME -> {
                !summary.contains("not configured") &&
                    !summary.contains("unavailable") &&
                    !summary.contains("failed") &&
                    !summary.contains("error") &&
                    !summary.contains("timeout")
            }
            ActionType.ANSWER -> true
            ActionType.MEMORY -> true // Memory ops are internal; treat as observed.
        }
    }

    fun recordEvidenceProgress(action: PendingAction, outcome: ActionOutcome, observed: Boolean) {
        if (!action.type.requiresFollowUpThought()) return
        val rootInputId = action.rootInputId ?: return
        val current = externalEvidence[rootInputId] ?: ExternalEvidenceProgress()
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
        externalEvidence[rootInputId] = current.copy(
            hadSuccessfulEvidence = current.hadSuccessfulEvidence || observed,
            hadExternalFailures = current.hadExternalFailures || !observed,
            latestPlannerSignal = if (observed) TextSecurity.clamp(outcome.plannerSignal, 420) else current.latestPlannerSignal,
            successfulEvidenceSignals = updatedSignals
        )
    }

    fun markEvidenceFailure(action: PendingAction) {
        if (!action.type.requiresFollowUpThought()) return
        val rootInputId = action.rootInputId ?: return
        val current = externalEvidence[rootInputId] ?: ExternalEvidenceProgress()
        externalEvidence[rootInputId] = current.copy(hadExternalFailures = true)
    }

    fun clearEvidenceForInput(rootInputId: String?) {
        if (rootInputId.isNullOrBlank()) return
        externalEvidence.remove(rootInputId)
    }

    fun evidenceFor(rootInputId: String?): ExternalEvidenceProgress? =
        rootInputId?.let { externalEvidence[it] }

    // --- Fetch circuit breaker ---

    fun recordFetchFailure(rootInputId: String?, errorCategory: FetchErrorCategory) {
        if (rootInputId.isNullOrBlank()) return
        if (errorCategory == FetchErrorCategory.NON_RETRYABLE) {
            val count = (fetchCircuitBreaker[rootInputId] ?: 0) + 1
            fetchCircuitBreaker[rootInputId] = count
            if (count >= FETCH_CIRCUIT_BREAKER_THRESHOLD) {
                instrumentation.emit(
                    AgentEvent(
                        type = "action_type_circuit_breaker_tripped",
                        data = mapOf(
                            "action_type" to "mcp_fetch",
                            "root_input_id" to rootInputId,
                            "non_retryable_failure_count" to count,
                            "threshold" to FETCH_CIRCUIT_BREAKER_THRESHOLD
                        )
                    )
                )
                instrumentation.emit(
                    AgentEvents.actionTypeTemporarilyDisabled(
                        actionType = "mcp_fetch",
                        reason = "circuit_breaker_non_retryable_failures",
                        rootInputId = rootInputId
                    )
                )
            }
        }
    }

    fun disabledActionTypes(rootInputId: String?): Set<ActionType> {
        if (rootInputId.isNullOrBlank()) return emptySet()
        val fetchFailures = fetchCircuitBreaker[rootInputId] ?: 0
        return if (fetchFailures >= FETCH_CIRCUIT_BREAKER_THRESHOLD) {
            setOf(ActionType.MCP_FETCH)
        } else {
            emptySet()
        }
    }

    // --- Reset ---

    fun clearForInput(rootInputId: String?) {
        if (rootInputId.isNullOrBlank()) return
        forcedTerminalAnswerQueuedByInput.remove(rootInputId)
        externalEvidence.remove(rootInputId)
        fetchCircuitBreaker.remove(rootInputId)
    }

    fun reset() {
        guidanceBySession.clear()
        lastAssessmentStep = 0
        forcedTerminalAnswerQueuedByInput.clear()
        externalEvidence.clear()
        fetchCircuitBreaker.clear()
        monitor.reset()
    }

    // --- Private helpers ---

    private fun maybeAssessDeliberation(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment? {
        if (!metaReasoner.enabled) return null
        val state = monitor.snapshot()
        val minStepReached = state.stepIndex >= config.metaReasoner.deliberationPressureAssessmentMinStep
        if (!minStepReached) return null
        val stepsSinceLast = state.stepIndex - lastAssessmentStep
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
        lastAssessmentStep = state.stepIndex
        instrumentation.emit(
            AgentEvent(
                type = "meta_reasoner_assessment",
                data = mapOf(
                    "step_index" to state.stepIndex,
                    "decision_pressure" to state.decisionPressure,
                    "verdict" to assessment.verdict.name.lowercase(),
                    "confidence" to assessment.confidence,
                    "reason" to assessment.reason
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

    private fun ActionType.requiresFollowUpThought(): Boolean =
        this == ActionType.WEB_SEARCH || this == ActionType.MCP_TIME || this == ActionType.MCP_FETCH

    data class ExternalEvidenceProgress(
        val hadSuccessfulEvidence: Boolean = false,
        val hadExternalFailures: Boolean = false,
        val latestPlannerSignal: String = "",
        val successfulEvidenceSignals: List<String> = emptyList(),
    )

    private companion object {
        private const val MODEL_ERROR_STREAK_THRESHOLD: Int = 3
        private const val MODEL_ERROR_PRESSURE_THRESHOLD: Double = 0.72
        private const val MODEL_ERROR_MIN_STEP_INDEX: Int = 6
        private const val MAX_EVIDENCE_ENTRIES: Int = 64
        private const val FETCH_CIRCUIT_BREAKER_THRESHOLD: Int = 3
        private const val MAX_EVIDENCE_SIGNALS_PER_INPUT: Int = 6
    }
}
