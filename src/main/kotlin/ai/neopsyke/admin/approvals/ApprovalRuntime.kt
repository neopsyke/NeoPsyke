package ai.neopsyke.admin.approvals

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.motor.actions.ConversationDeliveryResult
import ai.neopsyke.agent.cortex.motor.actions.TelegramMessageSink
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.dashboard.DashboardStateStore
import ai.neopsyke.session.RecordReplayChannel
import ai.neopsyke.session.SessionRecordEntry
import ai.neopsyke.session.SessionRecordingManager
import ai.neopsyke.session.SessionRecordingMode
import java.time.Instant
import java.util.UUID

interface ApprovalStagingHook {
    suspend fun onApprovalStaged(
        actionSummary: String,
        stagedAction: StagedAction,
        reason: String,
        reasonCode: String?,
        conversationContext: ConversationContext,
    )
}

class ApprovalRuntime(
    private val config: AgentConfig,
    private val store: ApprovalStore,
    private val actionControlService: ActionControlService,
    private val dashboardStore: DashboardStateStore,
    private val telegramConfig: TelegramChannelConfig,
    private val telegramSink: TelegramMessageSink?,
    private val interpreter: ApprovalInterpreter,
    private val forwardNormalInput: (String, String, InputPriority, ConversationContext) -> Boolean,
    private val onApprovalExecuted: (ActionControlDecisionResult.Executed) -> Unit,
    private val onApprovalDenied: (ActionControlDecisionResult.Cancelled) -> Unit,
    private val sessionRecordingManager: SessionRecordingManager? = null,
) : ApprovalStagingHook, AutoCloseable {

    private val approvalFlowChannel: RecordReplayChannel? = sessionRecordingManager?.approvalFlow
    private val channelStatusProvider: ApprovalChannelStatusProvider =
        DefaultApprovalChannelStatusProvider(
            approvals = config.approvals,
            dashboardStore = dashboardStore,
            telegramConfig = telegramConfig,
            telegramSink = telegramSink,
        )
    private val channelResolver: ApprovalChannelResolver =
        DefaultApprovalChannelResolver(
            approvals = config.approvals,
            statusProvider = channelStatusProvider,
        )

    override suspend fun onApprovalStaged(
        actionSummary: String,
        stagedAction: StagedAction,
        reason: String,
        reasonCode: String?,
        conversationContext: ConversationContext,
    ) {
        if (!config.approvals.enabled) return
        val existing = store.requestByStagedActionId(stagedAction.id)
        if (existing != null &&
            existing.actionHash == stagedAction.actionHash &&
            !existing.status.isTerminal()
        ) {
            return
        }
        expirePendingRequests()
        val nowMs = System.currentTimeMillis()
        val routing = channelResolver.resolve(stagedAction)
        val target = routing.target ?: unresolvedTarget(stagedAction)
        val activeForConversation = routing.target?.let { store.activeRequestForSession(it.sessionId) }
        val request = ApprovalRequest(
            id = UUID.randomUUID().toString(),
            stagedActionId = stagedAction.id,
            actionHash = stagedAction.actionHash,
            rootInputId = stagedAction.rootInputId,
            originalSessionId = stagedAction.conversationContext.sessionId,
            target = target,
            status = if (routing.target == null || activeForConversation == null) {
                ApprovalRequestStatus.AWAITING_OWNER_REPLY
            } else {
                ApprovalRequestStatus.QUEUED
            },
            actionType = stagedAction.actionType.id,
            summary = actionSummary.ifBlank { stagedAction.summary },
            reason = reason,
            canonicalSummary = ApprovalCanonicalSummary.from(stagedAction, reason).renderForInterpreter(),
            reasonCode = reasonCode,
            promptVersion = 1,
            promptInstanceId = newPromptInstanceId(),
            clarificationCount = 0,
            lastPromptAtMs = nowMs,
            expiresAtMs = nowMs + config.approvals.ttlMs,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            routingScope = routing.routingScope,
            routingFailureReason = routing.failureReason,
        )
        store.saveRequest(request)
        audit(request.id, "request_created", "Approval request created.", payload = request.status.name)
        if (routing.target == null) {
            audit(
                request.id,
                "routing_unavailable",
                routing.failureReason ?: "Approval request could not be routed to a verified owner channel.",
            )
            return
        }
        if (request.status == ApprovalRequestStatus.AWAITING_OWNER_REPLY) {
            deliverPrompt(request, stagedAction)
        } else {
            audit(request.id, "request_queued", "Approval request queued behind an active request.")
        }
    }

    fun routeOwnerMessage(message: OwnerMessageEnvelope): OwnerIngressResult {
        expirePendingRequests()
        val request = store.activeRequestForSession(message.conversationContext.sessionId)
            ?: return consumeDuplicateTerminalReply(message) ?: forward(message)
        if (isDuplicateEvent(request, message)) {
            audit(request.id, "duplicate_reply_ignored", "Duplicate approval reply ignored.", payload = message.eventId)
            return OwnerIngressResult.Consumed("Duplicate approval reply ignored.")
        }
        if (!matchesRequestTarget(request, message)) {
            audit(request.id, "reply_rejected_scope_mismatch", "Approval reply rejected due to provider/channel/principal mismatch.")
            return OwnerIngressResult.Consumed("Approval reply ignored because it did not match the active approval scope.")
        }
        val promptRef = extractApprovalRef(message.content)
        if (promptRef != null && promptRef != promptReference(request)) {
            audit(
                request.id,
                "reply_rejected_prompt_ref_mismatch",
                "Approval reply referenced a stale prompt instance.",
                payload = promptRef,
            )
            remindActivePromptReference(request, "The approval reference did not match the active pending prompt.")
            return OwnerIngressResult.Consumed("Approval reply ignored because it referenced a stale approval prompt.")
        }
        if (requiresExplicitPromptReference(request) && promptRef == null) {
            audit(
                request.id,
                "reply_rejected_missing_prompt_ref",
                "Approval reply ignored because the latest prompt requires an explicit approval reference.",
            )
            remindActivePromptReference(request, "Please reply to the latest approval prompt and include its approval ref.")
            return OwnerIngressResult.Consumed("Approval reply ignored because it did not include the current approval ref.")
        }
        if (message.receivedAtMs < request.lastPromptAtMs) {
            audit(request.id, "reply_rejected_stale", "Stale approval reply ignored.", payload = request.promptInstanceId)
            return OwnerIngressResult.Consumed("Stale approval reply ignored.")
        }
        val replayedClassification = replayedClassification(request, message)
        val classification = replayedClassification ?: interpreter.classify(
            ApprovalInterpreterInput(
                reply = message.content,
                canonicalSummary = request.canonicalSummary,
                sessionId = message.conversationContext.sessionId,
                rootInputId = request.rootInputId,
            )
        )
        recordReplyClassification(request, message, classification)
        audit(
            request.id,
            "reply_classified",
            "Reply classified as ${classification.kind.name.lowercase()}",
            payload = message.content,
        )
        return when (classification.kind) {
            ApprovalClassificationKind.APPROVE -> {
                if (handleApprove(request, message, classification)) {
                    OwnerIngressResult.Consumed("Approval processed.")
                } else {
                    OwnerIngressResult.Consumed("Approval could not be applied.")
                }
            }
            ApprovalClassificationKind.DENY -> {
                if (handleDeny(
                        request = request,
                        message = message,
                        classification = classification,
                        status = ApprovalRequestStatus.DENIED,
                        reason = "Denied from owner chat.",
                    )
                ) {
                    OwnerIngressResult.Consumed("Denial processed.")
                } else {
                    OwnerIngressResult.Consumed("Denial could not be applied.")
                }
            }
            ApprovalClassificationKind.DENY_AND_REISSUE -> {
                if (
                    handleDeny(
                        request = request,
                        message = message,
                        classification = classification,
                        status = ApprovalRequestStatus.DENIED_AND_REISSUED,
                        reason = "Owner requested a different action.",
                        forwardedOwnerReplyRaw = message.content,
                        forwardedOwnerSource = reissueSource(request, message),
                    )
                ) {
                    forwardReissued(message, request)
                } else {
                    OwnerIngressResult.Consumed("The pending action could not be denied, so the new instruction was not reissued.")
                }
            }
            ApprovalClassificationKind.EXPLAIN -> {
                handleExplain(request)
                OwnerIngressResult.Consumed("Approval explanation delivered.")
            }
            ApprovalClassificationKind.UNCLEAR -> {
                handleUnclear(request)
                OwnerIngressResult.Consumed("Approval clarification requested.")
            }
        }
    }

    fun expirePendingRequests() {
        if (!config.approvals.enabled) return
        val nowMs = System.currentTimeMillis()
        store.pendingRequests(nowMs).forEach { request ->
            val denyResult = runCatching {
                kotlinx.coroutines.runBlocking {
                    actionControlService.denyStagedAction(
                        stagedActionId = request.stagedActionId,
                        deniedBy = ConversationSecurityContexts.adminControl(
                            provider = "approval_runtime",
                            channelId = request.target.channelId,
                            principalId = "approval-runtime",
                        ),
                        reason = "Approval request expired after ${config.approvals.ttlMs / 1000} seconds.",
                        reasonCode = "APPROVAL_EXPIRED",
                    )
                }
            }.getOrElse {
                ActionControlDecisionResult.Refused("Expiry denial failed: ${it.message}", "APPROVAL_EXPIRY_DENIAL_FAILED")
            }
            when (denyResult) {
                is ActionControlDecisionResult.Cancelled -> {
                    onApprovalDenied(denyResult)
                    resolveExpiredRequest(request, nowMs)
                }

                is ActionControlDecisionResult.Refused -> {
                    val transitioned = store.transitionRequest(
                        request = request.copy(
                            status = ApprovalRequestStatus.EXPIRED,
                            updatedAtMs = nowMs,
                            resolutionAtMs = nowMs,
                            resolutionProvider = request.target.provider,
                            resolutionChannelId = request.target.channelId,
                            resolutionSessionId = request.target.sessionId,
                            resolutionPrincipalId = "approval-runtime",
                            resolutionReason = denyResult.reason,
                        ),
                        expectedStatuses = MUTABLE_REQUEST_STATUSES,
                    )
                    if (transitioned) {
                        audit(request.id, "request_expired", "Approval request expired without a denyable staged action.")
                        activateNextQueued(request.target.sessionId)
                    }
                }

                else -> Unit
            }
            if (request.target.provider != UNROUTED_PROVIDER) {
                deliverText(
                    target = request.target,
                    text = "Approval request expired after ${config.approvals.ttlMs / 1000} seconds. Request denied by default.",
                    source = "approval-expiry"
                )
            }
        }
    }

    fun handleLegacyActionControlMutation(mutation: String, result: ActionControlDecisionResult) {
        when (result) {
            is ActionControlDecisionResult.Executed -> {
                if (markResolvedFromResult(result.stagedAction.id, ApprovalRequestStatus.APPROVED, mutation)) {
                    onApprovalExecuted(result)
                }
            }
            is ActionControlDecisionResult.Cancelled -> {
                if (markResolvedFromResult(result.stagedAction.id, ApprovalRequestStatus.DENIED, mutation)) {
                    onApprovalDenied(result)
                }
            }
            else -> Unit
        }
    }

    suspend fun sendTelegramStartupAckIfEnabled() {
        if (!config.approvals.telegramStartupAckEnabled) return
        if (!telegramConfig.enabled) return
        val target = telegramOwnerTarget() ?: return
        deliverText(
            target = target,
            text = "NeoPsyke Telegram channel online. Outbound delivery confirmed at ${Instant.now()}.",
            source = "approval-startup-ack"
        )
    }

    private fun forward(message: OwnerMessageEnvelope): OwnerIngressResult {
        val accepted = forwardNormalInput(
            message.content,
            message.source,
            message.priority,
            message.conversationContext,
        )
        return OwnerIngressResult.Forwarded(
            enqueued = accepted,
            detail = if (accepted) "Message forwarded to sensory ingress." else "Input queue is full.",
        )
    }

    private fun handleApprove(
        request: ApprovalRequest,
        message: OwnerMessageEnvelope,
        classification: ApprovalClassification,
    ): Boolean {
        val current = actionControlService.stagedAction(request.stagedActionId)
        if (current == null) {
            deliverText(request.target, "Approval could not be applied because the staged action no longer exists.", "approval-refused")
            return false
        }
        if (current.actionHash != request.actionHash) {
            supersedeAndRefreshOnHashMismatch(request, current, message)
            deliverText(
                request.target,
                "Approval request is stale because the staged action changed. I sent a refreshed approval prompt.",
                "approval-refreshed"
            )
            return false
        }
        when (
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    actionControlService.authorizeStagedAction(
                        stagedActionId = request.stagedActionId,
                        grantedBy = message.conversationContext.security,
                        expectedActionHash = request.actionHash,
                    )
                }
            }.getOrElse {
                ActionControlDecisionResult.Refused("Approval execution failed: ${it.message}", "APPROVAL_EXECUTION_FAILED")
            }
        ) {
            is ActionControlDecisionResult.Executed -> {
                onApprovalExecuted(result)
                return resolveRequest(
                    request = request,
                    status = ApprovalRequestStatus.APPROVED,
                    conversationContext = message.conversationContext,
                    usedModelAssistance = classification.usedModelAssistance,
                    lastInboundEventId = message.eventId,
                )
            }
            is ActionControlDecisionResult.Refused -> {
                deliverText(request.target, "Approval could not be applied: ${result.reason}", "approval-refused")
                return false
            }
            else -> return false
        }
    }

    private fun handleDeny(
        request: ApprovalRequest,
        message: OwnerMessageEnvelope,
        classification: ApprovalClassification,
        status: ApprovalRequestStatus,
        reason: String,
        forwardedOwnerReplyRaw: String? = null,
        forwardedOwnerSource: String? = null,
    ): Boolean {
        val current = actionControlService.stagedAction(request.stagedActionId)
        if (current == null) {
            deliverText(request.target, "Denial could not be applied because the staged action no longer exists.", "approval-refused")
            return false
        }
        if (current.actionHash != request.actionHash) {
            supersedeAndRefreshOnHashMismatch(request, current, message)
            deliverText(
                request.target,
                "Approval request is stale because the staged action changed. I sent a refreshed approval prompt.",
                "approval-refreshed"
            )
            return false
        }
        when (
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    actionControlService.denyStagedAction(
                        stagedActionId = request.stagedActionId,
                        deniedBy = message.conversationContext.security,
                        reason = reason,
                        reasonCode = "OWNER_DENIED_FROM_CHAT",
                        expectedActionHash = request.actionHash,
                    )
                }
            }.getOrElse {
                ActionControlDecisionResult.Refused("Denial execution failed: ${it.message}", "APPROVAL_DENIAL_FAILED")
            }
        ) {
            is ActionControlDecisionResult.Cancelled -> {
                onApprovalDenied(result)
                return resolveRequest(
                    request = request,
                    status = status,
                    conversationContext = message.conversationContext,
                    usedModelAssistance = classification.usedModelAssistance,
                    forwardedOwnerReplyRaw = forwardedOwnerReplyRaw,
                    forwardedOwnerSource = forwardedOwnerSource,
                    lastInboundEventId = message.eventId,
                )
            }
            is ActionControlDecisionResult.Refused -> {
                deliverText(request.target, "Denial could not be applied: ${result.reason}", "approval-refused")
                return false
            }
            else -> return false
        }
    }

    private fun handleExplain(request: ApprovalRequest) {
        val stagedAction = actionControlService.stagedAction(request.stagedActionId) ?: return
        val view = ApprovalExplanationView.from(stagedAction, request.reason, request.expiresAtMs)
        val updated = request.copy(
            status = ApprovalRequestStatus.AWAITING_OWNER_REPLY,
            promptVersion = request.promptVersion + 1,
            promptInstanceId = newPromptInstanceId(),
            lastPromptAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        store.updateRequest(updated)
        val delivery = deliverText(
            updated.target,
            buildString {
                appendLine(view.render())
                append(bindingInstruction(updated))
            },
            "approval-explanation"
        )
        updateDeliveryStatus(updated, delivery)
        audit(request.id, "explanation_sent", "Approval explanation sent.")
    }

    private fun handleUnclear(request: ApprovalRequest) {
        val nextClarification = request.clarificationCount + 1
        if (nextClarification > config.approvals.clarificationTurns) {
            val nowMs = System.currentTimeMillis()
            val denyResult = runCatching {
                kotlinx.coroutines.runBlocking {
                    actionControlService.denyStagedAction(
                        stagedActionId = request.stagedActionId,
                        deniedBy = ConversationSecurityContexts.adminControl(
                            provider = "approval_runtime",
                            channelId = request.target.channelId,
                            principalId = "approval-runtime",
                        ),
                        reason = "Approval clarification limit exhausted.",
                        reasonCode = "APPROVAL_CLARIFICATION_EXHAUSTED",
                    )
                }
            }.getOrElse {
                ActionControlDecisionResult.Refused("Clarification expiry denial failed: ${it.message}", "APPROVAL_CLARIFICATION_DENIAL_FAILED")
            }
            if (denyResult is ActionControlDecisionResult.Cancelled) {
                onApprovalDenied(denyResult)
                store.transitionRequest(
                    request = request.copy(
                        status = ApprovalRequestStatus.EXPIRED,
                        clarificationCount = nextClarification,
                        updatedAtMs = nowMs,
                        resolutionAtMs = nowMs,
                        resolutionProvider = request.target.provider,
                        resolutionChannelId = request.target.channelId,
                        resolutionSessionId = request.target.sessionId,
                        resolutionPrincipalId = "approval-runtime",
                        resolutionReason = "Clarification limit exhausted.",
                    ),
                    expectedStatuses = MUTABLE_REQUEST_STATUSES,
                )
            } else {
                store.transitionRequest(
                    request = request.copy(
                        status = ApprovalRequestStatus.EXPIRED,
                        clarificationCount = nextClarification,
                        updatedAtMs = nowMs,
                        resolutionAtMs = nowMs,
                        resolutionProvider = request.target.provider,
                        resolutionChannelId = request.target.channelId,
                        resolutionSessionId = request.target.sessionId,
                        resolutionPrincipalId = "approval-runtime",
                        resolutionReason = if (denyResult is ActionControlDecisionResult.Refused) denyResult.reason else "Clarification limit exhausted.",
                    ),
                    expectedStatuses = MUTABLE_REQUEST_STATUSES,
                )
            }
            deliverText(
                request.target,
                "I could not determine whether that reply approved or denied the pending action. The approval request has expired. Please restate what you want as a new instruction.",
                "approval-unclear-expired"
            )
            audit(request.id, "clarification_exhausted", "Clarification limit exhausted.")
            activateNextQueued(request.target.sessionId)
            return
        }
        val updated = request.copy(
            status = ApprovalRequestStatus.AWAITING_CLARIFICATION,
            clarificationCount = nextClarification,
            promptVersion = request.promptVersion + 1,
            promptInstanceId = newPromptInstanceId(),
            lastPromptAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        store.updateRequest(updated)
        val delivery = deliverText(
            updated.target,
            "I could not tell whether that approved or denied the pending action. " +
                "Please answer clearly whether to proceed or deny it. ${bindingInstruction(updated)}",
            "approval-clarification"
        )
        updateDeliveryStatus(updated, delivery)
        audit(updated.id, "clarification_requested", "Approval clarification requested.")
    }

    private fun resolveRequest(
        request: ApprovalRequest,
        status: ApprovalRequestStatus,
        conversationContext: ConversationContext,
        usedModelAssistance: Boolean,
        resolutionReason: String? = null,
        forwardedOwnerReplyRaw: String? = null,
        forwardedOwnerSource: String? = null,
        lastInboundEventId: String? = null,
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        val transitioned = store.transitionRequest(
            request.copy(
                status = status,
                updatedAtMs = nowMs,
                resolutionProvider = conversationContext.security.channel.provider,
                resolutionChannelId = conversationContext.security.channel.channelId,
                resolutionSessionId = conversationContext.sessionId,
                resolutionPrincipalId = conversationContext.security.principal.id,
                resolutionAtMs = nowMs,
                resolutionReason = resolutionReason,
                forwardedOwnerReplyRaw = forwardedOwnerReplyRaw,
                forwardedOwnerSource = forwardedOwnerSource,
                lastInboundEventId = lastInboundEventId,
                usedModelAssistance = usedModelAssistance,
            ),
            expectedStatuses = MUTABLE_REQUEST_STATUSES,
        )
        if (!transitioned) return false
        audit(request.id, "request_resolved", "Approval request resolved as ${status.name.lowercase()}.")
        activateNextQueued(request.target.sessionId)
        return true
    }

    private fun markResolvedFromResult(stagedActionId: String, status: ApprovalRequestStatus, mutation: String): Boolean {
        val request = store.requestByStagedActionId(stagedActionId) ?: return false
        val nowMs = System.currentTimeMillis()
        val transitioned = store.transitionRequest(
            request.copy(
                status = status,
                updatedAtMs = nowMs,
                resolutionAtMs = nowMs,
            ),
            expectedStatuses = MUTABLE_REQUEST_STATUSES,
        )
        if (!transitioned) return false
        audit(request.id, "legacy_resolution", "Resolved via legacy action-control mutation '$mutation'.")
        activateNextQueued(request.target.sessionId)
        return true
    }

    private fun activateNextQueued(sessionId: String) {
        val next = store.queuedRequestsForSession(sessionId).firstOrNull() ?: return
        val stagedAction = actionControlService.stagedAction(next.stagedActionId) ?: return
        val nowMs = System.currentTimeMillis()
        val activated = next.copy(
            status = ApprovalRequestStatus.AWAITING_OWNER_REPLY,
            promptVersion = next.promptVersion + 1,
            promptInstanceId = newPromptInstanceId(),
            lastPromptAtMs = nowMs,
            expiresAtMs = nowMs + config.approvals.ttlMs,
            updatedAtMs = nowMs,
        )
        store.updateRequest(activated)
        deliverPrompt(activated, stagedAction)
    }

    private fun deliverPrompt(request: ApprovalRequest, stagedAction: StagedAction) {
        val prompt = buildString {
            appendLine("Approval required.")
            appendLine("Action: ${stagedAction.actionType.id}")
            appendLine("Summary: ${request.summary}")
            appendLine("Reason: ${request.reason}")
            appendLine("Approval ref: ${promptReference(request)}")
            append(bindingInstruction(request))
        }
        val delivery = deliverText(request.target, prompt, "approval-prompt")
        updateDeliveryStatus(request, delivery)
        audit(request.id, "prompt_delivery", "Approval prompt delivery recorded.", payload = delivery.detail)
        audit(request.id, "prompt_sent", "Approval prompt sent.", payload = prompt)
    }

    private fun deliverText(target: ApprovalTarget, text: String, source: String): ConversationDeliveryResult {
        recordOrReplayOutboundEvent(
            source = source,
            target = target,
            text = text,
        )
        val delivery = when (target.provider.trim().lowercase()) {
            "webapp" -> {
                dashboardStore.ensureChatSession(sessionId = target.sessionId, title = "Default")
                dashboardStore.addAssistantMessage(
                    sessionId = target.sessionId,
                    content = text,
                    source = source,
                )
                ConversationDeliveryResult(delivered = true, detail = "Dashboard delivery recorded.")
            }
            "telegram" -> {
                val telegramDelivery = telegramSink?.let { sink -> kotlinx.coroutines.runBlocking { sink.sendMessage(target.channelId, text) } }
                    ?: ConversationDeliveryResult(delivered = false, detail = "Telegram sink unavailable.")
                dashboardStore.ensureChatSession(sessionId = target.sessionId, title = "Telegram owner")
                dashboardStore.addAssistantMessage(
                    sessionId = target.sessionId,
                    content = text,
                    source = source,
                )
                telegramDelivery
            }
            else -> ConversationDeliveryResult(delivered = false, detail = "Unsupported approval provider '${target.provider}'.")
        }
        channelStatusProvider.recordDelivery(target, delivery, source)
        return delivery
    }

    private fun supersedeAndRefreshOnHashMismatch(
        request: ApprovalRequest,
        current: StagedAction,
        message: OwnerMessageEnvelope,
    ) {
        // Hash drift means owner intent no longer binds to the same staged side effect.
        // Supersede the old request and issue a fresh prompt instead of applying approve/deny.
        val nowMs = System.currentTimeMillis()
        val superseded = request.copy(
            status = ApprovalRequestStatus.SUPERSEDED,
            updatedAtMs = nowMs,
            resolutionProvider = message.conversationContext.security.channel.provider,
            resolutionChannelId = message.conversationContext.security.channel.channelId,
            resolutionSessionId = message.conversationContext.sessionId,
            resolutionPrincipalId = message.conversationContext.security.principal.id,
            resolutionAtMs = nowMs,
            resolutionReason = "Approval prompt superseded because staged action hash changed.",
            lastInboundEventId = message.eventId,
        )
        val transitioned = store.transitionRequest(
            request = superseded,
            expectedStatuses = LIVE_REQUEST_STATUSES,
        )
        if (!transitioned) return
        audit(request.id, "request_superseded", "Approval request superseded due to staged action hash mismatch.")

        val activeForConversation = store.activeRequestForSession(request.target.sessionId)
        val replacement = ApprovalRequest(
            id = UUID.randomUUID().toString(),
            stagedActionId = current.id,
            actionHash = current.actionHash,
            rootInputId = current.rootInputId,
            originalSessionId = current.conversationContext.sessionId,
            target = request.target,
            status = if (activeForConversation == null) ApprovalRequestStatus.AWAITING_OWNER_REPLY else ApprovalRequestStatus.QUEUED,
            actionType = current.actionType.id,
            summary = current.summary,
            reason = request.reason,
            canonicalSummary = ApprovalCanonicalSummary.from(current, request.reason).renderForInterpreter(),
            reasonCode = request.reasonCode,
            promptVersion = 1,
            promptInstanceId = newPromptInstanceId(),
            clarificationCount = 0,
            lastPromptAtMs = nowMs,
            expiresAtMs = nowMs + config.approvals.ttlMs,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            routingScope = request.routingScope,
            routingFailureReason = null,
        )
        store.saveRequest(replacement)
        audit(replacement.id, "request_created", "Replacement approval request created.", payload = replacement.status.name)
        if (replacement.status == ApprovalRequestStatus.AWAITING_OWNER_REPLY) {
            deliverPrompt(replacement, current)
        } else {
            audit(replacement.id, "request_queued", "Replacement approval request queued behind an active request.")
        }
    }

    private fun audit(requestId: String, kind: String, summary: String, payload: String? = null) {
        store.saveAudit(
            ApprovalAuditEntry(
                id = UUID.randomUUID().toString(),
                requestId = requestId,
                kind = kind,
                summary = summary,
                payload = payload,
                createdAtMs = System.currentTimeMillis(),
            )
        )
    }

    override fun close() {
        store.close()
    }

    private fun replayedClassification(
        request: ApprovalRequest,
        message: OwnerMessageEnvelope,
    ): ApprovalClassification? {
        val channel = approvalFlowChannel ?: return null
        if (channel.mode != SessionRecordingMode.REPLAY || channel.passthroughMode) return null
        val seq = channel.nextSequenceIndex()
        val hash = approvalReplyHash(request, message)
        val data = channel.replayOrDiverge(seq, hash) ?: return null
        if (!data.path("kind").isTextual) return null
        val kind = runCatching {
            ApprovalClassificationKind.valueOf(data.path("kind").asText())
        }.getOrElse { return null }
        return ApprovalClassification(
            kind = kind,
            usedModelAssistance = data.path("used_model_assistance").asBoolean(false),
        )
    }

    private fun recordReplyClassification(
        request: ApprovalRequest,
        message: OwnerMessageEnvelope,
        classification: ApprovalClassification,
    ) {
        val channel = approvalFlowChannel ?: return
        if (channel.mode != SessionRecordingMode.RECORD) return
        sessionRecordingManager?.captureRecordingContext(
            ai.neopsyke.session.RecordedContext(
                source = message.source,
                sessionId = message.conversationContext.sessionId,
                interlocutorId = message.conversationContext.interlocutor.id,
                instructionTrust = message.conversationContext.security.instructionTrust.name,
                channelSurface = message.conversationContext.security.channel.surface.name,
                channelTransport = message.conversationContext.security.channel.transport.name,
                principalRole = message.conversationContext.security.principal.role.name,
                goalsEnabled = System.getenv("NEOPSYKE_GOALS_ENABLED")?.trim()?.lowercase() != "false",
            )
        )
        val seq = channel.nextSequenceIndex()
        channel.recordEntry(
            SessionRecordEntry(
                seq = seq,
                hash = approvalReplyHash(request, message),
                channel = SessionRecordingManager.CHANNEL_APPROVAL_FLOW,
                data = mapper.createObjectNode().apply {
                    put("event", "reply")
                    put("request_id", request.id)
                    put("prompt_version", request.promptVersion)
                    put("prompt_instance_id", request.promptInstanceId)
                    message.eventId?.let { put("event_id", it) }
                    put("kind", classification.kind.name)
                    put("used_model_assistance", classification.usedModelAssistance)
                },
            )
        )
    }

    private fun recordOrReplayOutboundEvent(
        source: String,
        target: ApprovalTarget,
        text: String,
    ) {
        val channel = approvalFlowChannel ?: return
        val seq = channel.nextSequenceIndex()
        val hash = outboundEventHash(source, target, text)
        when (channel.mode) {
            SessionRecordingMode.RECORD -> {
                channel.recordEntry(
                    SessionRecordEntry(
                        seq = seq,
                        hash = hash,
                        channel = SessionRecordingManager.CHANNEL_APPROVAL_FLOW,
                        data = mapper.createObjectNode().apply {
                            put("event", "outbound")
                            put("source", source)
                            put("provider", target.provider)
                            put("session_id", target.sessionId)
                            put("channel_id", target.channelId)
                            put("text", text)
                        },
                    )
                )
            }
            SessionRecordingMode.REPLAY -> {
                channel.replayOrDiverge(seq, hash)
            }
            SessionRecordingMode.OFF -> Unit
        }
    }

    private fun approvalReplyHash(request: ApprovalRequest, message: OwnerMessageEnvelope): String =
        RecordReplayChannel.hashContent(
            "approval-reply",
            request.stagedActionId,
            request.promptInstanceId,
            message.source,
            message.content,
            message.conversationContext.sessionId,
            message.conversationContext.security.channel.provider,
            message.conversationContext.security.channel.channelId,
            message.conversationContext.security.principal.id,
            message.eventId.orEmpty(),
        )

    private fun outboundEventHash(source: String, target: ApprovalTarget, text: String): String =
        RecordReplayChannel.hashContent(
            "approval-outbound",
            source,
            target.provider,
            target.sessionId,
            target.channelId,
            text,
        )

    private companion object {
        val mapper = jacksonObjectMapper()
        val LIVE_REQUEST_STATUSES = setOf(
            ApprovalRequestStatus.AWAITING_OWNER_REPLY,
            ApprovalRequestStatus.AWAITING_CLARIFICATION,
        )
        val MUTABLE_REQUEST_STATUSES = LIVE_REQUEST_STATUSES + ApprovalRequestStatus.QUEUED
        const val UNROUTED_PROVIDER: String = "unrouted"
        const val PROMPT_REFERENCE_CHARS: Int = 8
        val APPROVAL_REF_REGEX: Regex = Regex("""(?:approval\s*ref|ref)\s*[:#]?\s*([a-z0-9]{8})""")
    }

    private fun resolveExpiredRequest(request: ApprovalRequest, nowMs: Long) {
        val transitioned = store.transitionRequest(
            request = request.copy(
                status = ApprovalRequestStatus.EXPIRED,
                updatedAtMs = nowMs,
                resolutionAtMs = nowMs,
                resolutionProvider = request.target.provider,
                resolutionChannelId = request.target.channelId,
                resolutionSessionId = request.target.sessionId,
                resolutionPrincipalId = "approval-runtime",
                resolutionReason = "Approval expired.",
            ),
            expectedStatuses = MUTABLE_REQUEST_STATUSES,
        )
        if (!transitioned) return
        audit(request.id, "request_expired", "Approval request expired.")
        activateNextQueued(request.target.sessionId)
    }

    private fun unresolvedTarget(stagedAction: StagedAction): ApprovalTarget =
        ApprovalTarget(
            provider = UNROUTED_PROVIDER,
            sessionId = "approval-unrouted:${stagedAction.id}",
            channelId = "approval-unrouted:${stagedAction.id}",
            principalId = "owner",
            principalLabel = "Owner",
        )

    private fun telegramOwnerTarget(): ApprovalTarget? {
        if (!telegramConfig.enabled || telegramSink == null) return null
        val ownerChatId = telegramConfig.ownerChatId.trim()
        if (ownerChatId.isBlank()) return null
        return ApprovalTarget(
            provider = "telegram",
            sessionId = "${telegramConfig.sessionIdPrefix}:$ownerChatId",
            channelId = ownerChatId,
            principalId = telegramConfig.ownerUserId.ifBlank { "telegram-owner" },
            principalLabel = "Telegram owner",
        )
    }

    private fun newPromptInstanceId(): String = UUID.randomUUID().toString()

    private fun matchesRequestTarget(request: ApprovalRequest, message: OwnerMessageEnvelope): Boolean =
        request.target.provider == message.conversationContext.security.channel.provider &&
            request.target.channelId == message.conversationContext.security.channel.channelId &&
            request.target.principalId == message.conversationContext.security.principal.id

    private fun isDuplicateEvent(request: ApprovalRequest, message: OwnerMessageEnvelope): Boolean =
        !message.eventId.isNullOrBlank() && message.eventId == request.lastInboundEventId

    private fun consumeDuplicateTerminalReply(message: OwnerMessageEnvelope): OwnerIngressResult? {
        val eventId = message.eventId ?: return null
        val latest = store.latestRequestForSession(message.conversationContext.sessionId) ?: return null
        if (!latest.status.isTerminal()) return null
        return if (latest.lastInboundEventId == eventId) {
            OwnerIngressResult.Consumed("Duplicate approval reply ignored.")
        } else {
            null
        }
    }

    private fun forwardReissued(message: OwnerMessageEnvelope, request: ApprovalRequest): OwnerIngressResult {
        val reissueSource = reissueSource(request, message)
        val accepted = forwardNormalInput(
            message.content,
            reissueSource,
            message.priority,
            message.conversationContext.copy(
                attributes = message.conversationContext.attributes + mapOf(
                    "approval_request_id" to request.id,
                    "approval_staged_action_id" to request.stagedActionId,
                    "approval_reissue" to "true",
                    "approval_prompt_instance_id" to request.promptInstanceId,
                )
            ),
        )
        audit(
            request.id,
            "owner_reply_reissued",
            if (accepted) "Owner reply reissued into normal ingress." else "Owner reply reissue failed because sensory ingress was full.",
            payload = reissueSource,
        )
        return OwnerIngressResult.Forwarded(
            enqueued = accepted,
            detail = if (accepted) "Denied pending action and reissued the owner reply into normal ingress." else "Pending action denied, but the new instruction could not be enqueued.",
        )
    }

    private fun reissueSource(request: ApprovalRequest, message: OwnerMessageEnvelope): String =
        "approval-reissue:${request.id}:${message.source}"

    private fun updateDeliveryStatus(request: ApprovalRequest, delivery: ConversationDeliveryResult) {
        store.updateRequest(
            request.copy(
                lastPromptDelivered = delivery.delivered,
                lastPromptDeliveryDetail = delivery.detail,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    private fun requiresExplicitPromptReference(request: ApprovalRequest): Boolean =
        request.promptVersion > 1

    private fun promptReference(request: ApprovalRequest): String =
        request.promptInstanceId.take(PROMPT_REFERENCE_CHARS).lowercase()

    private fun bindingInstruction(request: ApprovalRequest): String =
        if (requiresExplicitPromptReference(request)) {
            "Reply with approval ref ${promptReference(request)} to approve, deny, or say what to change."
        } else {
            "Reply naturally to approve, deny, or say what to change."
        }

    private fun remindActivePromptReference(request: ApprovalRequest, explanation: String) {
        val delivery = deliverText(
            request.target,
            "$explanation Reply with approval ref ${promptReference(request)} to resolve the pending action.",
            "approval-ref-binding"
        )
        updateDeliveryStatus(request, delivery)
    }

    private fun extractApprovalRef(content: String): String? =
        APPROVAL_REF_REGEX.find(content.lowercase())?.groupValues?.getOrNull(1)
}

private fun ApprovalRequestStatus.isTerminal(): Boolean =
    when (this) {
        ApprovalRequestStatus.APPROVED,
        ApprovalRequestStatus.DENIED,
        ApprovalRequestStatus.DENIED_AND_REISSUED,
        ApprovalRequestStatus.EXPIRED,
        ApprovalRequestStatus.SUPERSEDED -> true
        ApprovalRequestStatus.QUEUED,
        ApprovalRequestStatus.AWAITING_OWNER_REPLY,
        ApprovalRequestStatus.AWAITING_CLARIFICATION,
        -> false
    }
