package ai.neopsyke.agent.cortex.motor.actions.control

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ai.neopsyke.agent.cortex.motor.actions.control.SqliteActionControlStore
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionRecordImportance
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PolicyScope
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqliteActionControlStoreTest {
    private val mapper = jacksonObjectMapper()

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

    @Test
    fun `store reads staged actions with legacy policy scope id payloads`() {
        val tempDir = Files.createTempDirectory("action-control-store-legacy-stage")
        val dbPath = tempDir.resolve("action-control.db").toString()

        SqliteActionControlStore(dbPath).use { store ->
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO staged_actions(
                      id, status, commit_mode, action_type, root_input_id, thread_sequence, execution_key, created_at_ms, updated_at_ms, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "legacy-stage")
                    statement.setString(2, StagedActionStatus.WAITING_AUTHORIZATION.name)
                    statement.setString(3, CommitMode.APPROVAL_BACKED.name)
                    statement.setString(4, ActionType.CONTACT_USER.id)
                    statement.setString(5, "root-legacy")
                    statement.setLong(6, 1L)
                    statement.setString(7, "contact:web")
                    statement.setLong(8, 100L)
                    statement.setLong(9, 100L)
                    statement.setString(10, legacyStagedActionJson(policyScopeId = "deployment-restricted"))
                    statement.executeUpdate()
                }
            }

            val staged = store.stagedAction("legacy-stage")
            assertNotNull(staged)
            assertEquals(PolicyScope.DEPLOYMENT_RESTRICTED, staged.conversationContext.security.policyScope)
            assertEquals(listOf("legacy-stage"), store.listStagedActions(10).map { it.id })
        }
    }

    @Test
    fun `store reads ledger entries with legacy policy scope id payloads`() {
        val tempDir = Files.createTempDirectory("action-control-store-legacy-ledger")
        val dbPath = tempDir.resolve("action-control.db").toString()

        SqliteActionControlStore(dbPath).use { store ->
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO action_ledger_entries(
                      id, created_at_ms, importance, kind, action_type, root_input_id, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "legacy-ledger")
                    statement.setLong(2, 200L)
                    statement.setString(3, ActionRecordImportance.SIGNAL.name)
                    statement.setString(4, ActionLedgerKind.DENIED.name)
                    statement.setString(5, ActionType.CONTACT_USER.id)
                    statement.setString(6, "root-legacy")
                    statement.setString(7, legacyLedgerEntryJson(policyScopeId = "full-autonomy"))
                    statement.executeUpdate()
                }
            }

            val ledger = store.ledgerEntry("legacy-ledger")
            assertNotNull(ledger)
            assertEquals(PolicyScope.FULL_AUTONOMY, ledger.conversationContext.security.policyScope)
            assertEquals(listOf("legacy-ledger"), store.listLedgerEntries(10).map { it.id })
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

    private fun legacyStagedActionJson(policyScopeId: String): String =
        legacyPolicyScopePayload(
            StagedAction(
                id = "legacy-stage",
                preparedActionId = "prep-legacy-stage",
                rootInputId = "root-legacy",
                rootInputReceivedAtMs = 99L,
                threadSequence = 1L,
                executionKey = "contact:web",
                actionType = ActionType.CONTACT_USER,
                summary = "reply",
                payload = "hello",
                conversationContext = ConversationContext(
                    sessionId = "legacy-session",
                    interlocutor = Interlocutor.named("owner"),
                    security = ai.neopsyke.agent.model.ConversationSecurityContexts.ownerDirect(
                        provider = "web",
                        channelId = "legacy-session",
                        policyScope = PolicyScope.DEPLOYMENT_RESTRICTED,
                    ),
                ),
                commitMode = CommitMode.APPROVAL_BACKED,
                status = StagedActionStatus.WAITING_AUTHORIZATION,
                actionHash = "hash-legacy",
                policyVersion = "test-v1",
                createdAtMs = 100L,
                updatedAtMs = 100L,
            ),
            policyScopeId = policyScopeId,
        )

    private fun legacyLedgerEntryJson(policyScopeId: String): String =
        legacyPolicyScopePayload(
            ActionLedgerEntry(
                id = "legacy-ledger",
                kind = ActionLedgerKind.DENIED,
                importance = ActionRecordImportance.SIGNAL,
                actionType = ActionType.CONTACT_USER,
                summary = "denied",
                rootInputId = "root-legacy",
                stagedActionId = "legacy-stage",
                reasonCode = "ACTION_RATE_LIMIT_EXCEEDED",
                source = "test",
                conversationContext = ConversationContext(
                    sessionId = "legacy-session",
                    interlocutor = Interlocutor.named("owner"),
                    security = ai.neopsyke.agent.model.ConversationSecurityContexts.ownerDirect(
                        provider = "web",
                        channelId = "legacy-session",
                        policyScope = PolicyScope.FULL_AUTONOMY,
                    ),
                ),
                createdAtMs = 200L,
            ),
            policyScopeId = policyScopeId,
        )

    private fun legacyPolicyScopePayload(value: Any, policyScopeId: String): String {
        val root = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(value)
        val conversationContext = root.get("conversationContext") as com.fasterxml.jackson.databind.node.ObjectNode
        val security = conversationContext.get("security") as com.fasterxml.jackson.databind.node.ObjectNode
        security.remove("policyScope")
        security.put("policyScopeId", policyScopeId)
        return mapper.writeValueAsString(root)
    }
}
