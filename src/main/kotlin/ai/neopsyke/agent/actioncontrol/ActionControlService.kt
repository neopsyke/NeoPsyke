package ai.neopsyke.agent.actioncontrol

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionRecordImportance
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionType
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

    data class Cancelled(
        val stagedAction: StagedAction,
        val ledgerEntry: ActionLedgerEntry,
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

    suspend fun denyStagedAction(
        stagedActionId: String,
        deniedBy: ConversationSecurityContext,
        reason: String = "Denied from dashboard.",
        reasonCode: String? = null,
    ): ActionControlDecisionResult

    suspend fun processAutonomousStagedActions(limit: Int = 10): List<ActionControlDecisionResult.Executed>

    suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String? = null,
    ): ActionReceipt?

    fun recordLedgerEntry(
        action: PendingAction,
        conversationContext: ConversationContext,
        kind: ActionLedgerKind,
        importance: ActionRecordImportance,
        summary: String,
        reasonCode: String? = null,
        source: String? = null,
        stagedActionId: String? = null,
        authorizationId: String? = null,
        receiptId: String? = null,
    ): ActionLedgerEntry?

    fun stagedActions(limit: Int, includeTerminal: Boolean = true): List<StagedAction>
    fun stagedAction(id: String): StagedAction?
    fun receipts(limit: Int): List<ActionReceipt>
    fun receipt(id: String): ActionReceipt?
    fun ledgerEntries(limit: Int): List<ActionLedgerEntry>
    fun ledgerEntry(id: String): ActionLedgerEntry?
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

    override suspend fun denyStagedAction(
        stagedActionId: String,
        deniedBy: ConversationSecurityContext,
        reason: String,
        reasonCode: String?,
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

    override fun recordLedgerEntry(
        action: PendingAction,
        conversationContext: ConversationContext,
        kind: ActionLedgerKind,
        importance: ActionRecordImportance,
        summary: String,
        reasonCode: String?,
        source: String?,
        stagedActionId: String?,
        authorizationId: String?,
        receiptId: String?,
    ): ActionLedgerEntry? = null

    override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> = emptyList()
    override fun stagedAction(id: String): StagedAction? = null
    override fun receipts(limit: Int): List<ActionReceipt> = emptyList()
    override fun receipt(id: String): ActionReceipt? = null
    override fun ledgerEntries(limit: Int): List<ActionLedgerEntry> = emptyList()
    override fun ledgerEntry(id: String): ActionLedgerEntry? = null
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
                    argumentDataTrust = action.argumentDataTrust,
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
            argumentDataTrust = action.argumentDataTrust,
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

    override suspend fun denyStagedAction(
        stagedActionId: String,
        deniedBy: ConversationSecurityContext,
        reason: String,
        reasonCode: String?,
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

    override fun recordLedgerEntry(
        action: PendingAction,
        conversationContext: ConversationContext,
        kind: ActionLedgerKind,
        importance: ActionRecordImportance,
        summary: String,
        reasonCode: String?,
        source: String?,
        stagedActionId: String?,
        authorizationId: String?,
        receiptId: String?,
    ): ActionLedgerEntry? = null

    override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> = emptyList()
    override fun stagedAction(id: String): StagedAction? = null
    override fun receipts(limit: Int): List<ActionReceipt> = emptyList()
    override fun receipt(id: String): ActionReceipt? = null
    override fun ledgerEntries(limit: Int): List<ActionLedgerEntry> = emptyList()
    override fun ledgerEntry(id: String): ActionLedgerEntry? = null

    private fun nextId(): String = UUID.randomUUID().toString()
}

class DefaultActionControlService(
    private val config: ActionControlConfig,
    private val store: ActionControlStore,
    private val actionRegistry: ActionRegistry = ActionRegistry.empty(),
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
        rateLimitRefusal(action, conversationContext)?.let { return it }

        val prepared = prepare(action, conversationContext)
        // `threadSequence` ordering is enforced after the staged row exists. If staging later becomes
        // highly concurrent for the same root input across threads/processes, allocation should be
        // tightened into a transactional staging path rather than relying on pre-insert sequence reads.
        val threadSequence = action.rootInputId?.let(store::nextThreadSequence)
        val staged = store.saveStagedAction(
            StagedAction(
                id = nextId(),
                preparedActionId = prepared.id,
                rootInputId = action.rootInputId,
                rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                threadSequence = threadSequence,
                executionKey = deriveExecutionKey(action, conversationContext),
                actionType = action.type,
                summary = action.summary,
                payload = action.payload,
                argumentDataTrust = action.argumentDataTrust,
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
        saveStagedLedgerEntry(
            staged = staged,
            action = action,
            conversationContext = conversationContext,
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
        saveAuthorizedLedgerEntry(
            staged = staged,
            authorization = authorization,
            action = action,
            conversationContext = conversationContext,
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
        saveAuthorizedLedgerEntry(
            staged = staged,
            authorization = authorization,
            action = staged.toPendingAction(),
            conversationContext = staged.conversationContext,
        )
        return executeAuthorized(staged, authorization)
    }

    override suspend fun denyStagedAction(
        stagedActionId: String,
        deniedBy: ConversationSecurityContext,
        reason: String,
        reasonCode: String?,
    ): ActionControlDecisionResult {
        if (deniedBy.principal.role != PrincipalRole.OWNER &&
            deniedBy.principal.role != PrincipalRole.ADMIN_CONTROL
        ) {
            return ActionControlDecisionResult.Refused(
                reason = "Only owner or admin-control principals may deny staged actions.",
                reasonCode = "DENIAL_PRINCIPAL_NOT_ALLOWED",
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
                reason = "Staged action '${staged.id}' is not pending authorization or autonomous execution.",
                reasonCode = "STAGED_ACTION_NOT_DENYABLE",
            )
        }
        val cancelled = store.updateStagedAction(
            staged.copy(
                status = StagedActionStatus.CANCELLED,
                statusReason = reason,
                statusReasonCode = reasonCode ?: OWNER_DENIED_REASON_CODE,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
        val ledger = store.saveLedgerEntry(
            ActionLedgerEntry(
                id = nextId(),
                kind = ActionLedgerKind.CANCELLED,
                importance = ActionRecordImportance.SIGNAL,
                actionType = cancelled.actionType,
                summary = reason,
                rootInputId = cancelled.rootInputId,
                stagedActionId = cancelled.id,
                reasonCode = reasonCode ?: OWNER_DENIED_REASON_CODE,
                source = "dashboard_deny",
                conversationContext = cancelled.conversationContext,
            )
        )
        return ActionControlDecisionResult.Cancelled(cancelled, ledger)
    }

    override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> {
        val readyActions = store.listRunnableReadyAutonomousActions(
            limit.coerceAtLeast(1).coerceAtMost(config.maxInspectResults)
        )
        return buildList {
            readyActions.forEach { staged ->
                val nowMs = System.currentTimeMillis()
                val authorization = CommitAuthorization(
                    id = nextId(),
                    stagedActionId = staged.id,
                    commitMode = CommitMode.POLICY_AUTONOMOUS,
                    grantedByPrincipalId = AUTONOMOUS_WORKER_PRINCIPAL_ID,
                    grantedByChannelId = AUTONOMOUS_WORKER_CHANNEL_ID,
                    policyVersion = staged.policyVersion,
                    actionHash = staged.actionHash,
                    expiresAtMs = nowMs + config.authorizationTtlMs,
                )
                val claimed = store.tryClaimAutonomousReadyAction(
                    stagedActionId = staged.id,
                    authorization = authorization,
                    updatedAtMs = nowMs,
                ) ?: return@forEach
                when (val result = executeAuthorized(claimed, authorization)) {
                    is ActionControlDecisionResult.Executed -> add(result)
                    else -> Unit
                }
            }
        }
    }

    override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> =
        store.listStagedActions(
            limit = limit.coerceAtLeast(1).coerceAtMost(config.maxInspectResults),
            includeTerminal = includeTerminal,
        )

    override suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String?,
    ): ActionReceipt {
        val prepared = prepare(action, conversationContext)
        // Bypass executions still reserve thread order for inspectability. The same future caveat
        // applies here: concurrent same-root staging would need transactional sequence assignment.
        val threadSequence = action.rootInputId?.let(store::nextThreadSequence)
        val staged = store.saveStagedAction(
            StagedAction(
                id = nextId(),
                preparedActionId = prepared.id,
                rootInputId = action.rootInputId,
                rootInputReceivedAtMs = action.rootInputReceivedAtMs,
                threadSequence = threadSequence,
                executionKey = deriveExecutionKey(action, conversationContext),
                actionType = action.type,
                summary = action.summary,
                payload = action.payload,
                argumentDataTrust = action.argumentDataTrust,
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
                importance = receiptImportanceFor(outcome),
                executionStatus = outcome.executionStatus,
                statusSummary = outcome.statusSummary,
                plannerSignal = outcome.plannerSignal,
                effects = outcome.effects,
                asyncWait = outcome.asyncWait,
            )
        )
        store.saveLedgerEntry(
            ActionLedgerEntry(
                id = nextId(),
                kind = ActionLedgerKind.BYPASS_EXECUTED,
                importance = receipt.importance,
                actionType = action.type,
                summary = outcome.statusSummary,
                rootInputId = action.rootInputId,
                actionId = action.id,
                stagedActionId = staged.id,
                receiptId = receipt.id,
                reasonCode = reasonCode ?: BYPASS_REASON_CODE,
                source = "bypass_execution",
                conversationContext = conversationContext,
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

    override fun ledgerEntries(limit: Int): List<ActionLedgerEntry> =
        store.listLedgerEntries(limit.coerceAtLeast(1).coerceAtMost(config.maxInspectResults))

    override fun ledgerEntry(id: String): ActionLedgerEntry? = store.ledgerEntry(id)

    override fun recordLedgerEntry(
        action: PendingAction,
        conversationContext: ConversationContext,
        kind: ActionLedgerKind,
        importance: ActionRecordImportance,
        summary: String,
        reasonCode: String?,
        source: String?,
        stagedActionId: String?,
        authorizationId: String?,
        receiptId: String?,
    ): ActionLedgerEntry =
        store.saveLedgerEntry(
            ActionLedgerEntry(
                id = nextId(),
                kind = kind,
                importance = importance,
                actionType = action.type,
                summary = summary,
                rootInputId = action.rootInputId,
                actionId = action.id,
                stagedActionId = stagedActionId,
                authorizationId = authorizationId,
                receiptId = receiptId,
                reasonCode = reasonCode,
                source = source,
                conversationContext = conversationContext,
            )
        )

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
                importance = receiptImportanceFor(outcome),
                executionStatus = outcome.executionStatus,
                statusSummary = outcome.statusSummary,
                plannerSignal = outcome.plannerSignal,
                effects = outcome.effects,
                asyncWait = outcome.asyncWait,
            )
        )
        store.saveLedgerEntry(
            ActionLedgerEntry(
                id = nextId(),
                kind = if (outcome.executionStatus == ActionExecutionStatus.WAITING) {
                    ActionLedgerKind.WAITING_EXTERNAL
                } else {
                    ActionLedgerKind.EXECUTED
                },
                importance = receipt.importance,
                actionType = executing.actionType,
                summary = outcome.statusSummary,
                rootInputId = executing.rootInputId,
                stagedActionId = executing.id,
                authorizationId = authorization.id,
                receiptId = receipt.id,
                reasonCode = null,
                source = "execute_authorized",
                conversationContext = executing.conversationContext,
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

    private fun saveStagedLedgerEntry(
        staged: StagedAction,
        action: PendingAction,
        conversationContext: ConversationContext,
    ) {
        store.saveLedgerEntry(
            ActionLedgerEntry(
                id = nextId(),
                kind = ActionLedgerKind.STAGED,
                importance = when (staged.status) {
                    StagedActionStatus.WAITING_AUTHORIZATION -> ActionRecordImportance.SIGNAL
                    StagedActionStatus.READY,
                    StagedActionStatus.AUTHORIZED,
                    StagedActionStatus.EXECUTING,
                    StagedActionStatus.WAITING_EXTERNAL,
                    StagedActionStatus.COMPLETED,
                    StagedActionStatus.FAILED,
                    StagedActionStatus.CANCELLED -> ActionRecordImportance.BACKGROUND
                },
                actionType = staged.actionType,
                summary = staged.statusReason ?: "Action staged for lifecycle control.",
                rootInputId = staged.rootInputId,
                actionId = action.id,
                stagedActionId = staged.id,
                reasonCode = staged.statusReasonCode,
                source = "stage",
                conversationContext = conversationContext,
            )
        )
    }

    private fun saveAuthorizedLedgerEntry(
        staged: StagedAction,
        authorization: CommitAuthorization,
        action: PendingAction,
        conversationContext: ConversationContext,
    ) {
        store.saveLedgerEntry(
            ActionLedgerEntry(
                id = nextId(),
                kind = ActionLedgerKind.AUTHORIZED,
                importance = ActionRecordImportance.BACKGROUND,
                actionType = staged.actionType,
                summary = "Action authorized for commit.",
                rootInputId = staged.rootInputId,
                actionId = action.id,
                stagedActionId = staged.id,
                authorizationId = authorization.id,
                source = "authorize",
                conversationContext = conversationContext,
            )
        )
    }

    private fun receiptImportanceFor(outcome: ActionOutcome): ActionRecordImportance =
        when (outcome.executionStatus) {
            ActionExecutionStatus.FAILED -> ActionRecordImportance.SIGNAL
            ActionExecutionStatus.WAITING -> ActionRecordImportance.BACKGROUND
            ActionExecutionStatus.SUCCESS,
            ActionExecutionStatus.NO_EFFECT -> ActionRecordImportance.BACKGROUND
        }

    private fun prepare(action: PendingAction, conversationContext: ConversationContext): PreparedAction =
        PreparedAction(
            id = nextId(),
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            actionType = action.type,
            summary = action.summary,
            payload = action.payload,
            argumentDataTrust = action.argumentDataTrust,
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
            argumentDataTrust = argumentDataTrust,
            origin = origin,
        )

    private fun hashAction(action: PendingAction): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val raw = listOf(
            action.type.id,
            action.summary,
            action.payload,
            action.argumentDataTrust.name,
            action.rootInputId.orEmpty(),
            action.conversationContext.sessionId,
        ).joinToString(separator = "\n")
        return digest.digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private fun rateLimitRefusal(
        action: PendingAction,
        conversationContext: ConversationContext,
    ): ActionControlDecisionResult.Refused? {
        val rootInputId = action.rootInputId?.trim().orEmpty()
        if (rootInputId.isEmpty()) return null
        val scopedActions = store.listStagedActions(config.maxInspectResults)
            .asSequence()
            .filter { it.rootInputId == rootInputId }
            .filter { it.conversationContext.sessionId == conversationContext.sessionId }
            .filter { it.status != StagedActionStatus.CANCELLED }
            .toList()
        val effectClass = actionRegistry.contract(action.type)?.effectClass ?: defaultEffectClass(action)
        fun refusal(message: String): ActionControlDecisionResult.Refused =
            ActionControlDecisionResult.Refused(
                reason = message,
                reasonCode = "ACTION_RATE_LIMIT_EXCEEDED",
            )

        when (action.type) {
            ActionType.CONTACT_USER -> {
                val count = scopedActions.count { it.actionType == ActionType.CONTACT_USER }
                if (config.contactUserPerRootInput > 0 && count >= config.contactUserPerRootInput) {
                    return refusal("Action '${action.type.id}' exceeded the per-request rate limit.")
                }
                return null
            }

            ActionType.REFLECT_INTERNAL -> {
                val familyCount = scopedActions.count {
                    it.actionType == ActionType.REFLECT_INTERNAL || it.actionType == ActionType.REFLECT_EVIDENCE
                }
                if (config.reflectionFamilyPerRootInput > 0 && familyCount >= config.reflectionFamilyPerRootInput) {
                    return refusal("Action '${action.type.id}' exceeded the reflection-family rate limit.")
                }
                return null
            }

            ActionType.REFLECT_EVIDENCE -> {
                val familyCount = scopedActions.count {
                    it.actionType == ActionType.REFLECT_INTERNAL || it.actionType == ActionType.REFLECT_EVIDENCE
                }
                if (config.reflectionFamilyPerRootInput > 0 && familyCount >= config.reflectionFamilyPerRootInput) {
                    return refusal("Action '${action.type.id}' exceeded the reflection-family rate limit.")
                }
                val evidenceCount = scopedActions.count { it.actionType == ActionType.REFLECT_EVIDENCE }
                if (config.reflectEvidencePerRootInput > 0 && evidenceCount >= config.reflectEvidencePerRootInput) {
                    return refusal("Action '${action.type.id}' exceeded the evidence-reflection rate limit.")
                }
                return null
            }

            ActionType.GOAL_OPERATION -> {
                val limit = config.goalOperationPerRootInput
                if (limit <= 0) return null
                val matchingCount = scopedActions.count { goalOperationBucket(it) == goalOperationBucket(action) }
                if (matchingCount >= limit) {
                    return refusal("Action '${action.type.id}' exceeded the per-request rate limit for operation kind '${goalOperationBucket(action)}'.")
                }
                return null
            }

            else -> {
                val limit = when (effectClass) {
                    ActionEffectClass.OBSERVE -> config.observePerTypePerRootInput
                    ActionEffectClass.COMMIT_PRIVATE -> config.commitPrivatePerTypePerRootInput
                    ActionEffectClass.COMMIT_PUBLIC -> config.commitPublicPerTypePerRootInput
                    ActionEffectClass.COMMIT_STATEFUL -> config.commitStatefulPerTypePerRootInput
                    ActionEffectClass.CONTROL_PLANE -> config.controlPlanePerTypePerRootInput
                }
                if (limit <= 0) return null
                val matchingCount = scopedActions.count { it.actionType == action.type }
                if (matchingCount >= limit) {
                    return refusal("Action '${action.type.id}' exceeded the per-request rate limit.")
                }
                return null
            }
        }
    }

    private fun defaultEffectClass(action: PendingAction): ActionEffectClass =
        when (action.type) {
            ActionType.CONTACT_USER -> ActionEffectClass.COMMIT_PRIVATE
            ActionType.GOAL_OPERATION -> ActionEffectClass.CONTROL_PLANE
            ActionType.REFLECT_INTERNAL,
            ActionType.REFLECT_EVIDENCE -> ActionEffectClass.COMMIT_STATEFUL
            else -> ActionEffectClass.OBSERVE
        }

    private fun nextId(): String = UUID.randomUUID().toString()

    private fun deriveExecutionKey(
        action: PendingAction,
        conversationContext: ConversationContext,
    ): String? =
        when (action.type) {
            ActionType.CONTACT_USER -> {
                val channel = conversationContext.security.channel
                "contact:${channel.provider}:${channel.channelId}"
            }

            ActionType.GOAL_OPERATION -> deriveGoalExecutionKey(action)
            EMAIL_SEND_ACTION_TYPE -> deriveEmailExecutionKey(action)
            else -> null
        }

    private fun deriveGoalExecutionKey(action: PendingAction): String? {
        val payload = runCatching { payloadMapper.readTree(action.payload) }.getOrNull() ?: return null
        val goalId = payload.path("goal_id").textValue()
            ?.ifBlank { null }
            ?: payload.path("goalId").textValue()?.ifBlank { null }
        if (!goalId.isNullOrBlank()) {
            return "goal:${goalId.trim()}"
        }
        val operation = payload.path("operation").textValue()?.trim()?.lowercase().orEmpty()
        return if (operation in setOf("pause", "resume", "reprioritize", "complete", "revise_plan", "delete_all")) {
            "goal-operation:${action.rootInputId.orEmpty()}:${operation}"
        } else {
            null
        }
    }

    private fun goalOperationBucket(action: PendingAction): String {
        return goalOperationBucketPayload(action.type.id, action.payload)
    }

    private fun goalOperationBucket(action: StagedAction): String {
        return goalOperationBucketPayload(action.actionType.id, action.payload)
    }

    private fun goalOperationBucketPayload(actionTypeId: String, payloadRaw: String): String {
        val payload = runCatching { payloadMapper.readTree(payloadRaw) }.getOrNull()
        val operation = payload?.path("operation")?.asText()?.trim()?.lowercase().orEmpty()
        val cronExpression = payload?.path("cron_expression")?.asText()?.ifEmpty { payload.path("cronExpression").asText() }?.trim().orEmpty()
        val category = when (operation) {
            "list", "inspect" -> "goal_read"
            "create", "revise_plan" -> if (cronExpression.isNotBlank()) "goal_recurring_mutation" else "goal_mutation"
            "pause", "resume", "reprioritize", "complete", "delete", "delete_all" -> "goal_mutation"
            else -> "goal_other"
        }
        return "$actionTypeId:$category"
    }

    private fun deriveEmailExecutionKey(action: PendingAction): String? {
        val payload = runCatching { payloadMapper.readValue<EmailExecutionKeyPayload>(action.payload) }.getOrNull() ?: return null
        val sender = payload.sender?.trim().orEmpty()
            .ifBlank { payload.onBehalfOf?.trim().orEmpty() }
        val recipients = (payload.to.orEmpty() + payload.cc.orEmpty() + payload.bcc.orEmpty())
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
        if (sender.isBlank() || recipients.isEmpty()) {
            return null
        }
        return "email:$sender:${recipients.joinToString(",")}"
    }

    private companion object {
        const val AUTONOMOUS_WORKER_PRINCIPAL_ID: String = "policy-autonomous-worker"
        const val AUTONOMOUS_WORKER_CHANNEL_ID: String = "action-control-worker"
        const val BYPASS_POLICY_VERSION: String = "runtime-bypass-v1"
        const val BYPASS_REASON_CODE: String = "ACTION_BYPASS_EXECUTION"
        const val OWNER_DENIED_REASON_CODE: String = "OWNER_DENIED_STAGED_ACTION"
        val EMAIL_SEND_ACTION_TYPE = ActionType("email_send")
        val payloadMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

private data class EmailExecutionKeyPayload(
    val sender: String? = null,
    val onBehalfOf: String? = null,
    val to: List<String>? = null,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
)
