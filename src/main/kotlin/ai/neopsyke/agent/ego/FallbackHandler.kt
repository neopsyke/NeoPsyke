package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.memory.longterm.MemoryEventType
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation

private val logger = KotlinLogging.logger {}

internal class FallbackHandler(
    private val scheduler: AttentionScheduler,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val deliberation: DeliberationEngine,
    private val memory: MemorySystem,
    private val telemetry: EgoTelemetry,
    private val dialogueFor: (String) -> ArrayDeque<DialogueTurn>,
    private val resolveSessionId: (ConversationContext) -> String,
    private val processActionFallback: suspend (PendingAction) -> Unit,
) {
    private fun enqueueContinuation(
        continuation: Continuation,
        urgency: Urgency,
        passes: Int,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
        origin: ActionOrigin,
        groundingMetadata: GroundingMetadata,
    ): Boolean =
        scheduler.enqueueContinuation(
            continuation = continuation,
            urgency = urgency,
            passes = passes,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = origin,
            groundingMetadata = groundingMetadata,
        ) != null

    fun handleDeniedAction(
        action: PendingAction,
        reason: String,
        reasonCode: String?,
        conversationContext: ConversationContext,
        sessionId: String,
        source: String,
    ) {
        deliberation.onActionDenied()
        instrumentation.emit(AgentEvents.actionDenied(action, reason, reasonCode))
        memory.journal(
            MemoryEventType.ACTION_DENIED,
            "Denied ${action.type.name.lowercase()} by $source: $reason",
            actionType = action.type.name.lowercase(),
            metadata = mapOf(
                "source" to source,
                "reason_code" to reasonCode
            )
        )
        memory.maybeRecordLesson(
            trigger = "action_denied_$source",
            actionType = action.type,
            reasonCode = reasonCode,
            reason = reason,
            deniedPayload = action.payload,
            recentDialogue = dialogueFor(sessionId).takeLast(12),
            stepIndex = deliberation.snapshot().stepIndex
        )

        val denialContinuation = Continuation.RetryAlternative(
            content = TextSecurity.clamp(
            buildDenialThought(source, reason, reasonCode),
            config.planner.maxThoughtChars
            ),
            deniedActionType = action.type,
            deniedActionPayload = TextSecurity.clamp(action.payload, 240),
            denialReason = reason,
            denialReasonCode = reasonCode,
            allowFallbackExplanation = action.origin.source != OriginSource.ID,
            originActionType = action.type,
        )
        val queued = enqueueContinuation(
            continuation = denialContinuation,
            urgency = action.urgency,
            passes = action.attempts + 1,
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = action.origin,
            groundingMetadata = action.groundingMetadata,
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue denial continuation."))
            telemetry.recordQueueSaturation(
                queueType = "continuation",
                capacity = config.maxPendingContinuations,
                reason = "enqueue_denial_continuation_failed_full"
            )
        } else {
            action.groundingMetadata?.let { metadata ->
                instrumentation.emit(
                    AgentEvents.groundingMetadataPropagated(
                        rootInputId = action.rootInputId,
                        fromEnvelopeType = "pending_action",
                        toEnvelopeType = "queued_continuation",
                        groundingRequired = metadata.requirement == GroundingRequirement.REQUIRED,
                        source = metadata.source.name.lowercase(),
                    )
                )
            }
        }
        telemetry.emitQueueSnapshot("action_denied")
    }

    fun handleStagedAction(
        action: PendingAction,
        stagedAction: StagedAction,
        reason: String,
        reasonCode: String?,
        conversationContext: ConversationContext,
        source: String,
    ) {
        instrumentation.emit(
            AgentEvents.warning(
                "Action '${action.type.id}' was staged by $source and now requires authorization or a safer alternative."
            )
        )
        val stagedContinuation = Continuation.RetryAlternative(
            content = TextSecurity.clamp(
                "Action was staged by $source (${reason.ifBlank { "authorization required" }}). " +
                    "Choose the next best step: request approval, pick a lower-risk alternative, or explain the constraint to the interlocutor.",
                config.planner.maxThoughtChars
            ),
            deniedActionType = action.type,
            deniedActionPayload = TextSecurity.clamp(action.payload, 240),
            denialReason = "staged:${stagedAction.id}:$reason",
            denialReasonCode = reasonCode ?: "ACTION_STAGED_REQUIRES_AUTH",
            allowFallbackExplanation = action.origin.source != OriginSource.ID,
            originActionType = action.type,
        )
        val queued = enqueueContinuation(
            continuation = stagedContinuation,
            urgency = action.urgency,
            passes = action.attempts + 1,
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = action.origin,
            groundingMetadata = action.groundingMetadata,
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue staged-action follow-up continuation."))
            telemetry.recordQueueSaturation(
                queueType = "continuation",
                capacity = config.maxPendingContinuations,
                reason = "enqueue_staged_action_continuation_failed_full"
            )
        } else {
            action.groundingMetadata?.let { metadata ->
                instrumentation.emit(
                    AgentEvents.groundingMetadataPropagated(
                        rootInputId = action.rootInputId,
                        fromEnvelopeType = "pending_action",
                        toEnvelopeType = "queued_continuation",
                        groundingRequired = metadata.requirement == GroundingRequirement.REQUIRED,
                        source = metadata.source.name.lowercase(),
                    )
                )
            }
        }
        telemetry.emitQueueSnapshot("action_staged")
    }

    suspend fun enqueueFallbackExplanation(continuation: QueuedContinuation) {
        val sessionId = resolveSessionId(continuation.conversationContext)
        if (scheduler.hasPendingFallbackExplanationAction(continuation.rootInputId, sessionId)) {
            instrumentation.emit(
                AgentEvents.warning("Fallback explanation already queued for this input; skipping duplicate enqueue.")
            )
            return
        }
        val evidence = deliberation.evidenceFor(continuation.rootInputId, sessionId)
        val parseFailureLikely = continuation.content.contains("non-parseable", ignoreCase = true)
        val (payload, summary) = when {
            !continuation.denialReason.isNullOrBlank() -> {
                val message = "I cannot complete the previous action because it was blocked by policy " +
                    "(${continuation.denialReason ?: "no reason provided"}). " +
                    "I could not find a safe alternative."
                message to "Explain inability to comply after policy denial."
            }
            evidence?.hadSuccessfulEvidence == true -> {
                val signals = evidence.successfulEvidenceSignals
                val aggregatedEvidence = if (signals.isNotEmpty()) {
                    signals.joinToString(" | ")
                } else {
                    evidence.latestPlannerSignal.ifBlank {
                        "I gathered external evidence, but final synthesis failed."
                    }
                }
                val message = "I completed external verification, but repeated internal planner formatting/parsing failures " +
                    "prevented a clean final synthesis. Best-effort result from gathered evidence: $aggregatedEvidence"
                message to "Provide best-effort answer using gathered evidence after planner parse failures."
            }
            evidence?.hadExternalFailures == true -> {
                val message = "I could not complete external verification after multiple attempts due to transient tool/provider " +
                    "failures (for example, timeouts). I can still provide a best-effort answer based on currently available " +
                    "context, but it may be stale."
                message to "Explain inability to verify externally after repeated tool failures."
            }
            parseFailureLikely -> {
                val message = "I encountered repeated internal parsing/formatting failures while preparing the final response. " +
                    "I can still provide a concise best-effort answer, but confidence is reduced."
                message to "Explain inability to finalize due to internal parse failures."
            }
            else -> {
                val message = "I could not complete this request reliably after multiple attempts. " +
                    "I can still provide a concise best-effort answer from available context."
                message to "Explain inability to complete reliably after repeated failures."
            }
        }
        val queued = scheduler.enqueueAction(
            type = ActionType.CONTACT_USER,
            payload = TextSecurity.clamp(payload, config.maxActionPayloadChars),
            summary = summary,
            urgency = continuation.urgency,
            attempts = continuation.passes,
            isFallbackExplanation = true,
            rootInputId = continuation.rootInputId,
            rootInputReceivedAtMs = continuation.rootInputReceivedAtMs,
            conversationContext = continuation.conversationContext,
            origin = continuation.origin,
            groundingMetadata = continuation.groundingMetadata,
        )
        if (!queued) {
            logger.warn { "Fallback explanation enqueue failed; executing immediate fallback action." }
            instrumentation.emit(AgentEvents.warning("Failed to enqueue fallback explanation action. Executing immediately."))
            telemetry.recordQueueSaturation(
                queueType = "action",
                capacity = config.maxPendingActions,
                reason = "enqueue_fallback_action_failed_full"
            )
            processActionFallback(
                PendingAction(
                    id = -1,
                    urgency = continuation.urgency,
                    type = ActionType.CONTACT_USER,
                    payload = payload,
                    summary = summary,
                    attempts = continuation.passes,
                    isFallbackExplanation = true,
                    rootInputId = continuation.rootInputId,
                    rootInputReceivedAtMs = continuation.rootInputReceivedAtMs,
                    conversationContext = continuation.conversationContext,
                    origin = continuation.origin,
                    groundingMetadata = continuation.groundingMetadata,
                )
            )
            telemetry.emitQueueSnapshot("fallback_explanation_executed_immediate")
            return
        }
        telemetry.emitQueueSnapshot("fallback_explanation_enqueued")
    }

    fun enqueueFallbackExplanation(
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        reason: String,
        conversationContext: ConversationContext,
        origin: ActionOrigin = ActionOrigin.USER,
    ) {
        val sessionId = resolveSessionId(conversationContext)
        if (scheduler.hasPendingFallbackExplanationAction(rootInputId, sessionId)) {
            return
        }
        val payload = "I encountered repeated internal parsing/formatting failures while preparing the response. " +
            "Reason: $reason"
        val summary = "Fallback answer after planner circuit breaker trip."
        val queued = scheduler.enqueueAction(
            type = ActionType.CONTACT_USER,
            payload = TextSecurity.clamp(payload, config.maxActionPayloadChars),
            summary = summary,
            urgency = Urgency.HIGH,
            isFallbackExplanation = true,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = origin,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue circuit-breaker fallback."))
        }
        telemetry.emitQueueSnapshot("fallback_explanation_circuit_breaker")
    }

    private fun buildDenialThought(source: String, reason: String, reasonCode: String?): String =
        when (reasonCode) {
            GroundingGate.REASON_CODE_GROUNDING_EVIDENCE_REQUIRED ->
                "Grounding gate: this request requires external evidence, but no successful " +
                    "evidence-gathering action has run yet. Dispatch an evidence-gathering action " +
                    "and use its results before answering. Do not repeat the denied contact_user."
            GroundingGate.REASON_CODE_TECH_GROUNDING_EVIDENCE_FAILURE ->
                "Grounding gate: this request requires external evidence and prior evidence " +
                    "attempts failed for technical/transient reasons. Retry evidence gathering with " +
                    "the same or an alternate evidence source before answering. A repeated evidence " +
                    "attempt is allowed here."
            else ->
                "Action denied by $source ($reason). " +
                    "Try a different safe action than the denied one. " +
                    "If no safe alternative exists, prepare a concise explanation for the interlocutor."
        }

    fun isRepeatOfDeniedAction(continuation: QueuedContinuation, decision: EgoDecision.FormIntention): Boolean {
        val deniedType = continuation.deniedActionType ?: return false
        val deniedPayload = continuation.deniedActionPayload ?: return false
        if (decision.actionType != deniedType) return false
        return normalizeActionPayload(decision.payload) == normalizeActionPayload(deniedPayload)
    }

    fun normalizeActionPayload(payload: String): String =
        payload.lowercase().replace(Regex("\\s+"), " ").trim()
}
