package ai.neopsyke.integrations.telegram

import mu.KotlinLogging
import ai.neopsyke.agent.config.TelegramChannelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

private val logger = KotlinLogging.logger {}

internal class TelegramPollingBridge(
    private val scope: CoroutineScope,
    private val config: TelegramChannelConfig,
    private val processor: TelegramUpdateProcessor,
    private val deleteWebhook: suspend (Boolean) -> Boolean,
    private val fetchUpdates: suspend (Long?, Int) -> List<TelegramUpdate>,
) : Closeable {
    @Volatile
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            logger.info {
                "Telegram polling started poll_timeout_seconds=${config.pollTimeoutSeconds} retry_delay_ms=${config.pollRetryDelayMs} owner_chat_id=${config.ownerChatId.ifBlank { "unset" }} owner_user_id=${config.ownerUserId.ifBlank { "unset" }} require_direct_chat=${config.requireDirectChat}"
            }
            runCatching {
                val deleted = deleteWebhook(false)
                if (!deleted) {
                    logger.warn { "Telegram polling mode could not clear an existing webhook; getUpdates may remain empty." }
                } else {
                    logger.info { "Telegram polling cleared existing webhook state before starting getUpdates." }
                }
            }.onFailure { ex ->
                logger.warn(ex) { "Telegram polling mode failed while deleting webhook; continuing with getUpdates." }
            }

            var offset: Long? = null
            while (isActive) {
                try {
                    val updates = fetchUpdates(offset, config.pollTimeoutSeconds)
                    if (updates.isEmpty()) {
                        continue
                    }
                    logger.info {
                        "Telegram polling fetched updates count=${updates.size} first_update_id=${updates.minOfOrNull { it.updateId } ?: 0L} last_update_id=${updates.maxOfOrNull { it.updateId } ?: 0L}"
                    }
                    updates.sortedBy { it.updateId }.forEach { update ->
                        val disposition = processor.handle(update)
                        offset = update.updateId + 1
                        if (!disposition.accepted) {
                            logger.warn {
                                "Telegram polling skipped update_id=${update.updateId} reason_code=${disposition.reasonCode} chat_id=${disposition.chatId ?: "unknown"} from_user_id=${disposition.fromUserId ?: "unknown"} detail='${disposition.detail}'"
                            }
                        }
                    }
                } catch (ex: Exception) {
                    logger.warn(ex) { "Telegram polling iteration failed; retrying." }
                    delay(config.pollRetryDelayMs)
                }
            }
        }
    }

    override fun close() {
        job?.cancel()
        job = null
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            config: TelegramChannelConfig,
            apiClient: TelegramBotApiClient,
            processor: TelegramUpdateProcessor,
        ): TelegramPollingBridge = TelegramPollingBridge(
            scope = scope,
            config = config,
            processor = processor,
            deleteWebhook = { dropPendingUpdates -> apiClient.deleteWebhook(dropPendingUpdates) },
            fetchUpdates = { offset, timeoutSeconds -> apiClient.getUpdates(offset, timeoutSeconds) },
        )
    }
}
