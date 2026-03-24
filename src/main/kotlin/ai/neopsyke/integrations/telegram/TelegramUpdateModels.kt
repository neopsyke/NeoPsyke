package ai.neopsyke.integrations.telegram

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

internal data class TelegramUpdate(
    @field:JsonProperty("update_id")
    val updateId: Long = 0L,
    val message: TelegramMessage? = null,
    @field:JsonProperty("edited_message")
    val editedMessage: TelegramMessage? = null,
)

internal data class TelegramMessage(
    val text: String? = null,
    val chat: TelegramChat,
    val from: TelegramUser? = null,
)

internal data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String? = null,
)

internal data class TelegramUser(
    val id: Long,
    val username: String? = null,
    @field:JsonProperty("first_name")
    val firstName: String? = null,
    @field:JsonProperty("last_name")
    val lastName: String? = null,
)

internal object TelegramJson {
    val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
