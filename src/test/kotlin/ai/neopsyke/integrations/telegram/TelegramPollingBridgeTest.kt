package ai.neopsyke.integrations.telegram

import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.config.TelegramIngressMode
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.dashboard.DashboardStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramPollingBridgeTest {
    @Test
    fun `polling bridge clears webhook and enqueues owner direct message`() = runBlocking {
        DashboardStateStore().use { store ->
            AsyncSignalSource(includeStdin = false).use { sensory ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val deleteCalls = AtomicInteger(0)
                val fetchCalls = AtomicInteger(0)
                val bridge = TelegramPollingBridge(
                    scope = scope,
                    config = ownerConfig(),
                    processor = TelegramUpdateProcessor(
                        store = store,
                        sensoryInput = sensory,
                        config = ownerConfig(),
                    ),
                    deleteWebhook = {
                        deleteCalls.incrementAndGet()
                        true
                    },
                    fetchUpdates = { _, _ ->
                        when (fetchCalls.incrementAndGet()) {
                            1 -> listOf(ownerUpdate())
                            else -> {
                                delay(25)
                                emptyList()
                            }
                        }
                    },
                )
                try {
                    bridge.start()
                    val signal = withTimeout(1_000) {
                        while (true) {
                            when (val next = sensory.nextSignal()) {
                                is CognitiveSignal.StimulusReceived -> return@withTimeout next
                                else -> delay(10)
                            }
                        }
                    }
                    val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
                    assertEquals(1, deleteCalls.get())
                    assertTrue(store.hasChatSession("telegram:1234"))
                    assertEquals("Need the morning briefing", stimulus.content)
                    assertEquals("telegram:1234", stimulus.conversationContext.sessionId)
                } finally {
                    bridge.close()
                    scope.cancel()
                }
            }
        }
    }

    private fun ownerConfig(): TelegramChannelConfig =
        TelegramChannelConfig(
            enabled = true,
            mode = TelegramIngressMode.POLLING,
            ownerChatId = "1234",
            ownerUserId = "42",
            requireDirectChat = true,
            pollTimeoutSeconds = 1,
            pollRetryDelayMs = 10,
        )

    private fun ownerUpdate(): TelegramUpdate =
        TelegramUpdate(
            updateId = 101L,
            message = TelegramMessage(
                text = "Need the morning briefing",
                chat = TelegramChat(
                    id = 1234L,
                    type = "private",
                ),
                from = TelegramUser(
                    id = 42L,
                    firstName = "Victor",
                    lastName = "Toral",
                    username = "victor",
                ),
            ),
        )
}
