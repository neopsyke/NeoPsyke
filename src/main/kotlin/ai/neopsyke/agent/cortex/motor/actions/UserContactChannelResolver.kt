package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.ConversationContext

/**
 * Shared tracker for Telegram startup-ACK delivery state.
 *
 * Both the approval runtime and the contact-user delivery gateway
 * need to know whether Telegram is reachable. The approval runtime
 * records the ACK; consumers read [delivered].
 */
class TelegramStartupAckTracker {
    @Volatile var delivered: Boolean = false
        private set

    fun recordDelivery(success: Boolean) {
        delivered = success
    }
}

data class DeliveryTarget(
    val provider: String,
    val sessionId: String,
    val channelId: String,
)

data class ChannelResolutionResult(
    val target: DeliveryTarget?,
    val scope: String,
    val failureReason: String? = null,
)

interface UserContactChannelStatusProvider {
    fun availableChannels(): Map<String, DeliveryTarget>
}

interface UserContactChannelResolver {
    fun resolve(
        conversationContext: ConversationContext,
        preferredChannel: String? = null,
    ): ChannelResolutionResult
}

/**
 * Checks whether the dashboard has a reachable session for delivery.
 * Abstracts away `DashboardStateStore` so the motor/actions layer
 * does not depend on the dashboard package.
 */
fun interface DashboardAvailabilityCheck {
    fun isAvailable(sessionId: String): Boolean
}

class DefaultUserContactChannelStatusProvider(
    private val dashboardAvailability: DashboardAvailabilityCheck,
    private val telegramConfig: TelegramChannelConfig,
    private val telegramSink: TelegramMessageSink?,
    private val telegramAckTracker: TelegramStartupAckTracker,
) : UserContactChannelStatusProvider {

    override fun availableChannels(): Map<String, DeliveryTarget> {
        val candidates = linkedMapOf<String, DeliveryTarget>()
        dashboardTarget()?.let { candidates["dashboard"] = it }
        telegramTarget()?.let { candidates["telegram"] = it }
        return candidates
    }

    private fun dashboardTarget(): DeliveryTarget? {
        val sessionId = ConversationContext.DEFAULT_SESSION_ID
        if (!dashboardAvailability.isAvailable(sessionId)) return null
        return DeliveryTarget(provider = "webapp", sessionId = sessionId, channelId = sessionId)
    }

    private fun telegramTarget(): DeliveryTarget? {
        if (!telegramConfig.enabled || telegramSink == null) return null
        val ownerChatId = telegramConfig.ownerChatId.trim()
        if (ownerChatId.isBlank() || !telegramAckTracker.delivered) return null
        return DeliveryTarget(
            provider = "telegram",
            sessionId = "${telegramConfig.sessionIdPrefix}:$ownerChatId",
            channelId = ownerChatId,
        )
    }
}

class DefaultUserContactChannelResolver(
    private val channelStatusProvider: UserContactChannelStatusProvider,
    private val channelPriority: List<String>,
    private val defaultChannel: String,
) : UserContactChannelResolver {

    override fun resolve(
        conversationContext: ConversationContext,
        preferredChannel: String?,
    ): ChannelResolutionResult {
        val channel = conversationContext.security.channel
        val surface = channel.surface
        val provider = channel.provider.trim().lowercase()
        val channelId = channel.channelId.trim()

        // Only trust the inbound channelId when the conversation surface is an
        // authenticated direct chat (e.g. a real Telegram webhook/polling update
        // whose chat_id came from the transport itself). Any other surface —
        // AUTOMATION, ADMIN, GROUP, SHARED_WORKSPACE — must resolve through the
        // live status provider so untrusted callers cannot smuggle a channelId.
        if (surface == ChannelSurface.DIRECT && channelId.isNotBlank() && provider in KNOWN_DELIVERABLE_PROVIDERS) {
            return ChannelResolutionResult(
                target = DeliveryTarget(
                    provider = provider,
                    sessionId = conversationContext.sessionId,
                    channelId = channelId,
                ),
                scope = "conversation",
            )
        }

        val available = channelStatusProvider.availableChannels()

        val hintedChannel = preferredChannel?.trim()?.lowercase()?.ifBlank { null }
        if (hintedChannel != null) {
            val hinted = available[hintedChannel]
            if (hinted != null) {
                return ChannelResolutionResult(target = hinted, scope = "resolved_hint")
            }
        }

        channelPriority.forEach { candidateName ->
            val target = available[candidateName.trim().lowercase()]
            if (target != null) {
                return ChannelResolutionResult(target = target, scope = "resolved_priority")
            }
        }

        val defaultTarget = available[defaultChannel.trim().lowercase()]
        if (defaultTarget != null) {
            return ChannelResolutionResult(target = defaultTarget, scope = "resolved_default")
        }

        return ChannelResolutionResult(
            target = null,
            scope = "unresolved",
            failureReason = "No eligible delivery channel is currently available.",
        )
    }

    companion object {
        private val KNOWN_DELIVERABLE_PROVIDERS: Set<String> = setOf("telegram", "webapp")
    }
}
