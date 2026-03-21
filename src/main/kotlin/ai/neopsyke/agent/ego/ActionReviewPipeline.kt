package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.memory.episodic.EpisodicEventType
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.PhaseTimingCollector

private val logger = KotlinLogging.logger {}

internal class ActionReviewPipeline(
    private val superego: Superego,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val scheduler: AttentionScheduler,
    private val taskVerifier: DecisionVerifier,
    private val taskWorkspaceStore: ScratchpadStore,
    private val taskWorkspaceFinalizer: ScratchpadFinalizer,
    private val deliberation: DeliberationEngine,
    private val memory: MemorySystem,
    private val telemetry: EgoTelemetry,
    private val fallbackHandler: FallbackHandler,
    private val impulseTracker: ImpulseLifecycleTracker,
    private val dialogueFor: (String) -> ArrayDeque<DialogueTurn>,
    private val resolveSessionId: (ConversationContext) -> String,
    private val superegoContext: (String, ActionOrigin) -> SuperegoContext,
    private val cleanupResolvedInputAfterAnswer: (PendingAction) -> Unit,
    private val getId: () -> ai.neopsyke.agent.id.Id?,
    private val actionLifecycleObserver: ActionLifecycleObserver = NoopActionLifecycleObserver,
) {
    suspend fun reviewAndExecute(action: PendingAction) {
        val timing = PhaseTimingCollector("action", action.rootInputId)
        val convCtx = action.conversationContext
        val sessionId = resolveSessionId(convCtx)
        memory.setActiveSession(sessionId, convCtx.interlocutor)
        deliberation.setActiveSession(sessionId)

        timing.startPhase("workspace_final_pass")
        val resolvedAction = applyTaskWorkspaceFinalPass(action, sessionId)
        instrumentation.emit(AgentEvents.actionReviewRequested(resolvedAction))
        if (resolvedAction.isFallbackExplanation) {
            executeFallbackBypass(resolvedAction, sessionId, convCtx, timing)
            return
        }
        timing.startPhase("task_verifier")
        if (!passesDecisionVerifier(resolvedAction, sessionId, convCtx)) {
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }
        timing.startPhase("superego_review")
        if (!passesSuperego(resolvedAction, sessionId, convCtx)) {
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }

        timing.startPhase("action_execute")
        val outcome = executeActionSafely(resolvedAction)
        impulseTracker.recordActionOutcome(resolvedAction, outcome)
        instrumentation.emit(AgentEvents.actionExecuted(resolvedAction, outcome.statusSummary))
        if (resolvedAction.origin.source != OriginSource.ID && outcome.successful) {
            getId()?.onActivity("action_executed", resolvedAction.type.id)
        }
        journalActionExecution(resolvedAction, outcome)
        timing.startPhase("post_execute")
        val observed = deliberation.observedEvidence(resolvedAction, outcome)
        deliberation.recordEvidenceProgress(resolvedAction, outcome, observed)
        deliberation.onActionExecuted(resolvedAction, observed)
        maybeRecordTaskWorkspaceOutcome(resolvedAction, outcome, observed)
        deliberation.recordActionOutcome(resolvedAction, outcome, observed)
        actionLifecycleObserver.onActionExecuted(resolvedAction, outcome, observed)
        if (resolvedAction.type == ActionType.CONTACT_USER) {
            recordAnswerLatency(resolvedAction)
            deliberation.clearEvidenceForInput(resolvedAction.rootInputId, sessionId)
            cleanupResolvedInputAfterAnswer(resolvedAction)
        }
        maybeRecordDialogueTurn(resolvedAction, outcome, sessionId, convCtx)
        maybeRunTerminalAnswerMemoryAssessment(resolvedAction, outcome, sessionId)
        maybeRunLongTermMemoryAssessment(
            trigger = "post_allowed_action",
            force = config.memory.longTermMemoryForceAssessOnAllowedAction,
            latestActionType = resolvedAction.type,
            latestActionOutcome = outcome.plannerSignal,
            sessionId = sessionId
        )

        timing.startPhase("follow_up")
        maybeEnqueueFollowUp(resolvedAction, outcome, observed, convCtx)

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    // ── Fallback bypass path ──

    private suspend fun executeFallbackBypass(
        resolvedAction: PendingAction,
        sessionId: String,
        convCtx: ConversationContext,
        timing: PhaseTimingCollector,
    ) {
        instrumentation.emit(
            AgentEvents.actionReviewResult(
                actionId = resolvedAction.id,
                allow = true,
                reason = "fallback_explanation_bypass",
                reasonCode = "SYSTEM_FALLBACK_BYPASS"
            )
        )
        val outcome = executeActionSafely(resolvedAction)
        impulseTracker.recordActionOutcome(resolvedAction, outcome)
        instrumentation.emit(AgentEvents.actionExecuted(resolvedAction, outcome.statusSummary))
        if (resolvedAction.type == ActionType.CONTACT_USER) {
            memory.journal(
                EpisodicEventType.CONTACT_DELIVERED,
                "Contacted user (fallback): ${TextSecurity.preview(resolvedAction.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                actionType = "contact_user",
            )
            recordAnswerLatency(resolvedAction)
            deliberation.clearEvidenceForInput(resolvedAction.rootInputId, sessionId)
            cleanupResolvedInputAfterAnswer(resolvedAction)
        }
        maybeRecordDialogueTurn(resolvedAction, outcome, sessionId, convCtx)
        val observed = deliberation.observedEvidence(resolvedAction, outcome)
        deliberation.recordEvidenceProgress(resolvedAction, outcome, observed)
        deliberation.onActionExecuted(resolvedAction, observed)
        maybeRecordTaskWorkspaceOutcome(resolvedAction, outcome, observed)
        maybeRunTerminalAnswerMemoryAssessment(resolvedAction, outcome, sessionId)
        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    // ── Review gates ──

    private suspend fun passesDecisionVerifier(
        resolvedAction: PendingAction,
        sessionId: String,
        convCtx: ConversationContext,
    ): Boolean {
        val recentDialogue = dialogueFor(sessionId).takeLast(12)
        val latestUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            .orEmpty()
        val disabledForScope = deliberation.disabledActionTypes(resolvedAction.rootInputId, sessionId)
        val availableActionsForScope = motorCortex.availableActionTypes() - disabledForScope
        val dispatchableActionsForScope = motorCortex.dispatchableActionTypes() - disabledForScope
        val taskVerificationDecision = taskVerifier.review(
            action = resolvedAction,
            context = DecisionVerifierContext(
                recentDialogue = recentDialogue,
                externalEvidence = deliberation.evidenceFor(resolvedAction.rootInputId, sessionId),
                availableActions = availableActionsForScope,
                dispatchableActions = dispatchableActionsForScope,
                evidenceActionTypes = motorCortex.actionTypesWithCapability(ai.neopsyke.agent.actions.ActionCapability.GATHERS_EVIDENCE),
                latestUserTurn = latestUserTurn
            )
        )
        val assessment = taskVerificationDecision.assessment
        instrumentation.emit(
            AgentEvent(
                type = "task_verifier_review",
                data = mapOf(
                    "action_id" to resolvedAction.id,
                    "root_input_id" to resolvedAction.rootInputId,
                    "root_input_received_at_ms" to resolvedAction.rootInputReceivedAtMs,
                    "session_id" to sessionId,
                    "action_type" to resolvedAction.type.id,
                    "allow" to taskVerificationDecision.allow,
                    "reason" to taskVerificationDecision.reason,
                    "reason_code" to taskVerificationDecision.reasonCode,
                    "intent_category" to assessment?.intentCategory?.name?.lowercase(),
                    "volatility_level" to assessment?.volatilityLevel?.name?.lowercase(),
                    "volatility_score" to assessment?.volatilityScore,
                    "requires_external_evidence" to assessment?.requiresExternalEvidence,
                    "evidence_actions_available" to assessment?.evidenceActionsAvailable,
                    "evidence_actions_dispatchable" to assessment?.evidenceActionsDispatchable,
                    "had_successful_evidence" to assessment?.hadSuccessfulEvidence,
                    "had_external_failures" to assessment?.hadExternalFailures,
                    "latest_user_turn_preview" to TextSecurity.preview(latestUserTurn, 140)
                )
            )
        )
        if (!taskVerificationDecision.allow) {
            actionLifecycleObserver.onActionBlocked(
                action = resolvedAction,
                reason = taskVerificationDecision.reason,
                reasonCode = taskVerificationDecision.reasonCode,
                source = "task_verifier"
            )
            instrumentation.emit(
                AgentEvents.actionReviewResult(
                    actionId = resolvedAction.id,
                    allow = false,
                    reason = taskVerificationDecision.reason,
                    reasonCode = taskVerificationDecision.reasonCode
                )
            )
            fallbackHandler.handleDeniedAction(
                action = resolvedAction,
                reason = taskVerificationDecision.reason,
                reasonCode = taskVerificationDecision.reasonCode,
                conversationContext = convCtx,
                sessionId = sessionId,
                source = "task_verifier"
            )
            return false
        }
        return true
    }

    private fun passesSuperego(
        resolvedAction: PendingAction,
        sessionId: String,
        convCtx: ConversationContext,
    ): Boolean {
        val gateDecision = superego.review(resolvedAction, superegoContext(sessionId, resolvedAction.origin))
        instrumentation.emit(
            AgentEvents.actionReviewResult(
                actionId = resolvedAction.id,
                allow = gateDecision.allow,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode
            )
        )
        if (!gateDecision.allow) {
            actionLifecycleObserver.onActionBlocked(
                action = resolvedAction,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                source = "superego"
            )
            fallbackHandler.handleDeniedAction(
                action = resolvedAction,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                conversationContext = convCtx,
                sessionId = sessionId,
                source = "superego"
            )
            return false
        }
        return true
    }

    // ── Execution ──

    private suspend fun executeActionSafely(action: PendingAction): ActionOutcome {
        return try {
            motorCortex.execute(action, config.searchResultCount)
        } catch (ex: Exception) {
            deliberation.markEvidenceFailure(action)
            logger.warn(ex) { "Action execution failed for action_id=${action.id} type=${action.type}." }
            instrumentation.emit(AgentEvents.warning("Action execution failed; action dropped."))
            ActionOutcome(
                statusSummary = "Action execution failed: ${ex.message?.take(120) ?: "unknown error"}",
                executionStatus = ActionExecutionStatus.FAILED,
                observedEvidence = false,
            )
        }
    }

    // ── Post-execute bookkeeping ──

    private fun journalActionExecution(resolvedAction: PendingAction, outcome: ActionOutcome) {
        if (resolvedAction.type == ActionType.CONTACT_USER) {
            memory.journal(
                EpisodicEventType.CONTACT_DELIVERED,
                "Contacted user: ${TextSecurity.preview(resolvedAction.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                actionType = "contact_user",
            )
        } else {
            memory.journal(
                EpisodicEventType.ACTION_EXECUTED,
                "Executed ${resolvedAction.type.name.lowercase()}: ${TextSecurity.preview(outcome.statusSummary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                actionType = resolvedAction.type.name.lowercase(),
            )
        }
    }

    private fun recordAnswerLatency(resolvedAction: PendingAction) {
        val receivedAtMs = resolvedAction.rootInputReceivedAtMs ?: return
        val latencyMs = (System.currentTimeMillis() - receivedAtMs).coerceAtLeast(0L)
        instrumentation.emit(AgentEvents.responseLatencyRecorded(latencyMs = latencyMs, actionId = resolvedAction.id))
    }

    private fun maybeRecordDialogueTurn(
        resolvedAction: PendingAction,
        outcome: ActionOutcome,
        sessionId: String,
        convCtx: ConversationContext,
    ) {
        val assistantOutput = outcome.assistantOutput ?: return
        val assistantTurn = DialogueTurn(
            role = DialogueRole.ASSISTANT,
            content = assistantOutput,
            sessionId = sessionId,
            interlocutor = convCtx.interlocutor,
            timestamp = java.time.Instant.now()
        )
        dialogueFor(sessionId).addLast(assistantTurn)
        memory.remember(assistantTurn)
        trimDialogue(sessionId)
        if (resolvedAction.type == ActionType.CONTACT_USER &&
            resolvedAction.origin.source != OriginSource.ID &&
            outcome.successful
        ) {
            getId()?.onActivity("contact_delivered")
        }

        // Mirror Id-originated turns to default session for context continuity.
        if (resolvedAction.origin.source == OriginSource.ID &&
            sessionId != ConversationContext.DEFAULT_SESSION_ID
        ) {
            val defaultTurn = assistantTurn.copy(sessionId = ConversationContext.DEFAULT_SESSION_ID)
            dialogueFor(ConversationContext.DEFAULT_SESSION_ID).addLast(defaultTurn)
            trimDialogue(ConversationContext.DEFAULT_SESSION_ID)
        }
    }

    private fun maybeRecordTaskWorkspaceOutcome(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {
        if (action.type == ActionType.RESOLUTION_DRAFT) {
            taskWorkspaceStore.recordResolutionDraft(
                rootInputId = action.rootInputId,
                payload = action.payload
            )
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_updated",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "update_type" to "resolution_draft_recorded",
                        "action_type" to action.type.name.lowercase(),
                        "draft_preview" to TextSecurity.preview(action.payload, 140),
                        "active_tasks" to taskWorkspaceStore.activeTaskCount()
                    )
                )
            )
            telemetry.emitTaskWorkspaceTelemetry(
                rootInputId = action.rootInputId,
                rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                updateType = "resolution_draft_recorded"
            )
            return
        }
        if (action.type == ActionType.CONTACT_USER) return
        taskWorkspaceStore.recordActionOutcome(
            rootInputId = action.rootInputId,
            action = action,
            outcome = outcome,
            observedEvidence = observedEvidence
        )
        instrumentation.emit(
            AgentEvent(
                type = "scratchpad_updated",
                data = mapOf(
                    "root_input_id" to action.rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "update_type" to "action_outcome_recorded",
                    "action_type" to action.type.name.lowercase(),
                    "observed_evidence" to observedEvidence,
                    "status_preview" to TextSecurity.preview(outcome.statusSummary, 140),
                    "active_tasks" to taskWorkspaceStore.activeTaskCount()
                )
            )
        )
        telemetry.emitTaskWorkspaceTelemetry(
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "action_outcome_recorded"
        )
    }

    private fun maybeRunTerminalAnswerMemoryAssessment(
        action: PendingAction,
        outcome: ActionOutcome,
        sessionId: String,
    ) {
        if (action.type != ActionType.CONTACT_USER) return
        maybeRunLongTermMemoryAssessment(
            trigger = "post_terminal_answer",
            force = config.memory.longTermMemoryForceAssessOnTerminalAnswer,
            latestActionType = action.type,
            latestActionOutcome = outcome.plannerSignal,
            sessionId = sessionId
        )
    }

    private fun maybeRunLongTermMemoryAssessment(
        trigger: String,
        force: Boolean = false,
        latestActionType: ActionType? = null,
        latestActionOutcome: String? = null,
        sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
    ) {
        memory.maybeAssessLongTermMemory(
            trigger = trigger,
            force = force,
            latestActionType = latestActionType,
            latestActionOutcome = latestActionOutcome,
            deliberation = deliberation.snapshot(),
            recentDialogue = dialogueFor(sessionId).takeLast(12)
        )
    }

    // ── Workspace final pass ──

    private fun applyTaskWorkspaceFinalPass(action: PendingAction, sessionId: String): PendingAction {
        if (action.type != ActionType.CONTACT_USER) {
            return action
        }
        if (!config.memory.taskWorkspace.enabled || !config.memory.taskWorkspace.finalPassRewriteEnabled) {
            return action
        }
        val preFinalSnapshot = taskWorkspaceStore.debugSnapshot(action.rootInputId)
        if (preFinalSnapshot != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_pre_final_dump",
                    data = mapOf(
                        "session_id" to sessionId,
                        "root_input_id" to action.rootInputId,
                        "candidate_answer" to action.payload,
                        "snapshot" to preFinalSnapshot
                    )
                )
            )
        }
        taskWorkspaceStore.recordResolutionDraft(
            rootInputId = action.rootInputId,
            payload = action.payload
        )
        telemetry.emitTaskWorkspaceTelemetry(
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "resolution_draft_recorded"
        )
        val finalPassInput = taskWorkspaceStore.buildFinalPassInput(
            rootInputId = action.rootInputId,
            candidateAnswer = action.payload,
            maxChars = config.memory.taskWorkspace.finalCompilationMaxChars
        ) ?: return action
        val draftThreshold = maxOf(2, config.memory.taskWorkspace.activationMinPlanSteps)
        if (finalPassInput.evidenceCount == 0 && finalPassInput.resolutionDraftCount < draftThreshold) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "action_id" to action.id,
                        "reason" to "no_evidence_or_insufficient_drafts",
                        "resolution_draft_count" to finalPassInput.resolutionDraftCount,
                        "draft_threshold" to draftThreshold,
                    )
                )
            )
            return action
        }
        instrumentation.emit(
            AgentEvent(
                type = "scratchpad_final_pass",
                data = mapOf(
                    "root_input_id" to action.rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "action_id" to action.id,
                    "workspace_confidence" to finalPassInput.workspaceConfidence,
                    "section_count" to finalPassInput.sectionCount,
                    "evidence_count" to finalPassInput.evidenceCount,
                    "resolution_draft_count" to finalPassInput.resolutionDraftCount,
                    "compilation_preview" to TextSecurity.preview(finalPassInput.compilation, 220)
                )
            )
        )
        if (finalPassInput.workspaceConfidence < config.memory.taskWorkspace.finalPassMinWorkspaceConfidence) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "workspace_confidence_gate",
                        "workspace_confidence" to finalPassInput.workspaceConfidence,
                        "min_workspace_confidence" to config.memory.taskWorkspace.finalPassMinWorkspaceConfidence
                    )
                )
            )
            return action
        }
        val finalizerResult = taskWorkspaceFinalizer.finalize(
            ScratchpadFinalizerRequest(
                action = action,
                workspaceCompilation = finalPassInput.compilation,
                workspaceConfidence = finalPassInput.workspaceConfidence,
                recentDialogue = dialogueFor(sessionId).takeLast(12)
            )
        ) ?: run {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "finalizer_unavailable_or_parse"
                    )
                )
            )
            return action
        }
        if (finalizerResult.confidence < config.memory.taskWorkspace.finalPassMinModelConfidence) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "model_confidence_gate",
                        "model_confidence" to finalizerResult.confidence,
                        "min_model_confidence" to config.memory.taskWorkspace.finalPassMinModelConfidence
                    )
                )
            )
            return action
        }
        val rewrittenPayload = TextSecurity.clamp(finalizerResult.rewrittenPayload, config.maxActionPayloadChars)
        if (rewrittenPayload.isBlank() || rewrittenPayload == action.payload) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "rewrite_empty_or_unchanged"
                    )
                )
            )
            return action
        }
        instrumentation.emit(
            AgentEvent(
                type = "scratchpad_final_pass_applied",
                data = mapOf(
                    "root_input_id" to action.rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "action_id" to action.id,
                    "workspace_confidence" to finalPassInput.workspaceConfidence,
                    "model_confidence" to finalizerResult.confidence,
                    "resolution_draft_count" to finalPassInput.resolutionDraftCount,
                    "rewrite_reason" to finalizerResult.reason,
                    "payload_before_preview" to TextSecurity.preview(action.payload, 180),
                    "payload_after_preview" to TextSecurity.preview(rewrittenPayload, 180)
                )
            )
        )
        return action.copy(payload = rewrittenPayload)
    }

    // ── Follow-up ──

    private fun maybeEnqueueFollowUp(
        resolvedAction: PendingAction,
        outcome: ActionOutcome,
        observed: Boolean,
        convCtx: ConversationContext,
    ) {
        if (!resolvedAction.requiresFollowUpThought) return
        if (!actionLifecycleObserver.allowFollowUp(resolvedAction)) return
        val safePlannerSignal = PromptInjectionDefense.asUntrustedDataBlock(
            text = outcome.plannerSignal,
            maxChars = FOLLOW_UP_SIGNAL_MAX_CHARS
        )
        val followUpThought = TextSecurity.clamp(
            "${resolvedAction.followUpPrefix}\n$safePlannerSignal\n" +
                "Produce the next planner decision as one raw JSON object only. " +
                "Do not use tool or function wrappers.",
            config.planner.maxThoughtChars
        )
        val queued = scheduler.enqueueThought(
            content = followUpThought,
            urgency = resolvedAction.urgency,
            passes = resolvedAction.attempts,
            rootInputId = resolvedAction.rootInputId,
            rootInputReceivedAtMs = resolvedAction.rootInputReceivedAtMs,
            allowFallbackExplanation = true,
            originActionType = resolvedAction.type,
            originActionObservedEvidence = observed,
            conversationContext = convCtx,
            origin = resolvedAction.origin,
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue follow-up thought after action."))
            telemetry.recordQueueSaturation(
                queueType = "thought",
                capacity = config.maxPendingThoughts,
                reason = "enqueue_followup_thought_failed_full"
            )
        }
        telemetry.emitQueueSnapshot("follow_up_thought_enqueued")
    }

    // ── Dialogue trim ──

    private fun trimDialogue(sessionId: String) {
        val deque = dialogueFor(sessionId)
        while (deque.size > MAX_DIALOGUE_SIZE) {
            deque.removeFirst()
        }
    }

    private companion object {
        const val FOLLOW_UP_SIGNAL_MAX_CHARS: Int = 420
        const val JOURNAL_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_DIALOGUE_SIZE: Int = 20
    }
}
