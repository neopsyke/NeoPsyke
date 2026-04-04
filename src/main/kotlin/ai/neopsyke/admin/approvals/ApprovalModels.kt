package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.StagedAction

enum class ApprovalRequestStatus {
    QUEUED,
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
}

enum class ApprovalClassificationKind {
    APPROVE,
    DENY,
    DENY_AND_REISSUE,
    EXPLAIN,
    UNCLEAR,
}

data class ApprovalTarget(
    val provider: String,
    val sessionId: String,
    val channelId: String,
    val principalId: String = "owner",
    val principalLabel: String? = "Owner",
)

data class ApprovalRequest(
    val id: String,
    val stagedActionId: String,
    val actionHash: String,
    val rootInputId: String? = null,
    val originalSessionId: String,
    val target: ApprovalTarget,
    val status: ApprovalRequestStatus,
    val actionType: String,
    val summary: String,
    val reason: String,
    val reasonCode: String? = null,
    val promptVersion: Int = 1,
    val clarificationCount: Int = 0,
    val lastPromptAtMs: Long,
    val expiresAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    val resolutionProvider: String? = null,
    val resolutionChannelId: String? = null,
    val resolutionSessionId: String? = null,
    val resolutionPrincipalId: String? = null,
    val resolutionAtMs: Long? = null,
    val usedModelAssistance: Boolean = false,
)

data class ApprovalAuditEntry(
    val id: String,
    val requestId: String,
    val kind: String,
    val summary: String,
    val payload: String? = null,
    val createdAtMs: Long,
)

data class OwnerMessageEnvelope(
    val content: String,
    val source: String,
    val priority: InputPriority,
    val conversationContext: ConversationContext,
    val receivedAtMs: Long,
)

sealed interface OwnerIngressResult {
    data class Forwarded(val enqueued: Boolean, val detail: String) : OwnerIngressResult
    data class Consumed(val detail: String) : OwnerIngressResult
}

data class ApprovalClassification(
    val kind: ApprovalClassificationKind,
    val usedModelAssistance: Boolean,
)

data class ApprovalExplanationView(
    val actionType: String,
    val summary: String,
    val commitMode: String,
    val targetDescription: String,
    val effectDescription: String,
    val reason: String,
    val provider: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val originDescription: String,
) {
    companion object {
        fun from(stagedAction: StagedAction, reason: String, expiresAtMs: Long): ApprovalExplanationView =
            ApprovalExplanationView(
                actionType = stagedAction.actionType.id,
                summary = stagedAction.summary,
                commitMode = stagedAction.commitMode.name.lowercase(),
                targetDescription = stagedAction.conversationContext.interlocutor.displayName(),
                effectDescription = stagedAction.actionType.id,
                reason = reason,
                provider = stagedAction.conversationContext.security.channel.provider,
                createdAtMs = stagedAction.createdAtMs,
                expiresAtMs = expiresAtMs,
                originDescription = stagedAction.origin.source.name.lowercase(),
            )
    }
}
