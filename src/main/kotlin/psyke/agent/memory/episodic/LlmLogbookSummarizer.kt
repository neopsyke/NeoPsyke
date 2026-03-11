package psyke.agent.memory.episodic

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

/**
 * LLM-based keyword extraction and input summarization for logbook entries.
 * Falls back to [DeterministicLogbookSummarizer] on parse failure or LLM error.
 */
class LlmLogbookSummarizer(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val fallback: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
) : LogbookSummarizer {

    override fun extractKeywords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = callLlm(keywordExtractionMessages(text)) ?: return fallback.extractKeywords(text)
        return parseKeywords(result) ?: fallback.extractKeywords(text)
    }

    override fun summarizeInput(content: String, maxChars: Int): String {
        if (content.isBlank()) return "User: "
        val result = callLlm(summarizationMessages(content, maxChars)) ?: return fallback.summarizeInput(content, maxChars)
        return parseSummary(result, maxChars) ?: fallback.summarizeInput(content, maxChars)
    }

    private fun callLlm(messages: List<ChatMessage>): String? {
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.llmRetryAttempts)
        var lastError: Exception? = null
        for (attempt in 1..retryAttempts) {
            try {
                val response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = LOGBOOK_SUMMARIZER_MAX_TOKENS,
                        metadata = ChatCallMetadata(actor = "ego", callSite = "logbook_summarization"),
                    ),
                )
                return response.content
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    logger.warn(ex) { "Logbook summarizer call failed (attempt $attempt/$retryAttempts); retrying." }
                }
            }
        }
        logger.warn(lastError) { "Logbook summarizer call failed after $retryAttempts attempts." }
        return null
    }

    private fun keywordExtractionMessages(text: String): List<ChatMessage> {
        val capped = TextSecurity.preview(text, MAX_INPUT_PREVIEW_CHARS)
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                Extract 3-10 keywords from the following text. Return JSON: {"keywords":["k1","k2",...]}.
                Only include meaningful content words. Omit stopwords, articles, prepositions.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = "Text: $capped"
            ),
        )
    }

    private fun summarizationMessages(content: String, maxChars: Int): List<ChatMessage> {
        val capped = TextSecurity.preview(content, MAX_INPUT_PREVIEW_CHARS)
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                Summarize the following user input in at most $maxChars characters for an episodic logbook.
                Start with "User: ". Be concise but preserve intent and key entities.
                Return JSON: {"summary":"User: ..."}.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = "Input: $capped"
            ),
        )
    }

    private fun parseKeywords(raw: String): List<String>? {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<KeywordPayload>(json)
            val keywords = payload.keywords
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.take(config.logbook.maxKeywordsPerEntry)
            if (keywords.isNullOrEmpty()) null else keywords
        } catch (ex: Exception) {
            logger.debug(ex) { "Failed to parse keyword extraction response. preview='${TextSecurity.preview(raw, 80)}'" }
            null
        }
    }

    private fun parseSummary(raw: String, maxChars: Int): String? {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<SummaryPayload>(json)
            val summary = payload.summary?.trim()
            if (summary.isNullOrBlank()) return null
            val prefixed = if (summary.startsWith("User: ")) summary else "User: $summary"
            TextSecurity.clamp(prefixed, maxChars + USER_PREFIX_LENGTH)
        } catch (ex: Exception) {
            logger.debug(ex) { "Failed to parse summarization response. preview='${TextSecurity.preview(raw, 80)}'" }
            null
        }
    }

    private data class KeywordPayload(
        val keywords: List<String>? = null,
    )

    private data class SummaryPayload(
        val summary: String? = null,
    )

    private companion object {
        const val LOGBOOK_SUMMARIZER_MAX_TOKENS: Int = 150
        const val MAX_INPUT_PREVIEW_CHARS: Int = 800
        const val USER_PREFIX_LENGTH: Int = 6

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
