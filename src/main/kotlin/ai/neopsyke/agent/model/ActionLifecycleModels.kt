package ai.neopsyke.agent.model

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
    ALLOW_PREPARE,
    ALLOW_STAGE,
    ALLOW_COMMIT,
}

data class AuthorizationDecision(
    val progress: AuthorizationProgress,
    val commitMode: CommitMode = CommitMode.NOT_APPLICABLE,
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
    val actionType: ActionType,
    val summary: String,
    val payload: String,
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
    val actionType: ActionType,
    val summary: String,
    val payload: String,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val provenance: Provenance = Provenances.defaultExternal(),
    val origin: ActionOrigin = ActionOrigin.USER,
    val status: StagedActionStatus,
    val actionHash: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
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
    val actionType: ActionType,
    val executionStatus: ActionExecutionStatus,
    val statusSummary: String,
    val effects: Set<ActionEffect> = emptySet(),
    val asyncWait: ai.neopsyke.agent.actions.async.AsyncActionWait? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)
