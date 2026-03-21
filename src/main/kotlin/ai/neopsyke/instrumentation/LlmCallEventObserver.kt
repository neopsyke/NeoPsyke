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
                    "cognitive_role" to record.metadata.cognitiveRole,
                    "trigger" to record.metadata.trigger,
                    "origin_source" to record.metadata.originSource,
                    "need_id" to record.metadata.needId,
                    "root_impulse_id" to record.metadata.rootImpulseId,
                    "thought_id" to record.metadata.thoughtId,
                    "plan_id" to record.metadata.planId,
                    "plan_step_index" to record.metadata.planStepIndex,
                    "plan_total_steps" to record.metadata.planTotalSteps,
                    "plan_step_description" to record.metadata.planStepDescription,
                    "structured_output_mode" to record.metadata.structuredOutputMode,
                    "session_id" to record.metadata.sessionId,
                    "root_input_id" to record.metadata.rootInputId,
                    "prompt_tokens" to record.promptTokens,
                    "completion_tokens" to record.completionTokens,
                    "total_tokens" to record.totalTokens,
                    "latency_ms" to record.latencyMs,
                    "status" to record.status.name.lowercase(),
                    "error_code" to record.errorCode,
                    "error_message" to record.errorMessage,
                    "provider_error_type" to record.providerErrorType,
                    "provider_error_code" to record.providerErrorCode,
                    "failed_generation_preview" to record.failedGenerationPreview,
                    "error_body_preview" to record.errorBodyPreview
                )
            )
        )
    }
}
