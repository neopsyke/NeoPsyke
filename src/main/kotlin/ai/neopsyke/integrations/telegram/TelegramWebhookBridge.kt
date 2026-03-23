package ai.neopsyke.integrations.telegram

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.dashboard.DashboardStateStore

data class TelegramWebhookResult(
    val statusCode: Int,
    val accepted: Boolean,
    val detail: String,
)

class TelegramWebhookBridge(
    private val store: DashboardStateStore,
    private val sensoryInput: AsyncSignalSource,
    private val config: TelegramChannelConfig,
    private val webhookSecret: String?,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    fun webhookPath(): String = config.webhookPath

    fun handleWebhook(
        requestMethod: String,
        secretTokenHeader: String?,
        requestBody: String,
    ): TelegramWebhookResult {
        if (!config.enabled) {
            return TelegramWebhookResult(503, accepted = false, detail = "Telegram channel is disabled.")
        }
        if (!requestMethod.equals("POST", ignoreCase = true)) {
            return TelegramWebhookResult(405, accepted = false, detail = "Method not allowed.")
        }
        val expectedSecret = webhookSecret?.trim().orEmpty()
        if (expectedSecret.isBlank()) {
            return TelegramWebhookResult(503, accepted = false, detail = "Telegram webhook secret is not configured.")
        }
        if (secretTokenHeader?.trim().orEmpty() != expectedSecret) {
            return TelegramWebhookResult(403, accepted = false, detail = "Telegram webhook secret token mismatch.")
        }

        val update = try {
            mapper.readValue<TelegramUpdate>(requestBody)
        } catch (_: Exception) {
            return TelegramWebhookResult(400, accepted = false, detail = "Invalid Telegram update payload.")
        }
        val message = update.message ?: update.editedMessage
            ?: return TelegramWebhookResult(200, accepted = false, detail = "Unsupported Telegram update type.")
        val text = message.text?.trim().orEmpty()
        if (text.isBlank()) {
            return TelegramWebhookResult(200, accepted = false, detail = "Telegram message has no text content.")
        }

        if (config.requireDirectChat && !message.chat.type.equals("private", ignoreCase = true)) {
            return ignoreUnauthorized("Telegram channel is restricted to direct owner chats.")
        }
        if (config.ownerChatId.isNotBlank() && message.chat.id.toString() != config.ownerChatId.trim()) {
            return ignoreUnauthorized("Telegram chat is not allowlisted as the owner chat.")
        }
        val fromId = message.from?.id?.toString().orEmpty()
        if (config.ownerUserId.isNotBlank() && fromId != config.ownerUserId.trim()) {
            return ignoreUnauthorized("Telegram sender is not allowlisted as the owner.")
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
        ) ?: return TelegramWebhookResult(503, accepted = false, detail = "Failed to store Telegram message.")

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
            TelegramWebhookResult(202, accepted = true, detail = "Telegram message enqueued.")
        } else {
            TelegramWebhookResult(503, accepted = false, detail = "Telegram input queue is full.")
        }
    }

    private fun ignoreUnauthorized(detail: String): TelegramWebhookResult =
        if (config.dropUnauthorizedMessages) {
            TelegramWebhookResult(202, accepted = false, detail = detail)
        } else {
            TelegramWebhookResult(403, accepted = false, detail = detail)
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

    private data class TelegramUpdate(
        val message: TelegramMessage? = null,
        @field:JsonProperty("edited_message")
        val editedMessage: TelegramMessage? = null,
    )

    private data class TelegramMessage(
        val text: String? = null,
        val chat: TelegramChat,
        val from: TelegramUser? = null,
    )

    private data class TelegramChat(
        val id: Long,
        val type: String,
        val title: String? = null,
    )

    private data class TelegramUser(
        val id: Long,
        val username: String? = null,
        @field:JsonProperty("first_name")
        val firstName: String? = null,
        @field:JsonProperty("last_name")
        val lastName: String? = null,
    )

    companion object {
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
