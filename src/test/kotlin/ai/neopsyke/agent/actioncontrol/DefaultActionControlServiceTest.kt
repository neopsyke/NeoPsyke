package ai.neopsyke.agent.cortex.motor.actions.control

import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.DefaultActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.SqliteActionControlStore
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            val stagedResult = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 1,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.CONTACT_USER,
                        payload = "hello",
                        summary = "reply",
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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

            val executed = runBlocking {
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

            runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 11,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"revise","goal_id":"goal-1","step":"first"}""",
                        summary = "first action",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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

            val firstBatch = runBlocking {
                service.processAutonomousStagedActions(limit = 10)
            }
            assertEquals(1, firstBatch.size)
            assertEquals("first action", firstBatch.single().stagedAction.summary)
            assertContentEquals(listOf("""{"operation":"revise","goal_id":"goal-1","step":"first"}"""), executedPayloads)

            val secondBatch = runBlocking {
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
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            )
            val outcome = ActionOutcome(
                statusSummary = "Delivered fallback explanation",
                executionStatus = ActionExecutionStatus.SUCCESS,
                plannerSignal = "fallback delivered",
            )

            val receipt = runBlocking {
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
            val stagedResult = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 21,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.CONTACT_USER,
                        payload = "reply",
                        summary = "reply for approval",
                        conversationContext = ownerContext,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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

            val denied = runBlocking {
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

    @Test
    fun `reflect evidence enforces both family and evidence specific rate limits`() {
        val tempDir = Files.createTempDirectory("action-control-reflect-rate-limit-test")
        val dbPath = tempDir.resolve("action-control.db").toString()
        SqliteActionControlStore(dbPath).use { store ->
            val service = DefaultActionControlService(
                config = ActionControlConfig(
                    dbPath = dbPath,
                    reflectionFamilyPerRootInput = 2,
                    reflectEvidencePerRootInput = 1,
                ),
                store = store,
            ) { action, _ ->
                ActionOutcome(
                    statusSummary = "Executed ${action.type.id}",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                )
            }
            val context = ConversationContext.default()
            val rootInputId = "root-reflect-rate-limit"

            val first = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 101,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.REFLECT_INTERNAL,
                        payload = """{"summary":"trusted insight","keywords":["one"]}""",
                        summary = "reflect one",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage reflection",
                        reasonCode = "TEST_REFLECT_STAGE",
                    ),
                    conversationContext = context,
                )
            }
            assertTrue(first is ActionControlDecisionResult.Staged)

            val second = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 102,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.REFLECT_EVIDENCE,
                        payload = """{"artifact_ids":["artifact-1"],"summary_hint":"evidence insight","keywords":["gmail"]}""",
                        summary = "reflect evidence",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage reflection",
                        reasonCode = "TEST_REFLECT_STAGE",
                    ),
                    conversationContext = context,
                )
            }
            assertTrue(second is ActionControlDecisionResult.Staged)

            val third = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 103,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.REFLECT_EVIDENCE,
                        payload = """{"artifact_ids":["artifact-2"],"summary_hint":"second evidence insight","keywords":["calendar"]}""",
                        summary = "reflect evidence second",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage reflection",
                        reasonCode = "TEST_REFLECT_STAGE",
                    ),
                    conversationContext = context,
                )
            }

            val refused = third as ActionControlDecisionResult.Refused
            assertEquals("ACTION_RATE_LIMIT_EXCEEDED", refused.reasonCode)
        }
    }

    @Test
    fun `goal operation rate limit is scoped by normalized operation kind`() {
        val tempDir = Files.createTempDirectory("action-control-goal-rate-limit-test")
        val dbPath = tempDir.resolve("action-control.db").toString()
        SqliteActionControlStore(dbPath).use { store ->
            val service = DefaultActionControlService(
                config = ActionControlConfig(
                    dbPath = dbPath,
                    goalOperationPerRootInput = 1,
                ),
                store = store,
            ) { action, _ ->
                ActionOutcome(
                    statusSummary = "Executed ${action.type.id}",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                )
            }
            val context = ConversationContext.default()
            val rootInputId = "root-goal-rate-limit"

            val listResult = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 201,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"list"}""",
                        summary = "list goals",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage goal operation",
                        reasonCode = "TEST_GOAL_STAGE",
                    ),
                    conversationContext = context,
                )
            }
            assertTrue(listResult is ActionControlDecisionResult.Staged)

            val createResult = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 202,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"create","title":"Goal","instruction":"Do it"}""",
                        summary = "create goal",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage goal operation",
                        reasonCode = "TEST_GOAL_STAGE",
                    ),
                    conversationContext = context,
                )
            }
            assertTrue(createResult is ActionControlDecisionResult.Staged)

            val secondCreate = runBlocking {
                service.handleAuthorizationDecision(
                    action = PendingAction(
                        id = 203,
                        urgency = Urgency.MEDIUM,
                        type = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"create","title":"Goal 2","instruction":"Do it again"}""",
                        summary = "create goal 2",
                        rootInputId = rootInputId,
                        conversationContext = context,
                        groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                    ),
                    decision = AuthorizationDecision(
                        progress = AuthorizationProgress.ALLOW_STAGE,
                        commitMode = CommitMode.POLICY_AUTONOMOUS,
                        policyVersion = "test-v1",
                        reason = "stage goal operation",
                        reasonCode = "TEST_GOAL_STAGE",
                    ),
                    conversationContext = context,
                )
            }

            val refused = secondCreate as ActionControlDecisionResult.Refused
            assertEquals("ACTION_RATE_LIMIT_EXCEEDED", refused.reasonCode)
        }
    }
}
