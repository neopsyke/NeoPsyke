package ai.neopsyke.integrations.telegram

import mu.KotlinLogging
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.dashboard.DashboardStateStore

private val logger = KotlinLogging.logger {}

internal data class TelegramIngressDisposition(
    val accepted: Boolean,
    val detail: String,
    val reasonCode: String,
    val updateId: Long,
    val chatId: String? = null,
    val fromUserId: String? = null,
)

internal class TelegramUpdateProcessor(
    private val store: DashboardStateStore,
    private val sensoryInput: AsyncSignalSource,
    private val config: TelegramChannelConfig,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    fun handle(update: TelegramUpdate): TelegramIngressDisposition {
        val updateId = update.updateId
        val message = update.message ?: update.editedMessage
            ?: return reject(
                reasonCode = "unsupported_update_type",
                detail = "Unsupported Telegram update type.",
                updateId = updateId,
            )
        val text = message.text?.trim().orEmpty()
        val chatId = message.chat.id.toString()
        val fromId = message.from?.id?.toString().orEmpty()
        if (text.isBlank()) {
            return reject(
                reasonCode = "blank_text",
                detail = "Telegram message has no text content.",
                updateId = updateId,
                chatId = chatId,
                fromUserId = fromId,
            )
        }

        if (config.requireDirectChat && !message.chat.type.equals("private", ignoreCase = true)) {
            return reject(
                reasonCode = "non_private_chat",
                detail = "Telegram channel is restricted to direct owner chats.",
                updateId = updateId,
                chatId = chatId,
                fromUserId = fromId,
            )
        }
        if (config.ownerChatId.isNotBlank() && chatId != config.ownerChatId.trim()) {
            return reject(
                reasonCode = "owner_chat_mismatch",
                detail = "Telegram chat is not allowlisted as the owner chat.",
                updateId = updateId,
                chatId = chatId,
                fromUserId = fromId,
            )
        }
        if (config.ownerUserId.isNotBlank() && fromId != config.ownerUserId.trim()) {
            return reject(
                reasonCode = "owner_user_mismatch",
                detail = "Telegram sender is not allowlisted as the owner.",
                updateId = updateId,
                chatId = chatId,
                fromUserId = fromId,
            )
        }

        val sessionId = "${config.sessionIdPrefix}:${message.chat.id}"
        val source = "telegram:${message.chat.id}"
        val resolvedInterlocutor = resolveOwnerInterlocutor(message)
        store.ensureChatSession(
            sessionId = sessionId,
            title = resolvedInterlocutor.displayName(),
            interlocutor = resolvedInterlocutor,
        )
        val stored = store.addUserMessage(
            sessionId = sessionId,
            content = text,
            source = "telegram",
        ) ?: return reject(
            reasonCode = "store_failed",
            detail = "Failed to store Telegram message.",
            updateId = updateId,
            chatId = chatId,
            fromUserId = fromId,
        )

        val conversationContext = ConversationContext(
            sessionId = sessionId,
            interlocutor = interlocutorResolver.resolve(source).takeUnless { it == Interlocutor.UNKNOWN } ?: resolvedInterlocutor,
            security = ConversationSecurityContexts.ownerDirect(
                provider = "telegram",
                channelId = message.chat.id.toString(),
                accountId = config.ownerUserId.ifBlank { null },
                principalId = fromId.ifBlank { "telegram-owner" },
                principalLabel = resolvedInterlocutor.displayName(),
                policyScopeId = config.policyScopeId,
            ),
        )
        val accepted = sensoryInput.submitInput(
            content = stored.content,
            source = source,
            priority = InputPriority.HIGH,
            conversationContext = conversationContext,
        )
        return if (accepted) {
            logger.info {
                "Telegram ingress accepted reason_code=accepted update_id=$updateId chat_id=$chatId from_user_id=${fromId.ifBlank { "unknown" }} session_id=$sessionId"
            }
            TelegramIngressDisposition(
                accepted = true,
                detail = "Telegram message enqueued.",
                reasonCode = "accepted",
                updateId = updateId,
                chatId = chatId,
                fromUserId = fromId,
            )
        } else {
            reject(
                reasonCode = "queue_full",
                detail = "Telegram input queue is full.",
                updateId = updateId,
                chatId = chatId,
                fromUserId = fromId,
            )
        }
    }

    private fun reject(
        reasonCode: String,
        detail: String,
        updateId: Long,
        chatId: String? = null,
        fromUserId: String? = null,
    ): TelegramIngressDisposition {
        logger.warn {
            "Telegram ingress rejected reason_code=$reasonCode update_id=$updateId chat_id=${chatId ?: "unknown"} from_user_id=${fromUserId ?: "unknown"} detail='$detail'"
        }
        return TelegramIngressDisposition(
            accepted = false,
            detail = detail,
            reasonCode = reasonCode,
            updateId = updateId,
            chatId = chatId,
            fromUserId = fromUserId,
        )
    }

    private fun resolveOwnerInterlocutor(message: TelegramMessage): Interlocutor {
        val fullName = listOfNotNull(message.from?.firstName, message.from?.lastName)
            .joinToString(separator = " ")
            .trim()
            .ifBlank { message.from?.username?.let { "@$it" }.orEmpty() }
            .ifBlank { message.chat.title.orEmpty() }
            .ifBlank { "Telegram owner" }
        val id = message.from?.id?.toString()?.ifBlank { null }
            ?: message.chat.id.toString()
        return Interlocutor(id = id, label = fullName)
    }
}
