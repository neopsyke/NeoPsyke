package ai.neopsyke.admin.approvals

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.prompt.PromptCatalog
import mu.KotlinLogging
import java.text.Normalizer

private val logger = KotlinLogging.logger {}

data class ApprovalInterpreterInput(
    val reply: String,
    val canonicalSummary: String,
    val approvalContextText: String = "",
    val sessionId: String,
    val rootInputId: String?,
)

fun interface ApprovalInterpreter {
    fun classify(input: ApprovalInterpreterInput): ApprovalClassification
}

class DefaultApprovalInterpreter(
    private val config: AgentConfig,
    private val llmClient: ChatModelClient? = null,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : ApprovalInterpreter {
    override fun classify(input: ApprovalInterpreterInput): ApprovalClassification {
        val canonical = canonicalize(input)
        if (canonical.reply.isBlank()) {
            return ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = false)
        }
        val client = llmClient
        if (client == null) {
            logger.warn { "Approval interpreter has no LLM client; falling back to unclear." }
            return ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = false)
        }
        val result = llmClassify(client, canonical)
        logger.debug {
            "Approval classified: kind=${result.kind.name.lowercase()} model_assisted=${result.usedModelAssistance} " +
                "reply='${TextSecurity.preview(input.reply, 80)}'"
        }
        return result
    }

    private fun llmClassify(client: ChatModelClient, input: CanonicalApprovalInput): ApprovalClassification {
        val retryAttempts = maxOf(1, config.llmRetryAttempts)
        var lastError: Exception? = null
        for (attempt in 1..retryAttempts) {
            try {
                val prompt = promptCatalog.renderSections(
                    "approvals/interpreter",
                    mapOf(
                        "canonical_summary" to input.canonicalSummary,
                        "reply" to input.reply,
                    )
                )
                val schema = promptCatalog.responseFormat("approval-interpreter-decision")
                val completion = client.chat(
                    messages = prompt.sections.map { ChatMessage(role = it.role, content = it.content) },
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = MAX_COMPLETION_TOKENS,
                        responseFormat = schema.format,
                        metadata = promptCatalog.metadata(
                            ChatCallMetadata(
                                actor = "approval_interpreter",
                                callSite = "approval_interpreter_classify",
                                cognitiveRole = "approval_interpreter",
                                sessionId = input.sessionId,
                                rootInputId = input.rootInputId,
                            ),
                            prompt,
                            schema,
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
                    "explain" -> ApprovalClassificationKind.EXPLAIN
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
            "Approval interpreter failed after $retryAttempts attempts; reply='${TextSecurity.preview(input.reply, 120)}'"
        }
        return ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = true)
    }

    private fun canonicalize(input: ApprovalInterpreterInput): CanonicalApprovalInput {
        val reply = normalize(input.reply)
            .take(MAX_REPLY_CHARS)
        return CanonicalApprovalInput(
            reply = reply,
            canonicalSummary = normalize(input.canonicalSummary).take(MAX_SUMMARY_CHARS),
            sessionId = input.sessionId,
            rootInputId = input.rootInputId,
        )
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,;:!?])"), "$1")
            .replace(Regex("[\u201C\u201D\u201E\u201F]"), "\"")
            .replace(Regex("[\u2018\u2019\u201A\u201B]"), "'")
            .replace(Regex("[\u2010\u2011\u2012\u2013\u2014]"), "-")

    private data class CanonicalApprovalInput(
        val reply: String,
        val canonicalSummary: String,
        val sessionId: String,
        val rootInputId: String?,
    )

    private data class ApprovalDecisionPayload(
        val decision: String? = null,
    )

    companion object {
        private val mapper = jacksonObjectMapper()
        private const val MAX_REPLY_CHARS: Int = 400
        private const val MAX_SUMMARY_CHARS: Int = 240
        private const val MAX_COMPLETION_TOKENS: Int = 10_000
    }
}
