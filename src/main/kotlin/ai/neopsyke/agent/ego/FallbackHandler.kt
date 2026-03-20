package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.memory.episodic.EpisodicEventType
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation

private val logger = KotlinLogging.logger {}

internal class FallbackHandler(
    private val scheduler: AttentionScheduler,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val deliberation: DeliberationEngine,
    private val memory: MemoryCoordinator,
    private val telemetry: EgoTelemetry,
    private val dialogueFor: (String) -> ArrayDeque<DialogueTurn>,
    private val resolveSessionId: (ConversationContext) -> String,
    private val processActionFallback: suspend (PendingAction) -> Unit,
) {
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
            EpisodicEventType.ACTION_DENIED,
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

        // Id-origin denials are silently absorbed: the lesson is recorded above,
        // and the Id's own backoff mechanism handles future impulses.
        if (action.origin?.source == OriginSource.ID) {
            telemetry.emitQueueSnapshot("action_denied_id_silent")
            return
        }

        val denialThought = TextSecurity.clamp(
            "Action denied by $source ($reason). " +
                "Try a different safe action than the denied one. " +
                "If no safe alternative exists, prepare a concise explanation for the interlocutor.",
            config.planner.maxThoughtChars
        )
        val queued = scheduler.enqueueThought(
            content = denialThought,
            urgency = action.urgency,
            passes = action.attempts + 1,
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            deniedActionType = action.type,
            deniedActionPayload = TextSecurity.clamp(action.payload, 240),
            denialReason = reason,
            denialReasonCode = reasonCode,
            allowFallbackExplanation = true,
            conversationContext = conversationContext,
            origin = action.origin,
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue denial thought."))
            telemetry.recordQueueSaturation(
                queueType = "thought",
                capacity = config.maxPendingThoughts,
                reason = "enqueue_denial_thought_failed_full"
            )
        }
        telemetry.emitQueueSnapshot("action_denied")
    }

    suspend fun enqueueFallbackExplanation(thought: PendingThought) {
        val sessionId = resolveSessionId(thought.conversationContext)
        if (scheduler.hasPendingFallbackExplanationAction(thought.rootInputId, sessionId)) {
            instrumentation.emit(
                AgentEvents.warning("Fallback explanation already queued for this input; skipping duplicate enqueue.")
            )
            return
        }
        val evidence = deliberation.evidenceFor(thought.rootInputId, sessionId)
        val parseFailureLikely = thought.content.contains("non-parseable", ignoreCase = true)
        val (payload, summary) = when {
            !thought.denialReason.isNullOrBlank() -> {
                val message = "I cannot complete the previous action because it was blocked by policy " +
                    "(${thought.denialReason ?: "no reason provided"}). " +
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
            urgency = thought.urgency,
            attempts = thought.passes,
            isFallbackExplanation = true,
            rootInputId = thought.rootInputId,
            rootInputReceivedAtMs = thought.rootInputReceivedAtMs,
            conversationContext = thought.conversationContext,
            origin = thought.origin,
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
                    urgency = thought.urgency,
                    type = ActionType.CONTACT_USER,
                    payload = payload,
                    summary = summary,
                    attempts = thought.passes,
                    isFallbackExplanation = true,
                    rootInputId = thought.rootInputId,
                    rootInputReceivedAtMs = thought.rootInputReceivedAtMs,
                    conversationContext = thought.conversationContext,
                    origin = thought.origin,
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
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue circuit-breaker fallback."))
        }
        telemetry.emitQueueSnapshot("fallback_explanation_circuit_breaker")
    }

    fun isRepeatOfDeniedAction(thought: PendingThought, decision: EgoDecision.ProposeAction): Boolean {
        val deniedType = thought.deniedActionType ?: return false
        val deniedPayload = thought.deniedActionPayload ?: return false
        if (decision.actionType != deniedType) return false
        return normalizeActionPayload(decision.payload) == normalizeActionPayload(deniedPayload)
    }

    fun normalizeActionPayload(payload: String): String =
        payload.lowercase().replace(Regex("\\s+"), " ").trim()
}
