package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultActionControlServiceTest {

    @Test
    fun `policy autonomous staged actions are marked ready and executed through autonomous processor`() {
        val tempDir = Files.createTempDirectory("action-control-service-test")
        val dbPath = tempDir.resolve("action-control.db").toString()
        SqliteActionControlStore(dbPath).use { store ->
            val service = DefaultActionControlService(
                config = ActionControlConfig(dbPath = dbPath),
                store = store,
            ) { action, _ ->
                ActionOutcome(
                    statusSummary = "Executed ${action.type.id}",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                )
            }
            val context = ConversationContext.default()
            val stagedResult = kotlinx.coroutines.runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 1,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.CONTACT_USER,
                        payload = "hello",
                        summary = "reply",
                        conversationContext = context,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage before autonomous execution",
                        reasonCode = "TEST_AUTONOMOUS_STAGE",
                    ),
                    conversationContext = context,
                )
            }

            val staged = stagedResult as ActionControlDecisionResult.Staged
            assertEquals(StagedActionStatus.READY, staged.stagedAction.status)

            val executed = kotlinx.coroutines.runBlocking {
                service.processAutonomousStagedActions(limit = 10)
            }
            assertEquals(1, executed.size)
            assertEquals(StagedActionStatus.COMPLETED, executed.single().stagedAction.status)
            val authorization = store.authorization(executed.single().authorization.id)
            assertNotNull(authorization)
            assertEquals(CommitMode.POLICY_AUTONOMOUS, authorization.commitMode)
            assertEquals("policy-autonomous-worker", authorization.grantedByPrincipalId)
            assertEquals(1, store.listReceipts(10).size)
        }
    }

    @Test
    fun `autonomous processor preserves same thread action order across polling cycles`() {
        val tempDir = Files.createTempDirectory("action-control-thread-order-test")
        val dbPath = tempDir.resolve("action-control.db").toString()
        SqliteActionControlStore(dbPath).use { store ->
            val executedPayloads = mutableListOf<String>()
            val service = DefaultActionControlService(
                config = ActionControlConfig(dbPath = dbPath),
                store = store,
            ) { action, _ ->
                executedPayloads += action.payload
                ActionOutcome(
                    statusSummary = "Executed ${action.payload}",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                )
            }
            val context = ConversationContext.default()
            val rootInputId = "root-thread-order"

            kotlinx.coroutines.runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 11,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"revise","goal_id":"goal-1","step":"first"}""",
                        summary = "first action",
                        rootInputId = rootInputId,
                        conversationContext = context,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "autonomous stage",
                        reasonCode = "TEST_THREAD_ORDER",
                    ),
                    conversationContext = context,
                )
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 12,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"revise","goal_id":"goal-2","step":"second"}""",
                        summary = "second action",
                        rootInputId = rootInputId,
                        conversationContext = context,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "autonomous stage",
                        reasonCode = "TEST_THREAD_ORDER",
                    ),
                    conversationContext = context,
                )
            }

            val firstBatch = kotlinx.coroutines.runBlocking {
                service.processAutonomousStagedActions(limit = 10)
            }
            assertEquals(1, firstBatch.size)
            assertEquals("first action", firstBatch.single().stagedAction.summary)
            assertContentEquals(listOf("""{"operation":"revise","goal_id":"goal-1","step":"first"}"""), executedPayloads)

            val secondBatch = kotlinx.coroutines.runBlocking {
                service.processAutonomousStagedActions(limit = 10)
            }
            assertEquals(1, secondBatch.size)
            assertEquals("second action", secondBatch.single().stagedAction.summary)
            assertContentEquals(
                listOf(
                    """{"operation":"revise","goal_id":"goal-1","step":"first"}""",
                    """{"operation":"revise","goal_id":"goal-2","step":"second"}""",
                ),
                executedPayloads,
            )
        }
    }

    @Test
    fun `bypass executions are persisted as durable receipts`() {
        val tempDir = Files.createTempDirectory("action-control-bypass-test")
        val dbPath = tempDir.resolve("action-control.db").toString()
        SqliteActionControlStore(dbPath).use { store ->
            val service = DefaultActionControlService(
                config = ActionControlConfig(dbPath = dbPath),
                store = store,
            ) { _, _ ->
                error("Bypass receipt test should not execute committed actions")
            }
            val action = PendingAction(
                id = 7,
                urgency = Urgency.MEDIUM,
                type = ActionType.CONTACT_USER,
                payload = "fallback",
                summary = "fallback explanation",
                conversationContext = ConversationContext.default(),
            )
            val outcome = ActionOutcome(
                statusSummary = "Delivered fallback explanation",
                executionStatus = ActionExecutionStatus.SUCCESS,
                plannerSignal = "fallback delivered",
            )

            val receipt = kotlinx.coroutines.runBlocking {
                service.recordBypassExecution(
                    action = action,
                    conversationContext = action.conversationContext,
                    outcome = outcome,
                    reason = "Fallback explanation bypass executed directly.",
                    reasonCode = "SYSTEM_FALLBACK_BYPASS",
                )
            }

            assertEquals(ActionExecutionStatus.SUCCESS, receipt.executionStatus)
            val persistedReceipt = store.receipt(receipt.id)
            assertNotNull(persistedReceipt)
            val staged = store.stagedAction(receipt.stagedActionId)
            assertNotNull(staged)
            assertEquals(StagedActionStatus.COMPLETED, staged.status)
            assertEquals(receipt.id, staged.receiptId)
            assertEquals("SYSTEM_FALLBACK_BYPASS", staged.statusReasonCode)
            assertNull(staged.authorizationId)
            val ledger = store.listLedgerEntries(10)
            assertEquals(ActionLedgerKind.BYPASS_EXECUTED, ledger.first().kind)
        }
    }

    @Test
    fun `owner can deny staged action and create signal ledger entry`() {
        val tempDir = Files.createTempDirectory("action-control-deny-test")
        val dbPath = tempDir.resolve("action-control.db").toString()
        SqliteActionControlStore(dbPath).use { store ->
            val ownerContext = ConversationContext(
                sessionId = ConversationContext.DEFAULT_SESSION_ID,
                interlocutor = ConversationContext.default().interlocutor,
                security = ConversationSecurityContexts.ownerDirect(
                    provider = "webapp",
                    channelId = "dashboard-test",
                ),
            )
            val service = DefaultActionControlService(
                config = ActionControlConfig(dbPath = dbPath),
                store = store,
            ) { _, _ ->
                error("Denied staged action should not execute")
            }
            val stagedResult = kotlinx.coroutines.runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 21,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.CONTACT_USER,
                        payload = "reply",
                        summary = "reply for approval",
                        conversationContext = ownerContext,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.APPROVAL_BACKED,
                        policyVersion = "test-v1",
                        reason = "Approval required",
                        reasonCode = "TEST_APPROVAL_REQUIRED",
                    ),
                    conversationContext = ownerContext,
                )
            } as ActionControlDecisionResult.Staged

            val denied = kotlinx.coroutines.runBlocking {
                service.denyStagedAction(
                    stagedActionId = stagedResult.stagedAction.id,
                    deniedBy = ownerContext.security,
                    reason = "No longer needed",
                    reasonCode = "TEST_OWNER_DENIED",
                )
            }

            denied as ActionControlDecisionResult.Cancelled
            assertEquals(StagedActionStatus.CANCELLED, denied.stagedAction.status)
            assertEquals(ActionLedgerKind.CANCELLED, denied.ledgerEntry.kind)
            assertEquals("TEST_OWNER_DENIED", denied.ledgerEntry.reasonCode)
        }
    }
}
