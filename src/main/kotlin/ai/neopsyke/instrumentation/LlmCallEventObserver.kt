package ai.neopsyke.instrumentation

import ai.neopsyke.llm.ChatCallObserver
import ai.neopsyke.llm.ChatCallRecord

class LlmCallEventObserver(
    private val provider: String,
    private val instrumentation: AgentInstrumentation,
) : ChatCallObserver {
    override fun onChatCall(record: ChatCallRecord) {
        instrumentation.emit(
            AgentEvent(
                type = "llm_call",
                data = mapOf(
                    "provider" to provider,
                    "model" to record.model,
                    "actor" to record.metadata.actor,
                    "call_site" to record.metadata.callSite,
                    "action_type" to record.metadata.actionType,
                    "structured_output_mode" to record.metadata.structuredOutputMode,
                    "session_id" to record.metadata.sessionId,
                    "root_input_id" to record.metadata.rootInputId,
                    "prompt_tokens" to record.promptTokens,
                    "completion_tokens" to record.completionTokens,
                    "total_tokens" to record.totalTokens,
                    "latency_ms" to record.latencyMs,
                    "status" to record.status.name.lowercase(),
                    "error_code" to record.errorCode,
                    "error_message" to record.errorMessage
                )
            )
        )
    }
}
