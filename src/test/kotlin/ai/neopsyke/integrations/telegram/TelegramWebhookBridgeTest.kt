package ai.neopsyke.integrations.telegram

import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.dashboard.DashboardStateStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramWebhookBridgeTest {
    @Test
    fun `accepts owner direct message and enqueues trusted telegram conversation`() = runBlocking {
        DashboardStateStore().use { store ->
            AsyncSignalSource(includeStdin = false).use { sensory ->
                val bridge = TelegramWebhookBridge(
                    store = store,
                    sensoryInput = sensory,
                    config = ownerConfig(),
                    webhookSecret = "secret-token",
                )

                val result = bridge.handleWebhook(
                    requestMethod = "POST",
                    secretTokenHeader = "secret-token",
                    requestBody = ownerMessagePayload(),
                )

                assertEquals(202, result.statusCode)
                assertTrue(result.accepted)
                assertTrue(store.hasChatSession("telegram:1234"))
                val sessionJson = store.chatSessionJson("telegram:1234")
                assertNotNull(sessionJson)
                assertTrue(sessionJson.contains("Need the morning briefing"))

                val signal = sensory.nextSignal()
                val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
                assertEquals("telegram:1234", stimulus.conversationContext.sessionId)
                assertEquals("telegram", stimulus.conversationContext.security.channel.provider)
                assertEquals("1234", stimulus.conversationContext.security.channel.channelId)
                assertEquals(PrincipalRole.OWNER, stimulus.conversationContext.security.principal.role)
                assertEquals(ai.neopsyke.agent.model.PolicyScope.DEFAULT, stimulus.conversationContext.security.policyScope)
                assertEquals("Need the morning briefing", stimulus.content)
            }
        }
    }

    @Test
    fun `rejects webhook when secret header mismatches`() {
        DashboardStateStore().use { store ->
            AsyncSignalSource(includeStdin = false).use { sensory ->
                val bridge = TelegramWebhookBridge(
                    store = store,
                    sensoryInput = sensory,
                    config = ownerConfig(),
                    webhookSecret = "secret-token",
                )

                val result = bridge.handleWebhook(
                    requestMethod = "POST",
                    secretTokenHeader = "wrong-token",
                    requestBody = ownerMessagePayload(),
                )

                assertEquals(403, result.statusCode)
                assertFalse(result.accepted)
                assertFalse(store.hasChatSession("telegram:1234"))
            }
        }
    }

    @Test
    fun `drops unauthorized sender when owner filtering is enabled`() = runBlocking {
        DashboardStateStore().use { store ->
            AsyncSignalSource(includeStdin = false).use { sensory ->
                val bridge = TelegramWebhookBridge(
                    store = store,
                    sensoryInput = sensory,
                    config = ownerConfig(dropUnauthorizedMessages = true),
                    webhookSecret = "secret-token",
                )

                val result = bridge.handleWebhook(
                    requestMethod = "POST",
                    secretTokenHeader = "secret-token",
                    requestBody = ownerMessagePayload(fromUserId = 99L),
                )

                assertEquals(202, result.statusCode)
                assertFalse(result.accepted)
                assertFalse(store.hasChatSession("telegram:1234"))
                assertIs<CognitiveSignal.NoStimulus>(sensory.nextSignal())
            }
        }
    }

    @Test
    fun `rejects group chats when direct owner mode is required`() = runBlocking {
        DashboardStateStore().use { store ->
            AsyncSignalSource(includeStdin = false).use { sensory ->
                val bridge = TelegramWebhookBridge(
                    store = store,
                    sensoryInput = sensory,
                    config = ownerConfig(),
                    webhookSecret = "secret-token",
                )

                val result = bridge.handleWebhook(
                    requestMethod = "POST",
                    secretTokenHeader = "secret-token",
                    requestBody = ownerMessagePayload(chatType = "group"),
                )

                assertEquals(403, result.statusCode)
                assertFalse(result.accepted)
                assertFalse(store.hasChatSession("telegram:1234"))
                assertIs<CognitiveSignal.NoStimulus>(sensory.nextSignal())
            }
        }
    }

    private fun ownerConfig(dropUnauthorizedMessages: Boolean = false): TelegramChannelConfig =
        TelegramChannelConfig(
            enabled = true,
            webhookPath = "/api/channels/telegram/webhook",
            ownerChatId = "1234",
            ownerUserId = "42",
            botTokenHandle = "TELEGRAM_BOT_TOKEN",
            webhookSecretHandle = "TELEGRAM_WEBHOOK_SECRET",
            policyScope = ai.neopsyke.agent.model.PolicyScope.DEFAULT,
            sessionIdPrefix = "telegram",
            requireDirectChat = true,
            dropUnauthorizedMessages = dropUnauthorizedMessages,
        )

    private fun ownerMessagePayload(
        fromUserId: Long = 42L,
        chatType: String = "private",
    ): String =
        """
        {
          "update_id": 10000,
          "message": {
            "message_id": 1365,
            "from": {
              "id": $fromUserId,
              "is_bot": false,
              "first_name": "Victor",
              "last_name": "Toral",
              "username": "victor"
            },
            "chat": {
              "id": 1234,
              "first_name": "Victor",
              "last_name": "Toral",
              "username": "victor",
              "type": "$chatType"
            },
            "date": 1710000000,
            "text": "Need the morning briefing"
          }
        }
        """.trimIndent()
}
