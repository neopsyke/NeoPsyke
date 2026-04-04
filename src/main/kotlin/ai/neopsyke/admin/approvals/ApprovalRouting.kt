package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.config.ApprovalRuntimeConfig
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.motor.actions.ConversationDeliveryResult
import ai.neopsyke.agent.cortex.motor.actions.TelegramMessageSink
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.dashboard.DashboardStateStore

data class ApprovalChannelStatus(
    val target: ApprovalTarget,
    val deliverable: Boolean,
    val live: Boolean,
)

data class ApprovalRoutingDecision(
    val target: ApprovalTarget?,
    val routingScope: String,
    val failureReason: String? = null,
)

fun interface ApprovalChannelResolver {
    fun resolve(stagedAction: StagedAction): ApprovalRoutingDecision
}

interface ApprovalChannelStatusProvider {
    fun statuses(): Map<String, ApprovalChannelStatus>
    fun recordDelivery(target: ApprovalTarget, delivery: ConversationDeliveryResult)
}

class DefaultApprovalChannelStatusProvider(
    private val approvals: ApprovalRuntimeConfig,
    private val dashboardStore: DashboardStateStore,
    private val telegramConfig: TelegramChannelConfig,
    private val telegramSink: TelegramMessageSink?,
) : ApprovalChannelStatusProvider {
    @Volatile private var lastTelegramSuccessAtMs: Long? = null
    @Volatile private var lastTelegramFailureAtMs: Long? = null

    override fun statuses(): Map<String, ApprovalChannelStatus> {
        val candidates = linkedMapOf<String, ApprovalChannelStatus>()
        dashboardStatus()?.let { candidates["dashboard"] = it }
        telegramStatus()?.let { candidates["telegram"] = it }
        return candidates
    }

    override fun recordDelivery(target: ApprovalTarget, delivery: ConversationDeliveryResult) {
        if (target.provider != "telegram") return
        val ownerChatId = telegramConfig.ownerChatId.trim()
        if (ownerChatId.isBlank() || target.channelId != ownerChatId) return
        val nowMs = System.currentTimeMillis()
        if (delivery.delivered) {
            lastTelegramSuccessAtMs = nowMs
        } else {
            lastTelegramFailureAtMs = nowMs
        }
    }

    private fun dashboardStatus(): ApprovalChannelStatus? {
        val sessionId = ConversationContext.DEFAULT_SESSION_ID
        val target = ApprovalTarget(
            provider = "webapp",
            sessionId = sessionId,
            channelId = sessionId,
        )
        val deliverable = dashboardStore.hasChatSession(sessionId)
        val live = !approvals.dashboardRequiresLiveSubscriber || dashboardStore.hasActiveChatSubscriber(sessionId)
        return ApprovalChannelStatus(target = target, deliverable = deliverable, live = live)
    }

    private fun telegramStatus(): ApprovalChannelStatus? {
        if (!telegramConfig.enabled || telegramSink == null) return null
        val ownerChatId = telegramConfig.ownerChatId.trim()
        if (ownerChatId.isBlank()) return null
        val target = ApprovalTarget(
            provider = "telegram",
            sessionId = "${telegramConfig.sessionIdPrefix}:$ownerChatId",
            channelId = ownerChatId,
            principalId = telegramConfig.ownerUserId.ifBlank { "telegram-owner" },
            principalLabel = "Telegram owner",
        )
        val lastSuccess = lastTelegramSuccessAtMs
        val lastFailure = lastTelegramFailureAtMs
        val deliverable = lastSuccess != null && (lastFailure == null || lastSuccess >= lastFailure)
        val live = deliverable && System.currentTimeMillis() - lastSuccess <= TELEGRAM_LIVE_WINDOW_MS
        return ApprovalChannelStatus(
            target = target,
            deliverable = deliverable,
            live = live,
        )
    }

    private companion object {
        const val TELEGRAM_LIVE_WINDOW_MS: Long = 15 * 60 * 1000L
    }
}

class DefaultApprovalChannelResolver(
    private val approvals: ApprovalRuntimeConfig,
    private val statusProvider: ApprovalChannelStatusProvider,
) : ApprovalChannelResolver {
    override fun resolve(stagedAction: StagedAction): ApprovalRoutingDecision {
        val security = stagedAction.conversationContext.security
        if (security.principal.role == PrincipalRole.OWNER) {
            return ApprovalRoutingDecision(
                target = ApprovalTarget(
                    provider = security.channel.provider,
                    sessionId = stagedAction.conversationContext.sessionId,
                    channelId = security.channel.channelId,
                    principalId = security.principal.id,
                    principalLabel = security.principal.label,
                ),
                routingScope = "conversation",
            )
        }

        val candidates = statusProvider.statuses()

        approvals.channelPriority.forEach { candidateName ->
            val candidate = candidates[candidateName.trim().lowercase()] ?: return@forEach
            if (candidate.deliverable && candidate.live) {
                return ApprovalRoutingDecision(target = candidate.target, routingScope = "resolved_eligible")
            }
        }

        val defaultCandidate = candidates[approvals.defaultChannel.trim().lowercase()]
        if (defaultCandidate != null && defaultCandidate.deliverable) {
            return ApprovalRoutingDecision(target = defaultCandidate.target, routingScope = "resolved_default_deliverable")
        }

        return ApprovalRoutingDecision(
            target = null,
            routingScope = "unresolved",
            failureReason = "No eligible verified owner channel is currently available for approval delivery.",
        )
    }
}
