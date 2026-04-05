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
import ai.neopsyke.session.SessionRecordingManager
import ai.neopsyke.session.SessionRecordingMode
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * End-to-end deterministic runtime tests for the approval subsystem (spec 18.16).
 *
 * These tests exercise full pipeline flows through real ApprovalRuntime,
 * SqliteApprovalStore, and ActionControlService fakes.
 */
class ApprovalE2ETest {

    private class FakeActionControlService(
        private val stagedActions: MutableMap<String, StagedAction>,
    ) : ActionControlService {
        var authorizeCalls: Int = 0
        var denyCalls: Int = 0

        constructor(stagedAction: StagedAction) : this(mutableMapOf(stagedAction.id to stagedAction))

        override suspend fun handleAuthorizationDecision(
            action: PendingAction, decision: AuthorizationDecision, conversationContext: ConversationContext,
        ): ActionControlDecisionResult =
            ActionControlDecisionResult.Refused("Not used in e2e tests", "NOT_USED")

        override suspend fun authorizeStagedAction(
            stagedActionId: String, grantedBy: ConversationSecurityContext, expectedActionHash: String?,
        ): ActionControlDecisionResult {
            authorizeCalls += 1
            val staged = stagedActions[stagedActionId]
                ?: return ActionControlDecisionResult.Refused("Not found", "NOT_FOUND")
            val authorization = CommitAuthorization(
                id = "auth-$authorizeCalls", stagedActionId = stagedActionId, commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id, grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = "test", actionHash = staged.actionHash,
            )
            val receipt = ActionReceipt(
                id = "receipt-$authorizeCalls", stagedActionId = stagedActionId, authorizationId = authorization.id,
                rootInputId = staged.rootInputId, actionType = staged.actionType,
                executionStatus = ActionExecutionStatus.SUCCESS, statusSummary = "done",
            )
            val updated = staged.copy(status = StagedActionStatus.COMPLETED, authorizationId = authorization.id, receiptId = receipt.id)
            stagedActions[stagedActionId] = updated
            return ActionControlDecisionResult.Executed(
                stagedAction = updated, authorization = authorization, receipt = receipt,
                outcome = ActionOutcome(statusSummary = "done", executionStatus = ActionExecutionStatus.SUCCESS, plannerSignal = "done"),
                executedAction = PendingAction(
                    id = 1L, urgency = Urgency.MEDIUM, type = staged.actionType, payload = staged.payload,
                    summary = staged.summary, rootInputId = staged.rootInputId, conversationContext = staged.conversationContext,
                ),
            )
        }

        override suspend fun denyStagedAction(
            stagedActionId: String, deniedBy: ConversationSecurityContext, reason: String, reasonCode: String?,
        expectedActionHash: String?,
        ): ActionControlDecisionResult {
            denyCalls += 1
            val staged = stagedActions[stagedActionId]
                ?: return ActionControlDecisionResult.Refused("Not found", "NOT_FOUND")
            val updated = staged.copy(status = StagedActionStatus.CANCELLED, statusReason = reason, statusReasonCode = reasonCode)
            stagedActions[stagedActionId] = updated
            return ActionControlDecisionResult.Cancelled(
                stagedAction = updated,
                ledgerEntry = ActionLedgerEntry(
                    id = "ledger-$denyCalls", kind = ActionLedgerKind.CANCELLED, importance = ActionRecordImportance.SIGNAL,
                    actionType = staged.actionType, summary = reason, rootInputId = staged.rootInputId,
                    stagedActionId = staged.id, reasonCode = reasonCode, conversationContext = staged.conversationContext,
                ),
            )
        }

        override suspend fun processAutonomousStagedActions(limit: Int) = emptyList<ActionControlDecisionResult.Executed>()
        override suspend fun recordBypassExecution(action: PendingAction, conversationContext: ConversationContext, outcome: ActionOutcome, reason: String, reasonCode: String?): ActionReceipt? = null
        override fun recordLedgerEntry(action: PendingAction, conversationContext: ConversationContext, kind: ActionLedgerKind, importance: ActionRecordImportance, summary: String, reasonCode: String?, source: String?, stagedActionId: String?, authorizationId: String?, receiptId: String?): ActionLedgerEntry? = null
        override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> = stagedActions.values.toList()
        override fun stagedAction(id: String): StagedAction? = stagedActions[id]
        override fun receipts(limit: Int) = emptyList<ActionReceipt>()
        override fun receipt(id: String): ActionReceipt? = null
        override fun ledgerEntries(limit: Int) = emptyList<ActionLedgerEntry>()
        override fun ledgerEntry(id: String): ActionLedgerEntry? = null
    }

    private class FakeTelegramSink : TelegramMessageSink {
        val messages = mutableListOf<Pair<String, String>>()
        override suspend fun sendMessage(chatId: String, text: String): ConversationDeliveryResult {
            messages += chatId to text
            return ConversationDeliveryResult(delivered = true, detail = "telegram-delivered:$chatId")
        }
    }

    private fun ownerContext(sessionId: String = "chat-1", provider: String = "webapp", channelId: String = sessionId): ConversationContext =
        ConversationContext(
            sessionId = sessionId,
            interlocutor = Interlocutor.named("Owner"),
            security = ConversationSecurityContexts.ownerDirect(provider = provider, channelId = channelId, principalId = "owner"),
        )

    private fun stagedAction(
        id: String = "staged-1", rootInputId: String = "root-1",
        ctx: ConversationContext = ownerContext(), hash: String = "hash-1",
        origin: ActionOrigin = ActionOrigin.USER,
    ): StagedAction =
        StagedAction(
            id = id, preparedActionId = "prepared-$id", rootInputId = rootInputId,
            actionType = ActionType.CONTACT_USER, summary = "Send a message", payload = "hello",
            conversationContext = ctx, origin = origin, commitMode = CommitMode.APPROVAL_BACKED,
            status = StagedActionStatus.WAITING_AUTHORIZATION, actionHash = hash, statusReason = "approval required",
        )

    private fun envelope(content: String, ctx: ConversationContext = ownerContext(), eventId: String? = null): OwnerMessageEnvelope =
        OwnerMessageEnvelope(
            content = content, source = "chat:${ctx.sessionId}", priority = InputPriority.HIGH,
            conversationContext = ctx, receivedAtMs = System.currentTimeMillis(), eventId = eventId,
        )

    // ---- 18.16 E2E Scenarios ----

    @Test
    fun `1 conversation origin staged action approved via NL in dashboard chat`() = runBlocking {
        val staged = stagedAction()
        val tempDb = Files.createTempFile("e2e-approve", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged.summary, staged, "Owner approval required.", "NEEDS_APPROVAL", staged.conversationContext)

            val result = runtime.routeOwnerMessage(envelope("yes"))
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)
            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId(staged.id)?.status)

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `2 conversation origin staged action denied via NL in dashboard chat`() = runBlocking {
        val staged = stagedAction()
        val tempDb = Files.createTempFile("e2e-deny", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged.summary, staged, "Owner approval required.", "NEEDS_APPROVAL", staged.conversationContext)

            val result = runtime.routeOwnerMessage(envelope("no"))
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.denyCalls)
            assertEquals(ApprovalRequestStatus.DENIED, store.requestByStagedActionId(staged.id)?.status)

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `3 modification reply becomes deny and reissue with fresh owner message`() = runBlocking {
        val staged = stagedAction()
        val tempDb = Files.createTempFile("e2e-reissue", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        val forwarded = mutableListOf<Pair<String, ConversationContext>>()
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { content, source, _, context -> forwarded += "$source::$content" to context; true },
                onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged.summary, staged, "Owner approval required.", "NEEDS_APPROVAL", staged.conversationContext)

            val result = runtime.routeOwnerMessage(envelope("no, ask him first"))
            assertTrue(result is OwnerIngressResult.Forwarded)
            assertEquals(1, actionControl.denyCalls)
            assertEquals(1, forwarded.size)
            assertEquals(ApprovalRequestStatus.DENIED_AND_REISSUED, store.requestByStagedActionId(staged.id)?.status)

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `4 blocked thread does not continue while awaiting approval`() = runBlocking {
        val staged = stagedAction()
        val tempDb = Files.createTempFile("e2e-blocked", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged.summary, staged, "Owner approval required.", "NEEDS_APPROVAL", staged.conversationContext)

            val request = store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, request.status)
            assertEquals(staged.rootInputId, request.rootInputId)
            assertEquals(0, actionControl.authorizeCalls)
            assertEquals(0, actionControl.denyCalls)

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `5 unrelated thread progresses during blocked approval`() = runBlocking {
        val ctx1 = ownerContext("chat-1")
        val ctx2 = ownerContext("chat-2")
        val staged1 = stagedAction(id = "staged-1", ctx = ctx1)
        val staged2 = stagedAction(id = "staged-2", ctx = ctx2, hash = "hash-2")
        val tempDb = Files.createTempFile("e2e-multi", ".db")
        val actionControl = FakeActionControlService(mutableMapOf(staged1.id to staged1, staged2.id to staged2))
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession("chat-1")
        dashboardStore.ensureChatSession("chat-2")
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        val forwarded = mutableListOf<String>()
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { content, _, _, _ -> forwarded += content; true },
                onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged1.summary, staged1, "Approval 1.", "NEEDS_APPROVAL", ctx1)

            assertNotNull(store.activeRequestForSession("chat-1"))
            assertNull(store.activeRequestForSession("chat-2"))

            val msg2 = OwnerMessageEnvelope(
                content = "hello from chat-2", source = "chat:chat-2", priority = InputPriority.HIGH,
                conversationContext = ctx2, receivedAtMs = System.currentTimeMillis(),
            )
            val result2 = runtime.routeOwnerMessage(msg2)
            assertTrue(result2 is OwnerIngressResult.Forwarded)
            assertTrue(forwarded.contains("hello from chat-2"))

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `6 same semantics hold for telegram channel`() = runBlocking {
        val tgCtx = ownerContext(sessionId = "telegram:tg-owner", provider = "telegram", channelId = "tg-owner")
        val staged = stagedAction(ctx = tgCtx)
        val tempDb = Files.createTempFile("e2e-telegram", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(tgCtx.sessionId)
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        val telegramSink = FakeTelegramSink()
        val telegramConfig = TelegramChannelConfig(enabled = true, ownerChatId = "tg-owner", ownerUserId = "owner")
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = telegramConfig, telegramSink = telegramSink,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged.summary, staged, "Approval.", "NEEDS_APPROVAL", tgCtx)

            val tgEnvelope = OwnerMessageEnvelope(
                content = "approve", source = "telegram:tg-owner", priority = InputPriority.HIGH,
                conversationContext = tgCtx, receivedAtMs = System.currentTimeMillis(),
            )
            val result = runtime.routeOwnerMessage(tgEnvelope)
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)
            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId(staged.id)?.status)

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `7 fallback classifier uses deterministic path with expected default behavior`() = runBlocking {
        val staged = stagedAction()
        val tempDb = Files.createTempFile("e2e-classifier", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(ConversationContext.DEFAULT_SESSION_ID)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(staged.summary, staged, "Approval.", "NEEDS_APPROVAL", staged.conversationContext)

            runtime.routeOwnerMessage(envelope("approved"))
            assertEquals(1, actionControl.authorizeCalls)
            val request = store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(false, request.usedModelAssistance)

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `8 approval and denial recorded and replayed through freud without divergence`() = runBlocking {
        val sessionDir = Files.createTempDirectory("e2e-replay")
        val recordDb = Files.createTempFile("e2e-replay-record", ".db")
        val replayDb = Files.createTempFile("e2e-replay-replay", ".db")
        val staged = stagedAction()
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(staged.conversationContext.sessionId)

        // Record phase
        SessionRecordingManager(mode = SessionRecordingMode.RECORD, sessionDir = sessionDir).use { recordingManager ->
            SqliteApprovalStore(recordDb.toString()).use { store ->
                val actionControl = FakeActionControlService(staged)
                val runtime = ApprovalRuntime(
                    config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                    telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                    interpreter = DefaultApprovalInterpreter(AgentConfig()),
                    forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
                    sessionRecordingManager = recordingManager,
                )
                runtime.onApprovalStaged(staged.summary, staged, "Approval.", "NEEDS_APPROVAL", staged.conversationContext)
                runtime.routeOwnerMessage(envelope("yes"))
                assertEquals(1, actionControl.authorizeCalls)
                runtime.close()
            }
        }

        // Replay phase with a broken interpreter that should be overridden by replay
        SessionRecordingManager(mode = SessionRecordingMode.REPLAY, sessionDir = sessionDir).use { replayManager ->
            SqliteApprovalStore(replayDb.toString()).use { store ->
                val actionControl = FakeActionControlService(staged)
                val runtime = ApprovalRuntime(
                    config = AgentConfig(), store = store, actionControlService = actionControl, dashboardStore = dashboardStore,
                    telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                    interpreter = ApprovalInterpreter {
                        ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = false)
                    },
                    forwardNormalInput = { _, _, _, _ -> true }, onApprovalExecuted = {}, onApprovalDenied = {},
                    sessionRecordingManager = replayManager,
                )
                runtime.onApprovalStaged(staged.summary, staged, "Approval.", "NEEDS_APPROVAL", staged.conversationContext)
                val replayResult = runtime.routeOwnerMessage(envelope("yes"))
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
