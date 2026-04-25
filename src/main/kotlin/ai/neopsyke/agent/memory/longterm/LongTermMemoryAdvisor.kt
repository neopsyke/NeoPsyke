package ai.neopsyke.agent.memory.longterm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.support.ContextBlockCompressor
import ai.neopsyke.agent.support.RetryPolicy
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.prompt.PromptCatalog
import java.util.Locale

private val logger = KotlinLogging.logger {}

data class LongTermMemoryAssessmentContext(
    val trigger: String,
    val deliberation: DeliberationState,
    val recentDialogue: List<DialogueTurn>,
    val shortTermContextSummary: String,
    val longTermMemoryRecall: String,
    val metaGuidance: String,
    val latestActionType: ActionType? = null,
    val latestActionOutcome: String? = null,
    val subject: LongTermMemorySubject = LongTermMemorySubject.USER,
)

data class LongTermMemoryAssessmentDecision(
    val shouldSave: Boolean,
    val summary: String,
    val confidence: Double,
    val reason: String,
    val tags: List<String> = emptyList(),
    val parseFallback: Boolean = false,
)

enum class LongTermMemorySubject {
    USER,
    SELF,
}

interface LongTermMemoryAdvisor {
    val enabled: Boolean
        get() = true

    fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision
}

object NoopLongTermMemoryAdvisor : LongTermMemoryAdvisor {
    override val enabled: Boolean = false

    override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
        LongTermMemoryAssessmentDecision(
            shouldSave = false,
            summary = "",
            confidence = 0.0,
            reason = "long-term memory assessment disabled"
        )
}

class LlmLongTermMemoryAdvisor(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : LongTermMemoryAdvisor {
    override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision {
        val promptPayload = buildPromptPayload(context)
        val prompt = buildPrompt(context, promptPayload)
        val messages = prompt.sections.map { ChatMessage(role = it.role, content = it.content) }
        val schema = promptCatalog.responseFormat("long-term-memory-assessment")
        emitCompressionDiagnosticsIfNeeded(promptPayload)
        val completionTokenBudget = resolveCompletionTokenBudget(messages)
        var response: ai.neopsyke.llm.ChatCompletion? = null
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = completionTokenBudget,
                        responseFormat = schema.format,
                        metadata = promptCatalog.metadata(
                            ChatCallMetadata(
                                actor = "ego",
                                callSite = "long_term_memory_assessment"
                            ),
                            prompt,
                            schema,
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    logger.warn(ex) { "LongTermMemoryAdvisor call failed (attempt $attempt/$retryAttempts); retrying." }
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "LongTermMemoryAdvisor call failed after $retryAttempts attempts." }
            return LongTermMemoryAssessmentDecision(
                shouldSave = false,
                summary = "",
                confidence = 0.0,
                reason = "long-term memory advisor unavailable",
                parseFallback = true
            )
        }
        return parseResponse(response.content, context)
    }

    private fun resolveCompletionTokenBudget(@Suppress("UNUSED_PARAMETER") messages: List<ChatMessage>): Int =
        config.memory.longTermMemoryMaxTokens

    private fun buildPromptPayload(context: LongTermMemoryAssessmentContext): MemoryAdvisorPromptPayload {
        val dialogueBlock = context.recentDialogue
            .takeLast(12)
            .joinToString(separator = "\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, 160)}"
            }
            .ifBlank { "none" }
        val shortTermContextSummaryBlock = context.shortTermContextSummary.ifBlank { "none" }
        val longTermRecallBlock = context.longTermMemoryRecall.ifBlank { "none" }
        val guidanceBlock = context.metaGuidance.ifBlank { "none" }
        if (!config.memory.longTermMemoryPromptCompressionEnabled) {
            return MemoryAdvisorPromptPayload(
                dialogue = dialogueBlock,
                longTermRecall = longTermRecallBlock,
                shortTermContextSummary = shortTermContextSummaryBlock,
                guidance = guidanceBlock
            )
        }
        val dialogueCompression = ContextBlockCompressor.compress(
            text = dialogueBlock,
            maxChars = config.memory.longTermMemoryPromptDialogueMaxChars
        )
        val recallCompression = ContextBlockCompressor.compress(
            text = longTermRecallBlock,
            maxChars = config.memory.longTermMemoryPromptRecallMaxChars
        )
        return MemoryAdvisorPromptPayload(
            dialogue = dialogueCompression.text.ifBlank { "none" },
            longTermRecall = recallCompression.text.ifBlank { "none" },
            shortTermContextSummary = shortTermContextSummaryBlock,
            guidance = guidanceBlock,
            dialogueCompression = dialogueCompression,
            recallCompression = recallCompression
        )
    }

    private fun emitCompressionDiagnosticsIfNeeded(payload: MemoryAdvisorPromptPayload) {
        val dialogue = payload.dialogueCompression
        val recall = payload.recallCompression
        val dialogueCompressed = dialogue?.compressed == true
        val recallCompressed = recall?.compressed == true
        if (!dialogueCompressed && !recallCompressed) {
            return
        }
        instrumentation.emit(
            AgentEvent(
                type = "memory_advisor_prompt_compressed",
                data = mapOf(
                    "dialogue_compressed" to dialogueCompressed,
                    "dialogue_original_chars" to (dialogue?.originalChars ?: payload.dialogue.length),
                    "dialogue_final_chars" to (dialogue?.compressedChars ?: payload.dialogue.length),
                    "recall_compressed" to recallCompressed,
                    "recall_original_chars" to (recall?.originalChars ?: payload.longTermRecall.length),
                    "recall_final_chars" to (recall?.compressedChars ?: payload.longTermRecall.length),
                )
            )
        )
        instrumentation.emit(
            AgentEvent(
                type = "warning",
                data = mapOf(
                    "message" to "Memory advisor prompt compressed long dialogue/recall blocks."
                )
            )
        )
    }

    private fun buildPrompt(
        context: LongTermMemoryAssessmentContext,
        promptPayload: MemoryAdvisorPromptPayload,
    ): PromptCatalog.RenderedPrompt {
        val actionType = context.latestActionType?.name?.lowercase() ?: "none"
        val actionOutcome = context.latestActionOutcome?.let { TextSecurity.preview(it, 220) } ?: "none"
        val shortTermContextSummary = promptPayload.shortTermContextSummary
        val longTermRecall = promptPayload.longTermRecall
        val guidance = promptPayload.guidance
        val dialogue = promptPayload.dialogue
        val d = context.deliberation
        val memorySubject = context.subject.name.lowercase(Locale.ROOT)
        val subjectDescription = when (context.subject) {
            LongTermMemorySubject.USER ->
                "The candidate memory is about the user, their preferences, goals, or durable facts relevant to helping them."
            LongTermMemorySubject.SELF ->
                "The candidate memory is about the agent's own internal drive, reflection, learning interest, or durable self-observation."
        }
        return promptCatalog.renderSections(
            "memory/long-term-advisor",
            mapOf(
                "trigger" to context.trigger,
                "step_index" to d.stepIndex.toString(),
                "decision_pressure" to String.format(Locale.ROOT, "%.3f", d.decisionPressure),
                "stale_streak" to d.staleStreak.toString(),
                "progress_score" to String.format(Locale.ROOT, "%.3f", d.progressScore),
                "denial_count" to d.denialCount.toString(),
                "steps_since_new_evidence" to d.stepsSinceNewEvidence.toString(),
                "repeat_signature_hits" to d.repeatSignatureHits.toString(),
                "noop_streak" to d.noopStreak.toString(),
                "latest_action_type" to actionType,
                "latest_action_outcome" to actionOutcome,
                "memory_subject" to memorySubject,
                "subject_description" to subjectDescription,
                "meta_guidance" to guidance,
                "long_term_memory_recall" to longTermRecall,
                "short_term_context_summary" to shortTermContextSummary,
                "recent_dialogue" to dialogue,
            )
        )
    }

    private fun parseResponse(
        raw: String,
        context: LongTermMemoryAssessmentContext,
    ): LongTermMemoryAssessmentDecision {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<LongTermMemoryAssessmentPayload>(json)
            if (payload.save == null) {
                logger.warn {
                    "Long-term memory assessment response missing required 'save' field. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
                }
                return LongTermMemoryAssessmentDecision(
                    shouldSave = false,
                    summary = "",
                    confidence = 0.0,
                    reason = "parse fallback: missing save field",
                    parseFallback = true
                )
            }
            val shouldSave = payload.save == true
            val summary = if (shouldSave) {
                normalizeSummaryPerspective(payload.summary?.trim().orEmpty())
            } else {
                ""
            }
            LongTermMemoryAssessmentDecision(
                shouldSave = shouldSave && summary.isNotBlank(),
                summary = summary,
                confidence = payload.confidence?.coerceIn(0.0, 1.0) ?: 0.5,
                reason = normalizeReasonForSubject(
                    rawReason = payload.reason?.trim().orEmpty().ifBlank { "no reason" },
                    subject = context.subject
                ),
                tags = normalizeTagsForSubject(
                    rawTags = payload.tags.orEmpty(),
                    subject = context.subject
                ),
                parseFallback = false
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse long-term memory assessment response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            LongTermMemoryAssessmentDecision(
                shouldSave = false,
                summary = "",
                confidence = 0.0,
                reason = "parse fallback",
                parseFallback = true
            )
        }
    }

    private fun normalizeSummaryPerspective(rawSummary: String): String {
        val summary = rawSummary.trim()
        if (summary.isBlank()) return ""
        // Strip metacognitive verb prefixes ("I learned that ...", "I should remember ...", etc.)
        // but preserve genuine first-person identity/state statements ("I am X", "My name is Y").
        val stripped = METACOGNITIVE_PREFIX_REGEX.replaceFirst(summary, "").trimStart()
        val bare = stripped.ifBlank { summary }
        // Capitalize first letter if stripping lowered it.
        val result = bare.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return TextSecurity.clamp(result, config.memory.longTermMemoryMaxSummaryChars)
    }

    private fun normalizeReasonForSubject(
        rawReason: String,
        subject: LongTermMemorySubject,
    ): String {
        val clamped = TextSecurity.clamp(rawReason.trim(), 140)
        if (subject != LongTermMemorySubject.SELF) {
            return clamped
        }
        val replaced = clamped
            .replace(Regex("\\b[Tt]he user\\b"), "the agent")
            .replace(Regex("\\b[Uu]ser\\b"), "agent")
            .replace(Regex("\\bstable preference\\b", RegexOption.IGNORE_CASE), "stable self preference")
        val normalized = when {
            replaced.startsWith("I ", ignoreCase = true) -> replaced
            replaced.startsWith("internal", ignoreCase = true) -> replaced
            replaced.startsWith("agent", ignoreCase = true) -> replaced.replaceFirstChar { it.uppercase() }
            else -> "Internal self-observation: $replaced"
        }
        return TextSecurity.clamp(normalized, 140)
    }

    private fun normalizeTagsForSubject(
        rawTags: List<String>,
        subject: LongTermMemorySubject,
    ): List<String> {
        val normalized = rawTags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { tag ->
                if (subject != LongTermMemorySubject.SELF) {
                    tag
                } else {
                    when (tag.lowercase(Locale.ROOT)) {
                        "user preference" -> "self preference"
                        "user_preference" -> "self_preference"
                        else -> tag
                    }
                }
            }
            .take(6)
        return normalized
    }

    private data class LongTermMemoryAssessmentPayload(
        val save: Boolean? = null,
        val summary: String? = null,
        val confidence: Double? = null,
        val reason: String? = null,
        val tags: List<String>? = null,
    )

    private data class MemoryAdvisorPromptPayload(
        val dialogue: String,
        val longTermRecall: String,
        val shortTermContextSummary: String,
        val guidance: String,
        val dialogueCompression: ContextBlockCompressor.Result? = null,
        val recallCompression: ContextBlockCompressor.Result? = null,
    )

    private companion object {
        /**
         * Matches metacognitive verb prefixes that wrap an underlying fact.
         * Examples stripped: "I learned that ...", "I should remember ...", "I'm keeping in mind that ...".
         * Does NOT match genuine first-person identity/state: "I am ...", "I like ...", "My name is ...".
         */
        private val METACOGNITIVE_PREFIX_REGEX = Regex(
            pattern = """^(?:I\s+(?:learned|should\s+remember|need\s+to\s+(?:remember|keep\s+in\s+mind)|want\s+to\s+remember|noted|discovered|know)|I'm\s+keeping\s+in\s+mind)\b[:\s]*(?:that\s+)?""",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
