package psyke.agent

import psyke.llm.ChatMessage
import psyke.llm.ChatRole
import kotlin.math.max

object TextSecurity {
    fun clamp(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars)

    fun clampToTokenBudget(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (estimateTokens(text) <= maxTokens) return text
        val charBudget = maxTokens * 4
        return clamp(text, charBudget)
    }

    fun preview(text: String, maxChars: Int = 80): String =
        clamp(text.replace(Regex("\\s+"), " ").trim(), maxChars)

    fun estimateTokens(text: String): Int = max(1, text.length / 4)

    fun trimMessagesToBudget(messages: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        if (messages.sumOf { estimateTokens(it.content) + 4 } <= maxTokens) {
            return messages
        }

        val leadingSystem = mutableListOf<ChatMessage>()
        var index = 0
        while (index < messages.size && messages[index].role == ChatRole.SYSTEM) {
            leadingSystem.add(messages[index])
            index += 1
        }

        val retainedTail = mutableListOf<ChatMessage>()
        var runningTotal = leadingSystem.sumOf { estimateTokens(it.content) + 4 }
        for (i in messages.lastIndex downTo index) {
            val candidate = messages[i]
            val candidateCost = estimateTokens(candidate.content) + 4
            if (runningTotal + candidateCost > maxTokens) {
                break
            }
            retainedTail.add(candidate)
            runningTotal += candidateCost
        }

        retainedTail.reverse()
        return leadingSystem + retainedTail
    }

    fun extractJsonObject(rawText: String): String {
        val cleaned = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object found in model response." }
        return cleaned.substring(start, end + 1)
    }
}
