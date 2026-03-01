package psyke.instrumentation

import psyke.agent.support.TextSecurity
import psyke.llm.ChatCompletion
import psyke.llm.ChatMessage
import psyke.llm.ChatModelHook
import psyke.llm.ChatRequestOptions

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
