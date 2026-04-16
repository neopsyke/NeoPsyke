package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.agent.config.TelegramChannelConfig
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
            override fun resolve(
                conversationContext: ConversationContext,
                preferredChannel: String?,
            ) = ChannelResolutionResult(
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
    fun `durable-work automation context routes through resolver using preferred channel hint`() = runBlocking {
        var deliveredChatId: String? = null
        var seenHint: String? = null
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = { error("fallback should not be used") },
            telegramSink = TelegramMessageSink { chatId, _ ->
                deliveredChatId = chatId
                ConversationDeliveryResult(delivered = true, detail = "sent")
            },
        )
        gateway.setChannelResolver(object : UserContactChannelResolver {
            override fun resolve(
                conversationContext: ConversationContext,
                preferredChannel: String?,
            ): ChannelResolutionResult {
                seenHint = preferredChannel
                return ChannelResolutionResult(
                    target = DeliveryTarget(
                        provider = "telegram",
                        sessionId = "telegram:owner",
                        channelId = "owner-chat",
                    ),
                    scope = "resolved_hint",
                )
            }
        })

        val baseSecurity = ConversationSecurityContexts.internalAutomation(
            provider = "durable-work-runtime",
            channelId = "",
        )
        val security = baseSecurity.copy(
            channel = baseSecurity.channel.copy(
                attributes = mapOf(ContactChannelPolicy.PREFERRED_CHANNEL_ATTRIBUTE to "telegram"),
            ),
        )
        val result = gateway.deliver(
            text = "Hamburg forecast: sunny.",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("durable-work-runtime"),
                security = security,
            ),
        )

        assertTrue(result.delivered)
        assertEquals("owner-chat", deliveredChatId)
        assertEquals("telegram", seenHint)
    }

    @Test
    fun `real resolver honors dashboard preferred hint using production channel keys`() = runBlocking {
        var sinkSessionId: String? = null
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = { error("fallback should not be used") },
            dashboardSink = DashboardMessageSink { sessionId, _, _ ->
                sinkSessionId = sessionId
                ConversationDeliveryResult(delivered = true, detail = "dashboard ok")
            },
        )
        val statusProvider = DefaultUserContactChannelStatusProvider(
            dashboardAvailability = DashboardAvailabilityCheck { sessionId ->
                sessionId == ConversationContext.DEFAULT_SESSION_ID
            },
            telegramConfig = TelegramChannelConfig(),
            telegramSink = null,
            telegramAckTracker = TelegramStartupAckTracker(),
        )
        gateway.setChannelResolver(
            DefaultUserContactChannelResolver(
                channelStatusProvider = statusProvider,
                channelPriority = listOf("telegram", "dashboard"),
                defaultChannel = "dashboard",
            )
        )

        val baseSecurity = ConversationSecurityContexts.internalAutomation(
            provider = "durable-work-runtime",
            channelId = "",
        )
        val security = baseSecurity.copy(
            channel = baseSecurity.channel.copy(
                attributes = mapOf(ContactChannelPolicy.PREFERRED_CHANNEL_ATTRIBUTE to "dashboard"),
            ),
        )

        val result = gateway.deliver(
            text = "Weather update",
            conversationContext = ConversationContext(
                sessionId = ConversationContext.DEFAULT_SESSION_ID,
                interlocutor = Interlocutor.named("durable-work-runtime"),
                security = security,
            ),
        )

        assertTrue(result.delivered)
        assertEquals(ConversationContext.DEFAULT_SESSION_ID, sinkSessionId)
    }

    @Test
    fun `non-DIRECT surface with trusted provider is not trusted with inbound channelId`() = runBlocking {
        // Regression: a synthesized durable-work context with provider=telegram
        // and a non-chat channelId must not be delivered directly; the resolver
        // has to decide the real chat id.
        var telegramCalledWith: String? = null
        val gateway = RoutedConversationOutputGateway(
            fallbackOutput = { error("fallback should not be used") },
            telegramSink = TelegramMessageSink { chatId, _ ->
                telegramCalledWith = chatId
                ConversationDeliveryResult(delivered = true, detail = "sent")
            },
        )
        gateway.setChannelResolver(object : UserContactChannelResolver {
            override fun resolve(
                conversationContext: ConversationContext,
                preferredChannel: String?,
            ) = ChannelResolutionResult(
                target = DeliveryTarget(
                    provider = "telegram",
                    sessionId = "telegram:owner",
                    channelId = "real-owner-chat-id",
                ),
                scope = "resolved_hint",
            )
        })

        gateway.deliver(
            text = "hello",
            conversationContext = ConversationContext(
                sessionId = "default",
                interlocutor = Interlocutor.named("durable-work-runtime"),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = "telegram",
                    channelId = "work:some-goal:step2",
                ),
            ),
        )

        assertEquals("real-owner-chat-id", telegramCalledWith)
    }

    @Test
    fun `resolver returning no target fails closed`() = runBlocking {
        val gateway = RoutedConversationOutputGateway(fallbackOutput = {})
        gateway.setChannelResolver(object : UserContactChannelResolver {
            override fun resolve(
                conversationContext: ConversationContext,
                preferredChannel: String?,
            ) = ChannelResolutionResult(
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
