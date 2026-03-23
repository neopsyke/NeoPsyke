package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionRecordImportance
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun `store persists ledger entries`() {
        val tempDir = Files.createTempDirectory("action-control-ledger-test")
        val dbPath = tempDir.resolve("action-control.db").toString()

        SqliteActionControlStore(dbPath).use { store ->
            val entry = store.saveLedgerEntry(
                ActionLedgerEntry(
                    id = "ledger-1",
                    kind = ActionLedgerKind.DENIED,
                    importance = ActionRecordImportance.SIGNAL,
                    actionType = ActionType.CONTACT_USER,
                    summary = "Task verifier denied action",
                    rootInputId = "root-1",
                    source = "task_verifier",
                    reasonCode = "TASK_DENIED",
                    conversationContext = ConversationContext.default(),
                )
            )

            assertNotNull(store.ledgerEntry(entry.id))
            assertEquals(1, store.listLedgerEntries(20).size)
            assertEquals(ActionLedgerKind.DENIED, store.listLedgerEntries(20).single().kind)
        }
    }

    @Test
    fun `store exposes only earliest runnable action per cognitive thread`() {
        val tempDir = Files.createTempDirectory("action-control-store-thread-test")
        val dbPath = tempDir.resolve("action-control.db").toString()

        SqliteActionControlStore(dbPath).use { store ->
            store.saveStagedAction(
                stagedAction(
                    id = "stage-thread-1",
                    rootInputId = "root-1",
                    threadSequence = 1L,
                    executionKey = "goal:1",
                    createdAtMs = 10L,
                )
            )
            store.saveStagedAction(
                stagedAction(
                    id = "stage-thread-2",
                    rootInputId = "root-1",
                    threadSequence = 2L,
                    executionKey = "goal:2",
                    createdAtMs = 20L,
                )
            )

            val firstRunnable = store.listRunnableReadyAutonomousActions(limit = 10)
            assertContentEquals(listOf("stage-thread-1"), firstRunnable.map { it.id })

            store.updateStagedAction(firstRunnable.single().copy(status = StagedActionStatus.COMPLETED, updatedAtMs = 30L))

            val secondRunnable = store.listRunnableReadyAutonomousActions(limit = 10)
            assertContentEquals(listOf("stage-thread-2"), secondRunnable.map { it.id })
        }
    }

    @Test
    fun `store exposes only earliest runnable action per execution key`() {
        val tempDir = Files.createTempDirectory("action-control-store-key-test")
        val dbPath = tempDir.resolve("action-control.db").toString()

        SqliteActionControlStore(dbPath).use { store ->
            store.saveStagedAction(
                stagedAction(
                    id = "stage-key-1",
                    rootInputId = "root-1",
                    threadSequence = 1L,
                    executionKey = "contact:webapp:default-owner",
                    createdAtMs = 10L,
                )
            )
            store.saveStagedAction(
                stagedAction(
                    id = "stage-key-2",
                    rootInputId = "root-2",
                    threadSequence = 1L,
                    executionKey = "contact:webapp:default-owner",
                    createdAtMs = 20L,
                )
            )

            val runnable = store.listRunnableReadyAutonomousActions(limit = 10)
            assertContentEquals(listOf("stage-key-1"), runnable.map { it.id })

            val authorization = CommitAuthorization(
                id = "auth-claim-1",
                stagedActionId = "stage-key-1",
                commitMode = CommitMode.POLICY_AUTONOMOUS,
                grantedByPrincipalId = "worker",
                grantedByChannelId = "worker",
                policyVersion = "test-v1",
                actionHash = "hash-stage-key-1",
            )
            val claimed = store.tryClaimAutonomousReadyAction(
                stagedActionId = "stage-key-1",
                authorization = authorization,
                updatedAtMs = 25L,
            )
            assertNotNull(claimed)
            assertEquals(StagedActionStatus.EXECUTING, claimed.status)

            val whileExecuting = store.listRunnableReadyAutonomousActions(limit = 10)
            assertEquals(emptyList(), whileExecuting.map { it.id })

            store.updateStagedAction(claimed.copy(status = StagedActionStatus.COMPLETED, updatedAtMs = 30L))

            val afterCompletion = store.listRunnableReadyAutonomousActions(limit = 10)
            assertContentEquals(listOf("stage-key-2"), afterCompletion.map { it.id })
        }
    }

    private fun stagedAction(
        id: String,
        rootInputId: String,
        threadSequence: Long,
        executionKey: String?,
        createdAtMs: Long,
    ): StagedAction =
        StagedAction(
            id = id,
            preparedActionId = "prep-$id",
            rootInputId = rootInputId,
            threadSequence = threadSequence,
            executionKey = executionKey,
            actionType = ActionType.CONTACT_USER,
            summary = id,
            payload = id,
            conversationContext = ConversationContext.default(),
            commitMode = CommitMode.POLICY_AUTONOMOUS,
            status = StagedActionStatus.READY,
            actionHash = "hash-$id",
            policyVersion = "test-v1",
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
        )
}
