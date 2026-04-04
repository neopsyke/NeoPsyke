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
import ai.neopsyke.agent.model.PrincipalRole
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

    override suspend fun onApprovalStaged(
        actionSummary: String,
        stagedAction: StagedAction,
        reason: String,
        reasonCode: String?,
        conversationContext: ConversationContext,
    ) {
        if (!config.approvals.enabled) return
        val existing = store.requestByStagedActionId(stagedAction.id)
        if (existing != null) return
        expirePendingRequests()
        val target = resolveTarget(stagedAction) ?: return
        val nowMs = System.currentTimeMillis()
        val activeForConversation = store.activeRequestForSession(target.sessionId)
        val request = ApprovalRequest(
            id = UUID.randomUUID().toString(),
            stagedActionId = stagedAction.id,
            actionHash = stagedAction.actionHash,
            rootInputId = stagedAction.rootInputId,
            originalSessionId = stagedAction.conversationContext.sessionId,
            target = target,
            status = if (activeForConversation == null) ApprovalRequestStatus.PENDING else ApprovalRequestStatus.QUEUED,
            actionType = stagedAction.actionType.id,
            summary = actionSummary.ifBlank { stagedAction.summary },
            reason = reason,
            reasonCode = reasonCode,
            promptVersion = 1,
            clarificationCount = 0,
            lastPromptAtMs = nowMs,
            expiresAtMs = nowMs + config.approvals.ttlMs,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        store.saveRequest(request)
        audit(request.id, "request_created", "Approval request created.", payload = request.status.name)
        if (request.status == ApprovalRequestStatus.PENDING) {
            deliverPrompt(request, stagedAction)
        } else {
            audit(request.id, "request_queued", "Approval request queued behind an active request.")
        }
    }

    fun routeOwnerMessage(message: OwnerMessageEnvelope): OwnerIngressResult {
        expirePendingRequests()
        val request = store.activeRequestForSession(message.conversationContext.sessionId)
            ?: return forward(message)
        if (message.receivedAtMs < request.lastPromptAtMs) {
            return OwnerIngressResult.Consumed("Stale approval reply ignored.")
        }
        val replayedClassification = replayedClassification(request, message)
        val classification = replayedClassification ?: interpreter.classify(
            ApprovalInterpreterInput(
                reply = message.content,
                promptSummary = request.reason,
                actionSummary = request.summary,
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
                handleApprove(request, message, classification)
                OwnerIngressResult.Consumed("Approval processed.")
            }
            ApprovalClassificationKind.DENY -> {
                handleDeny(request, message, classification, reason = "Denied from owner chat.")
                OwnerIngressResult.Consumed("Denial processed.")
            }
            ApprovalClassificationKind.DENY_AND_REISSUE -> {
                handleDeny(request, message, classification, reason = "Owner requested a different action.")
                forward(message)
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
            val expired = request.copy(
                status = ApprovalRequestStatus.EXPIRED,
                updatedAtMs = nowMs,
                resolutionAtMs = nowMs,
                resolutionProvider = request.target.provider,
                resolutionChannelId = request.target.channelId,
                resolutionSessionId = request.target.sessionId,
            )
            store.updateRequest(expired)
            audit(expired.id, "request_expired", "Approval request expired.")
            deliverText(
                target = expired.target,
                text = "Approval request expired after ${config.approvals.ttlMs / 1000} seconds. Request denied by default.",
                source = "approval-expiry"
            )
            activateNextQueued(expired.target.sessionId)
        }
    }

    fun handleLegacyActionControlMutation(mutation: String, result: ActionControlDecisionResult) {
        when (result) {
            is ActionControlDecisionResult.Executed -> {
                onApprovalExecuted(result)
                markResolvedFromResult(result.stagedAction.id, ApprovalRequestStatus.APPROVED, mutation)
            }
            is ActionControlDecisionResult.Cancelled -> {
                onApprovalDenied(result)
                markResolvedFromResult(result.stagedAction.id, ApprovalRequestStatus.DENIED, mutation)
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
    ) {
        when (
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    actionControlService.authorizeStagedAction(
                        stagedActionId = request.stagedActionId,
                        grantedBy = message.conversationContext.security,
                    )
                }
            }.getOrElse {
                ActionControlDecisionResult.Refused("Approval execution failed: ${it.message}", "APPROVAL_EXECUTION_FAILED")
            }
        ) {
            is ActionControlDecisionResult.Executed -> {
                onApprovalExecuted(result)
                resolveRequest(
                    request = request,
                    status = ApprovalRequestStatus.APPROVED,
                    conversationContext = message.conversationContext,
                    usedModelAssistance = classification.usedModelAssistance,
                )
            }
            is ActionControlDecisionResult.Refused -> {
                deliverText(request.target, "Approval could not be applied: ${result.reason}", "approval-refused")
            }
            else -> Unit
        }
    }

    private fun handleDeny(
        request: ApprovalRequest,
        message: OwnerMessageEnvelope,
        classification: ApprovalClassification,
        reason: String,
    ) {
        when (
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    actionControlService.denyStagedAction(
                        stagedActionId = request.stagedActionId,
                        deniedBy = message.conversationContext.security,
                        reason = reason,
                        reasonCode = "OWNER_DENIED_FROM_CHAT",
                    )
                }
            }.getOrElse {
                ActionControlDecisionResult.Refused("Denial execution failed: ${it.message}", "APPROVAL_DENIAL_FAILED")
            }
        ) {
            is ActionControlDecisionResult.Cancelled -> {
                onApprovalDenied(result)
                resolveRequest(
                    request = request,
                    status = ApprovalRequestStatus.DENIED,
                    conversationContext = message.conversationContext,
                    usedModelAssistance = classification.usedModelAssistance,
                )
            }
            is ActionControlDecisionResult.Refused -> {
                deliverText(request.target, "Denial could not be applied: ${result.reason}", "approval-refused")
            }
            else -> Unit
        }
    }

    private fun handleExplain(request: ApprovalRequest) {
        val stagedAction = actionControlService.stagedAction(request.stagedActionId) ?: return
        val view = ApprovalExplanationView.from(stagedAction, request.reason, request.expiresAtMs)
        val updated = request.copy(
            promptVersion = request.promptVersion + 1,
            lastPromptAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        store.updateRequest(updated)
        deliverText(
            request.target,
            """
            Approval details:
            - action: ${view.actionType}
            - summary: ${view.summary}
            - commit mode: ${view.commitMode}
            - target: ${view.targetDescription}
            - reason: ${view.reason}
            - provider: ${view.provider}
            - origin: ${view.originDescription}
            - expires at: ${Instant.ofEpochMilli(view.expiresAtMs)}
            """.trimIndent(),
            "approval-explanation"
        )
        audit(request.id, "explanation_sent", "Approval explanation sent.")
    }

    private fun handleUnclear(request: ApprovalRequest) {
        val nextClarification = request.clarificationCount + 1
        if (nextClarification > config.approvals.clarificationTurns) {
            val expired = request.copy(
                status = ApprovalRequestStatus.EXPIRED,
                clarificationCount = nextClarification,
                updatedAtMs = System.currentTimeMillis(),
                resolutionAtMs = System.currentTimeMillis(),
            )
            store.updateRequest(expired)
            deliverText(
                expired.target,
                "I could not determine whether that reply approved or denied the pending action. The approval request has expired. Please restate what you want as a new instruction.",
                "approval-unclear-expired"
            )
            audit(expired.id, "clarification_exhausted", "Clarification limit exhausted.")
            activateNextQueued(expired.target.sessionId)
            return
        }
        val updated = request.copy(
            clarificationCount = nextClarification,
            promptVersion = request.promptVersion + 1,
            lastPromptAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        store.updateRequest(updated)
        deliverText(
            updated.target,
            "I could not tell whether that approved or denied the pending action. Please answer clearly whether to proceed or deny it.",
            "approval-clarification"
        )
        audit(updated.id, "clarification_requested", "Approval clarification requested.")
    }

    private fun resolveRequest(
        request: ApprovalRequest,
        status: ApprovalRequestStatus,
        conversationContext: ConversationContext,
        usedModelAssistance: Boolean,
    ) {
        val nowMs = System.currentTimeMillis()
        store.updateRequest(
            request.copy(
                status = status,
                updatedAtMs = nowMs,
                resolutionProvider = conversationContext.security.channel.provider,
                resolutionChannelId = conversationContext.security.channel.channelId,
                resolutionSessionId = conversationContext.sessionId,
                resolutionPrincipalId = conversationContext.security.principal.id,
                resolutionAtMs = nowMs,
                usedModelAssistance = usedModelAssistance,
            )
        )
        audit(request.id, "request_resolved", "Approval request resolved as ${status.name.lowercase()}.")
        activateNextQueued(request.target.sessionId)
    }

    private fun markResolvedFromResult(stagedActionId: String, status: ApprovalRequestStatus, mutation: String) {
        val request = store.requestByStagedActionId(stagedActionId) ?: return
        val nowMs = System.currentTimeMillis()
        store.updateRequest(
            request.copy(
                status = status,
                updatedAtMs = nowMs,
                resolutionAtMs = nowMs,
            )
        )
        audit(request.id, "legacy_resolution", "Resolved via legacy action-control mutation '$mutation'.")
        activateNextQueued(request.target.sessionId)
    }

    private fun activateNextQueued(sessionId: String) {
        val next = store.queuedRequestsForSession(sessionId).firstOrNull() ?: return
        val stagedAction = actionControlService.stagedAction(next.stagedActionId) ?: return
        val activated = next.copy(
            status = ApprovalRequestStatus.PENDING,
            promptVersion = next.promptVersion + 1,
            lastPromptAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
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
            append("Reply naturally to approve, deny, or say what to change.")
        }
        deliverText(request.target, prompt, "approval-prompt")
        audit(request.id, "prompt_sent", "Approval prompt sent.", payload = prompt)
    }

    private fun resolveTarget(stagedAction: StagedAction): ApprovalTarget? {
        val security = stagedAction.conversationContext.security
        if (security.principal.role == PrincipalRole.OWNER) {
            return ApprovalTarget(
                provider = security.channel.provider,
                sessionId = stagedAction.conversationContext.sessionId,
                channelId = security.channel.channelId,
                principalId = security.principal.id,
                principalLabel = security.principal.label,
            )
        }
        val priorities = config.approvals.channelPriority
        priorities.forEach { candidate ->
            when (candidate.trim().lowercase()) {
                "dashboard" -> {
                    val sessionId = ConversationContext.DEFAULT_SESSION_ID
                    val liveOkay = !config.approvals.dashboardRequiresLiveSubscriber ||
                        dashboardStore.hasActiveChatSubscriber(sessionId)
                    if (liveOkay) {
                        return ApprovalTarget(
                            provider = "webapp",
                            sessionId = sessionId,
                            channelId = sessionId,
                        )
                    }
                }

                "telegram" -> {
                    telegramOwnerTarget()?.let { return it }
                }
            }
        }
        return null
    }

    private fun telegramOwnerTarget(): ApprovalTarget? {
        if (!telegramConfig.enabled || telegramSink == null) return null
        val ownerChatId = telegramConfig.ownerChatId.trim()
        if (ownerChatId.isBlank()) return null
        val sessionId = "${telegramConfig.sessionIdPrefix}:$ownerChatId"
        return ApprovalTarget(
            provider = "telegram",
            sessionId = sessionId,
            channelId = ownerChatId,
            principalId = telegramConfig.ownerUserId.ifBlank { "telegram-owner" },
            principalLabel = "Telegram owner",
        )
    }

    private fun deliverText(target: ApprovalTarget, text: String, source: String): ConversationDeliveryResult {
        recordOrReplayOutboundEvent(
            source = source,
            target = target,
            text = text,
        )
        return when (target.provider.trim().lowercase()) {
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
                val delivery = telegramSink?.let { sink -> kotlinx.coroutines.runBlocking { sink.sendMessage(target.channelId, text) } }
                    ?: ConversationDeliveryResult(delivered = false, detail = "Telegram sink unavailable.")
                dashboardStore.ensureChatSession(sessionId = target.sessionId, title = "Telegram owner")
                dashboardStore.addAssistantMessage(
                    sessionId = target.sessionId,
                    content = text,
                    source = source,
                )
                delivery
            }
            else -> ConversationDeliveryResult(delivered = false, detail = "Unsupported approval provider '${target.provider}'.")
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
            request.promptVersion.toString(),
            message.source,
            message.content,
            message.conversationContext.sessionId,
            message.conversationContext.security.channel.provider,
            message.conversationContext.security.channel.channelId,
            message.conversationContext.security.principal.id,
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
    }
}
