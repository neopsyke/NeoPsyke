package ai.neopsyke.eval

import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions

class UsageTrackingChatClient(
    private val delegate: ChatModelClient,
) : ChatModelClient {
    private var callCount: Int = 0
    private var callErrors: Int = 0
    private var promptTokens: Int = 0
    private var completionTokens: Int = 0
    private var totalTokens: Int = 0

    override val modelName: String
        get() = delegate.modelName

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        callCount += 1
        return try {
            val completion = delegate.chat(messages, options)
            val usage = completion.usage
            if (usage != null) {
                promptTokens += usage.promptTokens ?: 0
                completionTokens += usage.completionTokens ?: 0
                totalTokens += usage.totalTokens ?: 0
            }
            completion
        } catch (ex: Exception) {
            callErrors += 1
            throw ex
        }
    }

    override fun close() {
        delegate.close()
    }

    fun snapshot(): UsageSnapshot =
        UsageSnapshot(
            calls = callCount,
            callErrors = callErrors,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
}

data class UsageSnapshot(
    val calls: Int,
    val callErrors: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
) {
    operator fun minus(previous: UsageSnapshot): UsageSnapshot =
        UsageSnapshot(
            calls = calls - previous.calls,
            callErrors = callErrors - previous.callErrors,
            promptTokens = promptTokens - previous.promptTokens,
            completionTokens = completionTokens - previous.completionTokens,
            totalTokens = totalTokens - previous.totalTokens
        )

    operator fun plus(other: UsageSnapshot): UsageSnapshot =
        UsageSnapshot(
            calls = calls + other.calls,
            callErrors = callErrors + other.callErrors,
            promptTokens = promptTokens + other.promptTokens,
            completionTokens = completionTokens + other.completionTokens,
            totalTokens = totalTokens + other.totalTokens
        )
}
