package ai.neopsyke.agent.cortex.motor.actions

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
    fun `non telegram conversations fall back to local output when no resolver set`() = runBlocking {
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

    @Test
    fun `webapp conversations route through dashboard sink`() = runBlocking {
        var sinkSessionId: String? = null
        var sinkText: String? = null
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = { error("fallback should not be used") },
            dashboardSink = DashboardMessageSink { sessionId, text, _ ->
                sinkSessionId = sessionId
                sinkText = text
                ConversationDeliveryResult(delivered = true, detail = "dashboard ok")
            },
        )

        val result = gateway.deliver(
            text = "Weather update",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("Owner"),
                security = ConversationSecurityContexts.ownerDirect(
                    provider = "webapp",
                    channelId = "default",
                ),
            ),
        )

        assertTrue(result.delivered)
        assertEquals("default", sinkSessionId)
        assertEquals("Weather update", sinkText)
    }

    @Test
    fun `webapp conversations fail closed when dashboard sink is not configured`() = runBlocking {
        val gateway = RoutedConversationOutputGateway(fallbackOutput = {})

        val result = gateway.deliver(
            text = "hello",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("Owner"),
                security = ConversationSecurityContexts.ownerDirect(
                    provider = "webapp",
                    channelId = "default",
                ),
            ),
        )

        assertFalse(result.delivered)
        assertEquals("Dashboard delivery is not configured for this runtime.", result.detail)
    }

    @Test
    fun `unknown provider with resolver routes to best available channel`() = runBlocking {
        var sinkSessionId: String? = null
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = { error("fallback should not be used") },
            dashboardSink = DashboardMessageSink { sessionId, text, _ ->
                sinkSessionId = sessionId
                ConversationDeliveryResult(delivered = true, detail = "dashboard ok")
            },
        )
        gateway.setChannelResolver(object : UserContactChannelResolver {
            override fun resolve(conversationContext: ConversationContext) = ChannelResolutionResult(
                target = DeliveryTarget(provider = "webapp", sessionId = "default", channelId = "default"),
                scope = "resolved_priority",
            )
        })

        val result = gateway.deliver(
            text = "Goal result",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("goal-runtime"),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = "goal-runtime",
                    channelId = "default",
                ),
            ),
        )

        assertTrue(result.delivered)
        assertEquals("default", sinkSessionId)
    }

    @Test
    fun `resolver returning no target fails closed`() = runBlocking {
        val gateway = RoutedConversationOutputGateway(fallbackOutput = {})
        gateway.setChannelResolver(object : UserContactChannelResolver {
            override fun resolve(conversationContext: ConversationContext) = ChannelResolutionResult(
                target = null,
                scope = "unresolved",
                failureReason = "No channels available",
            )
        })

        val result = gateway.deliver(
            text = "hello",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("goal-runtime"),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = "goal-runtime",
                    channelId = "default",
                ),
            ),
        )

        assertFalse(result.delivered)
        assertEquals("No channels available", result.detail)
    }
}
