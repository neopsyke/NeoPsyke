package ai.neopsyke.agent.memory.longterm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.support.RetryPolicy
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.prompt.PromptCatalog

private val logger = KotlinLogging.logger {}

/**
 * LLM-based keyword extraction and input summarization for logbook entries.
 * Falls back to [DeterministicLogbookSummarizer] on parse failure or LLM error.
 */
class LlmLogbookSummarizer(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val fallback: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : LogbookSummarizer {

    override fun extractKeywords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val prompt = keywordExtractionPrompt(text)
        val schema = promptCatalog.responseFormat("logbook-keyword-extraction")
        val result = callLlm(
            prompt = prompt,
            schema = schema,
            callSite = "logbook_keyword_extraction",
        ) ?: return fallback.extractKeywords(text)
        return parseKeywords(result) ?: fallback.extractKeywords(text)
    }

    override fun summarizeInput(content: String, maxChars: Int): String {
        if (content.isBlank()) return "User: "
        val prompt = summarizationPrompt(content, maxChars)
        val schema = promptCatalog.responseFormat("logbook-input-summary")
        val result = callLlm(
            prompt = prompt,
            schema = schema,
            callSite = "logbook_input_summary",
        ) ?: return fallback.summarizeInput(content, maxChars)
        return parseSummary(result, maxChars) ?: fallback.summarizeInput(content, maxChars)
    }

    private fun callLlm(
        prompt: PromptCatalog.RenderedPrompt,
        schema: PromptCatalog.RenderedSchema,
        callSite: String,
    ): String? {
        val messages = prompt.sections.map { ChatMessage(role = it.role, content = it.content) }
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.llmRetryAttempts)
        var lastError: Exception? = null
        for (attempt in 1..retryAttempts) {
            try {
                val response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = LOGBOOK_SUMMARIZER_MAX_TOKENS,
                        responseFormat = schema.format,
                        metadata = promptCatalog.metadata(
                            ChatCallMetadata(actor = "ego", callSite = callSite),
                            prompt,
                            schema,
                        ),
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

    private fun keywordExtractionPrompt(text: String): PromptCatalog.RenderedPrompt {
        val capped = TextSecurity.preview(text, MAX_INPUT_PREVIEW_CHARS)
        return promptCatalog.renderSections(
            "memory/logbook-keyword-extraction",
            mapOf("text" to capped),
        )
    }

    private fun summarizationPrompt(content: String, maxChars: Int): PromptCatalog.RenderedPrompt {
        val capped = TextSecurity.preview(content, MAX_INPUT_PREVIEW_CHARS)
        return promptCatalog.renderSections(
            "memory/logbook-input-summary",
            mapOf(
                "max_chars" to maxChars.toString(),
                "input" to capped,
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
