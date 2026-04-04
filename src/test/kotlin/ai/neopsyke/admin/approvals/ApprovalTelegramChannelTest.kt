package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.ApprovalRuntimeConfig
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.cortex.motor.actions.ConversationDeliveryResult
import ai.neopsyke.agent.cortex.motor.actions.TelegramMessageSink
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionOrigin
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
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.dashboard.DashboardStateStore
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Telegram channel-specific approval tests (spec 18.17).
 */
class ApprovalTelegramChannelTest {

    private class FakeActionControlService(
        stagedAction: StagedAction,
    ) : ActionControlService {
        var currentStagedAction: StagedAction = stagedAction
        var authorizeCalls: Int = 0
        var denyCalls: Int = 0

        override suspend fun handleAuthorizationDecision(
            action: PendingAction, decision: AuthorizationDecision, conversationContext: ConversationContext,
        ): ActionControlDecisionResult =
            ActionControlDecisionResult.Staged(
                stagedAction = currentStagedAction,
                authorizationDecision = AuthorizationDecision(
                    progress = AuthorizationProgress.ALLOW_STAGE,
                    commitMode = CommitMode.APPROVAL_BACKED,
                    reason = "approval required",
                ),
            )

        override suspend fun authorizeStagedAction(
            stagedActionId: String, grantedBy: ConversationSecurityContext, expectedActionHash: String?,
        ): ActionControlDecisionResult {
            authorizeCalls += 1
            val authorization = CommitAuthorization(
                id = "auth-$authorizeCalls", stagedActionId = stagedActionId,
                commitMode = CommitMode.APPROVAL_BACKED, grantedByPrincipalId = grantedBy.principal.id,
                grantedByChannelId = grantedBy.channel.channelId, policyVersion = "test",
                actionHash = currentStagedAction.actionHash,
            )
            val outcome = ActionOutcome(statusSummary = "done", executionStatus = ActionExecutionStatus.SUCCESS, plannerSignal = "done")
            val receipt = ActionReceipt(
                id = "receipt-$authorizeCalls", stagedActionId = stagedActionId,
                authorizationId = authorization.id, rootInputId = currentStagedAction.rootInputId,
                actionType = currentStagedAction.actionType, executionStatus = ActionExecutionStatus.SUCCESS,
                statusSummary = "done",
            )
            currentStagedAction = currentStagedAction.copy(
                status = StagedActionStatus.COMPLETED, authorizationId = authorization.id, receiptId = receipt.id,
            )
            return ActionControlDecisionResult.Executed(
                stagedAction = currentStagedAction, authorization = authorization, receipt = receipt, outcome = outcome,
                executedAction = PendingAction(
                    id = 1L, urgency = Urgency.MEDIUM, type = currentStagedAction.actionType,
                    payload = currentStagedAction.payload, summary = currentStagedAction.summary,
                    rootInputId = currentStagedAction.rootInputId, conversationContext = currentStagedAction.conversationContext,
                ),
            )
        }

        override suspend fun denyStagedAction(
            stagedActionId: String, deniedBy: ConversationSecurityContext, reason: String, reasonCode: String?,
        ): ActionControlDecisionResult {
            denyCalls += 1
            currentStagedAction = currentStagedAction.copy(status = StagedActionStatus.CANCELLED, statusReason = reason, statusReasonCode = reasonCode)
            return ActionControlDecisionResult.Cancelled(
                stagedAction = currentStagedAction,
                ledgerEntry = ActionLedgerEntry(
                    id = "ledger-$denyCalls", kind = ActionLedgerKind.CANCELLED, importance = ActionRecordImportance.SIGNAL,
                    actionType = currentStagedAction.actionType, summary = reason, rootInputId = currentStagedAction.rootInputId,
                    stagedActionId = currentStagedAction.id, reasonCode = reasonCode, conversationContext = currentStagedAction.conversationContext,
                )
            )
        }

        override suspend fun processAutonomousStagedActions(limit: Int): List<ActionControlDecisionResult.Executed> = emptyList()
        override suspend fun recordBypassExecution(action: PendingAction, conversationContext: ConversationContext, outcome: ActionOutcome, reason: String, reasonCode: String?): ActionReceipt? = null
        override fun recordLedgerEntry(action: PendingAction, conversationContext: ConversationContext, kind: ActionLedgerKind, importance: ActionRecordImportance, summary: String, reasonCode: String?, source: String?, stagedActionId: String?, authorizationId: String?, receiptId: String?): ActionLedgerEntry? = null
        override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> = listOf(currentStagedAction)
        override fun stagedAction(id: String): StagedAction? = currentStagedAction.takeIf { it.id == id }
        override fun receipts(limit: Int): List<ActionReceipt> = emptyList()
        override fun receipt(id: String): ActionReceipt? = null
        override fun ledgerEntries(limit: Int): List<ActionLedgerEntry> = emptyList()
        override fun ledgerEntry(id: String): ActionLedgerEntry? = null
    }

    private class FakeTelegramSink : TelegramMessageSink {
        val messages = mutableListOf<Pair<String, String>>()
        override suspend fun sendMessage(chatId: String, text: String): ConversationDeliveryResult {
            messages += chatId to text
            return ConversationDeliveryResult(delivered = true, detail = "telegram-delivered:$chatId")
        }
    }

    private val telegramConfig = TelegramChannelConfig(
        enabled = true,
        ownerChatId = "tg-owner-123",
        ownerUserId = "tg-user-1",
    )

    private fun telegramOwnerContext(): ConversationContext =
        ConversationContext(
            sessionId = "telegram:tg-owner-123",
            interlocutor = Interlocutor.named("Telegram Owner"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "telegram",
                channelId = "tg-owner-123",
                principalId = "tg-user-1",
            ),
        )

    private fun stagedAction(ctx: ConversationContext = telegramOwnerContext()): StagedAction =
        StagedAction(
            id = "staged-tg", preparedActionId = "prepared-tg", rootInputId = "root-tg",
            actionType = ActionType.CONTACT_USER, summary = "Send a message via Telegram", payload = "hello",
            conversationContext = ctx, origin = ActionOrigin.USER, commitMode = CommitMode.APPROVAL_BACKED,
            status = StagedActionStatus.WAITING_AUTHORIZATION, actionHash = "hash-tg", statusReason = "approval required",
        )

    private fun envelope(content: String, ctx: ConversationContext = telegramOwnerContext(), eventId: String? = null): OwnerMessageEnvelope =
        OwnerMessageEnvelope(
            content = content, source = "telegram:tg-owner-123", priority = InputPriority.HIGH,
            conversationContext = ctx, receivedAtMs = System.currentTimeMillis(), eventId = eventId,
        )

    private suspend fun withRuntime(
        staged: StagedAction,
        config: AgentConfig = AgentConfig(),
        block: suspend (ApprovalRuntime, SqliteApprovalStore, FakeActionControlService, FakeTelegramSink) -> Unit,
    ) {
        val tempDb = Files.createTempFile("approval-tg", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        val telegramSink = FakeTelegramSink()
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = config, store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = telegramConfig, telegramSink = telegramSink,
                interpreter = DefaultApprovalInterpreter(config),
                forwardNormalInput = { _, _, _, _ -> true },
                onApprovalExecuted = {}, onApprovalDenied = {},
            )
            try {
                block(runtime, store, actionControl, telegramSink)
            } finally {
                runtime.close()
                dashboardStore.close()
                Files.deleteIfExists(tempDb)
            }
        }
    }

    @Test
    fun `1 approval prompt delivery to telegram`() = runBlocking {
        val staged = stagedAction()
        withRuntime(staged) { runtime, store, _, telegramSink ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Owner approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            val request = store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals("telegram", request.target.provider)
            assertTrue(telegramSink.messages.any { it.first == "tg-owner-123" && it.second.contains("Approval required.") })
        }
    }

    @Test
    fun `2 pending approval reply interception before normal ego ingress`() = runBlocking {
        val staged = stagedAction()
        val forwarded = mutableListOf<String>()
        val tempDb = Files.createTempFile("approval-tg-2", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        val telegramSink = FakeTelegramSink()
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = telegramConfig, telegramSink = telegramSink,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { content, _, _, _ -> forwarded += content; true },
                onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            val result = runtime.routeOwnerMessage(envelope("approve"))
            assertTrue(result is OwnerIngressResult.Consumed)
            assertTrue(forwarded.isEmpty(), "Approval reply must be consumed, not forwarded")
            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `3 natural language approval resolution via telegram`() = runBlocking {
        val staged = stagedAction()
        withRuntime(staged) { runtime, store, actionControl, _ ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            runtime.routeOwnerMessage(envelope("ok"))
            assertEquals(1, actionControl.authorizeCalls)
            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId(staged.id)?.status)
        }
    }

    @Test
    fun `4 natural language denial resolution via telegram`() = runBlocking {
        val staged = stagedAction()
        withRuntime(staged) { runtime, store, actionControl, _ ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            runtime.routeOwnerMessage(envelope("deny"))
            assertEquals(1, actionControl.denyCalls)
            assertEquals(ApprovalRequestStatus.DENIED, store.requestByStagedActionId(staged.id)?.status)
        }
    }

    @Test
    fun `5 explanatory question behavior via telegram`() = runBlocking {
        val staged = stagedAction()
        withRuntime(staged) { runtime, store, actionControl, telegramSink ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            runtime.routeOwnerMessage(envelope("what is this action?"))
            assertEquals(ApprovalRequestStatus.PENDING, store.requestByStagedActionId(staged.id)?.status)
            assertEquals(0, actionControl.authorizeCalls)
            assertTrue(telegramSink.messages.any { it.second.contains("Approval details:") })
        }
    }

    @Test
    fun `6 expiry notice delivery via telegram`() = runBlocking {
        val staged = stagedAction()
        val config = AgentConfig(approvals = ApprovalRuntimeConfig(ttlMs = 1L))
        withRuntime(staged, config = config) { runtime, store, actionControl, telegramSink ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            Thread.sleep(5)
            runtime.expirePendingRequests()
            assertEquals(1, actionControl.denyCalls)
            assertEquals(ApprovalRequestStatus.EXPIRED, store.requestByStagedActionId(staged.id)?.status)
            assertTrue(telegramSink.messages.any { it.second.contains("expired") })
        }
    }

    @Test
    fun `7 telegram channel eligibility semantics for approval router`() = runBlocking {
        val nonOwnerCtx = ConversationContext(
            sessionId = "goal-session", interlocutor = Interlocutor.named("System"),
            security = ConversationSecurityContexts.default(),
        )
        val staged = stagedAction(ctx = nonOwnerCtx).copy(origin = ActionOrigin(ai.neopsyke.agent.model.OriginSource.GOAL))
        val config = AgentConfig(
            approvals = ApprovalRuntimeConfig(
                telegramStartupAckEnabled = true,
                dashboardRequiresLiveSubscriber = true,
            ),
        )
        withRuntime(staged, config = config) { runtime, store, _, telegramSink ->
            runtime.sendTelegramStartupAckIfEnabled()
            assertTrue(telegramSink.messages.isNotEmpty(), "Startup ACK should have been sent")

            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Goal approval.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            val request = store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals("telegram", request.target.provider)
        }
    }

    @Test
    fun `8 telegram startup ack and outbound readiness state`() = runBlocking {
        val staged = stagedAction()
        val config = AgentConfig(approvals = ApprovalRuntimeConfig(telegramStartupAckEnabled = true))
        withRuntime(staged, config = config) { runtime, _, _, telegramSink ->
            runtime.sendTelegramStartupAckIfEnabled()
            assertTrue(telegramSink.messages.isNotEmpty())
            assertTrue(telegramSink.messages.any { it.second.contains("Outbound delivery confirmed") })
        }
    }

    @Test
    fun `9 provider native delivery metadata capture for telegram`() = runBlocking {
        val staged = stagedAction()
        withRuntime(staged) { runtime, store, _, telegramSink ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            val request = store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(true, request.lastPromptDelivered)
            assertNotNull(request.lastPromptDeliveryDetail)
            assertTrue(request.lastPromptDeliveryDetail!!.contains("telegram-delivered"))
        }
    }

    @Test
    fun `10 duplicate inbound event handling and idempotent terminal resolution`() = runBlocking {
        val staged = stagedAction()
        withRuntime(staged) { runtime, store, actionControl, _ ->
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            val result1 = runtime.routeOwnerMessage(envelope("yes", eventId = "tg-evt-1"))
            assertTrue(result1 is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)

            val result2 = runtime.routeOwnerMessage(envelope("yes", eventId = "tg-evt-1"))
            assertTrue(result2 is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)
        }
    }
}
