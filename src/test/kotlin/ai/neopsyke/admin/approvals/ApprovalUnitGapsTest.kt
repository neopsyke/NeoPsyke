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
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.dashboard.DashboardStateStore
import ai.neopsyke.llm.LlmRoleLabels
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Missing unit test scenarios from spec 18.14 gaps (review section 2.4.1).
 */
class ApprovalUnitGapsTest {

    // ---- Shared fakes ----

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
                    progress = AuthorizationProgress.ALLOW_STAGE, commitMode = CommitMode.APPROVAL_BACKED, reason = "approval required",
                ),
            )

        override suspend fun authorizeStagedAction(
            stagedActionId: String, grantedBy: ConversationSecurityContext, expectedActionHash: String?,
        ): ActionControlDecisionResult {
            authorizeCalls += 1
            val authorization = CommitAuthorization(
                id = "auth-$authorizeCalls", stagedActionId = stagedActionId, commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id, grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = "test", actionHash = currentStagedAction.actionHash,
            )
            val outcome = ActionOutcome(statusSummary = "done", executionStatus = ActionExecutionStatus.SUCCESS, plannerSignal = "done")
            val receipt = ActionReceipt(
                id = "receipt-$authorizeCalls", stagedActionId = stagedActionId, authorizationId = authorization.id,
                rootInputId = currentStagedAction.rootInputId, actionType = currentStagedAction.actionType,
                executionStatus = ActionExecutionStatus.SUCCESS, statusSummary = "done",
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
                    groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
            )
        }

        override suspend fun denyStagedAction(
            stagedActionId: String, deniedBy: ConversationSecurityContext, reason: String, reasonCode: String?,
        expectedActionHash: String?,
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

    private fun ownerContext(sessionId: String = "chat-1"): ConversationContext =
        ConversationContext(
            sessionId = sessionId,
            interlocutor = Interlocutor.named("Owner"),
            security = ConversationSecurityContexts.ownerDirect(provider = "webapp", channelId = sessionId, principalId = "owner"),
        )

    private fun stagedAction(ctx: ConversationContext = ownerContext()): StagedAction =
        StagedAction(
            id = "staged-1", preparedActionId = "prepared-1", rootInputId = "root-1",
            actionType = ActionType.CONTACT_USER, summary = "Send a message", payload = "hello",
            conversationContext = ctx, origin = ActionOrigin.USER, commitMode = CommitMode.APPROVAL_BACKED,
            status = StagedActionStatus.WAITING_AUTHORIZATION, actionHash = "hash-1", statusReason = "approval required",
        )

    private fun envelope(content: String, ctx: ConversationContext = ownerContext(), eventId: String? = null): OwnerMessageEnvelope =
        OwnerMessageEnvelope(
            content = content, source = "chat:${ctx.sessionId}", priority = InputPriority.HIGH,
            conversationContext = ctx, receivedAtMs = System.currentTimeMillis(), eventId = eventId,
        )

    // ---- 18.14.12: Channel-eligibility resolver policy ordering ----

    @Test
    fun `channel resolver prefers highest priority live deliverable channel`() {
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        val telegramSink = FakeTelegramSink()
        val telegramConfig = TelegramChannelConfig(enabled = true, ownerChatId = "tg-owner", ownerUserId = "tg-user")
        val approvalConfig = ApprovalRuntimeConfig(
            channelPriority = listOf("telegram", "dashboard"),
            dashboardRequiresLiveSubscriber = true,
        )
        val statusProvider = DefaultApprovalChannelStatusProvider(
            approvals = approvalConfig,
            dashboardStore = dashboardStore,
            telegramConfig = telegramConfig,
            telegramSink = telegramSink,
        )

        statusProvider.recordDelivery(
            ApprovalTarget(provider = "telegram", sessionId = "telegram:tg-owner", channelId = "tg-owner"),
            ConversationDeliveryResult(delivered = true, detail = "ok"),
            "approval-startup-ack",
        )

        val resolver = DefaultApprovalChannelResolver(approvals = approvalConfig, statusProvider = statusProvider)
        val nonOwnerCtx = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("System"),
            security = ConversationSecurityContexts.default(),
        )
        val staged = stagedAction(ctx = nonOwnerCtx).copy(origin = ActionOrigin(ai.neopsyke.agent.model.OriginSource.DURABLE_WORK))

        val decision = resolver.resolve(staged)
        assertNotNull(decision.target)
        assertEquals("telegram", decision.target!!.provider)
        assertEquals("resolved_eligible", decision.routingScope)

        dashboardStore.close()
    }

    @Test
    fun `channel resolver falls back to default deliverable when no channel is live`() {
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        val approvalConfig = ApprovalRuntimeConfig(
            defaultChannel = "dashboard",
            channelPriority = listOf("telegram"),
            dashboardRequiresLiveSubscriber = false,
        )
        val statusProvider = DefaultApprovalChannelStatusProvider(
            approvals = approvalConfig,
            dashboardStore = dashboardStore,
            telegramConfig = TelegramChannelConfig(enabled = false),
            telegramSink = null,
        )
        val resolver = DefaultApprovalChannelResolver(approvals = approvalConfig, statusProvider = statusProvider)
        val nonOwnerCtx = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("System"),
            security = ConversationSecurityContexts.default(),
        )
        val staged = stagedAction(ctx = nonOwnerCtx).copy(origin = ActionOrigin(ai.neopsyke.agent.model.OriginSource.DURABLE_WORK))

        val decision = resolver.resolve(staged)
        assertNotNull(decision.target)
        assertEquals("webapp", decision.target!!.provider)
        assertEquals("resolved_default_deliverable", decision.routingScope)

        dashboardStore.close()
    }

    @Test
    fun `channel resolver returns unresolved when no channels eligible`() {
        // Use a fresh dashboard store without any sessions to make dashboard undeliverable
        val dashboardStore = DashboardStateStore()
        val approvalConfig = ApprovalRuntimeConfig(
            defaultChannel = "telegram",
            dashboardRequiresLiveSubscriber = true,
        )
        val statusProvider = DefaultApprovalChannelStatusProvider(
            approvals = approvalConfig,
            dashboardStore = dashboardStore,
            telegramConfig = TelegramChannelConfig(enabled = false),
            telegramSink = null,
        )
        val resolver = DefaultApprovalChannelResolver(approvals = approvalConfig, statusProvider = statusProvider)
        val nonOwnerCtx = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("System"),
            security = ConversationSecurityContexts.default(),
        )
        val staged = stagedAction(ctx = nonOwnerCtx).copy(origin = ActionOrigin(ai.neopsyke.agent.model.OriginSource.DURABLE_WORK))

        val decision = resolver.resolve(staged)
        assertEquals(null, decision.target)
        assertEquals("unresolved", decision.routingScope)
        assertNotNull(decision.failureReason)

        dashboardStore.close()
    }

    // ---- 18.14.13: Approval-classifier runtime config loading and default-model selection ----

    @Test
    fun `approval interpreter role label is registered and classifiable`() {
        assertEquals("approval_interpreter", LlmRoleLabels.APPROVAL_INTERPRETER)
        val classified = LlmRoleLabels.classify(
            actor = "approval_interpreter",
            callSite = "approval_interpreter_classify",
            actionType = null,
        )
        assertEquals(LlmRoleLabels.APPROVAL_INTERPRETER, classified)
    }

    @Test
    fun `approval runtime config has expected defaults`() {
        val config = ApprovalRuntimeConfig()
        assertEquals(true, config.enabled)
        assertEquals(5 * 60 * 1000L, config.ttlMs)
        assertEquals(2, config.clarificationTurns)
        assertEquals(true, config.telegramStartupAckEnabled)
        assertEquals(true, config.dashboardRequiresLiveSubscriber)
    }

    // ---- 18.14.18: Reissued-message provenance field generation ----

    @Test
    fun `reissued message carries all provenance attributes`() = runBlocking {
        val staged = stagedAction()
        val forwarded = mutableListOf<Pair<String, ConversationContext>>()
        val tempDb = Files.createTempFile("approval-prov", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl,
                dashboardStore = dashboardStore, telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { content, source, _, context -> forwarded += "$source::$content" to context; true },
                onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )
            val request = store.activeRequestForSession(staged.conversationContext.sessionId)!!
            runtime.routeOwnerMessage(envelope("no, do something else"))

            assertEquals(1, forwarded.size)
            val (source, ctx) = forwarded.single()
            assertTrue(source.startsWith("approval-reissue:"))
            assertEquals("true", ctx.attributes["approval_reissue"])
            assertEquals(request.id, ctx.attributes["approval_request_id"])
            assertEquals(staged.id, ctx.attributes["approval_staged_action_id"])
            assertNotNull(ctx.attributes["approval_prompt_instance_id"])

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    // ---- 18.14.7: Blocked-root scheduler suppression in approval context ----

    @Test
    fun `approval request creation emits audit trail for scheduling coordination`() = runBlocking {
        val staged = stagedAction()
        val tempDb = Files.createTempFile("approval-block", ".db")
        val actionControl = FakeActionControlService(staged)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = staged.conversationContext.sessionId)
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(), store = store, actionControlService = actionControl,
                dashboardStore = dashboardStore, telegramConfig = TelegramChannelConfig(enabled = false), telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true },
                onApprovalExecuted = {}, onApprovalDenied = {},
            )
            runtime.onApprovalStaged(
                actionSummary = staged.summary, stagedAction = staged,
                reason = "Approval required.", reasonCode = "NEEDS_APPROVAL",
                conversationContext = staged.conversationContext,
            )

            val request = store.requestByStagedActionId(staged.id)
            assertNotNull(request)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, request.status)
            assertEquals(staged.rootInputId, request.rootInputId)

            val audit = store.listAudit(request.id)
            assertTrue(audit.any { it.kind == "request_created" })

            runtime.close()
            dashboardStore.close()
            Files.deleteIfExists(tempDb)
        }
    }

    // ---- SUPERSEDED status is terminal ----

    @Test
    fun `superseded status is available and distinct from other terminal states`() {
        val statuses = ApprovalRequestStatus.entries
        assertTrue(statuses.contains(ApprovalRequestStatus.SUPERSEDED))

        val terminalStatuses = setOf(
            ApprovalRequestStatus.APPROVED,
            ApprovalRequestStatus.DENIED,
            ApprovalRequestStatus.DENIED_AND_REISSUED,
            ApprovalRequestStatus.EXPIRED,
            ApprovalRequestStatus.SUPERSEDED,
        )
        val nonTerminalStatuses = setOf(
            ApprovalRequestStatus.QUEUED,
            ApprovalRequestStatus.AWAITING_OWNER_REPLY,
            ApprovalRequestStatus.AWAITING_CLARIFICATION,
        )
        assertEquals(terminalStatuses.size + nonTerminalStatuses.size, statuses.size)
    }

    // ---- OwnerMessageEnvelope provenance fields ----

    @Test
    fun `owner message envelope supports provenance fields`() {
        val envelope = OwnerMessageEnvelope(
            content = "test",
            source = "chat:test",
            priority = InputPriority.HIGH,
            conversationContext = ownerContext(),
            receivedAtMs = System.currentTimeMillis(),
            originApprovalRequestId = "req-123",
            originStagedActionId = "staged-456",
            originApprovalSource = "deny_and_reissue",
        )
        assertEquals("req-123", envelope.originApprovalRequestId)
        assertEquals("staged-456", envelope.originStagedActionId)
        assertEquals("deny_and_reissue", envelope.originApprovalSource)
    }
}
