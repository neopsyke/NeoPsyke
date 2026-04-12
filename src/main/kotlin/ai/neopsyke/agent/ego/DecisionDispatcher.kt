package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.ego.planner.ActionSummary
import ai.neopsyke.agent.ego.planner.PlanKind
import ai.neopsyke.agent.ego.planner.PlanRefiner
import ai.neopsyke.agent.ego.planner.PlanRefinementMode
import ai.neopsyke.agent.ego.planner.PlanRefinementRequest
import ai.neopsyke.agent.ego.planner.PlanStepCandidate
import ai.neopsyke.agent.ego.planner.TerminalPolicy
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.support.DenialReasonClassifier
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import java.time.Instant

internal data class InputScope(
    val rootInputId: String?,
    val sessionId: String,
)

internal class DecisionDispatcher(
    private val scheduler: AttentionScheduler,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val deliberation: DeliberationEngine,
    private val memory: MemorySystem,
    private val motorCortex: MotorCortex,
    private val scratchpadStore: ScratchpadStore,
    private val telemetry: EgoTelemetry,
    private val fallbackHandler: FallbackHandler,
    private val planRefiner: PlanRefiner,
    private val dialogueFor: (String) -> ArrayDeque<DialogueTurn>,
    private val resolveSessionId: (ConversationContext) -> String,
    private val inputScope: (String?, ConversationContext) -> InputScope,
) {
    // Coroutine-confined: accessed only from dispatch() within the single runLoop
    // coroutine, and cleared by resetForNewInput() when the loop is idle.
    private val planCountByInput = mutableMapOf<InputScope, Int>()
    private val emittedPlanHashes = mutableMapOf<InputScope, MutableSet<String>>()
    private val externalActionSignatureHitsByInput = mutableMapOf<InputScope, MutableMap<String, Int>>()

    fun resetForNewInput() {
        planCountByInput.clear()
        emittedPlanHashes.clear()
        externalActionSignatureHitsByInput.clear()
    }

    fun clearExternalActionSignatures(scope: InputScope) {
        externalActionSignatureHitsByInput.remove(scope)
    }

    private fun preservesDraftSequence(actionType: ActionType): Boolean =
        actionType == ActionType.RESOLUTION_DRAFT || actionType == ActionType.CONTACT_USER

    private fun enqueueContinuation(
        continuation: Continuation,
        urgency: Urgency,
        nextPassCount: Int,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        origin: ActionOrigin,
        groundingMetadata: GroundingMetadata,
    ): Boolean {
        val queued = scheduler.enqueueContinuation(
            continuation = continuation,
            urgency = urgency,
            passes = nextPassCount,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = origin,
            groundingMetadata = groundingMetadata,
        )
        if (queued != null) {
            groundingMetadata?.let { metadata ->
                instrumentation.emit(
                    AgentEvents.groundingMetadataPropagated(
                        rootInputId = rootInputId,
                        fromEnvelopeType = "planner_context",
                        toEnvelopeType = "queued_continuation",
                        groundingRequired = metadata.requirement == GroundingRequirement.REQUIRED,
                        source = metadata.source.name.lowercase(),
                    )
                )
            }
        }
        return queued != null
    }

    suspend fun dispatch(
        decision: EgoDecision,
        nextPassCount: Int,
        originContinuation: QueuedContinuation?,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        plannerContext: PlannerContext? = null,
        origin: ActionOrigin = ActionOrigin.USER,
    ) {
        when (decision) {
            is EgoDecision.EnqueueContinuation -> {
                scratchpadStore.resetDraftSequence(rootInputId)
                val resolvedGrounding = originContinuation?.groundingMetadata ?: plannerContext?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER
                val queued = enqueueContinuation(
                    continuation = decision.continuation,
                    urgency = decision.urgency,
                    nextPassCount = nextPassCount,
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    conversationContext = conversationContext,
                    origin = origin,
                    groundingMetadata = resolvedGrounding,
                )
                val recallQuery = decision.continuation.longTermMemoryRecallQuery
                if (!recallQuery.isNullOrBlank()) {
                    instrumentation.emit(
                        AgentEvents.longTermMemoryRecallRequested(
                            trigger = "continuation",
                            source = "planner",
                            queryPreview = TextSecurity.preview(recallQuery, 180)
                        )
                    )
                }
                instrumentation.emit(
                    AgentEvent(
                        type = "continuation_enqueued",
                        data = mapOf(
                            "queued" to queued,
                            "urgency" to decision.urgency.name.lowercase(),
                            "content" to decision.continuation.content,
                            "continuation_type" to decision.continuation.javaClass.simpleName,
                            "grounding_required" to (resolvedGrounding?.requirement?.name?.lowercase()),
                            "grounding_source" to (resolvedGrounding?.source?.name?.lowercase()),
                        )
                    )
                )
                if (!queued) {
                    instrumentation.emit(AgentEvents.warning("Failed to enqueue planner continuation."))
                    telemetry.recordQueueSaturation(
                        queueType = "continuation",
                        capacity = config.maxPendingContinuations,
                        reason = "enqueue_planner_continuation_failed_full"
                    )
                }
                telemetry.emitQueueSnapshot("decision_continuation")
            }

            is EgoDecision.FormIntention -> {
                if (!preservesDraftSequence(decision.actionType)) {
                    scratchpadStore.resetDraftSequence(rootInputId)
                }
                plannerContextViolationFor(decision, plannerContext)?.let { violation ->
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Planner formed intention '${decision.intentionKind.name.lowercase()}/${decision.actionType.id}' outside the current opportunity contract; requesting an alternative."
                        )
                    )
                    instrumentation.emit(
                        AgentEvent(
                            type = "planner_decision_blocked",
                            data = mapOf(
                                "intention_kind" to decision.intentionKind.name.lowercase(),
                                "commit_mode_preference" to decision.commitModePreference.name.lowercase(),
                                "action_type" to decision.actionType.id,
                                "summary" to decision.summary,
                                "reason_code" to violation.reasonCode,
                                "reason" to violation.reason,
                                "opportunity_kind" to plannerContext?.opportunityKind?.name?.lowercase(),
                                "allowed_intentions" to plannerContext?.allowedIntentions?.map { it.name.lowercase() }.orEmpty(),
                                "available_actions" to plannerContext?.availableActions?.map { it.id }?.sorted().orEmpty(),
                                "dispatchable_actions" to plannerContext?.dispatchableActions?.map { it.id }?.sorted().orEmpty(),
                                "root_input_id" to rootInputId,
                            )
                        )
                    )
                    val retryContinuation = Continuation.RetryAlternative(
                        content = TextSecurity.clamp(
                        buildInvalidActionRetryThought(decision, plannerContext, violation),
                        config.planner.maxThoughtChars
                        ),
                        deniedActionType = decision.actionType,
                        deniedActionPayload = decision.payload,
                        denialReason = violation.reason,
                        denialReasonCode = violation.reasonCode,
                        allowFallbackExplanation = origin.source != OriginSource.ID,
                        originActionType = originContinuation?.originActionType,
                        originActionObservedEvidence = originContinuation?.originActionObservedEvidence,
                    )
                    val queuedRetry = enqueueContinuation(
                        continuation = retryContinuation,
                        urgency = decision.urgency,
                        nextPassCount = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext,
                        origin = origin,
                        groundingMetadata = originContinuation?.groundingMetadata ?: plannerContext?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    )
                    if (!queuedRetry) {
                        instrumentation.emit(
                            AgentEvents.warning("Failed to enqueue retry continuation after blocking an invalid planner action.")
                        )
                        telemetry.recordQueueSaturation(
                            queueType = "continuation",
                            capacity = config.maxPendingContinuations,
                            reason = "enqueue_invalid_action_retry_continuation_failed_full"
                        )
                    }
                    telemetry.emitQueueSnapshot("decision_action_blocked")
                    return
                }
                val repeatedDeniedAction = originContinuation != null && fallbackHandler.isRepeatOfDeniedAction(originContinuation, decision)
                val technicalDenial = DenialReasonClassifier.isLikelyTechnical(
                    reasonCode = originContinuation?.denialReasonCode,
                    reason = originContinuation?.denialReason
                )
                if (repeatedDeniedAction && !technicalDenial) {
                    instrumentation.emit(AgentEvents.warning("Planner repeated a denied action; requesting an alternative."))
                    deliberation.onRepeatedDeniedAction()
                    memory.maybeRecordLesson(
                        trigger = "repeated_denied_action",
                        actionType = decision.actionType,
                        reasonCode = originContinuation!!.denialReasonCode,
                        reason = originContinuation.denialReason,
                        deniedPayload = decision.payload,
                        recentDialogue = dialogueFor(resolveSessionId(conversationContext)).takeLast(12),
                        stepIndex = deliberation.snapshot().stepIndex
                    )
                    val retryContinuation = Continuation.RetryAlternative(
                        content = TextSecurity.clamp(
                            "Previous proposed action repeats a denied action. Pick a materially different safe action.",
                            config.planner.maxThoughtChars
                        ),
                        deniedActionType = originContinuation.deniedActionType,
                        deniedActionPayload = originContinuation.deniedActionPayload,
                        denialReason = originContinuation.denialReason,
                        denialReasonCode = originContinuation.denialReasonCode,
                        allowFallbackExplanation = originContinuation.allowFallbackExplanation,
                        originActionType = originContinuation.originActionType,
                        originActionObservedEvidence = originContinuation.originActionObservedEvidence,
                    )
                    val queuedRetry = enqueueContinuation(
                        continuation = retryContinuation,
                        urgency = originContinuation.urgency,
                        nextPassCount = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext,
                        origin = origin,
                        groundingMetadata = originContinuation.groundingMetadata,
                    )
                    if (!queuedRetry) {
                        instrumentation.emit(AgentEvents.warning("Failed to enqueue retry continuation after repeated denied action."))
                        telemetry.recordQueueSaturation(
                            queueType = "continuation",
                            capacity = config.maxPendingContinuations,
                            reason = "enqueue_retry_continuation_failed_full"
                        )
                    }
                    telemetry.emitQueueSnapshot("repeated_denied_action_blocked")
                    return
                }
                emitExternalActionRedundancySignal(
                    decision = decision,
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    conversationContext = conversationContext
                )
                val intention = Intention(
                    id = RootInputIds.next(),
                    cognitiveThreadId = rootInputId ?: RootInputIds.next(),
                    kind = decision.intentionKind,
                    summary = decision.summary,
                    createdAt = Instant.now(),
                    conversationContext = conversationContext,
                    commitMode = decision.commitModePreference,
                    rootStimulusId = rootInputId,
                )
                val queued = scheduler.enqueueIntention(
                    QueuedIntention(
                        intention = intention,
                        urgency = decision.urgency,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        proposedActionType = decision.actionType,
                        proposedActionPayload = decision.payload,
                        proposedActionSummary = decision.summary,
                        argumentDataTrust = deliberation.threadSecurityContext(rootInputId, conversationContext).aggregatedDataTrust,
                        origin = origin,
                        groundingMetadata = originContinuation?.groundingMetadata ?: plannerContext?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    )
                )
                if (queued) {
                    deliberation.recordIntention(rootInputId, conversationContext, intention)
                }
                val actionGrounding = originContinuation?.groundingMetadata ?: plannerContext?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER
                if (queued) {
                    instrumentation.emit(
                        AgentEvents.groundingMetadataPropagated(
                            rootInputId = rootInputId,
                            fromEnvelopeType = "planner_context",
                            toEnvelopeType = "queued_intention",
                            groundingRequired = actionGrounding.requirement == GroundingRequirement.REQUIRED,
                            source = actionGrounding.source.name.lowercase(),
                        )
                    )
                }
                instrumentation.emit(
                    AgentEvents.actionProposed(
                        actionType = decision.actionType.id,
                        intentionKind = decision.intentionKind.name.lowercase(),
                        commitModePreference = decision.commitModePreference.name.lowercase(),
                        urgency = decision.urgency.name.lowercase(),
                        payload = decision.payload,
                        summary = decision.summary,
                        queued = queued,
                        groundingRequired = actionGrounding.requirement.name.lowercase(),
                        groundingSource = actionGrounding.source.name.lowercase(),
                    )
                )
                if (!queued) {
                    instrumentation.emit(AgentEvents.warning("Failed to enqueue proposed intention."))
                    telemetry.recordQueueSaturation(
                        queueType = "action",
                        capacity = config.maxPendingActions,
                        reason = "enqueue_intention_failed_full"
                    )
                }
                telemetry.emitQueueSnapshot("decision_intention")
            }

            is EgoDecision.EnqueuePlan -> {
                scratchpadStore.resetDraftSequence(rootInputId)

                // ── Plan refinement ──
                val refinedDecision = refineInlinePlan(decision, plannerContext)

                val scope = inputScope(rootInputId, conversationContext)
                // ── Gate 1: plan budget per input ──
                val currentPlanCount = planCountByInput.getOrDefault(scope, 0)
                if (currentPlanCount >= config.planner.maxPlansPerInput) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Duplicate plan suppressed; plan budget exhausted " +
                                "($currentPlanCount/${config.planner.maxPlansPerInput}) for this input."
                        )
                    )
                    instrumentation.emit(
                        AgentEvents.duplicatePlanSuppressed(
                            reason = "budget_exhausted",
                            rootInputId = rootInputId,
                            rootInputReceivedAtMs = rootInputReceivedAtMs
                        )
                    )
                    recoverFromSuppressedPlan(
                        suppressionReason = "budget_exhausted",
                        decision = decision,
                        nextPassCount = nextPassCount,
                        originContinuation = originContinuation,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    telemetry.emitQueueSnapshot("decision_plan_suppressed_budget")
                    return
                }

                // ── Gate 2: exact plan hash dedup (uses refined plan) ──
                val planHash = normalizePlanHash(refinedDecision.goal, refinedDecision.steps)
                val inputHashes = emittedPlanHashes.getOrPut(scope) { mutableSetOf() }
                if (!inputHashes.add(planHash)) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Duplicate plan suppressed; identical plan hash already emitted for this input."
                        )
                    )
                    instrumentation.emit(
                        AgentEvents.duplicatePlanSuppressed(
                            reason = "hash_dedup",
                            rootInputId = rootInputId,
                            rootInputReceivedAtMs = rootInputReceivedAtMs
                        )
                    )
                    recoverFromSuppressedPlan(
                        suppressionReason = "hash_dedup",
                        decision = refinedDecision,
                        nextPassCount = nextPassCount,
                        originContinuation = originContinuation,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    telemetry.emitQueueSnapshot("decision_plan_suppressed_hash")
                    return
                }

                // ── All gates passed: emit plan ──
                planCountByInput[scope] = currentPlanCount + 1
                val scratchpadActivated = scratchpadStore.recordPlan(
                    rootInputId = rootInputId,
                    goal = refinedDecision.goal,
                    steps = refinedDecision.steps
                )
                if (scratchpadActivated) {
                    instrumentation.emit(
                        AgentEvent(
                            type = "scratchpad_created",
                            data = mapOf(
                                "root_input_id" to rootInputId,
                                "root_input_received_at_ms" to rootInputReceivedAtMs,
                                "goal_preview" to TextSecurity.preview(refinedDecision.goal, 140),
                                "active_tasks" to scratchpadStore.activeTaskCount(),
                                "activation_trigger" to "plan_complexity",
                                "plan_step_count" to refinedDecision.steps.size
                            )
                        )
                    )
                    telemetry.emitScratchpadTelemetry(
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        updateType = "scratchpad_activated"
                    )
                }
                instrumentation.emit(
                    AgentEvent(
                        type = "scratchpad_updated",
                        data = mapOf(
                            "root_input_id" to rootInputId,
                            "root_input_received_at_ms" to rootInputReceivedAtMs,
                            "update_type" to "plan_recorded",
                            "goal_preview" to TextSecurity.preview(refinedDecision.goal, 140),
                            "step_count" to refinedDecision.steps.size,
                            "active_tasks" to scratchpadStore.activeTaskCount()
                        )
                    )
                )
                telemetry.emitScratchpadTelemetry(
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    updateType = "plan_recorded"
                )

                val planId = java.util.UUID.randomUUID().toString().take(PLAN_ID_LENGTH)
                instrumentation.emit(
                    AgentEvents.planCreated(
                        planId = planId,
                        goal = refinedDecision.goal,
                        stepCount = refinedDecision.steps.size,
                        urgency = refinedDecision.urgency.name.lowercase(),
                        steps = refinedDecision.steps,
                        rootInputId = rootInputId,
                    )
                )
                var allQueued = true
                refinedDecision.steps.forEachIndexed { index, stepDescription ->
                    val stepContinuation = Continuation.PlanStepContinuation(
                        content = TextSecurity.clamp(
                            "Plan step ${index + 1}/${refinedDecision.steps.size}: $stepDescription",
                            config.planner.maxThoughtChars
                        ),
                        planContext = PlanContext(
                            planId = planId,
                            planGoal = refinedDecision.goal,
                            stepIndex = index,
                            totalSteps = refinedDecision.steps.size,
                            stepDescription = stepDescription,
                        ),
                        allowFallbackExplanation = originContinuation?.allowFallbackExplanation == true || origin.source != OriginSource.ID,
                        originActionType = originContinuation?.originActionType,
                        originActionObservedEvidence = originContinuation?.originActionObservedEvidence,
                    )
                    val queued = enqueueContinuation(
                        continuation = stepContinuation,
                        urgency = refinedDecision.urgency,
                        nextPassCount = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext,
                        origin = origin,
                        groundingMetadata = originContinuation?.groundingMetadata ?: plannerContext?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    )
                    if (!queued) {
                        allQueued = false
                        instrumentation.emit(
                            AgentEvents.warning("Failed to enqueue plan step ${index + 1}/${refinedDecision.steps.size}.")
                        )
                        telemetry.recordQueueSaturation(
                            queueType = "continuation",
                            capacity = config.maxPendingContinuations,
                            reason = "enqueue_plan_step_failed_full"
                        )
                    }
                }
                val planGrounding = originContinuation?.groundingMetadata ?: plannerContext?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER
                instrumentation.emit(
                    AgentEvents.planStepsEnqueued(
                        planId = planId,
                        totalSteps = refinedDecision.steps.size,
                        allQueued = allQueued,
                        groundingRequired = planGrounding?.requirement?.name?.lowercase(),
                        groundingSource = planGrounding?.source?.name?.lowercase(),
                    )
                )
                telemetry.emitQueueSnapshot("decision_plan")
            }

            is EgoDecision.Noop -> {
                scratchpadStore.resetDraftSequence(rootInputId)
                if (decision.parseFailureShortCircuit) {
                    instrumentation.emit(
                        AgentEvents.warning("Parse-failure circuit breaker tripped; skipping noop re-enqueue and going to fallback.")
                    )
                    fallbackHandler.enqueueFallbackExplanation(
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        reason = decision.reason,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    telemetry.emitQueueSnapshot("decision_noop_short_circuit")
                } else {
                    instrumentation.emit(
                        AgentEvent(
                            type = "noop_recorded",
                            data = mapOf("queued_continuation" to false, "reason" to decision.reason)
                        )
                    )
                    if (originContinuation?.allowFallbackExplanation == true) {
                        fallbackHandler.enqueueFallbackExplanation(originContinuation)
                    }
                    telemetry.emitQueueSnapshot("decision_noop")
                }
            }
        }
    }

    private suspend fun recoverFromSuppressedPlan(
        suppressionReason: String,
        decision: EgoDecision.EnqueuePlan,
        nextPassCount: Int,
        originContinuation: QueuedContinuation?,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        origin: ActionOrigin,
    ) {
        val sessionId = resolveSessionId(conversationContext)
        if (scheduler.hasPendingPlanContinuationsForInput(rootInputId, sessionId)) {
            return
        }
        if (scheduler.hasPendingConvergenceContinuationForInput(rootInputId, sessionId)) {
            return
        }
        val convergenceContinuation = Continuation.ConvergeNow(
            content = TextSecurity.clamp(
                "${AttentionScheduler.CONVERGENCE_CONTINUATION_PREFIX}" +
                    "Plan emission was suppressed ($suppressionReason). " +
                    "Converge now: use gathered evidence and produce a final answer, " +
                    "or provide a concise fallback explanation if completion is not possible.",
                config.planner.maxThoughtChars
            ),
            convergenceReason = suppressionReason,
            allowFallbackExplanation =
                originContinuation?.allowFallbackExplanation == true || origin.source != OriginSource.ID,
            originActionType = originContinuation?.originActionType,
            originActionObservedEvidence = originContinuation?.originActionObservedEvidence,
        )
        val queued = enqueueContinuation(
            continuation = convergenceContinuation,
            urgency = decision.urgency,
            nextPassCount = nextPassCount,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = origin,
            groundingMetadata = originContinuation?.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        if (queued) {
            instrumentation.emit(
                AgentEvents.convergenceContinuationEnqueued(
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs
                )
            )
            return
        }
        instrumentation.emit(
            AgentEvents.warning("Failed to enqueue convergence continuation after plan suppression recovery.")
        )
        telemetry.recordQueueSaturation(
            queueType = "continuation",
            capacity = config.maxPendingContinuations,
            reason = "enqueue_plan_suppression_recovery_continuation_failed_full"
        )
        if (originContinuation?.allowFallbackExplanation == true) {
            fallbackHandler.enqueueFallbackExplanation(originContinuation)
        }
    }

    private fun emitExternalActionRedundancySignal(
        decision: EgoDecision.FormIntention,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
    ) {
        if (!motorCortex.requiresFollowUpThought(decision.actionType)) return
        val scope = inputScope(rootInputId, conversationContext)
        val signature = "${decision.actionType.id}:${fallbackHandler.normalizeActionPayload(decision.payload)}"
        val hitsBySignature = externalActionSignatureHitsByInput.getOrPut(scope) { mutableMapOf() }
        val signatureHits = (hitsBySignature[signature] ?: 0) + 1
        hitsBySignature[signature] = signatureHits

        val evidence = deliberation.evidenceFor(rootInputId, conversationContext.sessionId)
        val hadSuccessfulEvidence = evidence?.hadSuccessfulEvidence == true
        val hadExternalFailures = evidence?.hadExternalFailures == true
        val redundantRisk = hadSuccessfulEvidence && signatureHits > 1
        if (signatureHits < REDUNDANCY_SIGNAL_MIN_HITS && !redundantRisk) return

        instrumentation.emit(
            AgentEvents.externalActionRedundancySignal(
                actionType = decision.actionType.id,
                signatureHits = signatureHits,
                hadSuccessfulEvidence = hadSuccessfulEvidence,
                hadExternalFailures = hadExternalFailures,
                redundantRisk = redundantRisk,
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs
            )
        )
    }

    private fun normalizePlanHash(goal: String, steps: List<String>): String {
        val normalized = (listOf(goal) + steps)
            .joinToString("|") { it.lowercase().replace(Regex("\\s+"), " ").trim() }
        return normalized.hashCode().toString(16)
    }

    private fun plannerContextViolationFor(
        decision: EgoDecision.FormIntention,
        plannerContext: PlannerContext?,
    ): PlannerContextViolation? {
        if (plannerContext == null) return null
        if (plannerContext.opportunityKind == null) return null
        return when {
            decision.intentionKind !in plannerContext.allowedIntentions ->
                PlannerContextViolation(
                    reasonCode = "INTENTION_KIND_NOT_ALLOWED",
                    reason = "Intention '${decision.intentionKind.name.lowercase()}' is not allowed in the current opportunity.",
                )

            decision.actionType !in plannerContext.availableActions ->
                PlannerContextViolation(
                    reasonCode = "ACTION_TYPE_NOT_AVAILABLE",
                    reason = "Action '${decision.actionType.id}' is not available in the current opportunity.",
                )

            decision.actionType !in plannerContext.dispatchableActions ->
                PlannerContextViolation(
                    reasonCode = "ACTION_TYPE_NOT_DISPATCHABLE",
                    reason = "Action '${decision.actionType.id}' is visible but not dispatchable in the current opportunity.",
                )

            decision.commitModePreference !in plannerContext.allowedCommitModes ->
                PlannerContextViolation(
                    reasonCode = "COMMIT_MODE_NOT_ALLOWED",
                    reason = "Commit mode '${decision.commitModePreference.name.lowercase()}' is not allowed in the current opportunity.",
                )

            else -> null
        }
    }

    private fun buildInvalidActionRetryThought(
        decision: EgoDecision.FormIntention,
        plannerContext: PlannerContext?,
        violation: PlannerContextViolation,
    ): String {
        val allowedIntentions = plannerContext?.allowedIntentions
            ?.map { it.name.lowercase() }
            ?.sorted()
            ?.joinToString(", ")
            .orEmpty()
        val dispatchableActions = plannerContext?.dispatchableActions
            ?.map { it.id }
            ?.sorted()
            ?.joinToString(", ")
            .orEmpty()
        return buildString {
            append(violation.reason)
            append(' ')
            append("Pick a different next move that fits the current opportunity.")
            if (allowedIntentions.isNotBlank()) {
                append(" Allowed intentions: ")
                append(allowedIntentions)
                append('.')
            }
            if (dispatchableActions.isNotBlank()) {
                append(" Dispatchable actions: ")
                append(dispatchableActions)
                append('.')
            }
            append(" Do not repeat action '")
            append(decision.actionType.id)
            append("'.")
        }
    }

    private data class PlannerContextViolation(
        val reasonCode: String,
        val reason: String,
    )

    private fun refineInlinePlan(
        decision: EgoDecision.EnqueuePlan,
        plannerContext: PlannerContext?,
    ): EgoDecision.EnqueuePlan {
        if (!config.planner.planRefinementEnabled) return decision

        val candidates = decision.steps.mapIndexed { i, desc ->
            PlanStepCandidate(
                id = "step-${i + 1}",
                description = desc,
            )
        }
        val request = PlanRefinementRequest(
            planKind = PlanKind.INLINE_EGO,
            terminalPolicy = TerminalPolicy.MAY_END_WITH_USER_DELIVERY,
            goal = decision.goal,
            instruction = decision.goal,
            steps = candidates,
            availableActions = buildInlineRefinementActions(plannerContext),
            runtimeFacts = buildInlineRuntimeFacts(plannerContext),
            recentDialogue = plannerContext?.recentDialogue?.map { "${it.role.name.lowercase()}: ${it.content}" }.orEmpty(),
            shortTermContextSummary = plannerContext?.shortTermContextSummary.orEmpty(),
            longTermMemoryRecall = plannerContext?.longTermMemoryRecall.orEmpty(),
            episodicRecall = plannerContext?.episodicRecall.orEmpty(),
            evidenceHints = plannerContext?.evidenceHints.orEmpty(),
        )

        val result = planRefiner.refine(request)
        instrumentation.emit(
            AgentEvent(
                type = "plan_refinement_completed",
                data = mapOf(
                    "plan_kind" to "inline_ego",
                    "refinement_mode" to result.refinementMode.name.lowercase(),
                    "original_step_count" to decision.steps.size,
                    "refined_step_count" to result.steps.size,
                    "dropped_step_count" to result.droppedSteps.size,
                )
            )
        )

        if (result.refinementMode == PlanRefinementMode.UNCHANGED) return decision

        val refinedSteps = result.steps.map { it.description }
        return decision.copy(steps = refinedSteps)
    }

    private fun buildInlineRefinementActions(plannerContext: PlannerContext?): List<ActionSummary> {
        val definitions = plannerContext?.actionDefinitions.orEmpty()
        if (definitions.isNotEmpty()) {
            return definitions
                .map { def ->
                    ActionSummary(
                        actionType = def.actionType.id,
                        description = def.description,
                    )
                }
                .sortedBy { it.actionType }
        }
        return plannerContext?.dispatchableActions
            .orEmpty()
            .map { actionType ->
                ActionSummary(
                    actionType = actionType.id,
                    description = actionType.id,
                )
            }
            .sortedBy { it.actionType }
    }

    private fun buildInlineRuntimeFacts(plannerContext: PlannerContext?): Map<String, String> {
        val now = java.time.ZonedDateTime.now()
        val facts = linkedMapOf(
            "date" to now.toLocalDate().toString(),
            "time" to now.toLocalTime().toString().take(RUNTIME_FACT_TIME_CHARS),
            "timezone" to now.zone.id,
        )
        plannerContext?.opportunityKind?.let { facts["opportunity_kind"] = it.name.lowercase() }
        plannerContext?.conversationContext?.sessionId
            ?.takeIf { it.isNotBlank() }
            ?.let { facts["session_id"] = it }
        return facts
    }

    private companion object {
        const val PLAN_ID_LENGTH: Int = 8
        const val REDUNDANCY_SIGNAL_MIN_HITS: Int = 2
        const val RUNTIME_FACT_TIME_CHARS: Int = 5
    }
}
