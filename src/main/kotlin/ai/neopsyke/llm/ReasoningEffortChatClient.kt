package ai.neopsyke.llm

/**
 * Thin decorator that injects a default [reasoningEffort] when the caller does not specify one.
 * Configured per cognitive role via `reasoning_effort` in `llm-runtime.yaml`.
 */
class ReasoningEffortChatClient(
    private val delegate: ChatModelClient,
    private val defaultEffort: String,
) : ChatModelClient by delegate {
    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion =
        delegate.chat(
            messages,
            if (options.reasoningEffort == null) options.copy(reasoningEffort = defaultEffort) else options
        )
}
