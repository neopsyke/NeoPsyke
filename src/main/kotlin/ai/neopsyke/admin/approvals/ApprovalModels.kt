package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.StagedAction
import java.text.Normalizer

enum class ApprovalRequestStatus {
    QUEUED,
    AWAITING_OWNER_REPLY,
    AWAITING_CLARIFICATION,
    APPROVED,
    DENIED,
    DENIED_AND_REISSUED,
    EXPIRED,
    SUPERSEDED,
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
    /**
     * Canonical, allowlisted control-plane summary consumed by the interpreter.
     * Deterministic rules still mostly parse reply text, but we keep one stable
     * canonical context shape for audit/replay parity with LLM fallback.
     */
    val canonicalSummary: String,
    val reasonCode: String? = null,
    val promptVersion: Int = 1,
    val promptInstanceId: String,
    val clarificationCount: Int = 0,
    val lastPromptAtMs: Long,
    val expiresAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    val routingScope: String = "conversation",
    val routingFailureReason: String? = null,
    val lastPromptDelivered: Boolean? = null,
    val lastPromptDeliveryDetail: String? = null,
    val resolutionProvider: String? = null,
    val resolutionChannelId: String? = null,
    val resolutionSessionId: String? = null,
    val resolutionPrincipalId: String? = null,
    val resolutionAtMs: Long? = null,
    val resolutionReason: String? = null,
    val forwardedOwnerReplyRaw: String? = null,
    val forwardedOwnerSource: String? = null,
    val lastInboundEventId: String? = null,
    val usedModelAssistance: Boolean = false,
)

data class ApprovalCanonicalSummary(
    val actionType: String,
    val summary: String,
    val reason: String,
    val commitMode: String,
    val targetDescription: String,
    val effectDescription: String,
    val provider: String,
    val originDescription: String,
) {
    fun renderForInterpreter(): String =
        buildString {
            appendLine("action: $actionType")
            appendLine("summary: $summary")
            appendLine("reason: $reason")
            appendLine("commit_mode: $commitMode")
            appendLine("target: $targetDescription")
            appendLine("effect: $effectDescription")
            appendLine("provider: $provider")
            append("origin: $originDescription")
        }

    companion object {
        fun from(stagedAction: StagedAction, reason: String): ApprovalCanonicalSummary =
            ApprovalCanonicalSummary(
                actionType = stagedAction.actionType.id,
                summary = ApprovalTextViews.allowlistedPreview(stagedAction.summary),
                reason = ApprovalTextViews.allowlistedPreview(reason),
                commitMode = stagedAction.commitMode.name.lowercase(),
                targetDescription = ApprovalTextViews.allowlistedPreview(stagedAction.conversationContext.interlocutor.displayName()),
                effectDescription = ApprovalTextViews.effectDescription(stagedAction),
                provider = stagedAction.conversationContext.security.channel.provider,
                originDescription = stagedAction.origin.source.name.lowercase(),
            )
    }
}

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
    val eventId: String? = null,
    val originApprovalRequestId: String? = null,
    val originStagedActionId: String? = null,
    val originApprovalSource: String? = null,
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
    fun render(): String =
        """
        Approval details:
        - action: $actionType
        - summary: $summary
        - commit mode: $commitMode
        - target: $targetDescription
        - effect: $effectDescription
        - reason: $reason
        - provider: $provider
        - origin: $originDescription
        - created at: ${java.time.Instant.ofEpochMilli(createdAtMs)}
        - expires at: ${java.time.Instant.ofEpochMilli(expiresAtMs)}
        """.trimIndent()

    companion object {
        fun from(stagedAction: StagedAction, reason: String, expiresAtMs: Long): ApprovalExplanationView =
            ApprovalExplanationView(
                actionType = stagedAction.actionType.id,
                summary = ApprovalTextViews.allowlistedPreview(stagedAction.summary),
                commitMode = stagedAction.commitMode.name.lowercase(),
                targetDescription = ApprovalTextViews.allowlistedPreview(stagedAction.conversationContext.interlocutor.displayName()),
                effectDescription = ApprovalTextViews.effectDescription(stagedAction),
                reason = ApprovalTextViews.allowlistedPreview(reason),
                provider = stagedAction.conversationContext.security.channel.provider,
                createdAtMs = stagedAction.createdAtMs,
                expiresAtMs = expiresAtMs,
                originDescription = stagedAction.origin.source.name.lowercase(),
            )
    }
}

private object ApprovalTextViews {
    fun effectDescription(stagedAction: StagedAction): String =
        when (stagedAction.actionType.id) {
            "contact_user" -> "Send a user-visible message."
            "goal_operation" -> "Create, update, or remove a goal."
            "web_search" -> "Run a web search and gather external information."
            "website_fetch" -> "Fetch a website and inspect its contents."
            else -> "Execute the staged ${stagedAction.actionType.id} action."
        }

    fun allowlistedPreview(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[redacted-url]")
            .replace(Regex("\\b(?:localhost|127\\.0\\.0\\.1)(?::\\d+)?\\b", RegexOption.IGNORE_CASE), "[redacted-host]")
            .replace(
                Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", RegexOption.IGNORE_CASE),
                "[redacted-id]"
            )
            .replace(Regex("\\b[a-f0-9]{24,}\\b", RegexOption.IGNORE_CASE), "[redacted-token]")
            .trim()
            .take(EXPLANATION_TEXT_MAX_CHARS)

    private const val EXPLANATION_TEXT_MAX_CHARS: Int = 160
}
