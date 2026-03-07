package psyke.agent.ego

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.core.EgoTrigger
import psyke.agent.core.PlannerContext
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole
import java.util.Locale

private val logger = KotlinLogging.logger {}

enum class MetaReasonerVerdict {
    CONTINUE,
    CONTINUE_WITH_CONSTRAINTS,
    FINALIZE_NOW,
    REQUEST_TOOL_THEN_FINALIZE
}

data class MetaReasonerAssessment(
    val verdict: MetaReasonerVerdict,
    val confidence: Double,
    val reason: String,
)

interface MetaReasoner {
    val enabled: Boolean
        get() = true

    fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment
}

object NoopMetaReasoner : MetaReasoner {
    override val enabled: Boolean = false

    override fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment =
        MetaReasonerAssessment(
            verdict = MetaReasonerVerdict.CONTINUE,
            confidence = 1.0,
            reason = "meta reasoner disabled"
        )
}

class LlmMetaReasoner(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : MetaReasoner {
    override fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment {
        val messages = buildMessages(trigger, context)
        var response: psyke.llm.ChatCompletion? = null
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = config.metaReasoner.maxTokens,
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = "meta_reasoner"
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    logger.warn(ex) { "MetaReasoner call failed (attempt $attempt/$retryAttempts); retrying." }
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "MetaReasoner call failed after $retryAttempts attempts." }
            return MetaReasonerAssessment(
                verdict = MetaReasonerVerdict.CONTINUE,
                confidence = 0.2,
                reason = "Meta reasoner unavailable."
            )
        }
        return parseResponse(response.content)
    }

    private fun buildMessages(trigger: EgoTrigger, context: PlannerContext): List<ChatMessage> {
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
        }
        val triggerText = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.content
            is EgoTrigger.PendingThoughtInput -> trigger.thought.content
        }
        val dialogue = context.recentDialogue
            .takeLast(8)
            .joinToString(separator = "\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, 140)}"
            }
            .ifBlank { "none" }
        val longTermMemoryRecall = context.longTermMemoryRecall.ifBlank { "none" }
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        val deliberation = context.deliberation
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                You are MetaReasoner for Ego's thought loop.
                Return STRICT JSON only.
                Decide if continued deliberation is productive or stale.
                Output schema:
                {"verdict":"continue|continue_with_constraints|finalize_now|request_tool_then_finalize","confidence":0.0-1.0,"reason":"<=140 chars"}
                Use finalize_now when repeated loops or high pressure suggest diminishing returns.
                Use request_tool_then_finalize only if one decisive external action can unlock a better final answer.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = """
                Trigger:
                type=$triggerLabel
                content=${TextSecurity.preview(triggerText, 240)}

                Deliberation state:
                step_index=${deliberation.stepIndex}
                decision_pressure=${String.format(Locale.ROOT, "%.3f", deliberation.decisionPressure)}
                stale_streak=${deliberation.staleStreak}
                progress_score=${String.format(Locale.ROOT, "%.3f", deliberation.progressScore)}
                denial_count=${deliberation.denialCount}
                steps_since_new_evidence=${deliberation.stepsSinceNewEvidence}
                repeat_signature_hits=${deliberation.repeatSignatureHits}
                noop_streak=${deliberation.noopStreak}

                Queue:
                pending_inputs=${context.queue.pendingInputCount}
                pending_thoughts=${context.queue.pendingThoughtCount}
                pending_actions=${context.queue.pendingActionCount}

                Long-term memory recall:
                $longTermMemoryRecall

                Short-term context summary:
                $shortTermContextSummary

                Recent dialogue:
                $dialogue
                """.trimIndent()
            )
        )
    }

    private fun parseResponse(raw: String): MetaReasonerAssessment {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<MetaReasonerPayload>(json)
            if (payload.verdict.isNullOrBlank()) {
                logger.warn {
                    "MetaReasoner response missing required 'verdict' field. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
                }
                return MetaReasonerAssessment(
                    verdict = MetaReasonerVerdict.CONTINUE,
                    confidence = 0.2,
                    reason = "Meta reasoner: missing verdict field."
                )
            }
            MetaReasonerAssessment(
                verdict = resolveVerdict(payload.verdict),
                confidence = payload.confidence?.coerceIn(0.0, 1.0) ?: 0.5,
                reason = TextSecurity.clamp(payload.reason?.trim().orEmpty().ifBlank { "No reason provided." }, 140)
            )
        } catch (ex: Exception) {
            // Attempt truncation-tolerant extraction before falling back.
            val salvaged = salvageVerdictFromTruncated(raw)
            if (salvaged != null) {
                logger.info {
                    "MetaReasoner response was truncated but verdict salvaged: ${salvaged.verdict}. response_len=${raw.length}"
                }
                return salvaged
            }
            logger.warn(ex) {
                "Failed to parse MetaReasoner response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            MetaReasonerAssessment(
                verdict = MetaReasonerVerdict.CONTINUE,
                confidence = 0.2,
                reason = "Meta reasoner parse fallback."
            )
        }
    }

    /**
     * Extracts a verdict from a truncated JSON response where the closing brace
     * is missing. For example: `{"verdict":"finalize_now","confidence":0.97,"reason":"Sufficient info to`
     */
    private fun salvageVerdictFromTruncated(raw: String): MetaReasonerAssessment? {
        if (raw.isBlank()) return null
        val verdictMatch = TRUNCATED_VERDICT_REGEX.find(raw) ?: return null
        val verdictStr = verdictMatch.groupValues[1].trim()
        val verdict = resolveVerdict(verdictStr)
        // Only salvage non-continue verdicts — a truncated "continue" adds no value.
        if (verdict == MetaReasonerVerdict.CONTINUE) return null
        val confidence = TRUNCATED_CONFIDENCE_REGEX.find(raw)
            ?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.6
        return MetaReasonerAssessment(
            verdict = verdict,
            confidence = confidence,
            reason = "Verdict salvaged from truncated response."
        )
    }

    private fun resolveVerdict(raw: String?): MetaReasonerVerdict {
        val normalized = raw?.trim()?.lowercase() ?: return MetaReasonerVerdict.CONTINUE
        return MetaReasonerVerdict.entries.firstOrNull {
            it.name.equals(normalized, ignoreCase = true)
        } ?: when (normalized) {
            "continue_with_constraints" -> MetaReasonerVerdict.CONTINUE_WITH_CONSTRAINTS
            "finalize_now" -> MetaReasonerVerdict.FINALIZE_NOW
            "request_tool_then_finalize" -> MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE
            else -> MetaReasonerVerdict.CONTINUE
        }
    }

    private data class MetaReasonerPayload(
        val verdict: String? = null,
        val confidence: Double? = null,
        val reason: String? = null,
    )

    internal companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** Matches `"verdict":"<value>"` even if the surrounding JSON is truncated. */
        internal val TRUNCATED_VERDICT_REGEX =
            Regex(""""verdict"\s*:\s*"([^"]+)"""")

        /** Matches `"confidence":<number>` from a truncated JSON fragment. */
        internal val TRUNCATED_CONFIDENCE_REGEX =
            Regex(""""confidence"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    }
}
