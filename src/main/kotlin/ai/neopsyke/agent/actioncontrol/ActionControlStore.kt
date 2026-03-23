package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.StagedAction

interface ActionControlStore : AutoCloseable {
    fun saveStagedAction(action: StagedAction): StagedAction
    fun updateStagedAction(action: StagedAction): StagedAction
    fun stagedAction(id: String): StagedAction?
    fun listStagedActions(limit: Int): List<StagedAction>

    fun saveAuthorization(authorization: CommitAuthorization): CommitAuthorization
    fun authorization(id: String): CommitAuthorization?

    fun saveReceipt(receipt: ActionReceipt): ActionReceipt
    fun receipt(id: String): ActionReceipt?
    fun listReceipts(limit: Int): List<ActionReceipt>

    override fun close() {}
}
