package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.config.AgentConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ApprovalRuntimeTest {
    private class FakeActionControlService(
        stagedAction: StagedAction,
    ) : ActionControlService {
        var currentStagedAction: StagedAction = stagedAction
        var authorizeCalls: Int = 0
        var denyCalls: Int = 0
        var lastExpectedActionHash: String? = null
        var lastExpectedDeniedActionHash: String? = null

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
            lastExpectedActionHash = expectedActionHash
            if (!expectedActionHash.isNullOrBlank() && expectedActionHash != currentStagedAction.actionHash) {
                return ActionControlDecisionResult.Refused(
                    reason = "hash mismatch",
                    reasonCode = "STAGED_ACTION_HASH_MISMATCH",
                )
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
            lastExpectedDeniedActionHash = expectedActionHash
            if (!expectedActionHash.isNullOrBlank() && expectedActionHash != currentStagedAction.actionHash) {
                return ActionControlDecisionResult.Refused(
                    reason = "hash mismatch",
                    reasonCode = "STAGED_ACTION_HASH_MISMATCH",
                )
            }
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
            return ConversationDeliveryResult(
                delivered = true,
                detail = "telegram-delivered:$chatId",
            )
        }
    }

    @Test
    fun `approval prompt is delivered and approve reply authorizes staged action`() = runBlocking {
        withRuntime(testStagedAction()) { runtime, store, dashboardStore, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            assertTrue(dashboardStore.chatSessionJson(actionControl.currentStagedAction.conversationContext.sessionId).orEmpty().contains("Approval required."))

            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-approve-1",
                )
            )

            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)
            assertEquals("hash-1", actionControl.lastExpectedActionHash)
            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status)
        }
    }

    @Test
    fun `modified approval reply denies and reissues with provenance`() = runBlocking {
        val forwarded = mutableListOf<Pair<String, ConversationContext>>()
        withRuntime(
            stagedAction = testStagedAction(),
            forwardNormalInput = { content, source, _, context ->
                forwarded += "$source::$content" to context
                true
            }
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes, send it tomorrow instead",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-reissue-1",
                )
            )

            assertTrue(result is OwnerIngressResult.Forwarded)
            assertEquals(1, actionControl.denyCalls)
            assertEquals(1, forwarded.size)
            val (forwardedSource, forwardedContext) = forwarded.single()
            assertTrue(forwardedSource.startsWith("approval-reissue:"))
            assertEquals("true", forwardedContext.attributes["approval_reissue"])
            assertEquals("staged-1", forwardedContext.attributes["approval_staged_action_id"])
            assertEquals(
                ApprovalRequestStatus.DENIED_AND_REISSUED,
                store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status
            )
        }
    }

    @Test
    fun `approve on hash drift supersedes request and refreshes prompt`() = runBlocking {
        withRuntime(testStagedAction()) { runtime, store, dashboardStore, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            val initial = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            actionControl.currentStagedAction = actionControl.currentStagedAction.copy(
                actionHash = "hash-2",
                summary = "Send updated message",
            )

            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-hash-approve-1",
                )
            )

            val replacement = store.activeRequestForSession(actionControl.currentStagedAction.conversationContext.sessionId)
            val sessionJson = dashboardStore.chatSessionJson(actionControl.currentStagedAction.conversationContext.sessionId).orEmpty()
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(0, actionControl.authorizeCalls)
            assertEquals(0, actionControl.denyCalls)
            assertEquals(ApprovalRequestStatus.SUPERSEDED, store.request(initial.id)?.status)
            assertNotNull(replacement)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, replacement.status)
            assertEquals("hash-2", replacement.actionHash)
            assertTrue(replacement.id != initial.id)
            assertTrue(sessionJson.contains("stale because the staged action changed"))
        }
    }

    @Test
    fun `deny and reissue on hash drift supersedes request without forwarding`() = runBlocking {
        var forwarded = 0
        withRuntime(
            stagedAction = testStagedAction(),
            forwardNormalInput = { _, _, _, _ ->
                forwarded += 1
                true
            }
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            val initial = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            actionControl.currentStagedAction = actionControl.currentStagedAction.copy(actionHash = "hash-2")

            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "no, send it tomorrow instead",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-hash-reissue-1",
                )
            )

            val replacement = store.activeRequestForSession(actionControl.currentStagedAction.conversationContext.sessionId)
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(0, actionControl.denyCalls)
            assertEquals(0, forwarded)
            assertEquals(ApprovalRequestStatus.SUPERSEDED, store.request(initial.id)?.status)
            assertNotNull(replacement)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, replacement.status)
            assertEquals("hash-2", replacement.actionHash)
        }
    }

    @Test
    fun `stale reply is ignored after clarification refresh`() = runBlocking {
        withRuntime(testStagedAction()) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            val beforeRefresh = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "maybe",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-unclear-1",
                )
            )
            val refreshed = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            assertTrue(refreshed.promptVersion > beforeRefresh.promptVersion)

            val stale = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = beforeRefresh.lastPromptAtMs,
                    eventId = "evt-stale-1",
                )
            )

            assertTrue(stale is OwnerIngressResult.Consumed)
            assertEquals(0, actionControl.authorizeCalls)
            assertEquals(ApprovalRequestStatus.AWAITING_CLARIFICATION, store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status)
        }
    }

    @Test
    fun `duplicate terminal reply event is ignored instead of forwarding to normal ingress`() = runBlocking {
        var forwarded = 0
        withRuntime(
            stagedAction = testStagedAction(),
            forwardNormalInput = { _, _, _, _ ->
                forwarded += 1
                true
            }
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            val first = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-dup-1",
                )
            )
            val duplicate = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-dup-1",
                )
            )

            assertTrue(first is OwnerIngressResult.Consumed)
            assertTrue(duplicate is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)
            assertEquals(0, forwarded)
            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status)
        }
    }

    @Test
    fun `channel principal mismatch reply is rejected fail closed`() = runBlocking {
        withRuntime(testStagedAction()) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            val mismatched = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = ownerConversationContext(principalId = "different-owner"),
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-scope-mismatch-1",
                )
            )

            assertTrue(mismatched is OwnerIngressResult.Consumed)
            assertEquals(0, actionControl.authorizeCalls)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status)
        }
    }

    @Test
    fun `expiry denies staged action and marks request expired`() = runBlocking {
        val config = AgentConfig(
            approvals = AgentConfig().approvals.copy(ttlMs = 1L),
        )
        withRuntime(
            stagedAction = testStagedAction(),
            config = config,
        ) { runtime, store, _, actionControl, _, denied, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            Thread.sleep(5L)
            runtime.expirePendingRequests()

            val request = store.requestByStagedActionId(actionControl.currentStagedAction.id)
            assertEquals(1, actionControl.denyCalls)
            assertEquals(1, denied.size)
            assertEquals(ApprovalRequestStatus.EXPIRED, request?.status)
        }
    }

    @Test
    fun `unroutable non conversation approval persists fail closed artifact`() = runBlocking {
        val stagedAction = testStagedAction(
            id = "staged-unrouted",
            rootInputId = "root-unrouted",
            conversationContext = ConversationContext(
                sessionId = "system-session",
                interlocutor = Interlocutor.named("System"),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = "system",
                    channelId = "system",
                ),
            ),
            origin = ActionOrigin.SYSTEM,
        )
        val config = AgentConfig(
            approvals = AgentConfig().approvals.copy(
                defaultChannel = "telegram",
                channelPriority = emptyList(),
            ),
        )
        withRuntime(stagedAction, config = config) { runtime, store, dashboardStore, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            val request = store.requestByStagedActionId(actionControl.currentStagedAction.id)
            assertNotNull(request)
            assertEquals("unrouted", request.target.provider)
            assertTrue(request.routingFailureReason?.contains("No eligible verified owner channel") == true)
            assertFalse(dashboardStore.chatSessionJson(ConversationContext.DEFAULT_SESSION_ID).orEmpty().contains("Approval required."))
        }
    }

    @Test
    fun `non conversation telegram routing stays fail closed without delivery evidence`() = runBlocking {
        val stagedAction = testStagedAction(
            id = "staged-no-telegram-evidence",
            rootInputId = "root-no-telegram-evidence",
            conversationContext = ConversationContext(
                sessionId = "goal-session-no-evidence",
                interlocutor = Interlocutor.named("Goal"),
                security = ConversationSecurityContexts.internalAutomation(provider = "goal", channelId = "goal"),
            ),
            origin = ActionOrigin.SYSTEM,
        )
        val telegramSink = FakeTelegramSink()
        val config = AgentConfig(
            approvals = AgentConfig().approvals.copy(
                defaultChannel = "telegram",
                channelPriority = listOf("telegram"),
            ),
        )
        withRuntime(
            stagedAction = stagedAction,
            config = config,
            telegramConfig = TelegramChannelConfig(
                enabled = true,
                ownerChatId = "1234",
                ownerUserId = "5678",
            ),
            telegramSink = telegramSink,
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            val request = store.requestByStagedActionId(actionControl.currentStagedAction.id)
            assertEquals("unrouted", request?.target?.provider)
            assertTrue(request?.routingFailureReason?.contains("No eligible verified owner channel") == true)
            assertTrue(telegramSink.messages.isEmpty())
        }
    }

    @Test
    fun `explanatory question keeps request pending and redacts unsafe host text`() = runBlocking {
        val stagedAction = testStagedAction(
            summary = "Send localhost summary 550e8400-e29b-41d4-a716-446655440000 deadbeefdeadbeefdeadbeef"
        )
        withRuntime(stagedAction) { runtime, store, dashboardStore, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Review localhost:8080 before sending with token deadbeefdeadbeefdeadbeef.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "what exactly is this doing?",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-explain-1",
                )
            )

            val sessionJson = dashboardStore.chatSessionJson(actionControl.currentStagedAction.conversationContext.sessionId).orEmpty()
            assertTrue(result is OwnerIngressResult.Consumed)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status)
            assertTrue(sessionJson.contains("Approval details:"))
            assertTrue(sessionJson.contains("[redacted-host]"))
            assertTrue(sessionJson.contains("[redacted-id]"))
            assertTrue(sessionJson.contains("[redacted-token]"))
        }
    }

    @Test
    fun `approval works after clarification without requiring references`() = runBlocking {
        withRuntime(testStagedAction()) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            // First reply is ambiguous → triggers clarification
            runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "maybe",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-clarify-1",
                )
            )
            val clarified = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            assertEquals(ApprovalRequestStatus.AWAITING_CLARIFICATION, clarified.status)

            // Second reply is a clear "yes" — should be accepted without any approval ref
            val approved = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = clarified.lastPromptAtMs + 5,
                    eventId = "evt-approve-after-clarify-1",
                )
            )

            assertTrue(approved is OwnerIngressResult.Consumed)
            assertEquals(1, actionControl.authorizeCalls)
            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId(actionControl.currentStagedAction.id)?.status)
        }
    }

    @Test
    fun `one active approval prompt per conversation is enforced and next queued prompt activates after resolution`() = runBlocking {
        val firstAction = testStagedAction(id = "staged-1", rootInputId = "root-1", summary = "First")
        val secondAction = testStagedAction(id = "staged-2", rootInputId = "root-2", summary = "Second")
        val firstControl = FakeActionControlService(firstAction)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = firstAction.conversationContext.sessionId)
        SqliteApprovalStore(Files.createTempFile("approval-runtime-queue", ".db").toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = AgentConfig(),
                store = store,
                actionControlService = object : ActionControlService by firstControl {
                    override fun stagedAction(id: String): StagedAction? =
                        when (id) {
                            firstControl.currentStagedAction.id -> firstControl.currentStagedAction
                            secondAction.id -> secondAction
                            else -> null
                        }

                    override fun stagedActions(limit: Int, includeTerminal: Boolean): List<StagedAction> =
                        listOf(firstControl.currentStagedAction, secondAction)
                },
                dashboardStore = dashboardStore,
                telegramConfig = TelegramChannelConfig(enabled = false),
                telegramSink = null,
                interpreter = DefaultApprovalInterpreter(AgentConfig()),
                forwardNormalInput = { _, _, _, _ -> true },
                onApprovalExecuted = {},
                onApprovalDenied = {},
            )
            runtime.onApprovalStaged("First", firstAction, "Owner approval required.", "NEEDS_APPROVAL", firstAction.conversationContext)
            runtime.onApprovalStaged("Second", secondAction, "Owner approval required.", "NEEDS_APPROVAL", secondAction.conversationContext)

            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, store.requestByStagedActionId("staged-1")?.status)
            assertEquals(ApprovalRequestStatus.QUEUED, store.requestByStagedActionId("staged-2")?.status)

            runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = firstAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-queue-approve-1",
                )
            )

            assertEquals(ApprovalRequestStatus.APPROVED, store.requestByStagedActionId("staged-1")?.status)
            assertEquals(ApprovalRequestStatus.AWAITING_OWNER_REPLY, store.requestByStagedActionId("staged-2")?.status)
            runtime.close()
            dashboardStore.close()
        }
    }

    @Test
    fun `non conversation origin uses default deliverable telegram channel`() = runBlocking {
        val stagedAction = testStagedAction(
            id = "staged-telegram",
            rootInputId = "root-telegram",
            conversationContext = ConversationContext(
                sessionId = "goal-session",
                interlocutor = Interlocutor.named("Goal"),
                security = ConversationSecurityContexts.internalAutomation(provider = "goal", channelId = "goal"),
            ),
            origin = ActionOrigin.SYSTEM,
        )
        val telegramSink = FakeTelegramSink()
        val config = AgentConfig(
            approvals = AgentConfig().approvals.copy(
                defaultChannel = "telegram",
                channelPriority = listOf("dashboard"),
                telegramStartupAckEnabled = true,
            ),
        )
        withRuntime(
            stagedAction = stagedAction,
            config = config,
            telegramConfig = TelegramChannelConfig(
                enabled = true,
                ownerChatId = "1234",
                ownerUserId = "5678",
            ),
            telegramSink = telegramSink,
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.sendTelegramStartupAckIfEnabled()
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )

            val request = store.requestByStagedActionId(actionControl.currentStagedAction.id)
            assertEquals("telegram", request?.target?.provider)
            assertTrue(telegramSink.messages.isNotEmpty())
            assertTrue(request?.lastPromptDeliveryDetail?.contains("telegram-delivered") == true)
        }
    }

    @Test
    fun `approval replies replay from approval-flow recording channel`() = runBlocking {
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
                    telegramConfig = TelegramChannelConfig(enabled = false),
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
                        eventId = "evt-replay-1",
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
                    telegramConfig = TelegramChannelConfig(enabled = false),
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
                        eventId = "evt-replay-1",
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

    @Test
    fun `stale terminal request does not consume new message with different eventId`() = runBlocking {
        var forwarded = 0
        withRuntime(
            stagedAction = testStagedAction(),
            forwardNormalInput = { _, _, _, _ ->
                forwarded += 1
                true
            }
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            // Stage and approve to create a terminal request.
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-old-1",
                )
            )
            // Manually backdate the terminal request's resolution to >60s ago.
            val request = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            store.updateRequest(request.copy(resolutionAtMs = System.currentTimeMillis() - 120_000))

            // A new message with a different eventId should be forwarded, not consumed.
            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "Create a new goal for weather reports",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-new-2",
                )
            )
            assertTrue(result is OwnerIngressResult.Forwarded, "Stale terminal request should not block new messages")
            assertEquals(1, forwarded)
        }
    }

    @Test
    fun `fresh terminal request still consumes duplicate eventId`() = runBlocking {
        var forwarded = 0
        withRuntime(
            stagedAction = testStagedAction(),
            forwardNormalInput = { _, _, _, _ ->
                forwarded += 1
                true
            }
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-fresh-1",
                )
            )
            // Request was just resolved — within the 60s window.
            // A duplicate eventId should still be consumed.
            val duplicate = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-fresh-1",
                )
            )
            assertTrue(duplicate is OwnerIngressResult.Consumed, "Fresh duplicate should still be consumed")
            assertEquals(0, forwarded)
        }
    }

    @Test
    fun `stale terminal request with matching eventId is forwarded not consumed`() = runBlocking {
        var forwarded = 0
        withRuntime(
            stagedAction = testStagedAction(),
            forwardNormalInput = { _, _, _, _ ->
                forwarded += 1
                true
            }
        ) { runtime, store, _, actionControl, _, _, _, _ ->
            runtime.onApprovalStaged(
                actionSummary = actionControl.currentStagedAction.summary,
                stagedAction = actionControl.currentStagedAction,
                reason = "Owner approval required.",
                reasonCode = "NEEDS_APPROVAL",
                conversationContext = actionControl.currentStagedAction.conversationContext,
            )
            runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "yes",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-collide",
                )
            )
            // Backdate resolution to simulate a cross-run stale request.
            val request = store.requestByStagedActionId(actionControl.currentStagedAction.id)!!
            store.updateRequest(request.copy(resolutionAtMs = System.currentTimeMillis() - 120_000))

            // Same eventId as the stale request — simulates cross-run collision.
            // Should be forwarded because the request is stale.
            val result = runtime.routeOwnerMessage(
                OwnerMessageEnvelope(
                    content = "Create a new goal",
                    source = "chat:test",
                    priority = InputPriority.HIGH,
                    conversationContext = actionControl.currentStagedAction.conversationContext,
                    receivedAtMs = System.currentTimeMillis(),
                    eventId = "evt-collide",
                )
            )
            assertTrue(result is OwnerIngressResult.Forwarded, "Stale request with colliding eventId must not block messages")
            assertEquals(1, forwarded)
        }
    }

    private suspend fun withRuntime(
        stagedAction: StagedAction,
        config: AgentConfig = AgentConfig(),
        telegramConfig: TelegramChannelConfig = TelegramChannelConfig(enabled = false),
        telegramSink: TelegramMessageSink? = null,
        forwardNormalInput: (String, String, InputPriority, ConversationContext) -> Boolean = { _, _, _, _ -> true },
        block: suspend (
            ApprovalRuntime,
            SqliteApprovalStore,
            DashboardStateStore,
            FakeActionControlService,
            MutableList<ActionControlDecisionResult.Executed>,
            MutableList<ActionControlDecisionResult.Cancelled>,
            TelegramMessageSink?,
            AgentConfig,
        ) -> Unit,
    ) {
        val tempDb = Files.createTempFile("approval-runtime", ".db")
        val actionControl = FakeActionControlService(stagedAction)
        val dashboardStore = DashboardStateStore()
        dashboardStore.ensureChatSession(sessionId = stagedAction.conversationContext.sessionId)
        dashboardStore.ensureChatSession(sessionId = ConversationContext.DEFAULT_SESSION_ID)
        val executed = mutableListOf<ActionControlDecisionResult.Executed>()
        val denied = mutableListOf<ActionControlDecisionResult.Cancelled>()
        SqliteApprovalStore(tempDb.toString()).use { store ->
            val runtime = ApprovalRuntime(
                config = config,
                store = store,
                actionControlService = actionControl,
                dashboardStore = dashboardStore,
                telegramConfig = telegramConfig,
                telegramSink = telegramSink,
                interpreter = DefaultApprovalInterpreter(config),
                forwardNormalInput = forwardNormalInput,
                onApprovalExecuted = { executed += it },
                onApprovalDenied = { denied += it },
            )
            try {
                block(runtime, store, dashboardStore, actionControl, executed, denied, telegramSink, config)
            } finally {
                runtime.close()
                dashboardStore.close()
                Files.deleteIfExists(tempDb)
            }
        }
    }

    private fun ownerConversationContext(
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
        conversationContext: ConversationContext = ownerConversationContext(),
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
}
