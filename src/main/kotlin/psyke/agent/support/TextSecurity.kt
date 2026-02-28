package psyke.agent.support

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
        val candidates = buildList {
            fencedCodeBlockRegex.findAll(rawText).forEach { match ->
                val block = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (block.isNotBlank()) {
                    add(block)
                }
            }
            val trimmed = rawText.trim()
            if (trimmed.isNotBlank()) {
                add(trimmed)
            }
        }

        var firstBalancedCandidate: String? = null
        candidates.forEach { candidate ->
            findFirstValidJsonObject(candidate)?.let { return it }
            if (firstBalancedCandidate == null) {
                firstBalancedCandidate = findFirstBalancedJsonObject(candidate)
            }
        }
        firstBalancedCandidate?.let { return it }
        throw IllegalArgumentException("No JSON object found in model response.")
    }

    private fun findFirstValidJsonObject(text: String): String? {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = text.indexOf('{', startIndex = searchFrom)
            if (start < 0) {
                return null
            }
            val end = findMatchingClosingBrace(text, start)
            if (end >= 0) {
                val candidate = text.substring(start, end + 1).trim()
                if (isJsonObject(candidate)) {
                    return candidate
                }
            }
            searchFrom = start + 1
        }
        return null
    }

    private fun findFirstBalancedJsonObject(text: String): String? {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = text.indexOf('{', startIndex = searchFrom)
            if (start < 0) {
                return null
            }
            val end = findMatchingClosingBrace(text, start)
            if (end >= 0) {
                return text.substring(start, end + 1).trim()
            }
            searchFrom = start + 1
        }
        return null
    }

    private fun findMatchingClosingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                    if (depth < 0) {
                        return -1
                    }
                }
            }
        }
        return -1
    }

    private fun isJsonObject(candidate: String): Boolean {
        if (!candidate.startsWith('{') || !candidate.endsWith('}')) {
            return false
        }
        return try {
            jsonMapper.readTree(candidate).isObject
        } catch (_: Exception) {
            false
        }
    }

    private val fencedCodeBlockRegex = Regex(
        pattern = "```(?:[A-Za-z0-9_+.-]+)?\\s*(.*?)```",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val jsonMapper = jacksonObjectMapper()
}
