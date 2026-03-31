package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.*
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
    private val dialogueFor: (String) -> ArrayDeque<DialogueTurn>,
    private val resolveSessionId: (ConversationContext) -> String,
    private val inputScope: (String?, ConversationContext) -> InputScope,
) {
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

    private fun enqueueDeferredIntention(
        content: String,
        urgency: Urgency,
        nextPassCount: Int,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        origin: ActionOrigin,
        longTermMemoryRecallQuery: String? = null,
        deniedActionType: ActionType? = null,
        deniedActionPayload: String? = null,
        denialReason: String? = null,
        denialReasonCode: String? = null,
        allowFallbackExplanation: Boolean = false,
        planContext: PlanContext? = null,
        originActionType: ActionType? = null,
        originActionObservedEvidence: Boolean? = null,
    ): Boolean =
        scheduler.enqueueIntention(
            QueuedIntention(
                intention = Intention(
                    id = RootInputIds.next(),
                    cognitiveThreadId = rootInputId ?: RootInputIds.next(),
                    kind = IntentionKind.DEFER,
                    summary = TextSecurity.preview(content, 160),
                    createdAt = Instant.now(),
                    conversationContext = conversationContext,
                    commitMode = CommitMode.NOT_APPLICABLE,
                    rootStimulusId = rootInputId,
                ),
                urgency = urgency,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                origin = origin,
                deferredThoughtContent = content,
                deferredThoughtPasses = nextPassCount,
                deferredThoughtRecallQuery = longTermMemoryRecallQuery,
                deferredDeniedActionType = deniedActionType,
                deferredDeniedActionPayload = deniedActionPayload,
                deferredDenialReason = denialReason,
                deferredAllowFallbackExplanation = allowFallbackExplanation,
                deferredPlanContext = planContext,
                deferredDenialReasonCode = denialReasonCode,
                deferredOriginActionType = originActionType,
                deferredOriginActionObservedEvidence = originActionObservedEvidence,
            )
        )

    suspend fun dispatch(
        decision: EgoDecision,
        nextPassCount: Int,
        originThought: PendingThought?,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        origin: ActionOrigin = ActionOrigin.USER,
    ) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                val queued = enqueueDeferredIntention(
                    content = decision.content,
                    urgency = decision.urgency,
                    nextPassCount = nextPassCount,
                    longTermMemoryRecallQuery = decision.longTermMemoryRecallQuery,
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    deniedActionType = originThought?.deniedActionType,
                    deniedActionPayload = originThought?.deniedActionPayload,
                    denialReason = originThought?.denialReason,
                    denialReasonCode = originThought?.denialReasonCode,
                    allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false,
                    originActionType = originThought?.originActionType,
                    originActionObservedEvidence = originThought?.originActionObservedEvidence,
                    conversationContext = conversationContext,
                    origin = origin,
                )
                if (!decision.longTermMemoryRecallQuery.isNullOrBlank()) {
                    instrumentation.emit(
                        AgentEvents.longTermMemoryRecallRequested(
                            trigger = "thought",
                            source = "planner",
                            queryPreview = TextSecurity.preview(decision.longTermMemoryRecallQuery, 180)
                        )
                    )
                }
                instrumentation.emit(
                    AgentEvent(
                        type = "thought_enqueued",
                        data = mapOf(
                            "queued" to queued,
                            "urgency" to decision.urgency.name.lowercase(),
                            "content" to decision.content
                        )
                    )
                )
                if (!queued) {
                    instrumentation.emit(AgentEvents.warning("Failed to enqueue planner thought."))
                    telemetry.recordQueueSaturation(
                        queueType = "thought",
                        capacity = config.maxPendingThoughts,
                        reason = "enqueue_planner_thought_failed_full"
                    )
                }
                telemetry.emitQueueSnapshot("decision_thought")
            }

            is EgoDecision.ProposeAction -> {
                val repeatedDeniedAction = originThought != null && fallbackHandler.isRepeatOfDeniedAction(originThought, decision)
                val technicalDenial = DenialReasonClassifier.isLikelyTechnical(
                    reasonCode = originThought?.denialReasonCode,
                    reason = originThought?.denialReason
                )
                val repeatedVerifierDisagreement =
                    repeatedDeniedAction && shouldAllowRepeatedVerifierDisagreement(originThought, decision)
                if (repeatedDeniedAction && !technicalDenial && !repeatedVerifierDisagreement) {
                    instrumentation.emit(AgentEvents.warning("Planner repeated a denied action; requesting an alternative."))
                    deliberation.onRepeatedDeniedAction()
                    memory.maybeRecordLesson(
                        trigger = "repeated_denied_action",
                        actionType = decision.actionType,
                        reasonCode = originThought.denialReasonCode,
                        reason = originThought.denialReason,
                        deniedPayload = decision.payload,
                        recentDialogue = dialogueFor(resolveSessionId(conversationContext)).takeLast(12),
                        stepIndex = deliberation.snapshot().stepIndex
                    )
                    val retryThought = TextSecurity.clamp(
                        "Previous proposed action repeats a denied action. Pick a materially different safe action.",
                        config.planner.maxThoughtChars
                    )
                    val queuedRetry = enqueueDeferredIntention(
                        content = retryThought,
                        urgency = originThought.urgency,
                        nextPassCount = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        deniedActionType = originThought.deniedActionType,
                        deniedActionPayload = originThought.deniedActionPayload,
                        denialReason = originThought.denialReason,
                        denialReasonCode = originThought.denialReasonCode,
                        allowFallbackExplanation = originThought.allowFallbackExplanation,
                        originActionType = originThought.originActionType,
                        originActionObservedEvidence = originThought.originActionObservedEvidence,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    if (!queuedRetry) {
                        instrumentation.emit(AgentEvents.warning("Failed to enqueue retry thought after repeated denied action."))
                        telemetry.recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_retry_thought_failed_full"
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
                val intentionKind = intentionKindFor(decision.actionType)
                val queued = scheduler.enqueueIntention(
                    QueuedIntention(
                        intention = Intention(
                            id = RootInputIds.next(),
                            cognitiveThreadId = rootInputId ?: RootInputIds.next(),
                            kind = intentionKind,
                            summary = decision.summary,
                            createdAt = Instant.now(),
                            conversationContext = conversationContext,
                            commitMode = CommitMode.NOT_APPLICABLE,
                            rootStimulusId = rootInputId,
                        ),
                        urgency = decision.urgency,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        proposedActionType = decision.actionType,
                        proposedActionPayload = decision.payload,
                        proposedActionSummary = decision.summary,
                        argumentDataTrust = deliberation.threadSecurityContext(rootInputId, conversationContext).aggregatedDataTrust,
                        origin = origin,
                    )
                )
                instrumentation.emit(
                    AgentEvents.actionProposed(
                        actionType = decision.actionType.id,
                        urgency = decision.urgency.name.lowercase(),
                        payload = decision.payload,
                        summary = decision.summary,
                        queued = queued
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
                        originThought = originThought,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    telemetry.emitQueueSnapshot("decision_plan_suppressed_budget")
                    return
                }

                // ── Gate 2: exact plan hash dedup ──
                val planHash = normalizePlanHash(decision.goal, decision.steps)
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
                        decision = decision,
                        nextPassCount = nextPassCount,
                        originThought = originThought,
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
                    goal = decision.goal,
                    steps = decision.steps
                )
                if (scratchpadActivated) {
                    instrumentation.emit(
                        AgentEvent(
                            type = "scratchpad_created",
                            data = mapOf(
                                "root_input_id" to rootInputId,
                                "root_input_received_at_ms" to rootInputReceivedAtMs,
                                "goal_preview" to TextSecurity.preview(decision.goal, 140),
                                "active_tasks" to scratchpadStore.activeTaskCount(),
                                "activation_trigger" to "plan_complexity",
                                "plan_step_count" to decision.steps.size
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
                            "goal_preview" to TextSecurity.preview(decision.goal, 140),
                            "step_count" to decision.steps.size,
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
                        goal = decision.goal,
                        stepCount = decision.steps.size,
                        urgency = decision.urgency.name.lowercase(),
                        steps = decision.steps,
                        rootInputId = rootInputId,
                    )
                )
                var allQueued = true
                decision.steps.forEachIndexed { index, stepDescription ->
                    val stepContent = TextSecurity.clamp(
                        "Plan step ${index + 1}/${decision.steps.size}: $stepDescription",
                        config.planner.maxThoughtChars
                    )
                    val queued = enqueueDeferredIntention(
                        content = stepContent,
                        urgency = decision.urgency,
                        nextPassCount = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        planContext = PlanContext(
                            planId = planId,
                            planGoal = decision.goal,
                            stepIndex = index,
                            totalSteps = decision.steps.size,
                            stepDescription = stepDescription,
                        ),
                        originActionType = originThought?.originActionType,
                        originActionObservedEvidence = originThought?.originActionObservedEvidence,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    if (!queued) {
                        allQueued = false
                        instrumentation.emit(
                            AgentEvents.warning("Failed to enqueue plan step ${index + 1}/${decision.steps.size}.")
                        )
                        telemetry.recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_plan_step_failed_full"
                        )
                    }
                }
                instrumentation.emit(
                    AgentEvents.planStepsEnqueued(
                        planId = planId,
                        totalSteps = decision.steps.size,
                        allQueued = allQueued
                    )
                )
                telemetry.emitQueueSnapshot("decision_plan")
            }

            is EgoDecision.Noop -> {
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
                    val deniedActionType = decision.deniedActionType ?: originThought?.deniedActionType
                    val deniedActionPayload = decision.deniedActionPayload ?: originThought?.deniedActionPayload
                    val denialReason = if (decision.deniedActionType != null) {
                        decision.reason
                    } else {
                        originThought?.denialReason
                    }
                    val denialReasonCode = decision.denialReasonCode ?: originThought?.denialReasonCode
                    val noopThought = TextSecurity.clamp("Noop decision: ${decision.reason}", config.planner.maxThoughtChars)
                    val queued = enqueueDeferredIntention(
                        content = noopThought,
                        urgency = Urgency.LOW,
                        nextPassCount = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        deniedActionType = deniedActionType,
                        deniedActionPayload = deniedActionPayload,
                        denialReason = denialReason,
                        denialReasonCode = denialReasonCode,
                        allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false,
                        originActionType = originThought?.originActionType,
                        originActionObservedEvidence = originThought?.originActionObservedEvidence,
                        conversationContext = conversationContext,
                        origin = origin,
                    )
                    instrumentation.emit(
                        AgentEvent(
                            type = "noop_recorded",
                            data = mapOf("queued_thought" to queued, "reason" to decision.reason)
                        )
                    )
                    if (!queued) {
                        instrumentation.emit(AgentEvents.warning("Failed to enqueue noop thought."))
                        telemetry.recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_noop_thought_failed_full"
                        )
                    }
                    telemetry.emitQueueSnapshot("decision_noop")
                }
            }
        }
    }

    private fun shouldAllowRepeatedVerifierDisagreement(
        originThought: PendingThought?,
        decision: EgoDecision.ProposeAction,
    ): Boolean {
        val thought = originThought ?: return false
        if (thought.denialReasonCode != ACTION_VERIFIER_REJECT_REASON_CODE) return false
        if (thought.deniedActionType != ActionType.CONTACT_USER) return false
        return decision.actionType == ActionType.CONTACT_USER
    }

    private suspend fun recoverFromSuppressedPlan(
        suppressionReason: String,
        decision: EgoDecision.EnqueuePlan,
        nextPassCount: Int,
        originThought: PendingThought?,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        origin: ActionOrigin,
    ) {
        val sessionId = resolveSessionId(conversationContext)
        if (scheduler.hasPendingPlanThoughtsForInput(rootInputId, sessionId)) {
            return
        }
        if (scheduler.hasPendingConvergenceThoughtForInput(rootInputId, sessionId)) {
            return
        }
        val convergenceThought = TextSecurity.clamp(
            "${AttentionScheduler.CONVERGENCE_THOUGHT_PREFIX}" +
                "Plan emission was suppressed ($suppressionReason). " +
                "Converge now: use gathered evidence and produce a final answer, " +
                "or provide a concise fallback explanation if completion is not possible.",
            config.planner.maxThoughtChars
        )
        val queued = enqueueDeferredIntention(
            content = convergenceThought,
            urgency = decision.urgency,
            nextPassCount = nextPassCount,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            allowFallbackExplanation = originThought?.allowFallbackExplanation ?: true,
            originActionType = originThought?.originActionType,
            originActionObservedEvidence = originThought?.originActionObservedEvidence,
            conversationContext = conversationContext,
            origin = origin,
        )
        if (queued) {
            instrumentation.emit(
                AgentEvents.convergenceThoughtEnqueued(
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs
                )
            )
            return
        }
        instrumentation.emit(
            AgentEvents.warning("Failed to enqueue convergence thought after plan suppression recovery.")
        )
        telemetry.recordQueueSaturation(
            queueType = "thought",
            capacity = config.maxPendingThoughts,
            reason = "enqueue_plan_suppression_recovery_thought_failed_full"
        )
        if (originThought?.allowFallbackExplanation == true) {
            fallbackHandler.enqueueFallbackExplanation(originThought)
        }
    }

    private fun emitExternalActionRedundancySignal(
        decision: EgoDecision.ProposeAction,
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

    private fun intentionKindFor(actionType: ActionType): IntentionKind {
        val effectClass = motorCortex.actionRegistry().contract(actionType)?.effectClass
        return if (effectClass == ActionEffectClass.OBSERVE) {
            IntentionKind.OBSERVE
        } else {
            IntentionKind.PREPARE
        }
    }

    private companion object {
        const val PLAN_ID_LENGTH: Int = 8
        const val REDUNDANCY_SIGNAL_MIN_HITS: Int = 2
    }
}
