package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.StagedAction

interface ActionControlStore : AutoCloseable {
    fun nextThreadSequence(rootInputId: String): Long
    fun saveStagedAction(action: StagedAction): StagedAction
    fun updateStagedAction(action: StagedAction): StagedAction
    fun stagedAction(id: String): StagedAction?
    fun listStagedActions(limit: Int, includeTerminal: Boolean = true): List<StagedAction>
    fun listRunnableReadyAutonomousActions(limit: Int): List<StagedAction>
    fun tryClaimAutonomousReadyAction(
        stagedActionId: String,
        authorization: CommitAuthorization,
        updatedAtMs: Long,
    ): StagedAction?

    fun saveAuthorization(authorization: CommitAuthorization): CommitAuthorization
    fun authorization(id: String): CommitAuthorization?

    fun saveReceipt(receipt: ActionReceipt): ActionReceipt
    fun receipt(id: String): ActionReceipt?
    fun listReceipts(limit: Int): List<ActionReceipt>

    fun saveLedgerEntry(entry: ActionLedgerEntry): ActionLedgerEntry
    fun ledgerEntry(id: String): ActionLedgerEntry?
    fun listLedgerEntries(limit: Int): List<ActionLedgerEntry>

    override fun close() {}
}
