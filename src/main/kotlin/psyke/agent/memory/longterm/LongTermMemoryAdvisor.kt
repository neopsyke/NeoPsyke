package psyke.agent.memory.longterm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.DeliberationState
import psyke.agent.core.DialogueTurn
import psyke.agent.support.TextSecurity
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
) : LongTermMemoryAdvisor {
    override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision {
        val messages = buildMessages(context)
        val response = modelClient.chat(
            messages = messages,
            options = ChatRequestOptions(
                temperature = 0.0,
                maxTokens = config.longTermMemoryMaxTokens,
                metadata = ChatCallMetadata(
                    actor = "ego",
                    callSite = "long_term_memory_assessment"
                )
            )
        )
        return parseResponse(response.content)
    }

    private fun buildMessages(context: LongTermMemoryAssessmentContext): List<ChatMessage> {
        val dialogue = context.recentDialogue
            .takeLast(12)
            .joinToString(separator = "\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, 160)}"
            }
            .ifBlank { "none" }
        val actionType = context.latestActionType?.name?.lowercase() ?: "none"
        val actionOutcome = context.latestActionOutcome?.let { TextSecurity.preview(it, 220) } ?: "none"
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        val longTermRecall = context.longTermMemoryRecall.ifBlank { "none" }
        val guidance = context.metaGuidance.ifBlank { "none" }
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
            val shouldSave = payload.save == true
            val summary = if (shouldSave) {
                TextSecurity.clamp(payload.summary?.trim().orEmpty(), config.longTermMemoryMaxSummaryChars)
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

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
