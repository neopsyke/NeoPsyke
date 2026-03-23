package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PreparedAction
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

sealed interface ActionControlDecisionResult {
    data class Staged(
        val stagedAction: StagedAction,
        val authorizationDecision: AuthorizationDecision,
    ) : ActionControlDecisionResult

    data class Executed(
        val stagedAction: StagedAction,
        val authorization: CommitAuthorization,
        val receipt: ActionReceipt,
        val outcome: ActionOutcome,
        val executedAction: PendingAction,
    ) : ActionControlDecisionResult

    data class Refused(
        val reason: String,
        val reasonCode: String?,
    ) : ActionControlDecisionResult
}

interface ActionControlService {
    suspend fun handleAuthorizationDecision(
        action: PendingAction,
        decision: AuthorizationDecision,
        conversationContext: ConversationContext,
    ): ActionControlDecisionResult

    suspend fun authorizeStagedAction(
        stagedActionId: String,
        grantedBy: ConversationSecurityContext,
    ): ActionControlDecisionResult

    suspend fun processAutonomousStagedActions(limit: Int = 10): List<ActionControlDecisionResult.Executed>

    suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String? = null,
    ): ActionReceipt?

    fun stagedActions(limit: Int): List<StagedAction>
    fun stagedAction(id: String): StagedAction?
    fun receipts(limit: Int): List<ActionReceipt>
    fun receipt(id: String): ActionReceipt?
}

object NoopActionControlService : ActionControlService {
    override suspend fun handleAuthorizationDecision(
        action: PendingAction,
        decision: AuthorizationDecision,
        conversationContext: ConversationContext,
    ): ActionControlDecisionResult =
        ActionControlDecisionResult.Refused(
            reason = "Action control is not configured.",
            reasonCode = "ACTION_CONTROL_UNAVAILABLE",
        )

    override suspend fun authorizeStagedAction(
        stagedActionId: String,
        grantedBy: ConversationSecurityContext,
    ): ActionControlDecisionResult =
        ActionControlDecisionResult.Refused(
            reason = "Action control is not configured.",
            reasonCode = "ACTION_CONTROL_UNAVAILABLE",
        )

    override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> = emptyList()

    override suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String?,
    ): ActionReceipt? = null

    override fun stagedActions(limit: Int): List<StagedAction> = emptyList()
    override fun stagedAction(id: String): StagedAction? = null
    override fun receipts(limit: Int): List<ActionReceipt> = emptyList()
    override fun receipt(id: String): ActionReceipt? = null
}

class LegacyCompatibleActionControlService(
    private val executeCommittedAction: suspend (PendingAction, CommitAuthorization?) -> ActionOutcome,
) : ActionControlService {
    override suspend fun handleAuthorizationDecision(
        action: PendingAction,
        decision: AuthorizationDecision,
        conversationContext: ConversationContext,
    ): ActionControlDecisionResult {
        if (decision.progress == AuthorizationProgress.DENY) {
            return ActionControlDecisionResult.Refused(
                reason = decision.reason,
                reasonCode = decision.reasonCode,
            )
        }
        if (decision.progress != AuthorizationProgress.ALLOW_COMMIT) {
            return ActionControlDecisionResult.Staged(
                stagedAction = StagedAction(
                    id = nextId(),
                    preparedActionId = nextId(),
                    rootInputId = action.rootInputId,
                    rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                    actionType = action.type,
                    summary = action.summary,
                    payload = action.payload,
                    conversationContext = conversationContext,
                    provenance = when (conversationContext.security.instructionTrust) {
                        ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION ->
                            Provenances.trustedMessage(
                                provider = conversationContext.security.channel.provider,
                                sourceRef = action.rootInputId,
                            )
                        ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION ->
                            Provenances.defaultExternal(sourceRef = action.rootInputId)
                    },
                    origin = action.origin,
                    commitMode = decision.commitMode,
                    status = if (decision.commitMode == CommitMode.POLICY_AUTONOMOUS) {
                        StagedActionStatus.READY
                    } else {
                        StagedActionStatus.WAITING_AUTHORIZATION
                    },
                    actionHash = nextId(),
                    statusReason = decision.reason,
                    statusReasonCode = decision.reasonCode,
                    policyVersion = decision.policyVersion,
                ),
                authorizationDecision = decision,
            )
        }
        val authorization = CommitAuthorization(
            id = nextId(),
            stagedActionId = nextId(),
            commitMode = decision.commitMode,
            grantedByPrincipalId = conversationContext.security.principal.id,
            grantedByChannelId = conversationContext.security.channel.channelId,
            policyVersion = decision.policyVersion,
            actionHash = nextId(),
        )
        val outcome = try {
            executeCommittedAction(action, authorization)
        } catch (ex: Exception) {
            ActionOutcome(
                statusSummary = "Action execution failed: ${ex.message?.take(120) ?: "unknown error"}",
                executionStatus = ActionExecutionStatus.FAILED,
                observedEvidence = false,
                plannerSignal = "action execution failed for ${action.type.id}",
            )
        }
        val stagedAction = StagedAction(
            id = authorization.stagedActionId,
            preparedActionId = nextId(),
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            actionType = action.type,
            summary = action.summary,
            payload = action.payload,
            conversationContext = conversationContext,
            provenance = Provenances.defaultExternal(sourceRef = action.rootInputId),
            origin = action.origin,
            commitMode = decision.commitMode,
            status = if (outcome.executionStatus == ActionExecutionStatus.WAITING) {
                StagedActionStatus.WAITING_EXTERNAL
            } else {
                StagedActionStatus.COMPLETED
            },
            actionHash = authorization.actionHash,
            policyVersion = decision.policyVersion,
            authorizationId = authorization.id,
        )
        val receipt = ActionReceipt(
            id = nextId(),
            stagedActionId = stagedAction.id,
            authorizationId = authorization.id,
            rootInputId = action.rootInputId,
            actionType = action.type,
            executionStatus = outcome.executionStatus,
            statusSummary = outcome.statusSummary,
            plannerSignal = outcome.plannerSignal,
            effects = outcome.effects,
            asyncWait = outcome.asyncWait,
        )
        return ActionControlDecisionResult.Executed(
            stagedAction = stagedAction,
            authorization = authorization,
            receipt = receipt,
            outcome = outcome,
            executedAction = action,
        )
    }

    override suspend fun authorizeStagedAction(
        stagedActionId: String,
        grantedBy: ConversationSecurityContext,
    ): ActionControlDecisionResult =
        ActionControlDecisionResult.Refused(
            reason = "Legacy action control does not persist staged actions.",
            reasonCode = "LEGACY_ACTION_CONTROL_NO_PERSISTENCE",
        )

    override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> = emptyList()

    override suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String?,
    ): ActionReceipt? = null

    override fun stagedActions(limit: Int): List<StagedAction> = emptyList()
    override fun stagedAction(id: String): StagedAction? = null
    override fun receipts(limit: Int): List<ActionReceipt> = emptyList()
    override fun receipt(id: String): ActionReceipt? = null

    private fun nextId(): String = UUID.randomUUID().toString()
}

class DefaultActionControlService(
    private val config: ActionControlConfig,
    private val store: ActionControlStore,
    private val executeCommittedAction: suspend (PendingAction, CommitAuthorization?) -> ActionOutcome,
) : ActionControlService {
    override suspend fun handleAuthorizationDecision(
        action: PendingAction,
        decision: AuthorizationDecision,
        conversationContext: ConversationContext,
    ): ActionControlDecisionResult {
        if (decision.progress == AuthorizationProgress.DENY) {
            return ActionControlDecisionResult.Refused(decision.reason, decision.reasonCode)
        }

        val prepared = prepare(action, conversationContext)
        val staged = store.saveStagedAction(
            StagedAction(
                id = nextId(),
                preparedActionId = prepared.id,
                rootInputId = action.rootInputId,
                rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                actionType = action.type,
                summary = action.summary,
                payload = action.payload,
                conversationContext = conversationContext,
                provenance = prepared.provenance,
                origin = action.origin,
                commitMode = decision.commitMode,
                status = if (decision.progress == AuthorizationProgress.ALLOW_COMMIT) {
                    StagedActionStatus.AUTHORIZED
                } else if (decision.commitMode == CommitMode.POLICY_AUTONOMOUS) {
                    StagedActionStatus.READY
                } else {
                    StagedActionStatus.WAITING_AUTHORIZATION
                },
                actionHash = hashAction(action),
                statusReason = decision.reason,
                statusReasonCode = decision.reasonCode,
                policyVersion = decision.policyVersion,
            )
        )

        if (decision.progress != AuthorizationProgress.ALLOW_COMMIT) {
            return ActionControlDecisionResult.Staged(staged, decision)
        }

        val authorization = store.saveAuthorization(
            CommitAuthorization(
                id = nextId(),
                stagedActionId = staged.id,
                commitMode = decision.commitMode,
                grantedByPrincipalId = conversationContext.security.principal.id,
                grantedByChannelId = conversationContext.security.channel.channelId,
                policyVersion = decision.policyVersion,
                actionHash = staged.actionHash,
                expiresAtMs = System.currentTimeMillis() + config.authorizationTtlMs,
            )
        )
        return executeAuthorized(staged, authorization)
    }

    override suspend fun authorizeStagedAction(
        stagedActionId: String,
        grantedBy: ConversationSecurityContext,
    ): ActionControlDecisionResult {
        if (grantedBy.principal.role != PrincipalRole.OWNER &&
            grantedBy.principal.role != PrincipalRole.ADMIN_CONTROL
        ) {
            return ActionControlDecisionResult.Refused(
                reason = "Only owner or admin-control principals may authorize staged actions.",
                reasonCode = "AUTHORIZATION_PRINCIPAL_NOT_ALLOWED",
            )
        }
        val staged = store.stagedAction(stagedActionId)
            ?: return ActionControlDecisionResult.Refused(
                reason = "Staged action '$stagedActionId' was not found.",
                reasonCode = "STAGED_ACTION_NOT_FOUND",
            )
        if (staged.status != StagedActionStatus.WAITING_AUTHORIZATION &&
            staged.status != StagedActionStatus.READY
        ) {
            return ActionControlDecisionResult.Refused(
                reason = "Staged action '${staged.id}' is not awaiting authorization.",
                reasonCode = "STAGED_ACTION_NOT_WAITING_AUTH",
            )
        }
        val authorization = store.saveAuthorization(
            CommitAuthorization(
                id = nextId(),
                stagedActionId = staged.id,
                commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id,
                grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = staged.policyVersion,
                actionHash = staged.actionHash,
                expiresAtMs = System.currentTimeMillis() + config.authorizationTtlMs,
            )
        )
        return executeAuthorized(staged, authorization)
    }

    override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> {
        val readyActions = store.listStagedActions(limit.coerceAtLeast(1).coerceAtMost(config.maxInspectResults))
            .filter { staged ->
                staged.status == StagedActionStatus.READY &&
                    staged.commitMode == CommitMode.POLICY_AUTONOMOUS
            }
        return buildList {
            readyActions.forEach { staged ->
                val authorization = store.saveAuthorization(
                    CommitAuthorization(
                        id = nextId(),
                        stagedActionId = staged.id,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        grantedByPrincipalId = AUTONOMOUS_WORKER_PRINCIPAL_ID,
                        grantedByChannelId = AUTONOMOUS_WORKER_CHANNEL_ID,
                        policyVersion = staged.policyVersion,
                        actionHash = staged.actionHash,
                        expiresAtMs = System.currentTimeMillis() + config.authorizationTtlMs,
                    )
                )
                when (val result = executeAuthorized(staged, authorization)) {
                    is ActionControlDecisionResult.Executed -> add(result)
                    else -> Unit
                }
            }
        }
    }

    override fun stagedActions(limit: Int): List<StagedAction> =
        store.listStagedActions(limit.coerceAtLeast(1).coerceAtMost(config.maxInspectResults))

    override suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String?,
    ): ActionReceipt {
        val prepared = prepare(action, conversationContext)
        val staged = store.saveStagedAction(
            StagedAction(
                id = nextId(),
                preparedActionId = prepared.id,
                rootInputId = action.rootInputId,
                rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                actionType = action.type,
                summary = action.summary,
                payload = action.payload,
                conversationContext = conversationContext,
                provenance = prepared.provenance,
                origin = action.origin,
                commitMode = CommitMode.NOT_APPLICABLE,
                status = terminalStatusFor(outcome),
                actionHash = hashAction(action),
                statusReason = reason,
                statusReasonCode = reasonCode ?: BYPASS_REASON_CODE,
                policyVersion = BYPASS_POLICY_VERSION,
            )
        )
        val receipt = store.saveReceipt(
            ActionReceipt(
                id = nextId(),
                stagedActionId = staged.id,
                authorizationId = null,
                rootInputId = action.rootInputId,
                actionType = action.type,
                executionStatus = outcome.executionStatus,
                statusSummary = outcome.statusSummary,
                plannerSignal = outcome.plannerSignal,
                effects = outcome.effects,
                asyncWait = outcome.asyncWait,
            )
        )
        store.updateStagedAction(
            staged.copy(
                receiptId = receipt.id,
                updatedAtMs = System.currentTimeMillis(),
                statusReason = outcome.statusSummary,
            )
        )
        return receipt
    }

    override fun stagedAction(id: String): StagedAction? = store.stagedAction(id)

    override fun receipts(limit: Int): List<ActionReceipt> =
        store.listReceipts(limit.coerceAtLeast(1).coerceAtMost(config.maxInspectResults))

    override fun receipt(id: String): ActionReceipt? = store.receipt(id)

    private suspend fun executeAuthorized(
        staged: StagedAction,
        authorization: CommitAuthorization,
    ): ActionControlDecisionResult {
        if (authorization.expiresAtMs != null && authorization.expiresAtMs < System.currentTimeMillis()) {
            return ActionControlDecisionResult.Refused(
                reason = "Authorization for staged action '${staged.id}' has expired.",
                reasonCode = "AUTHORIZATION_EXPIRED",
            )
        }
        if (authorization.actionHash != staged.actionHash) {
            return ActionControlDecisionResult.Refused(
                reason = "Authorization hash mismatch for staged action '${staged.id}'.",
                reasonCode = "AUTHORIZATION_HASH_MISMATCH",
            )
        }
        val executing = store.updateStagedAction(
            staged.copy(
                status = StagedActionStatus.EXECUTING,
                authorizationId = authorization.id,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
        val action = executing.toPendingAction()
        val outcome = try {
            executeCommittedAction(action, authorization)
        } catch (ex: Exception) {
            ActionOutcome(
                statusSummary = "Action execution failed: ${ex.message?.take(120) ?: "unknown error"}",
                executionStatus = ActionExecutionStatus.FAILED,
                observedEvidence = false,
                plannerSignal = "action execution failed for ${action.type.id}",
            )
        }
        val receipt = store.saveReceipt(
            ActionReceipt(
                id = nextId(),
                stagedActionId = executing.id,
                authorizationId = authorization.id,
                rootInputId = executing.rootInputId,
                actionType = executing.actionType,
                executionStatus = outcome.executionStatus,
                statusSummary = outcome.statusSummary,
                plannerSignal = outcome.plannerSignal,
                effects = outcome.effects,
                asyncWait = outcome.asyncWait,
            )
        )
        val nextStatus = when (outcome.executionStatus) {
            ActionExecutionStatus.SUCCESS,
            ActionExecutionStatus.NO_EFFECT -> StagedActionStatus.COMPLETED
            ActionExecutionStatus.WAITING -> StagedActionStatus.WAITING_EXTERNAL
            ActionExecutionStatus.FAILED -> StagedActionStatus.FAILED
        }
        val completed = store.updateStagedAction(
            executing.copy(
                status = nextStatus,
                updatedAtMs = System.currentTimeMillis(),
                receiptId = receipt.id,
                authorizationId = authorization.id,
                statusReason = outcome.statusSummary,
            )
        )
        return ActionControlDecisionResult.Executed(
            stagedAction = completed,
            authorization = authorization,
            receipt = receipt,
            outcome = outcome,
            executedAction = action,
        )
    }

    private fun terminalStatusFor(outcome: ActionOutcome): StagedActionStatus =
        when (outcome.executionStatus) {
            ActionExecutionStatus.SUCCESS,
            ActionExecutionStatus.NO_EFFECT -> StagedActionStatus.COMPLETED
            ActionExecutionStatus.WAITING -> StagedActionStatus.WAITING_EXTERNAL
            ActionExecutionStatus.FAILED -> StagedActionStatus.FAILED
        }

    private fun prepare(action: PendingAction, conversationContext: ConversationContext): PreparedAction =
        PreparedAction(
            id = nextId(),
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            actionType = action.type,
            summary = action.summary,
            payload = action.payload,
            conversationContext = conversationContext,
            provenance = when (conversationContext.security.instructionTrust) {
                ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION ->
                    Provenances.trustedMessage(
                        provider = conversationContext.security.channel.provider,
                        sourceRef = action.rootInputId,
                    )
                ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION ->
                    Provenances.defaultExternal(sourceRef = action.rootInputId)
            },
            origin = action.origin,
        )

    private fun StagedAction.toPendingAction(): PendingAction =
        PendingAction(
            id = -1L,
            urgency = ai.neopsyke.agent.model.Urgency.MEDIUM,
            type = actionType,
            payload = payload,
            summary = summary,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext,
            origin = origin,
        )

    private fun hashAction(action: PendingAction): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val raw = listOf(
            action.type.id,
            action.summary,
            action.payload,
            action.rootInputId.orEmpty(),
            action.conversationContext.sessionId,
        ).joinToString(separator = "\n")
        return digest.digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private fun nextId(): String = UUID.randomUUID().toString()

    private companion object {
        const val AUTONOMOUS_WORKER_PRINCIPAL_ID: String = "policy-autonomous-worker"
        const val AUTONOMOUS_WORKER_CHANNEL_ID: String = "action-control-worker"
        const val BYPASS_POLICY_VERSION: String = "runtime-bypass-v1"
        const val BYPASS_REASON_CODE: String = "ACTION_BYPASS_EXECUTION"
    }
}
