package psyke.agent.memory.longterm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.DeliberationState
import psyke.agent.core.DialogueTurn
import psyke.agent.support.AdaptiveCompletionBudget
import psyke.agent.support.ContextBlockCompressor
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole
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
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : LongTermMemoryAdvisor {
    override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision {
        val promptPayload = buildPromptPayload(context)
        val messages = buildMessages(context, promptPayload)
        emitCompressionDiagnosticsIfNeeded(promptPayload)
        val completionTokenBudget = resolveCompletionTokenBudget(messages)
        var response: psyke.llm.ChatCompletion? = null
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
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
        return AdaptiveCompletionBudget.resolve(
            request = AdaptiveCompletionBudget.Request(
                messages = messages,
                baseMaxTokens = baseBudget,
                hardMaxTokens = config.memory.longTermMemoryDynamicCompletionHardMaxTokens,
                promptToCompletionRatio = config.memory.longTermMemoryDynamicPromptToCompletionRatio,
                minPromptTokensForScaling = config.memory.longTermMemoryDynamicCompletionMinPromptTokens,
                modelTokenWeight = modelTokenWeight
            )
        )
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
                TextSecurity.clamp(payload.summary?.trim().orEmpty(), config.memory.longTermMemoryMaxSummaryChars)
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
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
