package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.LegacyCompatibleActionControlService
import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.id.evaluateSatisfaction
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.memory.longterm.MemoryEventType
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.PhaseTimingCollector
import java.time.Instant

private val logger = KotlinLogging.logger {}

internal class ActionReviewPipeline(
    private val superego: Superego,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val scheduler: AttentionScheduler,
    private val taskVerifier: DecisionVerifier,
    private val scratchpadStore: ScratchpadStore,
    private val scratchpadFinalizer: ScratchpadFinalizer,
    private val deliberation: DeliberationEngine,
    private val memory: MemorySystem,
    private val telemetry: EgoTelemetry,
    private val fallbackHandler: FallbackHandler,
    private val impulseTracker: ImpulseLifecycleTracker,
    private val dialogueFor: (String) -> ArrayDeque<DialogueTurn>,
    private val resolveSessionId: (ConversationContext) -> String,
    private val superegoContext: (String, String?, ActionOrigin, ConversationContext) -> SuperegoContext,
    private val cleanupResolvedInputAfterAnswer: (PendingAction) -> Unit,
    private val cleanupSatisfiedIdImpulse: (PendingAction) -> Unit,
    private val getId: () -> ai.neopsyke.agent.id.Id?,
    private val recordThreadIntention: (String?, ConversationContext, String?, IntentionKind, String, CommitMode, Map<String, String>) -> Unit =
        { _, _, _, _, _, _, _ -> },
    private val recordThreadBlocked: (String?, ConversationContext, String?, String?) -> Unit = { _, _, _, _ -> },
    private val recordThreadDenied: (String?, ConversationContext, String?, String?) -> Unit = { _, _, _, _ -> },
    private val resolveTerminalControlPlaneDenial: (String?, ConversationContext, String?, String?) -> Unit =
        { _, _, _, _ -> },
    private val recordThreadWaiting: (String?, ConversationContext, String?, String?) -> Unit = { _, _, _, _ -> },
    private val emitThreadUpdate: (String?, ConversationContext, String) -> Unit = { _, _, _ -> },
    private val onApprovalStaged: suspend (PendingAction, StagedAction, String, String?, ConversationContext) -> Unit =
        { _, _, _, _, _ -> },
    private val actionControlService: ActionControlService = LegacyCompatibleActionControlService { action, authorization ->
        motorCortex.execute(action, config.searchResultCount, authorization)
    },
    private val actionLifecycleObserver: ActionLifecycleObserver = NoopActionLifecycleObserver,
    private val emitActionFeedback: (ActionFeedbackCue) -> Boolean = { false },
) {
    suspend fun reviewAndExecute(action: PendingAction) {
        val timing = PhaseTimingCollector("action", action.rootInputId)
        val convCtx = action.conversationContext
        val sessionId = resolveSessionId(convCtx)
        memory.setActiveSession(sessionId, convCtx.interlocutor, convCtx.security)
        deliberation.setActiveSession(sessionId)

        timing.startPhase("scratchpad_final_pass")
        val resolvedAction = applyScratchpadFinalPass(action, sessionId)
        instrumentation.emit(AgentEvents.actionReviewRequested(resolvedAction))
        emitIntentionTransition(
            action = resolvedAction,
            stage = "review_requested",
            kind = resolvedAction.intentionKind,
            commitMode = resolvedAction.requestedCommitMode,
        )
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
        val authorizationDecision = reviewSuperego(resolvedAction, sessionId, convCtx) ?: run {
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }

        timing.startPhase("action_control")
        when (
            val controlResult = actionControlService.handleAuthorizationDecision(
                action = resolvedAction,
                decision = authorizationDecision,
                conversationContext = convCtx,
            )
        ) {
            is ActionControlDecisionResult.Refused -> {
                recordThreadDenied(
                    resolvedAction.rootInputId,
                    convCtx,
                    controlResult.reason,
                    controlResult.reasonCode,
                )
                emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_refused")
                actionControlService.recordLedgerEntry(
                    action = resolvedAction,
                    conversationContext = convCtx,
                    kind = ActionLedgerKind.REFUSED,
                    importance = ActionRecordImportance.SIGNAL,
                    summary = controlResult.reason,
                    reasonCode = controlResult.reasonCode,
                    source = "action_control",
                )
                actionLifecycleObserver.onActionBlocked(
                    action = resolvedAction,
                    reason = controlResult.reason,
                    reasonCode = controlResult.reasonCode,
                    source = "action_control"
                )
                fallbackHandler.handleDeniedAction(
                    action = resolvedAction,
                    reason = controlResult.reason,
                    reasonCode = controlResult.reasonCode,
                    conversationContext = convCtx,
                    sessionId = sessionId,
                    source = "action_control"
                )
                instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
                return
            }

            is ActionControlDecisionResult.Staged -> {
                instrumentation.emit(
                    AgentEvent(
                        type = "action_staged",
                        data = mapOf(
                            "action_id" to resolvedAction.id,
                            "staged_action_id" to controlResult.stagedAction.id,
                            "action_type" to resolvedAction.type.id,
                            "commit_mode" to controlResult.authorizationDecision.commitMode.name.lowercase(),
                            "reason" to controlResult.authorizationDecision.reason,
                            "reason_code" to controlResult.authorizationDecision.reasonCode,
                        )
                    )
                )
                emitIntentionTransition(
                    action = resolvedAction,
                    stage = "staged",
                    kind = IntentionKind.STAGE,
                    commitMode = controlResult.authorizationDecision.commitMode,
                    extras = mapOf("staged_action_id" to controlResult.stagedAction.id)
                )
                if (controlResult.stagedAction.commitMode == CommitMode.POLICY_AUTONOMOUS) {
                    recordThreadWaiting(
                        resolvedAction.rootInputId,
                        convCtx,
                        controlResult.authorizationDecision.reason,
                        controlResult.stagedAction.id,
                    )
                    emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_staged_waiting")
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Action '${resolvedAction.type.id}' was queued for autonomous staged execution."
                        )
                    )
                    instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
                    return
                }
                emitIntentionTransition(
                    action = resolvedAction,
                    stage = "request_authorization",
                    kind = IntentionKind.REQUEST_AUTHORIZATION,
                    commitMode = controlResult.authorizationDecision.commitMode,
                    extras = mapOf("staged_action_id" to controlResult.stagedAction.id)
                )
                recordThreadBlocked(
                    resolvedAction.rootInputId,
                    convCtx,
                    controlResult.authorizationDecision.reason,
                    controlResult.authorizationDecision.reasonCode,
                )
                emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_staged_blocked")
                onApprovalStaged(
                    resolvedAction,
                    controlResult.stagedAction,
                    controlResult.authorizationDecision.reason,
                    controlResult.authorizationDecision.reasonCode,
                    convCtx,
                )
                instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
                return
            }

            is ActionControlDecisionResult.Cancelled -> {
                recordThreadDenied(
                    resolvedAction.rootInputId,
                    convCtx,
                    controlResult.ledgerEntry.summary,
                    controlResult.ledgerEntry.reasonCode,
                )
                emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_cancelled")
                actionLifecycleObserver.onActionBlocked(
                    action = resolvedAction,
                    reason = controlResult.ledgerEntry.summary,
                    reasonCode = controlResult.ledgerEntry.reasonCode,
                    source = "action_control_cancelled"
                )
                fallbackHandler.handleDeniedAction(
                    action = resolvedAction,
                    reason = controlResult.ledgerEntry.summary,
                    reasonCode = controlResult.ledgerEntry.reasonCode,
                    conversationContext = convCtx,
                    sessionId = sessionId,
                    source = "action_control_cancelled"
                )
                instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
                return
            }

            is ActionControlDecisionResult.Executed -> {
                timing.startPhase("action_execute")
                processExecutedControlResult(controlResult, timing)
                return
            }
        }
    }

    suspend fun processAutonomousStagedActions(limit: Int): Int {
        val executed = actionControlService.processAutonomousStagedActions(limit)
        executed.forEach { result ->
            val timing = PhaseTimingCollector("action_worker", result.stagedAction.rootInputId)
            timing.startPhase("worker_execute")
            processExecutedControlResult(result, timing)
        }
        return executed.size
    }

    fun processExternalApprovalExecuted(controlResult: ActionControlDecisionResult.Executed) {
        val timing = PhaseTimingCollector("external_action_control", controlResult.stagedAction.rootInputId)
        timing.startPhase("external_action_execute")
        processExecutedControlResult(controlResult, timing)
    }

    fun processExternalApprovalDenied(controlResult: ActionControlDecisionResult.Cancelled) {
        val action = controlResult.stagedAction.toPipelinePendingAction()
        val convCtx = action.conversationContext
        val sessionId = resolveSessionId(convCtx)
        val reasonCode = controlResult.ledgerEntry.reasonCode
        recordThreadDenied(
            action.rootInputId,
            convCtx,
            controlResult.ledgerEntry.summary,
            reasonCode,
        )
        emitThreadUpdate(action.rootInputId, convCtx, "external_action_cancelled")
        actionLifecycleObserver.onActionBlocked(
            action = action,
            reason = controlResult.ledgerEntry.summary,
            reasonCode = reasonCode,
            source = "external_action_control_cancelled"
        )
        if (reasonCode in CONTROL_PLANE_TERMINAL_DENIAL_CODES) {
            resolveTerminalControlPlaneDenial(
                action.rootInputId,
                convCtx,
                controlResult.ledgerEntry.summary,
                reasonCode,
            )
            emitThreadUpdate(action.rootInputId, convCtx, "external_action_terminal_denied")
            return
        }
        fallbackHandler.handleDeniedAction(
            action = action,
            reason = controlResult.ledgerEntry.summary,
            reasonCode = reasonCode,
            conversationContext = convCtx,
            sessionId = sessionId,
            source = "external_action_control_cancelled"
        )
    }

    private fun processExecutedControlResult(
        controlResult: ActionControlDecisionResult.Executed,
        timing: PhaseTimingCollector,
    ) {
        val resolvedAction = controlResult.executedAction
        val convCtx = resolvedAction.conversationContext
        val sessionId = resolveSessionId(convCtx)
        val outcome = controlResult.outcome
        emitIntentionTransition(
            action = resolvedAction,
            stage = "commit",
            kind = if (resolvedAction.intentionKind == IntentionKind.OBSERVE) {
                IntentionKind.OBSERVE
            } else {
                IntentionKind.COMMIT
            },
            commitMode = controlResult.authorization.commitMode,
            extras = mapOf(
                "staged_action_id" to controlResult.stagedAction.id,
                "authorization_id" to controlResult.authorization.id,
            )
        )
        emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_commit")
        processExecutedAction(
            resolvedAction = resolvedAction,
            outcome = outcome,
            sessionId = sessionId,
            convCtx = convCtx,
            timing = timing
        )
    }

    private fun processExecutedAction(
        resolvedAction: PendingAction,
        outcome: ActionOutcome,
        sessionId: String,
        convCtx: ConversationContext,
        timing: PhaseTimingCollector,
    ) {
        impulseTracker.recordActionOutcome(resolvedAction, outcome)
        instrumentation.emit(AgentEvents.actionExecuted(resolvedAction, outcome.statusSummary))
        if (resolvedAction.origin.source != OriginSource.ID && outcome.successful) {
            getId()?.onActivity("action_executed", resolvedAction.type.id)
        }
        journalActionExecution(resolvedAction, outcome)
        deliberation.recordActionArtifacts(resolvedAction, outcome)
        timing.startPhase("post_execute")
        val observed = outcome.observedEvidence ?: deliberation.observedEvidence(resolvedAction, outcome)
        maybeRecordScratchpadOutcome(resolvedAction, outcome, observed)
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
        if (shouldCleanupSatisfiedIdImpulse(resolvedAction, outcome)) {
            cleanupSatisfiedIdImpulse(resolvedAction)
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }

        timing.startPhase("follow_up")
        maybeEmitActionFeedback(resolvedAction, outcome, observed, convCtx)

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
        actionControlService.recordBypassExecution(
            action = resolvedAction,
            conversationContext = convCtx,
            outcome = outcome,
            reason = "Fallback explanation bypass executed directly.",
            reasonCode = "SYSTEM_FALLBACK_BYPASS",
        )
        impulseTracker.recordActionOutcome(resolvedAction, outcome)
        instrumentation.emit(AgentEvents.actionExecuted(resolvedAction, outcome.statusSummary))
        if (resolvedAction.type == ActionType.CONTACT_USER) {
            memory.journal(
                MemoryEventType.CONTACT_DELIVERED,
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
        maybeRecordScratchpadOutcome(resolvedAction, outcome, observed)
        maybeRunTerminalAnswerMemoryAssessment(resolvedAction, outcome, sessionId)
        if (shouldCleanupSatisfiedIdImpulse(resolvedAction, outcome)) {
            cleanupSatisfiedIdImpulse(resolvedAction)
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }
        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private fun shouldCleanupSatisfiedIdImpulse(
        action: PendingAction,
        outcome: ActionOutcome,
    ): Boolean {
        if (!outcome.successful) return false
        if (action.type == ActionType.CONTACT_USER) return false
        if (action.origin.source != OriginSource.ID) return false
        val needId = action.origin.needId?.trim().orEmpty()
        if (needId.isBlank()) return false
        val needConfig = getId()?.needConfig(needId) ?: return false
        return needConfig.evaluateSatisfaction(outcome.effects).satisfied
    }

    // ── Review gates ──

    private suspend fun passesDecisionVerifier(
        resolvedAction: PendingAction,
        sessionId: String,
        convCtx: ConversationContext,
    ): Boolean {
        val disabledForScope = deliberation.disabledActionTypes(resolvedAction.rootInputId, sessionId)
        val availableActionsForScope = motorCortex.availableActionTypes() - disabledForScope
        val gateDecision = taskVerifier.review(
            action = resolvedAction,
            context = DecisionVerifierContext(
                externalEvidence = deliberation.evidenceFor(resolvedAction.rootInputId, sessionId),
                availableActions = availableActionsForScope,
                evidenceActionTypes = motorCortex.actionTypesWithCapability(ActionCapability.GATHERS_EVIDENCE),
                groundingTechnicalFailureBudgetExceeded = deliberation.isGroundingTechnicalFailureBudgetExceeded(
                    resolvedAction.rootInputId,
                    sessionId,
                ),
            )
        )
        instrumentation.emit(
            AgentEvent(
                type = "grounding_gate_review",
                data = mapOf(
                    "action_id" to resolvedAction.id,
                    "root_input_id" to resolvedAction.rootInputId,
                    "session_id" to sessionId,
                    "action_type" to resolvedAction.type.id,
                    "allow" to gateDecision.allow,
                    "grounding_required" to gateDecision.groundingRequired,
                    "evidence_gathered" to gateDecision.evidenceGathered,
                    "evidence_failed_technically" to gateDecision.evidenceFailedTechnically,
                    "evidence_unavailable" to gateDecision.evidenceUnavailable,
                    "forced_terminal" to gateDecision.forcedTerminal,
                    "reason_code" to gateDecision.reasonCode,
                )
            )
        )
        if (!gateDecision.allow) {
            recordThreadDenied(
                resolvedAction.rootInputId,
                convCtx,
                gateDecision.reason,
                gateDecision.reasonCode,
            )
            emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_denied_grounding_gate")
            actionControlService.recordLedgerEntry(
                action = resolvedAction,
                conversationContext = convCtx,
                kind = ActionLedgerKind.DENIED,
                importance = ActionRecordImportance.SIGNAL,
                summary = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                source = "grounding_gate",
            )
            actionLifecycleObserver.onActionBlocked(
                action = resolvedAction,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                source = "grounding_gate"
            )
            instrumentation.emit(
                AgentEvents.actionReviewResult(
                    actionId = resolvedAction.id,
                    allow = false,
                    reason = gateDecision.reason,
                    reasonCode = gateDecision.reasonCode
                )
            )
            fallbackHandler.handleDeniedAction(
                action = resolvedAction,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                conversationContext = convCtx,
                sessionId = sessionId,
                source = "grounding_gate"
            )
            return false
        }
        return true
    }

    private fun reviewSuperego(
        resolvedAction: PendingAction,
        sessionId: String,
        convCtx: ConversationContext,
    ): AuthorizationDecision? {
        val authorizationDecision = superego.reviewAuthorization(
            resolvedAction,
            superegoContext(sessionId, resolvedAction.rootInputId, resolvedAction.origin, convCtx)
        )
        instrumentation.emit(
            AgentEvent(
                type = "superego_authorization_decision",
                data = mapOf(
                    "action_id" to resolvedAction.id,
                    "action_type" to resolvedAction.type.id,
                    "intention_kind" to resolvedAction.intentionKind.name.lowercase(),
                    "requested_commit_mode" to resolvedAction.requestedCommitMode.name.lowercase(),
                    "progress" to authorizationDecision.progress.name.lowercase(),
                    "commit_mode" to authorizationDecision.commitMode.name.lowercase(),
                    "reason" to authorizationDecision.reason,
                    "reason_code" to authorizationDecision.reasonCode,
                )
            )
        )
        val gateDecision = GateDecision(
            allow = authorizationDecision.progress != AuthorizationProgress.DENY,
            reason = authorizationDecision.reason,
            reasonCode = authorizationDecision.reasonCode,
        )
        instrumentation.emit(
            AgentEvents.actionReviewResult(
                actionId = resolvedAction.id,
                allow = gateDecision.allow,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode
            )
        )
        if (!gateDecision.allow) {
            recordThreadDenied(
                resolvedAction.rootInputId,
                convCtx,
                gateDecision.reason,
                gateDecision.reasonCode,
            )
            emitThreadUpdate(resolvedAction.rootInputId, convCtx, "action_denied_superego")
            actionControlService.recordLedgerEntry(
                action = resolvedAction,
                conversationContext = convCtx,
                kind = ActionLedgerKind.DENIED,
                importance = ActionRecordImportance.SIGNAL,
                summary = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                source = "superego",
            )
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
            return null
        }
        return authorizationDecision
    }

    private suspend fun executeActionSafely(action: PendingAction): ActionOutcome {
        val gate = actionLifecycleObserver.beforeActionExecution(action)
        if (!gate.allow) {
            val blockedReason = gate.reason.ifBlank { "Action blocked by lifecycle observer." }
            actionLifecycleObserver.onActionBlocked(
                action = action,
                reason = blockedReason,
                reasonCode = gate.reasonCode,
                source = gate.source,
            )
            return ActionOutcome(
                statusSummary = blockedReason,
                executionStatus = ActionExecutionStatus.FAILED,
                observedEvidence = false,
            )
        }
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

    private fun emitIntentionTransition(
        action: PendingAction,
        stage: String,
        kind: IntentionKind,
        commitMode: CommitMode,
        extras: Map<String, Any?> = emptyMap(),
    ) {
        instrumentation.emit(
            AgentEvent(
                type = "intention_transition",
                data = mapOf(
                    "action_id" to action.id,
                    "intention_id" to action.intentionId,
                    "intention_kind" to kind.name.lowercase(),
                    "summary" to action.summary,
                    "stage" to stage,
                    "commit_mode" to commitMode.name.lowercase(),
                    "action_type" to action.type.id,
                    "root_input_id" to action.rootInputId,
                ) + extras
            )
        )
        recordThreadIntention(
            action.rootInputId,
            action.conversationContext,
            action.intentionId,
            kind,
            action.summary,
            commitMode,
            extras.mapValues { (_, value) -> value?.toString().orEmpty() }
        )
    }

    // ── Post-execute bookkeeping ──

    private fun journalActionExecution(resolvedAction: PendingAction, outcome: ActionOutcome) {
        if (resolvedAction.type == ActionType.CONTACT_USER) {
            memory.journal(
                MemoryEventType.CONTACT_DELIVERED,
                "Contacted user: ${TextSecurity.preview(resolvedAction.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                actionType = "contact_user",
            )
        } else {
            memory.journal(
                MemoryEventType.ACTION_EXECUTED,
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
        // Only CONTACT_USER actions represent messages delivered to the user.
        // The action payload IS the message text.
        if (resolvedAction.type != ActionType.CONTACT_USER) return
        if (!outcome.successful) return
        val content = resolvedAction.payload.trim()
        if (content.isBlank()) return

        val assistantTurn = DialogueTurn(
            role = DialogueRole.ASSISTANT,
            content = content,
            sessionId = sessionId,
            interlocutor = convCtx.interlocutor,
            timestamp = java.time.Instant.now()
        )
        dialogueFor(sessionId).addLast(assistantTurn)
        memory.remember(assistantTurn)
        trimDialogue(sessionId)
        if (resolvedAction.origin.source != OriginSource.ID) {
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

    private fun maybeRecordScratchpadOutcome(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {
        if (action.type == ActionType.RESOLUTION_DRAFT) {
            scratchpadStore.recordResolutionDraft(
                rootInputId = action.rootInputId,
                payload = action.payload,
                intentionId = action.intentionId,
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
                        "active_tasks" to scratchpadStore.activeTaskCount()
                    )
                )
            )
            telemetry.emitScratchpadTelemetry(
                rootInputId = action.rootInputId,
                rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                updateType = "resolution_draft_recorded"
            )
            return
        }
        if (action.type == ActionType.CONTACT_USER) return
        scratchpadStore.recordActionOutcome(
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
                    "active_tasks" to scratchpadStore.activeTaskCount()
                )
            )
        )
        telemetry.emitScratchpadTelemetry(
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

    // ── Scratchpad final pass ──

    private fun applyScratchpadFinalPass(action: PendingAction, sessionId: String): PendingAction {
        if (action.type != ActionType.CONTACT_USER) {
            return action
        }
        if (!config.memory.scratchpad.enabled || !config.memory.scratchpad.finalPassRewriteEnabled) {
            return action
        }
        val preFinalSnapshot = scratchpadStore.debugSnapshot(action.rootInputId)
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
        scratchpadStore.recordResolutionDraft(
            rootInputId = action.rootInputId,
            payload = action.payload,
            intentionId = action.intentionId,
        )
        telemetry.emitScratchpadTelemetry(
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "resolution_draft_recorded"
        )
        val finalPassInput = scratchpadStore.buildFinalPassInput(
            rootInputId = action.rootInputId,
            candidateAnswer = action.payload,
            maxChars = config.memory.scratchpad.finalCompilationMaxChars,
            intentionId = action.intentionId,
        ) ?: return action
        val draftThreshold = maxOf(2, config.memory.scratchpad.activationMinPlanSteps)
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
                    "scratchpad_confidence" to finalPassInput.workspaceConfidence,
                    "section_count" to finalPassInput.sectionCount,
                    "evidence_count" to finalPassInput.evidenceCount,
                    "resolution_draft_count" to finalPassInput.resolutionDraftCount,
                    "compilation_preview" to TextSecurity.preview(finalPassInput.compilation, 220)
                )
            )
        )
        if (finalPassInput.workspaceConfidence < config.memory.scratchpad.finalPassMinWorkspaceConfidence) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "scratchpad_confidence_gate",
                        "scratchpad_confidence" to finalPassInput.workspaceConfidence,
                        "min_scratchpad_confidence" to config.memory.scratchpad.finalPassMinWorkspaceConfidence
                    )
                )
            )
            return action
        }
        val finalizerResult = scratchpadFinalizer.finalize(
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
        if (finalizerResult.confidence < config.memory.scratchpad.finalPassMinModelConfidence) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "model_confidence_gate",
                        "model_confidence" to finalizerResult.confidence,
                        "min_model_confidence" to config.memory.scratchpad.finalPassMinModelConfidence
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
                    "scratchpad_confidence" to finalPassInput.workspaceConfidence,
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

    private fun maybeEmitActionFeedback(
        resolvedAction: PendingAction,
        outcome: ActionOutcome,
        observed: Boolean,
        convCtx: ConversationContext,
    ) {
        if (resolvedAction.type == ActionType.CONTACT_USER) return
        // Durable work step progression is owned by the DurableWorkRuntime via
        // ActionLifecycleObserver, not by planner feedback cues. Suppress feedback
        // emission for actions originating from durable work steps so the planner
        // does not receive a redundant signal that duplicates what the runtime
        // already processed and re-delivers content the user already received.
        if (resolvedAction.rootInputId?.startsWith("work:") == true) return
        val cue = ActionFeedbackCue(
            rootInputId = resolvedAction.rootInputId ?: return,
            actionType = resolvedAction.type,
            actionSummary = resolvedAction.summary,
            feedbackContent = if (outcome.plannerSignal.isNotBlank()) outcome.plannerSignal else outcome.statusSummary,
            statusSummary = outcome.statusSummary,
            plannerSignal = outcome.plannerSignal,
            executionStatus = outcome.executionStatus,
            conversationContext = convCtx,
            observedEvidence = observed,
            actionErrorCategory = outcome.actionErrorCategory,
            fetchErrorCategory = outcome.fetchErrorCategory,
            sourceActionId = resolvedAction.id.takeIf { it > 0L },
            rootInputReceivedAtMs = resolvedAction.rootInputReceivedAtMs,
            attempts = resolvedAction.attempts,
            urgency = resolvedAction.urgency.name,
            requiresFollowUpThought = resolvedAction.requiresFollowUpThought,
            origin = resolvedAction.origin,
            groundingMetadata = resolvedAction.groundingMetadata,
        )
        resolvedAction.groundingMetadata.let { metadata ->
            instrumentation.emit(
                AgentEvents.groundingMetadataPropagated(
                    rootInputId = resolvedAction.rootInputId,
                    fromEnvelopeType = "pending_action",
                    toEnvelopeType = "action_feedback_cue",
                    groundingRequired = metadata.requirement == ai.neopsyke.agent.model.GroundingRequirement.REQUIRED,
                    source = metadata.source.name.lowercase(),
                )
            )
        }
        if (!emitActionFeedback(cue)) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue action feedback stimulus."))
            telemetry.recordQueueSaturation(
                queueType = "input",
                capacity = config.maxPendingInputs,
                reason = "enqueue_action_feedback_failed_full"
            )
            return
        }
        instrumentation.emit(
            AgentEvent(
                type = "action_feedback_emitted",
                data = mapOf(
                    "action_id" to resolvedAction.id,
                    "action_type" to resolvedAction.type.id,
                    "root_input_id" to resolvedAction.rootInputId,
                    "execution_status" to outcome.executionStatus.name.lowercase(),
                    "grounding_required" to resolvedAction.groundingMetadata.requirement.name.lowercase(),
                    "grounding_source" to resolvedAction.groundingMetadata.source.name.lowercase(),
                )
            )
        )
    }

    // ── Dialogue trim ──

    private fun trimDialogue(sessionId: String) {
        val deque = dialogueFor(sessionId)
        while (deque.size > MAX_DIALOGUE_SIZE) {
            deque.removeFirst()
        }
    }

    private companion object {
        const val JOURNAL_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_DIALOGUE_SIZE: Int = 20
        val CONTROL_PLANE_TERMINAL_DENIAL_CODES = setOf(
            "OWNER_DENIED_FROM_CHAT",
            "APPROVAL_EXPIRED",
            "APPROVAL_CLARIFICATION_EXHAUSTED",
            "APPROVAL_HASH_MISMATCH",
        )
    }
}

private fun StagedAction.toPipelinePendingAction(): PendingAction =
    PendingAction(
        id = -1L,
        urgency = urgency,
        type = actionType,
        payload = payload,
        summary = summary,
        rootInputId = rootInputId,
        rootInputReceivedAtMs = rootInputReceivedAtMs,
        conversationContext = conversationContext,
        argumentDataTrust = argumentDataTrust,
        origin = origin,
        intentionId = intentionId,
        intentionKind = intentionKind,
        requestedCommitMode = requestedCommitMode,
        groundingMetadata = groundingMetadata,
        isForcedTerminal = isForcedTerminal,
        requiresFollowUpThought = requiresFollowUpThought,
        followUpPrefix = followUpPrefix,
    )
