package ai.neopsyke.integrations.telegram

import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.admin.approvals.ApprovalRuntime
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.dashboard.DashboardStateStore

private val logger = KotlinLogging.logger {}

data class TelegramWebhookResult(
    val statusCode: Int,
    val accepted: Boolean,
    val detail: String,
)

class TelegramWebhookBridge(
    private val store: DashboardStateStore,
    private val sensoryInput: AsyncSignalSource,
    approvalRuntime: ApprovalRuntime? = null,
    private val config: TelegramChannelConfig,
    private val webhookSecret: String?,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    private val processor = TelegramUpdateProcessor(
        store = store,
        sensoryInput = sensoryInput,
        approvalRuntime = approvalRuntime,
        config = config,
        interlocutorResolver = interlocutorResolver,
    )

    fun setApprovalRuntime(runtime: ApprovalRuntime?) {
        processor.setApprovalRuntime(runtime)
    }

    fun webhookPath(): String = config.webhookPath

    fun handleWebhook(
        requestMethod: String,
        secretTokenHeader: String?,
        requestBody: String,
    ): TelegramWebhookResult {
        if (!config.enabled) {
            logger.warn { "Telegram webhook rejected reason_code=disabled detail='Telegram channel is disabled.'" }
            return TelegramWebhookResult(503, accepted = false, detail = "Telegram channel is disabled.")
        }
        if (!requestMethod.equals("POST", ignoreCase = true)) {
            logger.warn { "Telegram webhook rejected reason_code=method_not_allowed method=$requestMethod" }
            return TelegramWebhookResult(405, accepted = false, detail = "Method not allowed.")
        }
        val expectedSecret = webhookSecret?.trim().orEmpty()
        if (expectedSecret.isBlank()) {
            logger.warn { "Telegram webhook rejected reason_code=secret_missing detail='Telegram webhook secret is not configured.'" }
            return TelegramWebhookResult(503, accepted = false, detail = "Telegram webhook secret is not configured.")
        }
        if (secretTokenHeader?.trim().orEmpty() != expectedSecret) {
            logger.warn { "Telegram webhook rejected reason_code=secret_mismatch detail='Telegram webhook secret token mismatch.'" }
            return TelegramWebhookResult(403, accepted = false, detail = "Telegram webhook secret token mismatch.")
        }

        val update = try {
            mapper.readValue<TelegramUpdate>(requestBody)
        } catch (_: Exception) {
            logger.warn { "Telegram webhook rejected reason_code=invalid_payload detail='Invalid Telegram update payload.'" }
            return TelegramWebhookResult(400, accepted = false, detail = "Invalid Telegram update payload.")
        }
        val result = processor.handle(update)
        return if (result.accepted) {
            logger.info {
                "Telegram webhook accepted update_id=${result.updateId} chat_id=${result.chatId ?: "unknown"} from_user_id=${result.fromUserId ?: "unknown"}"
            }
            TelegramWebhookResult(202, accepted = true, detail = "Telegram message enqueued.")
        } else {
            val statusCode = when {
                result.detail.contains("allowlisted", ignoreCase = true) ||
                    result.detail.contains("restricted", ignoreCase = true) ->
                    if (config.dropUnauthorizedMessages) 202 else 403
                result.detail.contains("queue is full", ignoreCase = true) ||
                    result.detail.contains("Failed to store", ignoreCase = true) ->
                    503
                else -> 200
            }
            logger.warn {
                "Telegram webhook rejected update_id=${result.updateId} reason_code=${result.reasonCode} status=$statusCode chat_id=${result.chatId ?: "unknown"} from_user_id=${result.fromUserId ?: "unknown"} detail='${result.detail}'"
            }
            TelegramWebhookResult(statusCode, accepted = false, detail = result.detail)
        }
    }

    companion object {
        private val mapper = TelegramJson.mapper
    }
}
