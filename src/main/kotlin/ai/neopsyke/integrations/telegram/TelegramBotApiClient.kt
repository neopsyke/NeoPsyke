package ai.neopsyke.integrations.telegram

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import ai.neopsyke.agent.actions.ConversationDeliveryResult
import ai.neopsyke.agent.actions.TelegramMessageSink
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
                    val parsed: TelegramApiResponse = mapper.readValue(responseBody)
                    if (parsed.ok == true) {
                        return@withContext ConversationDeliveryResult(
                            delivered = true,
                            detail = "Telegram message delivered.",
                        )
                    }
                    logger.warn {
                        "Telegram sendMessage returned ok=false chat_id=$chatId description='${parsed.description.orEmpty().take(120)}'"
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

    private data class TelegramApiResponse(
        val ok: Boolean? = null,
        val description: String? = null,
    )

    companion object {
        private const val DEFAULT_BASE_URL: String = "https://api.telegram.org"
        private const val DEFAULT_TIMEOUT_SEC: Long = 20L
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
