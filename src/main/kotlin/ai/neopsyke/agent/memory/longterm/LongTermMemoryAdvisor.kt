package ai.neopsyke.agent.memory.longterm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.support.AdaptiveCompletionBudget
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
import ai.neopsyke.llm.ChatRole
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
)

data class LongTermMemoryAssessmentDecision(
    val shouldSave: Boolean,
    val summary: String,
    val confidence: Double,
    val reason: String,
    val tags: List<String> = emptyList(),
    val parseFallback: Boolean = false,
)

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
    private val modelTokenWeight: Double = DEFAULT_MODEL_TOKEN_WEIGHT,
    private val modelContextWindow: Int? = null,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : LongTermMemoryAdvisor {
    override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision {
        val promptPayload = buildPromptPayload(context)
        val messages = buildMessages(context, promptPayload)
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
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = "long_term_memory_assessment"
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
        return parseResponse(response.content)
    }

    private fun resolveCompletionTokenBudget(messages: List<ChatMessage>): Int {
        val baseBudget = config.memory.longTermMemoryMaxTokens
        if (!config.memory.longTermMemoryDynamicCompletionEnabled) {
            return baseBudget
        }
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            request = AdaptiveCompletionBudget.Request(
                messages = messages,
                baseMaxTokens = baseBudget,
                hardMaxTokens = config.memory.longTermMemoryDynamicCompletionHardMaxTokens,
                promptToCompletionRatio = config.memory.longTermMemoryDynamicPromptToCompletionRatio,
                minPromptTokensForScaling = config.memory.longTermMemoryDynamicCompletionMinPromptTokens,
                modelTokenWeight = modelTokenWeight,
                modelContextWindow = modelContextWindow
            )
        )
        if (resolution.contextClamped) {
            logger.warn {
                "LongTermMemoryAdvisor completion budget clamped by context window " +
                    "(prompt_estimate=${resolution.promptEstimate}, budget=${resolution.budget}, context_window=$modelContextWindow)."
            }
        }
        return resolution.budget
    }

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

    private fun buildMessages(
        context: LongTermMemoryAssessmentContext,
        promptPayload: MemoryAdvisorPromptPayload,
    ): List<ChatMessage> {
        val actionType = context.latestActionType?.name?.lowercase() ?: "none"
        val actionOutcome = context.latestActionOutcome?.let { TextSecurity.preview(it, 220) } ?: "none"
        val shortTermContextSummary = promptPayload.shortTermContextSummary
        val longTermRecall = promptPayload.longTermRecall
        val guidance = promptPayload.guidance
        val dialogue = promptPayload.dialogue
        val d = context.deliberation
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                You decide whether a durable long-term memory should be written.
                Return STRICT JSON only.
                If you save memory, write the summary in first person from the agent's perspective.
                Good: "I learned that the user prefers concise answers."
                Good: "I should remember that the user's name is Victor."
                Bad: "User prefers concise answers."
                Bad: "The agent learned the user's name is Victor."
                Prefer saving:
                - stable user preferences
                - durable project constraints or decisions
                - important factual outcomes
                Avoid saving transient chatter or redundant details.
                Never save a fact that is already present in the "Long-term memory recall" block
                unless the recent dialogue contains a correction or materially new detail.
                JSON schema:
                {"save":true|false,"summary":"<=320 chars","confidence":0.0-1.0,"reason":"<=140 chars","tags":["..."]}
                If save=false, keep summary empty.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = """
                Trigger=${
                    context.trigger
                }
                Deliberation:
                step_index=${d.stepIndex}
                decision_pressure=${String.format(Locale.ROOT, "%.3f", d.decisionPressure)}
                stale_streak=${d.staleStreak}
                progress_score=${String.format(Locale.ROOT, "%.3f", d.progressScore)}
                denial_count=${d.denialCount}
                steps_since_new_evidence=${d.stepsSinceNewEvidence}
                repeat_signature_hits=${d.repeatSignatureHits}
                noop_streak=${d.noopStreak}

                Latest action:
                type=$actionType
                outcome=$actionOutcome

                Meta guidance:
                $guidance

                Long-term memory recall:
                $longTermRecall

                Short-term context summary:
                $shortTermContextSummary

                Recent dialogue:
                $dialogue
                """.trimIndent()
            )
        )
    }

    private fun parseResponse(raw: String): LongTermMemoryAssessmentDecision {
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
                reason = TextSecurity.clamp(payload.reason?.trim().orEmpty().ifBlank { "no reason" }, 140),
                tags = payload.tags.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.take(6),
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
        if (FIRST_PERSON_PREFIXES.any { summary.startsWith(it, ignoreCase = true) }) {
            return TextSecurity.clamp(summary, config.memory.longTermMemoryMaxSummaryChars)
        }
        val normalizedLead = when {
            ASSISTANT_SELF_REFERENCE_REGEX.containsMatchIn(summary) ->
                summary.replaceFirst(ASSISTANT_SELF_REFERENCE_REGEX, "I")
            else -> summary
        }
        return TextSecurity.clamp(
            "$FIRST_PERSON_MEMORY_PREFIX$normalizedLead",
            config.memory.longTermMemoryMaxSummaryChars
        )
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
        private const val DEFAULT_MODEL_TOKEN_WEIGHT: Double = 1.0
        private const val FIRST_PERSON_MEMORY_PREFIX: String = "I learned: "
        private val FIRST_PERSON_PREFIXES: List<String> = listOf(
            "I learned",
            "I should remember",
            "I know",
            "I noted",
            "I discovered",
            "I need to remember",
            "I'm keeping in mind",
        )
        private val ASSISTANT_SELF_REFERENCE_REGEX = Regex("^(assistant|the assistant|the agent|neopsyke)\\b[:\\s-]*", RegexOption.IGNORE_CASE)
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
