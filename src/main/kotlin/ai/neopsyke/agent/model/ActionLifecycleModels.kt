package ai.neopsyke.agent.model

import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait

/**
 * Generic labeled context block attached to a staged action for approval display.
 * The producer builds display-ready text from canonical structured payloads.
 * This text is never parsed back into runtime state.
 */
data class ApprovalContextEntry(
    val label: String,
    val content: String,
)

enum class CommitMode {
    NOT_APPLICABLE,
    APPROVAL_BACKED,
    POLICY_AUTONOMOUS,
    ADMIN_OVERRIDE,
}

enum class ActionEffectClass {
    OBSERVE,
    COMMIT_PRIVATE,
    COMMIT_PUBLIC,
    COMMIT_STATEFUL,
    CONTROL_PLANE,
}

enum class AuthorizationProgress {
    DENY,
    ALLOW_STAGE,
    ALLOW_COMMIT,
}

data class AuthorizationDecision(
    val progress: AuthorizationProgress,
    val commitMode: CommitMode = CommitMode.NOT_APPLICABLE,
    val policyVersion: String = "builtin-defaults-v1",
    val reason: String = "",
    val reasonCode: String? = null,
)

data class ActionOpportunity(
    val actionType: ActionType,
    val effectClass: ActionEffectClass,
    val summary: String,
    val allowedIntentions: Set<IntentionKind>,
    val allowedCommitModes: Set<CommitMode>,
)

data class PreparedAction(
    val id: String,
    val rootInputId: String? = null,
    val rootInputReceivedAtMs: Long? = null,
    val actionType: ActionType,
    val summary: String,
    val payload: String,
    val argumentDataTrust: DataTrust = DataTrust.TRUSTED_DATA,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val provenance: Provenance = Provenances.defaultExternal(),
    val origin: ActionOrigin = ActionOrigin.USER,
    val createdAtMs: Long = System.currentTimeMillis(),
)

enum class StagedActionStatus {
    READY,
    WAITING_AUTHORIZATION,
    AUTHORIZED,
    EXECUTING,
    WAITING_EXTERNAL,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class StagedAction(
    val id: String,
    val preparedActionId: String,
    val rootInputId: String? = null,
    val rootInputReceivedAtMs: Long? = null,
    val threadSequence: Long? = null,
    val executionKey: String? = null,
    val actionType: ActionType,
    val summary: String,
    val payload: String,
    val argumentDataTrust: DataTrust = DataTrust.TRUSTED_DATA,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val provenance: Provenance = Provenances.defaultExternal(),
    val origin: ActionOrigin = ActionOrigin.USER,
    val commitMode: CommitMode = CommitMode.NOT_APPLICABLE,
    val status: StagedActionStatus,
    val actionHash: String,
    val statusReason: String? = null,
    val statusReasonCode: String? = null,
    val policyVersion: String = "builtin-defaults-v1",
    val authorizationId: String? = null,
    val receiptId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val urgency: Urgency = Urgency.MEDIUM,
    val intentionKind: IntentionKind = IntentionKind.PREPARE,
    val requestedCommitMode: CommitMode = CommitMode.NOT_APPLICABLE,
    val groundingMetadata: GroundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
    val isForcedTerminal: Boolean = false,
    val requiresFollowUpThought: Boolean = false,
    val followUpPrefix: String = "Action completed.",
    val intentionId: String? = null,
    val approvalContext: List<ApprovalContextEntry> = emptyList(),
)

data class CommitAuthorization(
    val id: String,
    val stagedActionId: String,
    val commitMode: CommitMode,
    val grantedByPrincipalId: String,
    val grantedByChannelId: String,
    val policyVersion: String,
    val actionHash: String,
    val expiresAtMs: Long? = null,
    val grantedAtMs: Long = System.currentTimeMillis(),
)

data class ActionReceipt(
    val id: String,
    val stagedActionId: String,
    val authorizationId: String? = null,
    val rootInputId: String? = null,
    val actionType: ActionType,
    val importance: ActionRecordImportance = ActionRecordImportance.BACKGROUND,
    val executionStatus: ActionExecutionStatus,
    val statusSummary: String,
    val plannerSignal: String = statusSummary,
    val effects: Set<ActionEffect> = emptySet(),
    val asyncWait: AsyncActionWait? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

enum class ActionRecordImportance {
    SIGNAL,
    BACKGROUND,
    TRACE,
}

enum class ActionLedgerKind {
    STAGED,
    AUTHORIZED,
    EXECUTED,
    WAITING_EXTERNAL,
    DENIED,
    REFUSED,
    CANCELLED,
    BYPASS_EXECUTED,
}

data class ActionLedgerEntry(
    val id: String,
    val kind: ActionLedgerKind,
    val importance: ActionRecordImportance,
    val actionType: ActionType,
    val summary: String,
    val rootInputId: String? = null,
    val actionId: Long? = null,
    val stagedActionId: String? = null,
    val authorizationId: String? = null,
    val receiptId: String? = null,
    val reasonCode: String? = null,
    val source: String? = null,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val createdAtMs: Long = System.currentTimeMillis(),
)
