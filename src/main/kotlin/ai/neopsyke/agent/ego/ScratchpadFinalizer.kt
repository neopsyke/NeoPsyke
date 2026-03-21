package ai.neopsyke.agent.ego

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.support.RetryPolicy
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

data class ScratchpadFinalizerRequest(
    val action: PendingAction,
    val workspaceCompilation: String,
    val workspaceConfidence: Double,
    val recentDialogue: List<DialogueTurn>,
)

data class ScratchpadFinalizerResult(
    val rewrittenPayload: String,
    val confidence: Double,
    val reason: String,
)

interface ScratchpadFinalizer {
    fun finalize(request: ScratchpadFinalizerRequest): ScratchpadFinalizerResult?
}

object NoopScratchpadFinalizer : ScratchpadFinalizer {
    override fun finalize(request: ScratchpadFinalizerRequest): ScratchpadFinalizerResult? = null
}

class LlmScratchpadFinalizer(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : ScratchpadFinalizer {
    override fun finalize(request: ScratchpadFinalizerRequest): ScratchpadFinalizerResult? {
        val mode = resolveMode(request.action)
        val messages = buildMessages(request, mode)
        val attempts = RetryPolicy.boundedLlmRetryAttempts(config.llmRetryAttempts)
        var response: String? = null
        var lastError: Exception? = null
        for (attempt in 1..attempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = config.memory.taskWorkspace.finalPassMaxTokens,
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = "scratchpad_finalizer",
                            actionType = request.action.type.name.lowercase()
                        )
                    )
                ).content
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < attempts) {
                    logger.warn(ex) { "Scratchpad finalizer call failed (attempt $attempt/$attempts); retrying." }
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Scratchpad finalizer call failed after $attempts attempts." }
            instrumentation.emit(AgentEvents.warning("Scratchpad finalizer unavailable; keeping original answer payload."))
            return null
        }
        return parseResponse(response, mode)
    }

    private fun parseResponse(raw: String, mode: String): ScratchpadFinalizerResult? {
        return try {
            val payload = mapper.readValue<FinalizerPayload>(TextSecurity.extractJsonObject(raw))
            val rewritten = payload.rewrite?.trim().orEmpty()
            val confidence = payload.confidence
            val grounded = payload.grounded
            if (rewritten.isBlank() || confidence == null || grounded == null) {
                instrumentation.emit(
                    AgentEvents.warning("Scratchpad finalizer response missing required fields; keeping original answer payload.")
                )
                return null
            }
            if (!grounded) {
                instrumentation.emit(
                    AgentEvents.warning("Scratchpad finalizer reported ungrounded output; keeping original answer payload.")
                )
                return null
            }
            ScratchpadFinalizerResult(
                rewrittenPayload = rewritten,
                confidence = confidence.coerceIn(0.0, 1.0),
                reason = TextSecurity.clamp(payload.reason?.trim().orEmpty().ifBlank { mode }, MAX_REASON_CHARS)
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse scratchpad finalizer response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            instrumentation.emit(AgentEvents.warning("Scratchpad finalizer returned non-parseable output; keeping original answer payload."))
            null
        }
    }

    private fun buildMessages(request: ScratchpadFinalizerRequest, mode: String): List<ChatMessage> {
        val recentDialogue = request.recentDialogue
            .takeLast(8)
            .joinToString("\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, DIALOGUE_PREVIEW_CHARS)}"
            }
            .ifBlank { "none" }
        val modeGuidance = when (mode) {
            MODE_FALLBACK_EXPLANATION -> {
                """
                Mode=fallback_explanation.
                Explain constraints/failures transparently and avoid fabricated claims.
                Preserve a concise, honest, best-effort tone.
                """.trimIndent()
            }
            MODE_DIRECT_ANSWER -> {
                """
                Mode=direct_answer.
                Produce a concise final answer grounded in workspace evidence.
                Prefer clarity over verbosity.
                """.trimIndent()
            }
            else -> {
                """
                Mode=$mode.
                Produce action-appropriate text grounded in workspace evidence.
                """.trimIndent()
            }
        }
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                You rewrite a terminal action payload using an ephemeral scratchpad.
                Return STRICT JSON only.
                Never invent facts not present in workspace compilation.
                JSON schema:
                {"rewrite":"string","confidence":0.0-1.0,"grounded":true|false,"reason":"<=120 chars"}
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = """
                $modeGuidance
                Workspace confidence score=${"%.3f".format(request.workspaceConfidence)}
                Existing candidate payload:
                ${request.action.payload}

                Workspace compilation:
                ${request.workspaceCompilation}

                Recent dialogue:
                $recentDialogue
                """.trimIndent()
            )
        )
    }

    private fun resolveMode(action: PendingAction): String =
        when (action.type) {
            ActionType.CONTACT_USER -> if (action.isFallbackExplanation) MODE_FALLBACK_EXPLANATION else MODE_DIRECT_ANSWER
            else -> "${action.type.id}_summary"
        }

    private data class FinalizerPayload(
        val rewrite: String? = null,
        val confidence: Double? = null,
        val grounded: Boolean? = null,
        val reason: String? = null,
    )

    private companion object {
        private const val MODE_DIRECT_ANSWER: String = "direct_answer"
        private const val MODE_FALLBACK_EXPLANATION: String = "fallback_explanation"
        private const val DIALOGUE_PREVIEW_CHARS: Int = 180
        private const val MAX_REASON_CHARS: Int = 120
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
