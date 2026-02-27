package psyke.llm

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

interface ChatModelHook {
    fun onSuccess(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        completion: ChatCompletion,
    ) {}

    fun onError(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        error: Exception,
    ) {}
}

class InstrumentedChatModelClient(
    private val delegate: ChatModelClient,
    private val hooks: List<ChatModelHook> = emptyList(),
) : ChatModelClient {
    override val modelName: String
        get() = delegate.modelName

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        return try {
            val completion = delegate.chat(messages, options)
            notifySuccess(messages, options, completion)
            completion
        } catch (ex: Exception) {
            notifyError(messages, options, ex)
            throw ex
        }
    }

    override fun close() {
        delegate.close()
    }

    private fun notifySuccess(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        completion: ChatCompletion,
    ) {
        hooks.forEach { hook ->
            try {
                hook.onSuccess(messages, options, completion)
            } catch (ignored: Exception) {
                logger.warn(ignored) { "Chat-model hook failed on success; continuing." }
            }
        }
    }

    private fun notifyError(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        error: Exception,
    ) {
        hooks.forEach { hook ->
            try {
                hook.onError(messages, options, error)
            } catch (ignored: Exception) {
                logger.warn(ignored) { "Chat-model hook failed on error; continuing." }
            }
        }
    }
}
