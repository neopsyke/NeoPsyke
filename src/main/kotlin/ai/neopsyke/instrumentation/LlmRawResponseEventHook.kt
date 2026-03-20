package ai.neopsyke.instrumentation

import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelHook
import ai.neopsyke.llm.ChatRequestOptions

class LlmRawResponseEventHook(
    private val instrumentation: AgentInstrumentation,
    private val maxRawResponseChars: Int,
) : ChatModelHook {
    override fun onSuccess(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        completion: ChatCompletion,
    ) {
        val actor = options.metadata.actor.trim()
        val callSite = options.metadata.callSite.trim()
        if (actor.isBlank() || callSite.isBlank()) {
            return
        }

        instrumentation.emit(
            AgentEvents.llmRawResponse(
                actor = actor,
                callSite = callSite,
                actionType = options.metadata.actionType?.trim()?.ifBlank { null },
                rawResponse = TextSecurity.clamp(completion.content, maxRawResponseChars)
            )
        )
    }
}
