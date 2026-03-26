package ai.neopsyke.integrations.telegram

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import ai.neopsyke.agent.cortex.motor.actions.ConversationDeliveryResult
import ai.neopsyke.agent.cortex.motor.actions.TelegramMessageSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class TelegramBotApiClient(
    private val botToken: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build(),
) : TelegramMessageSink {
    override suspend fun sendMessage(chatId: String, text: String): ConversationDeliveryResult =
        withContext(Dispatchers.IO) {
            if (botToken.isBlank()) {
                return@withContext ConversationDeliveryResult(
                    delivered = false,
                    detail = "Telegram bot token is not configured.",
                )
            }
            if (chatId.isBlank()) {
                return@withContext ConversationDeliveryResult(
                    delivered = false,
                    detail = "Telegram chat id is missing.",
                )
            }

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/bot$botToken/sendMessage")
                .post(
                    FormBody.Builder()
                        .add("chat_id", chatId)
                        .add("text", text)
                        .add("disable_web_page_preview", "true")
                        .build()
                )
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        logger.warn {
                            "Telegram sendMessage failed status=${response.code} chat_id=$chatId preview='${responseBody.take(120)}'"
                        }
                        return@withContext ConversationDeliveryResult(
                            delivered = false,
                            detail = "Telegram sendMessage failed (${response.code}).",
                        )
                    }
                    val parsed = mapper.readTree(responseBody)
                    if (parsed.path("ok").asBoolean(false)) {
                        return@withContext ConversationDeliveryResult(
                            delivered = true,
                            detail = "Telegram message delivered.",
                        )
                    }
                    logger.warn {
                        "Telegram sendMessage returned ok=false chat_id=$chatId description='${parsed.path("description").asText().take(120)}'"
                    }
                    ConversationDeliveryResult(
                        delivered = false,
                        detail = "Telegram sendMessage was rejected by the Bot API.",
                    )
                }
            } catch (ex: Exception) {
                logger.warn(ex) { "Telegram sendMessage call failed chat_id=$chatId." }
                ConversationDeliveryResult(
                    delivered = false,
                    detail = "Telegram delivery failed: ${ex.message ?: "request failed"}",
                )
            }
        }

    internal suspend fun deleteWebhook(dropPendingUpdates: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/bot$botToken/deleteWebhook")
                .post(
                    FormBody.Builder()
                        .add("drop_pending_updates", dropPendingUpdates.toString())
                        .build()
                )
                .build()
            callTelegramApi(request, operation = "deleteWebhook") != null
        }

    internal suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
        limit: Int = 10,
    ): List<TelegramUpdate> = withContext(Dispatchers.IO) {
        val timeout = timeoutSeconds.coerceAtLeast(1)
        val boundedLimit = limit.coerceIn(1, 100)
        val query = buildList {
            offset?.let { add("offset=${it}") }
            add("timeout=$timeout")
            add("limit=$boundedLimit")
        }.joinToString("&")
        val url = "${baseUrl.trimEnd('/')}/bot$botToken/getUpdates?$query"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val payload = callTelegramApi(request, operation = "getUpdates") ?: return@withContext emptyList()
        payload.path("result").takeIf { it.isArray }?.let { mapper.treeToValue(it, Array<TelegramUpdate>::class.java).toList() }
            ?: emptyList()
    }

    internal suspend fun setWebhook(
        url: String,
        secretToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/bot$botToken/setWebhook")
            .post(
                FormBody.Builder()
                    .add("url", url)
                    .add("secret_token", secretToken)
                    .build()
            )
            .build()
        callTelegramApi(request, operation = "setWebhook") != null
    }

    internal suspend fun getWebhookInfo(): TelegramWebhookInfo? =
        withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/bot$botToken/getWebhookInfo")
            .get()
            .build()
            val payload = callTelegramApi(request, operation = "getWebhookInfo") ?: return@withContext null
            payload.path("result").takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.treeToValue(it, TelegramWebhookInfo::class.java)
            }
        }

    private fun callTelegramApi(
        request: Request,
        operation: String,
    ): JsonNode? {
        if (botToken.isBlank()) {
            logger.warn { "Telegram $operation failed because bot token is not configured." }
            return null
        }
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    logger.warn {
                        "Telegram $operation failed status=${response.code} preview='${responseBody.take(160)}'"
                    }
                    return null
                }
                val parsed = mapper.readTree(responseBody)
                if (parsed.path("ok").asBoolean(false)) {
                    parsed
                } else {
                    logger.warn {
                        "Telegram $operation returned ok=false description='${parsed.path("description").asText().take(160)}'"
                    }
                    null
                }
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Telegram $operation call failed." }
            null
        }
    }

    data class TelegramWebhookInfo(
        val url: String? = null,
        @field:com.fasterxml.jackson.annotation.JsonProperty("pending_update_count")
        val pendingUpdateCount: Int? = null,
        @field:com.fasterxml.jackson.annotation.JsonProperty("last_error_message")
        val lastErrorMessage: String? = null,
    )

    companion object {
        private const val DEFAULT_BASE_URL: String = "https://api.telegram.org"
        private const val DEFAULT_TIMEOUT_SEC: Long = 45L
        private val mapper = TelegramJson.mapper
    }
}
