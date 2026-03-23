package ai.neopsyke.agent.actions

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

interface ConversationOutputGateway {
    suspend fun deliver(text: String, conversationContext: ConversationContext): ConversationDeliveryResult
}

class RoutedConversationOutputGateway(
    private val fallbackOutput: (String) -> Unit,
    private val telegramSink: TelegramMessageSink? = null,
) : ConversationOutputGateway {
    override suspend fun deliver(
        text: String,
        conversationContext: ConversationContext,
    ): ConversationDeliveryResult {
        val provider = conversationContext.security.channel.provider.trim().lowercase()
        return when (provider) {
            "telegram" -> {
                val sink = telegramSink
                    ?: return ConversationDeliveryResult(
                        delivered = false,
                        detail = "Telegram delivery is not configured for this runtime.",
                    )
                sink.sendMessage(conversationContext.security.channel.channelId, text)
            }

            else -> withContext(Dispatchers.IO) {
                fallbackOutput("ego> $text")
                ConversationDeliveryResult(
                    delivered = true,
                    detail = "Message delivered via local output.",
                )
            }
        }
    }
}
