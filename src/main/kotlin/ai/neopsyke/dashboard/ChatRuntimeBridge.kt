package ai.neopsyke.dashboard

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.cortex.sensory.AsyncSensoryInputSource

data class ChatSubmitResult(
    val accepted: Boolean,
    val detail: String,
)

class ChatRuntimeBridge(
    private val store: DashboardStateStore,
    private val sensoryInput: AsyncSensoryInputSource,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    init {
        store.ensureChatSession()
    }

    fun createSession(title: String? = null): ChatSessionSummary =
        store.createChatSession(title = title)

    fun listSessionsJson(): String = store.chatSessionsJson()

    fun sessionJson(sessionId: String): String? = store.chatSessionJson(sessionId = sessionId)

    fun submitMessage(sessionId: String, content: String): ChatSubmitResult {
        val sanitizedContent = content.trim()
        if (sanitizedContent.isBlank()) {
            return ChatSubmitResult(
                accepted = false,
                detail = "Message content cannot be blank."
            )
        }
        if (!store.hasChatSession(sessionId)) {
            return ChatSubmitResult(
                accepted = false,
                detail = "Unknown session id."
            )
        }
        if (sessionId.startsWith("id:")) {
            return ChatSubmitResult(
                accepted = false,
                detail = "This is a read-only internal session."
            )
        }
        val message = store.addUserMessage(
            sessionId = sessionId,
            content = sanitizedContent,
            source = "web"
        ) ?: return ChatSubmitResult(
            accepted = false,
            detail = "Failed to record user message."
        )
        val source = "chat:${message.sessionId}"
        val interlocutor = interlocutorResolver.resolve(source)
        val conversationContext = ConversationContext(
            sessionId = message.sessionId,
            interlocutor = interlocutor
        )
        val accepted = sensoryInput.submitInput(
            content = message.content,
            source = source,
            priority = InputPriority.HIGH,
            conversationContext = conversationContext
        )
        return if (accepted) {
            ChatSubmitResult(
                accepted = true,
                detail = "Message enqueued."
            )
        } else {
            ChatSubmitResult(
                accepted = false,
                detail = "Input queue is full."
            )
        }
    }

    fun subscribe(sessionId: String): DashboardFlowSubscription? =
        store.subscribeChat(sessionId)
}
