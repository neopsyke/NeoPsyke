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
import ai.neopsyke.agent.model.GroundingMetadata
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
 * Integration tests for the full approval pipeline: staged action -> approval request -> prompt
 * routing -> owner reply -> classification -> action-control mutation.
 *
 * Covers spec 18.15 integration scenarios.
 */
class ApprovalIntegrationTest {

    // ---- Shared test infrastructure ----

    private class FakeActionControlService(
        stagedAction: StagedAction,
    ) : ActionControlService {
        var currentStagedAction: StagedAction = stagedAction
        var authorizeCalls: Int = 0
        var denyCalls: Int = 0

        override suspend fun handleAuthorizationDecision(
            action: PendingAction,
            decision: AuthorizationDecision,
            conversationContext: ConversationContext,
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
            stagedActionId: String,
            grantedBy: ConversationSecurityContext,
            expectedActionHash: String?,
        ): ActionControlDecisionResult {
            authorizeCalls += 1
            if (!expectedActionHash.isNullOrBlank() && expectedActionHash != currentStagedAction.actionHash) {
                return ActionControlDecisionResult.Refused("hash mismatch", "HASH_MISMATCH")
            }
            val authorization = CommitAuthorization(
                id = "auth-$authorizeCalls",
                stagedActionId = stagedActionId,
                commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id,
                grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = "test",
                actionHash = currentStagedAction.actionHash,
            )
            val outcome = ActionOutcome(
                statusSummary = "done",
                executionStatus = ActionExecutionStatus.SUCCESS,
                plannerSignal = "done",
            )
            val receipt = ActionReceipt(
                id = "receipt-$authorizeCalls",
                stagedActionId = stagedActionId,
                authorizationId = authorization.id,
                rootInputId = currentStagedAction.rootInputId,
                actionType = currentStagedAction.actionType,
                executionStatus = ActionExecutionStatus.SUCCESS,
                statusSummary = "done",
            )
            currentStagedAction = currentStagedAction.copy(
                status = StagedActionStatus.COMPLETED,
                authorizationId = authorization.id,
                receiptId = receipt.id,
            )
            return ActionControlDecisionResult.Executed(
                stagedAction = currentStagedAction,
                authorization = authorization,
                receipt = receipt,
                outcome = outcome,
                executedAction = PendingAction(
                    id = 1L,
                    urgency = Urgency.MEDIUM,
                    type = currentStagedAction.actionType,
                    payload = currentStagedAction.payload,
                    summary = currentStagedAction.summary,
                    rootInputId = currentStagedAction.rootInputId,
                    conversationContext = currentStagedAction.conversationContext,
                    groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
            )
        }

        override suspend fun denyStagedAction(
            stagedActionId: String,
            deniedBy: ConversationSecurityContext,
            reason: String,
            reasonCode: String?,
        expectedActionHash: String?,
        ): ActionControlDecisionResult {
            denyCalls += 1
            currentStagedAction = currentStagedAction.copy(
                status = StagedActionStatus.CANCELLED,
                statusReason = reason,
                statusReasonCode = reasonCode,
            )
            return ActionControlDecisionResult.Cancelled(
                stagedAction = currentStagedAction,
                ledgerEntry = ActionLedgerEntry(
                    id = "ledger-$denyCalls",
                    kind = ActionLedgerKind.CANCELLED,
                    importance = ActionRecordImportance.SIGNAL,
                    actionType = currentStagedAction.actionType,
                    summary = reason,
                    rootInputId = currentStagedAction.rootInputId,
                    stagedActionId = currentStagedAction.id,
                    reasonCode = reasonCode,
                    conversationContext = currentStagedAction.conversationContext,
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

    private data class IntegrationHarness(
        val runtime: ApprovalRuntime,
        val store: SqliteApprovalStore,
        val dashboardStore: DashboardStateStore,
        val actionControl: FakeActionControlService,
        val telegramSink: FakeTelegramSink?,
        val executed: MutableList<ActionControlDecisionResult.Executed>,
        val denied: MutableList<ActionControlDecisionResult.Cancelled>,
        val forwarded: MutableList<Pair<String, ConversationContext>>,
    )

    private suspend fun withHarness(
        stagedAction: StagedAction,
        config: AgentConfig = AgentConfig(),
        telegramConfig: TelegramChannelConfig = TelegramChannelConfig(enabled = false),
        useTelegram: Boolean = false,
        block: suspend (IntegrationHarness) -> Unit,
    ) {
        val tempDb = Files.createTempFile("approval-integ", ".db")
        val actionControl = FakeActionControlService(stagedAction)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = stagedAction.conversationContext.sessionId)
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        val executed = mutableListOf<ActionControlDecisionResult.Executed>()
        val denied = mutableListOf<ActionControlDecisionResult.Cancelled>()
        val forwarded = mutableListOf<Pair<String, ConversationContext>>()
        val telegramSink = if (useTelegram) FakeTelegramSink() else null
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = config,
                store = store,
                actionControlService = actionControl,
                dashboardStore = dashboardStore,
                telegramConfig = telegramConfig,
                telegramSink = telegramSink,
                interpreter = DefaultApprovalInterpreter(config),
                forwardNormalInput = { content, source, _, context ->
                    forwarded += "$source::$content" to context
                    true
                },
                onApprovalExecuted = { executed += it },
                onApprovalDenied = { denied += it },
            )
            try {
                block(IntegrationHarness(runtime, store, dashboardStore, actionControl, telegramSink, executed, denied, forwarded))
            } finally {
                runtime.close()
                dashboardStore.close()
                Files.deleteIfExists(tempDb)
            }
        }
    }

    private fun ownerContext(
        sessionId: String = "chat-1",
        provider: String = "webapp",
        channelId: String = sessionId,
        principalId: String = "owner",
    ): ConversationContext =
        ConversationContext(
            sessionId = sessionId,
            interlocutor = Interlocutor.named("Owner"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = provider,
                channelId = channelId,
                principalId = principalId,
            ),
        )

    private fun testStagedAction(
        id: String = "staged-1",
        rootInputId: String = "root-1",
        conversationContext: ConversationContext = ownerContext(),
        summary: String = "Send a message",
        actionHash: String = "hash-1",
        origin: ActionOrigin = ActionOrigin.USER,
    ): StagedAction =
        StagedAction(
            id = id,
            preparedActionId = "prepared-$id",
            rootInputId = rootInputId,
            actionType = ActionType.CONTACT_USER,
            summary = summary,
            payload = "hello",
            conversationContext = conversationContext,
            origin = origin,
            commitMode = CommitMode.APPROVAL_BACKED,
            status = StagedActionStatus.WAITING_AUTHORIZATION,
            actionHash = actionHash,
            statusReason = "approval required",
        )

    private fun ownerEnvelope(
        content: String,
        conversationContext: ConversationContext = ownerContext(),
        eventId: String? = null,
    ): OwnerMessageEnvelope =
        OwnerMessageEnvelope(
            content = content,
            source = "chat:${conversationContext.sessionId}",
            priority = InputPriority.HIGH,
            conversationContext = conversationContext,
            receivedAtMs = System.currentTimeMillis(),
            eventId = eventId,
        )

    private suspend fun stageAndRoute(h: IntegrationHarness, staged: StagedAction) {
        h.runtime.onApprovalStaged(
            actionSummary = staged.summary,
            stagedAction = staged,
            reason = "Owner approval required.",
            reasonCode = "NEEDS_APPROVAL",
            conversationContext = staged.conversationContext,
        )
    }

    // ---- 18.15 Integration Scenarios ----

    @Test
    fun `1 staged action creates approval request and routes prompt to dashboard`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val request = h.store.activeRequestForSession(staged.conversationContext.sessionId)
            assertNotNull(request)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, request.status)
            assertEquals(staged.id, request.stagedActionId)
            assertEquals("webapp", request.target.provider)

            // Prompt delivery is verified through the store audit trail
            val audit = h.store.listAudit(request.id)
            assertTrue(audit.any { it.kind == "prompt_sent" })
        }
    }

    @Test
    fun `2 approve path authorizes staged action through action control`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val result = h.runtime.routeOwnerMessage(ownerEnvelope("yes"))
            assertTrue(result is OwnerIngressResult.Consumed)

            assertEquals(1, h.actionControl.authorizeCalls)
            assertEquals(1, h.executed.size)
            assertEquals(ApprovalRequestStatus.APPROVED, h.store.requestByStagedActionId(staged.id)?.status)
        }
    }

    @Test
    fun `3 deny path denies staged action through action control`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val result = h.runtime.routeOwnerMessage(ownerEnvelope("no"))
            assertTrue(result is OwnerIngressResult.Consumed)

            assertEquals(1, h.actionControl.denyCalls)
            assertEquals(1, h.denied.size)
            assertEquals(ApprovalRequestStatus.DENIED, h.store.requestByStagedActionId(staged.id)?.status)
        }
    }

    @Test
    fun `4 deny and reissue denies then forwards raw owner text to normal ingress`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val result = h.runtime.routeOwnerMessage(ownerEnvelope("yes, send it tomorrow instead"))
            assertTrue(result is OwnerIngressResult.Forwarded)

            assertEquals(1, h.actionControl.denyCalls)
            assertEquals(1, h.forwarded.size)
            val (source, ctx) = h.forwarded.single()
            assertTrue(source.startsWith("approval-reissue:"))
            assertTrue(source.contains("yes, send it tomorrow instead"))
            assertEquals("true", ctx.attributes["approval_reissue"])
            assertEquals(staged.id, ctx.attributes["approval_staged_action_id"])
            assertEquals(ApprovalRequestStatus.DENIED_AND_REISSUED, h.store.requestByStagedActionId(staged.id)?.status)
        }
    }

    @Test
    fun `5 explanatory question returns metadata answer and keeps approval pending`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val result = h.runtime.routeOwnerMessage(ownerEnvelope("what exactly does this do?"))
            assertTrue(result is OwnerIngressResult.Consumed)

            val request = h.store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, request.status)
            assertEquals(0, h.actionControl.authorizeCalls)
            assertEquals(0, h.actionControl.denyCalls)

            // Explanation delivery verified through audit trail
            val audit = h.store.listAudit(h.store.requestByStagedActionId(staged.id)!!.id)
            assertTrue(audit.any { it.kind == "explanation_sent" })
        }
    }

    @Test
    fun `6 expiry denies staged action and delivers expiry notice`() = runBlocking {
        val staged = testStagedAction()
        val config = AgentConfig(approvals = ApprovalRuntimeConfig(ttlMs = 1L))
        withHarness(staged, config = config) { h ->
            stageAndRoute(h, staged)
            Thread.sleep(5)

            h.runtime.expirePendingRequests()

            assertEquals(1, h.actionControl.denyCalls)
            assertEquals(ApprovalRequestStatus.EXPIRED, h.store.requestByStagedActionId(staged.id)?.status)

            // Expiry notice verified through audit trail
            val audit = h.store.listAudit(h.store.requestByStagedActionId(staged.id)!!.id)
            assertTrue(audit.any { it.kind == "request_expired" })
        }
    }

    @Test
    fun `7 one active approval per conversation with multiple staged actions`() = runBlocking {
        val staged1 = testStagedAction(id = "staged-1", rootInputId = "root-1")
        val staged2 = testStagedAction(id = "staged-2", rootInputId = "root-2", actionHash = "hash-2")
        withHarness(staged1) { h ->
            stageAndRoute(h, staged1)

            // Stage a second action while first is pending — it should queue
            h.runtime.onApprovalStaged(
                actionSummary = staged2.summary,
                stagedAction = staged2,
                reason = "Second approval.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged2.conversationContext,
            )

            val req1 = h.store.requestByStagedActionId("staged-1")
            val req2 = h.store.requestByStagedActionId("staged-2")
            assertNotNull(req1)
            assertNotNull(req2)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, req1.status)
            assertEquals(ApprovalRequestStatus.QUEUED, req2.status)

            // Approve the first — queued should activate
            // Note: activateNextQueued calls stagedAction(id) which needs to find staged-2.
            // Our FakeActionControlService tracks only one action, so after approval
            // staged-2 won't be found and activation won't deliver a prompt.
            // But the state transition from QUEUED to PENDING still happens via the store.
            h.runtime.routeOwnerMessage(ownerEnvelope("yes"))

            val req1After = h.store.requestByStagedActionId("staged-1")
            assertEquals(ApprovalRequestStatus.APPROVED, req1After?.status)
            // The queued request stays queued because the fake can't find staged-2
            // This is a limitation of the single-action fake, not a bug in the runtime.
            // The queue enforcement (QUEUED while another is PENDING) is the key test here.
        }
    }

    @Test
    fun `8 unrelated roots continue while blocked root is suspended`() = runBlocking {
        val ctx1 = ownerContext(sessionId = "chat-1")
        val ctx2 = ownerContext(sessionId = "chat-2")
        val staged1 = testStagedAction(id = "staged-1", rootInputId = "root-1", conversationContext = ctx1)
        val staged2 = testStagedAction(id = "staged-2", rootInputId = "root-2", conversationContext = ctx2, actionHash = "hash-2")

        val dashStore = DashboardStateStore()
        dashStore.ensureChatSession(sessionId = "chat-1")
        dashStore.ensureChatSession(sessionId = "chat-2")
        dashStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)

        val tempDb = Files.createTempFile("approval-integ-multi", ".db")
        val actionControl = FakeActionControlService(staged1)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val forwarded = mutableListOf<String>()
            val runtime = ApprovalRuntime(
                config = AgentConfig(),
                store = store,
                actionControlService = actionControl,
                dashboardStore = dashStore,
                telegramConfig = TelegramChannelConfig(enabled = false),
                telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { content, source, _, _ ->
                    forwarded += "$source::$content"
                    true
                },
                onApprovalExecuted = {},
                onApprovalDenied = {},
            )

            runtime.onApprovalStaged(
                actionSummary = staged1.summary,
                stagedAction = staged1,
                reason = "Approval for root-1.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = ctx1,
            )

            val req1 = store.activeRequestForSession("chat-1")
            assertNotNull(req1)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, req1.status)

            assertNull(store.activeRequestForSession("chat-2"))

            val msg2 = OwnerMessageEnvelope(
                content = "hello from session 2",
                source = "chat:chat-2",
                priority = InputPriority.HIGH,
                conversationContext = ctx2,
                receivedAtMs = System.currentTimeMillis(),
            )
            val result2 = runtime.routeOwnerMessage(msg2)
            assertTrue(result2 is OwnerIngressResult.Forwarded)
            assertEquals(1, forwarded.size)

            runtime.close()
            dashStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `9 non conversation origin routes through eligible channel selection`() = runBlocking {
        val nonOwnerCtx = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("System"),
            security = ConversationSecurityContexts.default(),
        )
        val staged = testStagedAction(
            conversationContext = nonOwnerCtx,
            origin = ActionOrigin(ai.neopsyke.agent.model.OriginSource.DURABLE_WORK),
        )
        val telegramConfig = TelegramChannelConfig(
            enabled = true,
            ownerChatId = "tg-owner-123",
            ownerUserId = "tg-user-1",
        )
        val config = AgentConfig(
            approvals = ApprovalRuntimeConfig(
                telegramStartupAckEnabled = true,
                dashboardRequiresLiveSubscriber = true,
            ),
        )
        withHarness(staged, config = config, telegramConfig = telegramConfig, useTelegram = true) { h ->
            // Prime Telegram channel status with a successful delivery
            h.runtime.sendTelegramStartupAckIfEnabled()

            stageAndRoute(h, staged)

            val request = h.store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals("telegram", request.target.provider)
        }
    }

    @Test
    fun `10 fail closed when no eligible owner channel exists`() = runBlocking {
        val nonOwnerCtx = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("System"),
            security = ConversationSecurityContexts.default(),
        )
        val staged = testStagedAction(
            conversationContext = nonOwnerCtx,
            origin = ActionOrigin(ai.neopsyke.agent.model.OriginSource.DURABLE_WORK),
        )
        val config = AgentConfig(approvals = ApprovalRuntimeConfig(
            dashboardRequiresLiveSubscriber = true,
            defaultChannel = "telegram",
        ))
        withHarness(staged, config = config) { h ->
            stageAndRoute(h, staged)

            val request = h.store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals("unrouted", request.target.provider)
            assertNotNull(request.routingFailureReason)
        }
    }

    @Test
    fun `11 approval classifier routing follows configured role model path`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val result = h.runtime.routeOwnerMessage(ownerEnvelope("sure"))
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(1, h.actionControl.authorizeCalls)

            val request = h.store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(false, request.usedModelAssistance)
        }
    }

    @Test
    fun `12 provider native delivery evidence captured and persisted`() = runBlocking {
        val telegramConfig = TelegramChannelConfig(
            enabled = true,
            ownerChatId = "tg-owner-123",
            ownerUserId = "tg-user-1",
        )
        val ctx = ownerContext(
            sessionId = "telegram:tg-owner-123",
            provider = "telegram",
            channelId = "tg-owner-123",
            principalId = "tg-user-1",
        )
        val staged = testStagedAction(conversationContext = ctx)
        withHarness(staged, telegramConfig = telegramConfig, useTelegram = true) { h ->
            stageAndRoute(h, staged)

            val request = h.store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(true, request.lastPromptDelivered)
            assertNotNull(request.lastPromptDeliveryDetail)
            assertTrue(request.lastPromptDeliveryDetail!!.contains("telegram-delivered"))
        }
    }

    @Test
    fun `13 prompt instance binding is enforced`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            h.runtime.routeOwnerMessage(ownerEnvelope("what is this?"))
            val refreshed = h.store.requestByStagedActionId(staged.id)
            assertNotNull(refreshed)
            assertTrue(refreshed.promptVersion > 1)

            val staleResult = h.runtime.routeOwnerMessage(ownerEnvelope("ref:invalidref yes"))
            assertTrue(staleResult is OwnerIngressResult.Consumed)
            assertTrue((staleResult as OwnerIngressResult.Consumed).detail.contains("stale"))
            assertEquals(0, h.actionControl.authorizeCalls)
        }
    }

    @Test
    fun `14 approval resolution remains terminal exactly once across competing paths`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            val result1 = h.runtime.routeOwnerMessage(ownerEnvelope("yes", eventId = "evt-1"))
            assertTrue(result1 is OwnerIngressResult.Consumed)
            assertEquals(1, h.actionControl.authorizeCalls)

            val result2 = h.runtime.routeOwnerMessage(ownerEnvelope("yes", eventId = "evt-1"))
            assertTrue(result2 is OwnerIngressResult.Consumed)
            assertEquals(1, h.actionControl.authorizeCalls)

            val result3 = h.runtime.routeOwnerMessage(ownerEnvelope("yes", eventId = "evt-2"))
            assertTrue(result3 is OwnerIngressResult.Forwarded)
            assertEquals(1, h.actionControl.authorizeCalls)
        }
    }

    @Test
    fun `15 reissued owner messages carry approval origin provenance`() = runBlocking {
        val staged = testStagedAction()
        withHarness(staged) { h ->
            stageAndRoute(h, staged)

            h.runtime.routeOwnerMessage(ownerEnvelope("yes, but do it differently"))

            assertEquals(1, h.forwarded.size)
            val (_, ctx) = h.forwarded.single()
            assertEquals("true", ctx.attributes["approval_reissue"])
            assertNotNull(ctx.attributes["approval_request_id"])
            assertEquals(staged.id, ctx.attributes["approval_staged_action_id"])
            assertNotNull(ctx.attributes["approval_prompt_instance_id"])
        }
    }

    @Test
    fun `16 freud record replay captures and replays approval decisions`() = runBlocking {
        val sessionDir = Files.createTempDirectory("approval-integ-session")
        val recordDb = Files.createTempFile("approval-integ-record", ".db")
        val replayDb = Files.createTempFile("approval-integ-replay", ".db")
        val staged = testStagedAction()
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = staged.conversationContext.sessionId)

        SessionRecordingManager(
            mode = SessionRecordingMode.RECORD,
            sessionDir = sessionDir,
        ).use { recordingManager ->
            SqliteApprovalStore(recordDb.toString()).use { store ->
                val actionControl = FakeActionControlService(staged)
                val runtime = ApprovalRuntime(
                    config = AgentConfig(),
                    store = store,
                    actionControlService = actionControl,
                    dashboardStore = dashboardStore,
                    telegramConfig = TelegramChannelConfig(enabled = false),
                    telegramSink = null,
                    interpreter = DefaultApprovalInterpreter(AgentConfig()),
                    forwardNormalInput = { _, _, _, _ -> true },
                    onApprovalExecuted = {},
                    onApprovalDenied = {},
                    sessionRecordingManager = recordingManager,
                )
                runtime.onApprovalStaged(
                    actionSummary = staged.summary,
                    stagedAction = staged,
                    reason = "Owner approval required.",
                    reasonCode = "NEEDS_APPROVAL",
                    conversationContext = staged.conversationContext,
                )
                runtime.routeOwnerMessage(ownerEnvelope("no"))
                assertEquals(1, actionControl.denyCalls)
                runtime.close()
            }
        }

        SessionRecordingManager(
            mode = SessionRecordingMode.REPLAY,
            sessionDir = sessionDir,
        ).use { replayManager ->
            SqliteApprovalStore(replayDb.toString()).use { store ->
                val actionControl = FakeActionControlService(staged)
                val runtime = ApprovalRuntime(
                    config = AgentConfig(),
                    store = store,
                    actionControlService = actionControl,
                    dashboardStore = dashboardStore,
                    telegramConfig = TelegramChannelConfig(enabled = false),
                    telegramSink = null,
                    interpreter = ApprovalInterpreter {
                        ApprovalClassification(ApprovalClassificationKind.UNCLEAR, usedModelAssistance = false)
                    },
                    forwardNormalInput = { _, _, _, _ -> true },
                    onApprovalExecuted = {},
                    onApprovalDenied = {},
                    sessionRecordingManager = replayManager,
                )
                runtime.onApprovalStaged(
                    actionSummary = staged.summary,
                    stagedAction = staged,
                    reason = "Owner approval required.",
                    reasonCode = "NEEDS_APPROVAL",
                    conversationContext = staged.conversationContext,
                )
                val replayResult = runtime.routeOwnerMessage(ownerEnvelope("no"))
                assertTrue(replayResult is OwnerIngressResult.Consumed)
                assertEquals(1, actionControl.denyCalls)
                runtime.close()
            }
        }

        dashboardStore.close()
        Files.deleteIfExists(recordDb)
        Files.deleteIfExists(replayDb)
    }
}
