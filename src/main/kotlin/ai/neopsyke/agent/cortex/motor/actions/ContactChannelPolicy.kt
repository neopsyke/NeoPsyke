package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PrincipalRole

/**
 * Authoritative gate for which delivery channels may be named on durable-work
 * commands and how a preferred-channel hint flows through a
 * [ConversationContext].
 *
 * Two decisions live here:
 *  1. May the current action (by typed principal + origin) alter a work item's
 *     contact channel? Only owner + user-initiated actions may.
 *  2. Is a requested channel name currently deliverable? Delegates to the live
 *     [UserContactChannelStatusProvider] so the allowlist tracks runtime
 *     availability (dashboard session present, Telegram ACK received, etc).
 */
class ContactChannelPolicy(
    private val statusProvider: UserContactChannelStatusProvider,
) {
    fun availableChannels(): Set<String> = statusProvider.availableChannels().keys

    fun canAlterContactChannel(context: ConversationContext, origin: ActionOrigin): Boolean =
        context.security.principal.role == PrincipalRole.OWNER &&
            origin.source == OriginSource.USER

    fun validate(rawChannel: String?): ChannelValidation {
        val normalized = rawChannel?.trim()?.lowercase()?.ifBlank { null }
            ?: return ChannelValidation.None
        val available = availableChannels()
        return if (normalized in available) {
            ChannelValidation.Accepted(normalized)
        } else {
            ChannelValidation.Rejected(requested = normalized, available = available)
        }
    }

    companion object {
        /** Attribute key on [ai.neopsyke.agent.model.ChannelRef.attributes] that
         *  carries a durable-work preferred-channel hint into the resolver. */
        const val PREFERRED_CHANNEL_ATTRIBUTE: String = "preferred_channel"
    }
}

sealed class ChannelValidation {
    data object None : ChannelValidation()
    data class Accepted(val channel: String) : ChannelValidation()
    data class Rejected(val requested: String, val available: Set<String>) : ChannelValidation()
}
