package psyke.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole
import java.util.Locale

private val logger = KotlinLogging.logger {}

data class MemoryConsolidationContext(
    val trigger: String,
    val deliberation: DeliberationState,
    val recentDialogue: List<DialogueTurn>,
    val memorySummary: String,
    val memoryRecall: String,
    val metaGuidance: String,
    val latestActionType: ActionType? = null,
    val latestActionOutcome: String? = null,
)

data class MemoryConsolidationDecision(
    val shouldSave: Boolean,
    val summary: String,
    val confidence: Double,
    val reason: String,
    val tags: List<String> = emptyList(),
)

interface MemoryConsolidationAdvisor {
    val enabled: Boolean
        get() = true

    fun assess(context: MemoryConsolidationContext): MemoryConsolidationDecision
}

object NoopMemoryConsolidationAdvisor : MemoryConsolidationAdvisor {
    override val enabled: Boolean = false

    override fun assess(context: MemoryConsolidationContext): MemoryConsolidationDecision =
        MemoryConsolidationDecision(
            shouldSave = false,
            summary = "",
            confidence = 0.0,
            reason = "memory consolidation disabled"
        )
}

class LlmMemoryConsolidationAdvisor(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : MemoryConsolidationAdvisor {
    override fun assess(context: MemoryConsolidationContext): MemoryConsolidationDecision {
        val messages = buildMessages(context)
        val response = modelClient.chat(
            messages = messages,
            options = ChatRequestOptions(
                temperature = 0.0,
                maxTokens = config.memoryConsolidationMaxTokens,
                metadata = ChatCallMetadata(
                    actor = "ego",
                    callSite = "memory_consolidation"
                )
            )
        )
        return parseResponse(response.content)
    }

    private fun buildMessages(context: MemoryConsolidationContext): List<ChatMessage> {
        val dialogue = context.recentDialogue
            .takeLast(12)
            .joinToString(separator = "\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, 160)}"
            }
            .ifBlank { "none" }
        val actionType = context.latestActionType?.name?.lowercase() ?: "none"
        val actionOutcome = context.latestActionOutcome?.let { TextSecurity.preview(it, 220) } ?: "none"
        val summary = context.memorySummary.ifBlank { "none" }
        val recall = context.memoryRecall.ifBlank { "none" }
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

                Memory recall:
                $recall

                Memory summary:
                $summary

                Recent dialogue:
                $dialogue
                """.trimIndent()
            )
        )
    }

    private fun parseResponse(raw: String): MemoryConsolidationDecision {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<MemoryConsolidationPayload>(json)
            val shouldSave = payload.save == true
            val summary = if (shouldSave) {
                TextSecurity.clamp(payload.summary?.trim().orEmpty(), config.memoryConsolidationMaxSummaryChars)
            } else {
                ""
            }
            MemoryConsolidationDecision(
                shouldSave = shouldSave && summary.isNotBlank(),
                summary = summary,
                confidence = payload.confidence?.coerceIn(0.0, 1.0) ?: 0.5,
                reason = TextSecurity.clamp(payload.reason?.trim().orEmpty().ifBlank { "no reason" }, 140),
                tags = payload.tags.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.take(6)
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse memory consolidation response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            MemoryConsolidationDecision(
                shouldSave = false,
                summary = "",
                confidence = 0.0,
                reason = "parse fallback"
            )
        }
    }

    private data class MemoryConsolidationPayload(
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
