package psyke.agent.ego

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.DialogueTurn
import psyke.agent.core.PendingAction
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

data class TaskWorkspaceFinalizerRequest(
    val action: PendingAction,
    val workspaceCompilation: String,
    val workspaceConfidence: Double,
    val recentDialogue: List<DialogueTurn>,
)

data class TaskWorkspaceFinalizerResult(
    val rewrittenPayload: String,
    val confidence: Double,
    val reason: String,
)

interface TaskWorkspaceFinalizer {
    fun finalize(request: TaskWorkspaceFinalizerRequest): TaskWorkspaceFinalizerResult?
}

object NoopTaskWorkspaceFinalizer : TaskWorkspaceFinalizer {
    override fun finalize(request: TaskWorkspaceFinalizerRequest): TaskWorkspaceFinalizerResult? = null
}

class LlmTaskWorkspaceFinalizer(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : TaskWorkspaceFinalizer {
    override fun finalize(request: TaskWorkspaceFinalizerRequest): TaskWorkspaceFinalizerResult? {
        val mode = resolveMode(request.action)
        val messages = buildMessages(request, mode)
        val attempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
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
                            callSite = "task_workspace_finalizer",
                            actionType = request.action.type.name.lowercase()
                        )
                    )
                ).content
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < attempts) {
                    logger.warn(ex) { "Task workspace finalizer call failed (attempt $attempt/$attempts); retrying." }
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Task workspace finalizer call failed after $attempts attempts." }
            instrumentation.emit(AgentEvents.warning("Task workspace finalizer unavailable; keeping original answer payload."))
            return null
        }
        return parseResponse(response, mode)
    }

    private fun parseResponse(raw: String, mode: String): TaskWorkspaceFinalizerResult? {
        return try {
            val payload = mapper.readValue<FinalizerPayload>(TextSecurity.extractJsonObject(raw))
            val rewritten = payload.rewrite?.trim().orEmpty()
            val confidence = payload.confidence
            val grounded = payload.grounded
            if (rewritten.isBlank() || confidence == null || grounded == null) {
                instrumentation.emit(
                    AgentEvents.warning("Task workspace finalizer response missing required fields; keeping original answer payload.")
                )
                return null
            }
            if (!grounded) {
                instrumentation.emit(
                    AgentEvents.warning("Task workspace finalizer reported ungrounded output; keeping original answer payload.")
                )
                return null
            }
            TaskWorkspaceFinalizerResult(
                rewrittenPayload = rewritten,
                confidence = confidence.coerceIn(0.0, 1.0),
                reason = TextSecurity.clamp(payload.reason?.trim().orEmpty().ifBlank { mode }, MAX_REASON_CHARS)
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse task workspace finalizer response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            instrumentation.emit(AgentEvents.warning("Task workspace finalizer returned non-parseable output; keeping original answer payload."))
            null
        }
    }

    private fun buildMessages(request: TaskWorkspaceFinalizerRequest, mode: String): List<ChatMessage> {
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
                You rewrite a terminal action payload using an ephemeral task workspace.
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
            ActionType.ANSWER -> if (action.isFallbackExplanation) MODE_FALLBACK_EXPLANATION else MODE_DIRECT_ANSWER
            ActionType.WEB_SEARCH -> "web_search_summary"
            ActionType.MCP_TIME -> "mcp_time_summary"
            ActionType.WEBSITE_FETCH -> "website_fetch_summary"
            ActionType.MEMORY -> "memory_summary"
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
