package ai.neopsyke.llm

import com.fasterxml.jackson.databind.JsonNode

internal data class AssistantContentInfo(
    val text: String,
    val nodeType: String,
    val partCount: Int,
) {
    val trimmedText: String = text.trim()
    val trimmedChars: Int = trimmedText.length

    fun summary(): String =
        "content_type=$nodeType,content_parts=$partCount,text_chars=$trimmedChars"
}

internal fun extractAssistantContentInfo(contentNode: JsonNode?): AssistantContentInfo {
    if (contentNode == null || contentNode.isNull) {
        return AssistantContentInfo(text = "", nodeType = "null", partCount = 0)
    }
    if (contentNode.isTextual) {
        return AssistantContentInfo(text = contentNode.asText(), nodeType = "text", partCount = 1)
    }
    if (contentNode.isArray) {
        val text = contentNode
            .mapNotNull { part ->
                when {
                    part.isTextual -> part.asText()
                    part.isObject -> {
                        val type = part.path("type").asText()
                        when {
                            type.equals("text", ignoreCase = true) -> part.path("text").asText()
                            part.has("text") -> part.path("text").asText()
                            part.has("content") -> part.path("content").asText()
                            part.has("value") -> part.path("value").asText()
                            else -> null
                        }
                    }

                    else -> null
                }
            }
            .joinToString(separator = "")
        return AssistantContentInfo(text = text, nodeType = "array", partCount = contentNode.size())
    }
    if (contentNode.isObject) {
        val text = when {
            contentNode.has("text") -> contentNode.path("text").asText()
            contentNode.has("content") -> contentNode.path("content").asText()
            contentNode.has("value") -> contentNode.path("value").asText()
            else -> ""
        }
        return AssistantContentInfo(text = text, nodeType = "object", partCount = 1)
    }
    return AssistantContentInfo(text = contentNode.asText(), nodeType = "other", partCount = 1)
}
