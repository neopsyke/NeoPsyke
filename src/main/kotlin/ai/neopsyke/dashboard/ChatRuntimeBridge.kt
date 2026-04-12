package ai.neopsyke.dashboard

import ai.neopsyke.admin.approvals.ApprovalRuntime
import ai.neopsyke.admin.approvals.OwnerMessageEnvelope
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.PolicyScope
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource

data class ChatSubmitResult(
    val recorded: Boolean,
    val enqueued: Boolean,
    val detail: String,
    val message: ChatMessage? = null,
)

class ChatRuntimeBridge(
    private val store: DashboardStateStore,
    private val sensoryInput: AsyncSignalSource,
    approvalRuntime: ApprovalRuntime? = null,
    private val policyScope: PolicyScope = PolicyScope.DEFAULT,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    @Volatile private var approvalRuntime: ApprovalRuntime? = approvalRuntime

    init {
        store.ensureChatSession()
    }

    fun setApprovalRuntime(runtime: ApprovalRuntime?) {
        approvalRuntime = runtime
    }

    fun createSession(title: String? = null): ChatSessionSummary =
        store.createChatSession(title = title)

    fun listSessionsJson(): String = store.chatSessionsJson()

    fun sessionJson(sessionId: String): String? = store.chatSessionJson(sessionId = sessionId)

    fun submitMessage(sessionId: String, content: String): ChatSubmitResult {
        val sanitizedContent = content.trim()
        if (sanitizedContent.isBlank()) {
            return ChatSubmitResult(
                recorded = false,
                enqueued = false,
                detail = "Message content cannot be blank."
            )
        }
        if (!store.hasChatSession(sessionId)) {
            return ChatSubmitResult(
                recorded = false,
                enqueued = false,
                detail = "Unknown session id."
            )
        }
        if (sessionId.startsWith("id:")) {
            return ChatSubmitResult(
                recorded = false,
                enqueued = false,
                detail = "This is a read-only internal session."
            )
        }
        val message = store.addUserMessage(
            sessionId = sessionId,
            content = sanitizedContent,
            source = "web"
        ) ?: return ChatSubmitResult(
            recorded = false,
            enqueued = false,
            detail = "Failed to record user message."
        )
        val source = "chat:${message.sessionId}"
        val interlocutor = interlocutorResolver.resolve(source)
        val conversationContext = ConversationContext(
            sessionId = message.sessionId,
            interlocutor = interlocutor,
            security = ConversationSecurityContexts.ownerDirect(
                provider = "webapp",
                channelId = message.sessionId,
                policyScope = policyScope,
            ),
        )
        val ingressResult = approvalRuntime?.routeOwnerMessage(
            OwnerMessageEnvelope(
                content = message.content,
                source = source,
                priority = InputPriority.HIGH,
                conversationContext = conversationContext,
                receivedAtMs = message.createdAtMs,
                eventId = store.eventIdForMessage(message),
            )
        ) ?: if (
            sensoryInput.submitInput(
                content = message.content,
                source = source,
                priority = InputPriority.HIGH,
                conversationContext = conversationContext
            )
        ) {
            ai.neopsyke.admin.approvals.OwnerIngressResult.Forwarded(true, "Message recorded and enqueued.")
        } else {
            ai.neopsyke.admin.approvals.OwnerIngressResult.Forwarded(false, "Message recorded, but the input queue is full.")
        }
        return when (ingressResult) {
            is ai.neopsyke.admin.approvals.OwnerIngressResult.Forwarded -> if (ingressResult.enqueued) {
            ChatSubmitResult(
                recorded = true,
                enqueued = true,
                detail = ingressResult.detail,
                message = message,
            )
        } else {
            ChatSubmitResult(
                recorded = true,
                enqueued = false,
                detail = ingressResult.detail,
                message = message,
            )
        }
            is ai.neopsyke.admin.approvals.OwnerIngressResult.Consumed ->
                ChatSubmitResult(
                    recorded = true,
                    enqueued = false,
                    detail = ingressResult.detail,
                    message = message,
                )
        }
    }

    fun subscribe(sessionId: String): DashboardFlowSubscription? =
        store.subscribeChat(sessionId)
}
