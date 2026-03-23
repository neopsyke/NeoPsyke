package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqliteActionControlStoreTest {

    @Test
    fun `store persists staged actions authorizations and receipts`() {
        val tempDir = Files.createTempDirectory("action-control-store-test")
        val dbPath = tempDir.resolve("action-control.db").toString()

        SqliteActionControlStore(dbPath).use { store ->
            val staged = store.saveStagedAction(
                StagedAction(
                    id = "stage-1",
                    preparedActionId = "prep-1",
                    rootInputId = "root-1",
                    actionType = ActionType.CONTACT_USER,
                    summary = "reply",
                    payload = "hello",
                    conversationContext = ConversationContext.default(),
                    commitMode = CommitMode.APPROVAL_BACKED,
                    status = StagedActionStatus.WAITING_AUTHORIZATION,
                    actionHash = "hash-1",
                )
            )
            val authorization = store.saveAuthorization(
                CommitAuthorization(
                    id = "auth-1",
                    stagedActionId = staged.id,
                    commitMode = CommitMode.APPROVAL_BACKED,
                    grantedByPrincipalId = "owner",
                    grantedByChannelId = "webapp",
                    policyVersion = "test-v1",
                    actionHash = staged.actionHash,
                )
            )
            val receipt = store.saveReceipt(
                ActionReceipt(
                    id = "receipt-1",
                    stagedActionId = staged.id,
                    authorizationId = authorization.id,
                    rootInputId = staged.rootInputId,
                    actionType = staged.actionType,
                    executionStatus = ActionExecutionStatus.SUCCESS,
                    statusSummary = "completed",
                )
            )

            assertNotNull(store.stagedAction(staged.id))
            assertNotNull(store.authorization(authorization.id))
            assertNotNull(store.receipt(receipt.id))
            assertEquals(1, store.listStagedActions(20).size)
            assertEquals(1, store.listReceipts(20).size)
        }
    }
}
