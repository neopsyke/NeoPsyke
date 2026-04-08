package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.agent.model.ConversationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConversationDeliveryResult(
    val delivered: Boolean,
    val detail: String,
)

fun interface TelegramMessageSink {
    suspend fun sendMessage(chatId: String, text: String): ConversationDeliveryResult
}

fun interface DashboardMessageSink {
    fun writeMessage(sessionId: String, text: String, source: String): ConversationDeliveryResult
}

interface ConversationOutputGateway {
    suspend fun deliver(text: String, conversationContext: ConversationContext): ConversationDeliveryResult
}

class RoutedConversationOutputGateway(
    private val fallbackOutput: (String) -> Unit,
    private val telegramSink: TelegramMessageSink? = null,
    private val dashboardSink: DashboardMessageSink? = null,
) : ConversationOutputGateway {

    @Volatile private var channelResolver: UserContactChannelResolver? = null

    fun setChannelResolver(resolver: UserContactChannelResolver) {
        channelResolver = resolver
    }

    override suspend fun deliver(
        text: String,
        conversationContext: ConversationContext,
    ): ConversationDeliveryResult {
        val provider = conversationContext.security.channel.provider.trim().lowercase()
        return when (provider) {
            "telegram" -> deliverViaTelegram(conversationContext.security.channel.channelId, text)
            "webapp" -> deliverViaDashboard(conversationContext.sessionId, text)
            else -> deliverViaResolver(text, conversationContext)
        }
    }

    private suspend fun deliverViaTelegram(channelId: String, text: String): ConversationDeliveryResult {
        val sink = telegramSink
            ?: return ConversationDeliveryResult(
                delivered = false,
                detail = "Telegram delivery is not configured for this runtime.",
            )
        return sink.sendMessage(channelId, text)
    }

    private fun deliverViaDashboard(sessionId: String, text: String): ConversationDeliveryResult {
        val sink = dashboardSink
            ?: return ConversationDeliveryResult(
                delivered = false,
                detail = "Dashboard delivery is not configured for this runtime.",
            )
        return sink.writeMessage(sessionId = sessionId, text = text, source = "contact_user")
    }

    private suspend fun deliverViaResolver(
        text: String,
        conversationContext: ConversationContext,
    ): ConversationDeliveryResult {
        val resolver = channelResolver
            ?: return withContext(Dispatchers.IO) {
                fallbackOutput("ego> $text")
                ConversationDeliveryResult(
                    delivered = true,
                    detail = "Message delivered via local output.",
                )
            }
        val resolution = resolver.resolve(conversationContext)
        val target = resolution.target
            ?: return ConversationDeliveryResult(
                delivered = false,
                detail = resolution.failureReason ?: "No delivery channel resolved.",
            )
        return when (target.provider) {
            "telegram" -> deliverViaTelegram(target.channelId, text)
            "webapp" -> deliverViaDashboard(target.sessionId, text)
            else -> ConversationDeliveryResult(
                delivered = false,
                detail = "Resolved to unsupported provider '${target.provider}'.",
            )
        }
    }
}
