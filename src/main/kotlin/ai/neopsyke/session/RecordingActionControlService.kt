package ai.neopsyke.session

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionRecordImportance
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedAction

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()

/**
 * Wraps an [ActionControlService] to record and replay authorization outcomes.
 *
 * Records the outcome type (Executed, Staged, Refused, Cancelled) of each
 * `handleAuthorizationDecision` call. During replay, verifies that the same
 * action type + authorization progress combination occurs in the same order.
 *
 * Also records `authorizeStagedAction`/`denyStagedAction` calls so user
 * approval decisions can be replayed without a dashboard.
 *
 * Hash strategy:
 * - `handleAuthorizationDecision`: SHA-256 of `actionType + decision.progress + decision.commitMode`
 * - `authorizeStagedAction`/`denyStagedAction`: sequence-based (order of user approvals)
 */
class RecordingActionControlService(
    private val delegate: ActionControlService,
    private val channel: RecordReplayChannel,
) : ActionControlService {

    override suspend fun handleAuthorizationDecision(
        action: PendingAction,
        decision: AuthorizationDecision,
        conversationContext: ConversationContext,
    ): ActionControlDecisionResult {
        return when (channel.mode) {
            SessionRecordingMode.RECORD -> {
                val result = delegate.handleAuthorizationDecision(action, decision, conversationContext)
                val seq = channel.nextSequenceIndex()
                val hash = hashAuthDecision(action, decision)
                channel.recordEntry(
                    SessionRecordEntry(
                        seq = seq,
                        hash = hash,
                        channel = SessionRecordingManager.CHANNEL_ACTION_CONTROL,
                        data = serializeOutcome(result),
                    )
                )
                result
            }
            SessionRecordingMode.REPLAY -> {
                if (channel.passthroughMode) {
                    return delegate.handleAuthorizationDecision(action, decision, conversationContext)
                }
                val seq = channel.nextSequenceIndex()
                if (seq >= channel.entryCount) {
                    logger.info { "Action control channel exhausted at seq=$seq, switching to live" }
                    return delegate.handleAuthorizationDecision(action, decision, conversationContext)
                }
                val hash = hashAuthDecision(action, decision)
                val data = channel.replayOrDiverge(seq, hash)
                if (data == null) {
                    logger.info { "Action control channel diverged at seq=$seq, switching to live" }
                    return delegate.handleAuthorizationDecision(action, decision, conversationContext)
                }
                // Hash matched — the same action+decision combination arrived.
                // Delegate to the real service since the logic is deterministic
                // given the same inputs.
                delegate.handleAuthorizationDecision(action, decision, conversationContext)
            }
            SessionRecordingMode.OFF ->
                delegate.handleAuthorizationDecision(action, decision, conversationContext)
        }
    }

    override suspend fun authorizeStagedAction(
        stagedActionId: String,
        grantedBy: ConversationSecurityContext,
        expectedActionHash: String?,
    ): ActionControlDecisionResult {
        // User approval — record for replay
        val result = delegate.authorizeStagedAction(stagedActionId, grantedBy, expectedActionHash)
        if (channel.mode == SessionRecordingMode.RECORD) {
            val seq = channel.nextSequenceIndex()
            channel.recordEntry(
                SessionRecordEntry(
                    seq = seq,
                    hash = RecordReplayChannel.hashContent("user_approval:$seq"),
                    channel = SessionRecordingManager.CHANNEL_ACTION_CONTROL,
                    data = serializeUserDecision(approved = true, stagedActionId = stagedActionId),
                )
            )
        }
        return result
    }

    override suspend fun denyStagedAction(
        stagedActionId: String,
        deniedBy: ConversationSecurityContext,
        reason: String,
        reasonCode: String?,
    ): ActionControlDecisionResult {
        val result = delegate.denyStagedAction(stagedActionId, deniedBy, reason, reasonCode)
        if (channel.mode == SessionRecordingMode.RECORD) {
            val seq = channel.nextSequenceIndex()
            channel.recordEntry(
                SessionRecordEntry(
                    seq = seq,
                    hash = RecordReplayChannel.hashContent("user_approval:$seq"),
                    channel = SessionRecordingManager.CHANNEL_ACTION_CONTROL,
                    data = serializeUserDecision(
                        approved = false,
                        stagedActionId = stagedActionId,
                        reason = reason,
                        reasonCode = reasonCode,
                    ),
                )
            )
        }
        return result
    }

    // ── Delegated methods (no recording needed) ─────────────────────────

    override suspend fun processAutonomousStagedActions(limit: Int) =
        delegate.processAutonomousStagedActions(limit)

    override suspend fun recordBypassExecution(
        action: PendingAction,
        conversationContext: ConversationContext,
        outcome: ActionOutcome,
        reason: String,
        reasonCode: String?,
    ) = delegate.recordBypassExecution(action, conversationContext, outcome, reason, reasonCode)

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
    ) = delegate.recordLedgerEntry(
        action, conversationContext, kind, importance, summary,
        reasonCode, source, stagedActionId, authorizationId, receiptId,
    )

    override fun stagedActions(limit: Int, includeTerminal: Boolean) =
        delegate.stagedActions(limit, includeTerminal)

    override fun stagedAction(id: String) = delegate.stagedAction(id)
    override fun receipts(limit: Int) = delegate.receipts(limit)
    override fun receipt(id: String) = delegate.receipt(id)
    override fun ledgerEntries(limit: Int) = delegate.ledgerEntries(limit)
    override fun ledgerEntry(id: String) = delegate.ledgerEntry(id)

    companion object {
        private fun hashAuthDecision(action: PendingAction, decision: AuthorizationDecision): String =
            RecordReplayChannel.hashContent(
                action.type.name,
                decision.progress.name,
                decision.commitMode.name,
            )

        private fun serializeOutcome(result: ActionControlDecisionResult): ObjectNode {
            val node = mapper.createObjectNode()
            when (result) {
                is ActionControlDecisionResult.Executed -> {
                    node.put("outcome_type", "executed")
                    node.put("action_type", result.executedAction.type.name)
                }
                is ActionControlDecisionResult.Staged -> {
                    node.put("outcome_type", "staged")
                    node.put("action_type", result.stagedAction.actionType.name)
                    node.put("status", result.stagedAction.status.name)
                }
                is ActionControlDecisionResult.Refused -> {
                    node.put("outcome_type", "refused")
                    node.put("reason", result.reason)
                    if (result.reasonCode != null) node.put("reason_code", result.reasonCode)
                }
                is ActionControlDecisionResult.Cancelled -> {
                    node.put("outcome_type", "cancelled")
                }
            }
            return node
        }

        private fun serializeUserDecision(
            approved: Boolean,
            stagedActionId: String,
            reason: String? = null,
            reasonCode: String? = null,
        ): ObjectNode {
            val node = mapper.createObjectNode()
            node.put("user_decision", true)
            node.put("approved", approved)
            node.put("staged_action_id", stagedActionId)
            if (reason != null) node.put("reason", reason)
            if (reasonCode != null) node.put("reason_code", reasonCode)
            return node
        }
    }
}
