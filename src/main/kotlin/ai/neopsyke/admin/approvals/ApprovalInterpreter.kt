package ai.neopsyke.admin.approvals

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import mu.KotlinLogging
import java.text.Normalizer

private val logger = KotlinLogging.logger {}

data class ApprovalInterpreterInput(
    val reply: String,
    val promptSummary: String,
    val actionSummary: String,
    val sessionId: String,
    val rootInputId: String?,
)

fun interface ApprovalInterpreter {
    fun classify(input: ApprovalInterpreterInput): ApprovalClassification
}

class DefaultApprovalInterpreter(
    private val config: AgentConfig,
    private val llmClient: ChatModelClient? = null,
) : ApprovalInterpreter {
    override fun classify(input: ApprovalInterpreterInput): ApprovalClassification {
        val normalizedReply = normalize(input.reply, stripTerminalQuestion = false)
            .take(MAX_REPLY_CHARS)
        val canonicalReply = stripTerminalQuestionMarker(normalizedReply)
        deterministic(canonicalReply, normalizedReply)?.let {
            return ApprovalClassification(it, usedModelAssistance = false)
        }
        return llmFallback(
            input.copy(
                reply = canonicalReply,
                promptSummary = canonicalizeSummary(input.promptSummary),
                actionSummary = canonicalizeSummary(input.actionSummary),
            )
        )
    }

    private fun deterministic(
        normalized: String,
        normalizedWithQuestion: String,
    ): ApprovalClassificationKind? {
        if (normalized.isBlank()) return ApprovalClassificationKind.UNCLEAR
        if (normalized in APPROVE_EXACT) return ApprovalClassificationKind.APPROVE
        if (normalized in DENY_EXACT) return ApprovalClassificationKind.DENY
        if (isExplanationQuestion(normalized, normalizedWithQuestion)) return ApprovalClassificationKind.EXPLAIN
        if (startsWithDecisionAndMore(normalized)) return ApprovalClassificationKind.DENY_AND_REISSUE
        return null
    }

    private fun llmFallback(input: ApprovalInterpreterInput): ApprovalClassification {
        val client = llmClient ?: return ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = false)
        val retryAttempts = maxOf(1, config.llmRetryAttempts)
        var lastError: Exception? = null
        for (attempt in 1..retryAttempts) {
            try {
                val completion = client.chat(
                    messages = listOf(
                        ChatMessage(
                            role = ChatRole.SYSTEM,
                            content = """
                            You classify owner replies for an approval control plane.
                            Return strict JSON with one field: decision.
                            Allowed values: approve, deny, deny_and_reissue, unclear.
                            Approve only if the reply clearly authorizes the exact staged action.
                            If the reply changes timing, target, or instructions, return deny_and_reissue.
                            """.trimIndent()
                        ),
                        ChatMessage(
                            role = ChatRole.USER,
                            content = """
                            Prompt summary: ${input.promptSummary}
                            Action summary: ${input.actionSummary}
                            Raw reply: ${input.reply}
                            """.trimIndent()
                        )
                    ),
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = 64,
                        responseFormat = ChatResponseFormat.JsonSchema(
                            name = "approval_interpreter_decision",
                            schemaJson = APPROVAL_SCHEMA_JSON,
                            strict = true,
                        ),
                        metadata = ChatCallMetadata(
                            actor = "approval_interpreter",
                            callSite = "approval_interpreter_classify",
                            cognitiveRole = "approval_interpreter",
                            sessionId = input.sessionId,
                            rootInputId = input.rootInputId,
                        )
                    )
                )
                val payload = mapper.readValue<ApprovalDecisionPayload>(completion.content)
                val decision = payload.decision?.trim()?.lowercase()
                if (decision.isNullOrBlank()) {
                    logger.warn { "Approval interpreter returned missing required field 'decision'; falling back to unclear." }
                    return ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = true)
                }
                val mapped = when (decision) {
                    "approve" -> ApprovalClassificationKind.APPROVE
                    "deny" -> ApprovalClassificationKind.DENY
                    "deny_and_reissue" -> ApprovalClassificationKind.DENY_AND_REISSUE
                    "unclear" -> ApprovalClassificationKind.UNCLEAR
                    else -> ApprovalClassificationKind.UNCLEAR
                }
                return ApprovalClassification(mapped, usedModelAssistance = true)
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    logger.warn(ex) { "Approval interpreter call failed (attempt $attempt/$retryAttempts); retrying." }
                }
            }
        }
        logger.warn(lastError) {
            "Approval interpreter fallback failed after $retryAttempts attempts; reply='${TextSecurity.preview(input.reply, 120)}'"
        }
        return ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = true)
    }

    private fun canonicalizeSummary(raw: String): String =
        normalize(raw)
            .take(MAX_SUMMARY_CHARS)

    private fun normalize(raw: String, stripTerminalQuestion: Boolean = true): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,;:!?])"), "$1")
            .replace(Regex("[“”„‟]"), "\"")
            .replace(Regex("[‘’‚‛]"), "'")
            .replace(Regex("[‐‑‒–—]"), "-")
        return if (stripTerminalQuestion) {
            stripTerminalQuestionMarker(normalized)
        } else {
            normalized
        }
    }

    private fun stripTerminalQuestionMarker(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("\\s*[.!?]+$"), "")

    private fun isExplanationQuestion(normalized: String, normalizedWithQuestion: String): Boolean =
        normalizedWithQuestion.endsWith("?") ||
            QUESTION_PREFIXES.any { normalized.startsWith(it) } ||
            EXPLANATION_MARKERS.any { normalized.contains(it) }

    private fun startsWithDecisionAndMore(normalized: String): Boolean =
        DECISION_PREFIXES.any { prefix ->
            normalized.startsWith(prefix) && normalized != prefix
        }

    private data class ApprovalDecisionPayload(
        val decision: String? = null,
    )

    companion object {
        private val mapper = jacksonObjectMapper()
        private val APPROVE_EXACT = setOf("yes", "approve", "approved", "ok", "okay", "sure", "do it", "go ahead", "proceed")
        private val DENY_EXACT = setOf("no", "deny", "denied", "cancel", "stop", "don't", "do not")
        private val QUESTION_PREFIXES = listOf("what ", "what is", "what exactly", "who ", "why ", "how ", "when ", "is this", "which ")
        private val EXPLANATION_MARKERS = listOf("explain", "clarify", "details", "detail", "more context", "more info", "more information")
        private val DECISION_PREFIXES = listOf("yes", "approve", "ok", "okay", "sure", "no", "deny", "cancel")
        private const val MAX_REPLY_CHARS: Int = 400
        private const val MAX_SUMMARY_CHARS: Int = 240
        private const val APPROVAL_SCHEMA_JSON: String =
            """{"type":"object","additionalProperties":false,"properties":{"decision":{"type":"string","enum":["approve","deny","deny_and_reissue","unclear"]}},"required":["decision"]}"""
    }
}
