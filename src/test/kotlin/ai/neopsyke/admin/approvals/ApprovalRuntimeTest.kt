package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionRecordImportance
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.dashboard.DashboardStateStore
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ai.neopsyke.session.SessionRecordingManager
import ai.neopsyke.session.SessionRecordingMode
import org.junit.jupiter.api.Test

class ApprovalRuntimeTest {
    private class FakeActionControlService(
        private val stagedAction: StagedAction,
    ) : ActionControlService {
        var authorizeCalls: Int = 0
        var denyCalls: Int = 0

        override suspend fun handleAuthorizationDecision(
            action: PendingAction,
            decision: AuthorizationDecision,
            conversationContext: ConversationContext,
        ): ActionControlDecisionResult =
            ActionControlDecisionResult.Staged(
                stagedAction = stagedAction,
                authorizationDecision = AuthorizationDecision(
                    progress = AuthorizationProgress.ALLOW_STAGE,
                    commitMode = CommitMode.APPROVAL_BACKED,
                    reason = "approval required",
                ),
            )

        override suspend fun authorizeStagedAction(
            stagedActionId: String,
            grantedBy: ConversationSecurityContext,
        ): ActionControlDecisionResult {
            authorizeCalls += 1
            val authorization = CommitAuthorization(
                id = "auth-1",
                stagedActionId = stagedActionId,
                commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id,
                grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = "test",
                actionHash = stagedAction.actionHash,
            )
            val outcome = ActionOutcome(
                statusSummary = "done",
                executionStatus = ActionExecutionStatus.SUCCESS,
                plannerSignal = "done",
            )
            val receipt = ActionReceipt(
                id = "receipt-1",
                stagedActionId = stagedActionId,
                authorizationId = authorization.id,
                rootInputId = stagedAction.rootInputId,
                actionType = stagedAction.actionType,
                executionStatus = ActionExecutionStatus.SUCCESS,
                statusSummary = "done",
            )
            return ActionControlDecisionResult.Executed(
                stagedAction = stagedAction.copy(status = StagedActionStatus.COMPLETED),
                authorization = authorization,
                receipt = receipt,
                outcome = outcome,
                executedAction = PendingAction(
                    id = 1L,
                    urgency = Urgency.MEDIUM,
                    type = stagedAction.actionType,
                    payload = stagedAction.payload,
                    summary = stagedAction.summary,
                    rootInputId = stagedAction.rootInputId,
                    conversationContext = stagedAction.conversationContext,
                ),
            )
        }

        override suspend fun denyStagedAction(
            stagedActionId: String,
            deniedBy: ConversationSecurityContext,
            reason: String,
            reasonCode: String?,
        ): ActionControlDecisionResult {
            denyCalls += 1
            return ActionControlDecisionResult.Cancelled(
                stagedAction = stagedAction.copy(status = StagedActionStatus.CANCELLED),
                ledgerEntry = ActionLedgerEntry(
                    id = "ledger-1",
                    kind = ActionLedgerKind.CANCELLED,
                    importance = ActionRecordImportance.SIGNAL,
                    actionType = stagedAction.actionType,
                    summary = reason,
                    rootInputId = stagedAction.rootInputId,
                    stagedActionId = stagedAction.id,
                    reasonCode = reasonCode,
                    conversationContext = stagedAction.conversationContext,
                )
            )
        }

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
        override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> = listOf(stagedAction)
        override fun stagedAction(id: String): StagedAction? = stagedAction.takeIf { it.id == id }
        override fun receipts(limit: Int): List<ActionReceipt> = emptyList()
        override fun receipt(id: String): ActionReceipt? = null
        override fun ledgerEntries(limit: Int): List<ActionLedgerEntry> = emptyList()
        override fun ledgerEntry(id: String): ActionLedgerEntry? = null
    }

    @Test
    fun `approval prompt is delivered and approve reply authorizes staged action`() {
        runBlocking {
            val tempDb = Files.createTempFile("approval-runtime", ".db")
            val stagedAction = testStagedAction()
            val actionControl = FakeActionControlService(stagedAction)
            val dashboardStore = DashboardStateStore()
            dashboardStore.ensureChatSession(sessionId = stagedAction.conversationContext.sessionId)
            var executed = 0
            SqliteApprovalStore(tempDb.toString()).use { store ->
                val runtime = ApprovalRuntime(
                    config = AgentConfig(),
                    store = store,
                    actionControlService = actionControl,
                    dashboardStore = dashboardStore,
                    telegramConfig = ai.neopsyke.agent.config.TelegramChannelConfig(enabled = false),
                    telegramSink = null,
                    interpreter = DefaultApprovalInterpreter(AgentConfig()),
                    forwardNormalInput = { _, _, _, _ -> true },
                    onApprovalExecuted = { executed += 1 },
                    onApprovalDenied = {},
                )
                runtime.onApprovalStaged(
                    actionSummary = stagedAction.summary,
                    stagedAction = stagedAction,
                    reason = "Owner approval required.",
                    reasonCode = "NEEDS_APPROVAL",
                    conversationContext = stagedAction.conversationContext,
                )
                assertTrue(dashboardStore.chatSessionJson(stagedAction.conversationContext.sessionId).orEmpty().contains("Approval required."))

                val result = runtime.routeOwnerMessage(
                    OwnerMessageEnvelope(
                        content = "yes",
                        source = "chat:test",
                        priority = InputPriority.HIGH,
                        conversationContext = stagedAction.conversationContext,
                        receivedAtMs = System.currentTimeMillis(),
                    )
                )

                assertTrue(result is OwnerIngressResult.Consumed)
                assertEquals(1, actionControl.authorizeCalls)
                assertEquals(1, executed)
                assertEquals(
                    ApprovalRequestStatus.APPROVED,
                    store.requestByStagedActionId(stagedAction.id)?.status
                )
                runtime.close()
            }
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `modified approval reply denies and reissues to normal ingress`() {
        runBlocking {
            val tempDb = Files.createTempFile("approval-runtime", ".db")
            val stagedAction = testStagedAction()
            val actionControl = FakeActionControlService(stagedAction)
            val dashboardStore = DashboardStateStore()
            dashboardStore.ensureChatSession(sessionId = stagedAction.conversationContext.sessionId)
            var forwarded = 0
            SqliteApprovalStore(tempDb.toString()).use { store ->
                val runtime = ApprovalRuntime(
                    config = AgentConfig(),
                    store = store,
                    actionControlService = actionControl,
                    dashboardStore = dashboardStore,
                    telegramConfig = ai.neopsyke.agent.config.TelegramChannelConfig(enabled = false),
                    telegramSink = null,
                    interpreter = DefaultApprovalInterpreter(AgentConfig()),
                    forwardNormalInput = { _, _, _, _ ->
                        forwarded += 1
                        true
                    },
                    onApprovalExecuted = {},
                    onApprovalDenied = {},
                )
                runtime.onApprovalStaged(
                    actionSummary = stagedAction.summary,
                    stagedAction = stagedAction,
                    reason = "Owner approval required.",
                    reasonCode = "NEEDS_APPROVAL",
                    conversationContext = stagedAction.conversationContext,
                )

                val result = runtime.routeOwnerMessage(
                    OwnerMessageEnvelope(
                        content = "yes, send it tomorrow instead",
                        source = "chat:test",
                        priority = InputPriority.HIGH,
                        conversationContext = stagedAction.conversationContext,
                        receivedAtMs = System.currentTimeMillis(),
                    )
                )

                assertTrue(result is OwnerIngressResult.Forwarded)
                assertEquals(1, actionControl.denyCalls)
                assertEquals(1, forwarded)
                assertEquals(
                    ApprovalRequestStatus.DENIED,
                    store.requestByStagedActionId(stagedAction.id)?.status
                )
                runtime.close()
            }
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `approval replies replay from approval-flow recording channel`() {
        runBlocking {
            val sessionDir = Files.createTempDirectory("approval-runtime-session")
            val recordDb = Files.createTempFile("approval-runtime-record", ".db")
            val replayDb = Files.createTempFile("approval-runtime-replay", ".db")
            val stagedAction = testStagedAction()
            val dashboardStore = DashboardStateStore()
            dashboardStore.ensureChatSession(sessionId = stagedAction.conversationContext.sessionId)

            SessionRecordingManager(
                mode = SessionRecordingMode.RECORD,
                sessionDir = sessionDir,
            ).use { recordingManager ->
                SqliteApprovalStore(recordDb.toString()).use { store ->
                    val runtime = ApprovalRuntime(
                        config = AgentConfig(),
                        store = store,
                        actionControlService = FakeActionControlService(stagedAction),
                        dashboardStore = dashboardStore,
                        telegramConfig = ai.neopsyke.agent.config.TelegramChannelConfig(enabled = false),
                        telegramSink = null,
                        interpreter = DefaultApprovalInterpreter(AgentConfig()),
                        forwardNormalInput = { _, _, _, _ -> true },
                        onApprovalExecuted = {},
                        onApprovalDenied = {},
                        sessionRecordingManager = recordingManager,
                    )
                    runtime.onApprovalStaged(
                        actionSummary = stagedAction.summary,
                        stagedAction = stagedAction,
                        reason = "Owner approval required.",
                        reasonCode = "NEEDS_APPROVAL",
                        conversationContext = stagedAction.conversationContext,
                    )
                    val result = runtime.routeOwnerMessage(
                        OwnerMessageEnvelope(
                            content = "yes",
                            source = "chat:test",
                            priority = InputPriority.HIGH,
                            conversationContext = stagedAction.conversationContext,
                            receivedAtMs = System.currentTimeMillis(),
                        )
                    )
                    assertTrue(result is OwnerIngressResult.Consumed)
                    runtime.close()
                }
            }

            SessionRecordingManager(
                mode = SessionRecordingMode.REPLAY,
                sessionDir = sessionDir,
            ).use { replayManager ->
                SqliteApprovalStore(replayDb.toString()).use { store ->
                    val actionControl = FakeActionControlService(stagedAction)
                    val runtime = ApprovalRuntime(
                        config = AgentConfig(),
                        store = store,
                        actionControlService = actionControl,
                        dashboardStore = dashboardStore,
                        telegramConfig = ai.neopsyke.agent.config.TelegramChannelConfig(enabled = false),
                        telegramSink = null,
                        interpreter = ApprovalInterpreter {
                            ApprovalClassification(
                                kind = ApprovalClassificationKind.UNCLEAR,
                                usedModelAssistance = false,
                            )
                        },
                        forwardNormalInput = { _, _, _, _ -> true },
                        onApprovalExecuted = {},
                        onApprovalDenied = {},
                        sessionRecordingManager = replayManager,
                    )
                    runtime.onApprovalStaged(
                        actionSummary = stagedAction.summary,
                        stagedAction = stagedAction,
                        reason = "Owner approval required.",
                        reasonCode = "NEEDS_APPROVAL",
                        conversationContext = stagedAction.conversationContext,
                    )
                    val replayResult = runtime.routeOwnerMessage(
                        OwnerMessageEnvelope(
                            content = "yes",
                            source = "chat:test",
                            priority = InputPriority.HIGH,
                            conversationContext = stagedAction.conversationContext,
                            receivedAtMs = System.currentTimeMillis(),
                        )
                    )
                    assertTrue(replayResult is OwnerIngressResult.Consumed)
                    assertEquals(1, actionControl.authorizeCalls)
                    runtime.close()
                }
            }

            dashboardStore.close()
            Files.deleteIfExists(recordDb)
            Files.deleteIfExists(replayDb)
        }
    }

    private fun testStagedAction(): StagedAction {
        val conversationContext = ConversationContext(
            sessionId = "chat-1",
            interlocutor = ai.neopsyke.agent.model.Interlocutor.named("Owner"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "webapp",
                channelId = "chat-1",
            ),
        )
        return StagedAction(
            id = "staged-1",
            preparedActionId = "prepared-1",
            rootInputId = "root-1",
            actionType = ActionType.CONTACT_USER,
            summary = "Send a message",
            payload = "hello",
            conversationContext = conversationContext,
            commitMode = CommitMode.APPROVAL_BACKED,
            status = StagedActionStatus.WAITING_AUTHORIZATION,
            actionHash = "hash-1",
            statusReason = "approval required",
        )
    }
}
