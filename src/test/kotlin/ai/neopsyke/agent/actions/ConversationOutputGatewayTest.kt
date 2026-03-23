package ai.neopsyke.agent.actions

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.Interlocutor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationOutputGatewayTest {
    @Test
    fun `telegram conversations route contact output through telegram sink`() = runBlocking {
        var deliveredChatId: String? = null
        var deliveredText: String? = null
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = { error("fallback should not be used for telegram conversations") },
            telegramSink = TelegramMessageSink { chatId, text ->
                deliveredChatId = chatId
                deliveredText = text
                ConversationDeliveryResult(delivered = true, detail = "sent")
            },
        )

        val result = gateway.deliver(
            text = "Morning briefing ready",
            conversationContext = ConversationContext(
                sessionId = "telegram:1234",
                interlocutor = Interlocutor.named("Owner"),
                security = ConversationSecurityContexts.ownerDirect(
                    provider = "telegram",
                    channelId = "1234",
                    principalId = "owner-telegram",
                ),
            ),
        )

        assertTrue(result.delivered)
        assertEquals("1234", deliveredChatId)
        assertEquals("Morning briefing ready", deliveredText)
    }

    @Test
    fun `non telegram conversations fall back to local output`() = runBlocking {
        val outputs = mutableListOf<String>()
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = outputs::add,
            telegramSink = TelegramMessageSink { _, _ ->
                error("telegram sink should not be used for non-telegram conversations")
            },
        )

        val result = gateway.deliver(
            text = "hello",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("Owner"),
                security = ConversationSecurityContexts.ownerDirect(
                    provider = "dashboard",
                    channelId = "default",
                ),
            ),
        )

        assertTrue(result.delivered)
        assertEquals(listOf("ego> hello"), outputs)
    }

    @Test
    fun `telegram conversations fail closed when telegram delivery is not configured`() = runBlocking {
        val gateway = RoutedConversationOutputGateway(fallbackOutput = {})

        val result = gateway.deliver(
            text = "hello",
            conversationContext = ConversationContext(
                sessionId = "telegram:1234",
                interlocutor = Interlocutor.named("Owner"),
                security = ConversationSecurityContexts.ownerDirect(
                    provider = "telegram",
                    channelId = "1234",
                ),
            ),
        )

        assertFalse(result.delivered)
        assertEquals("Telegram delivery is not configured for this runtime.", result.detail)
    }
}
