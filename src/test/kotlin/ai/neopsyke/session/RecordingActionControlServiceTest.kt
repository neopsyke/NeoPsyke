package ai.neopsyke.session

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
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RecordingActionControlServiceTest {

    private class FakeActionControlService : ActionControlService {
        var handleCallCount = 0
            private set

        override suspend fun handleAuthorizationDecision(
            action: PendingAction,
            decision: AuthorizationDecision,
            conversationContext: ConversationContext,
        ): ActionControlDecisionResult {
            handleCallCount++
            return ActionControlDecisionResult.Refused(
                reason = "Test refused",
                reasonCode = "TEST_REFUSED",
            )
        }

        override suspend fun authorizeStagedAction(
            stagedActionId: String,
            grantedBy: ConversationSecurityContext,
            expectedActionHash: String?,
        ) = ActionControlDecisionResult.Refused("not implemented", null)

        override suspend fun denyStagedAction(
            stagedActionId: String,
            deniedBy: ConversationSecurityContext,
            reason: String,
            reasonCode: String?,
        ) = ActionControlDecisionResult.Refused("not implemented", null)

        override suspend fun processAutonomousStagedActions(limit: Int) = emptyList<ActionControlDecisionResult.Executed>()

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

        override fun stagedActions(limit: Int, includeTerminal: Boolean) = emptyList<StagedAction>()
        override fun stagedAction(id: String): StagedAction? = null
        override fun receipts(limit: Int) = emptyList<ActionReceipt>()
        override fun receipt(id: String): ActionReceipt? = null
        override fun ledgerEntries(limit: Int) = emptyList<ActionLedgerEntry>()
        override fun ledgerEntry(id: String): ActionLedgerEntry? = null
    }

    private fun testAction(type: ActionType = ActionType.CONTACT_USER) = PendingAction(
        id = 1L,
        type = type,
        urgency = Urgency.MEDIUM,
        summary = "test action",
        payload = "{}",
    )

    private val testDecision = AuthorizationDecision(
        progress = AuthorizationProgress.DENY,
        commitMode = CommitMode.NOT_APPLICABLE,
        reason = "Test deny",
    )

    @Test
    fun `RECORD mode delegates and records handleAuthorizationDecision`() = runBlocking {
        val file = Files.createTempFile("session-ac-rec-", ".jsonl")
        try {
            val fake = FakeActionControlService()
            val channel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recording = RecordingActionControlService(delegate = fake, channel = channel)

            val result = recording.handleAuthorizationDecision(
                testAction(), testDecision, ConversationContext.default(),
            )
            assertTrue(result is ActionControlDecisionResult.Refused)
            assertEquals(1, fake.handleCallCount)

            channel.close()
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("\"refused\""))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY verifies hash and delegates on match`() = runBlocking {
        val file = Files.createTempFile("session-ac-replay-", ".jsonl")
        try {
            // Record
            val recFake = FakeActionControlService()
            val recChannel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingActionControlService(delegate = recFake, channel = recChannel)
                .handleAuthorizationDecision(testAction(), testDecision, ConversationContext.default())
            recChannel.close()

            // Replay with same action+decision
            val replayFake = FakeActionControlService()
            val replayChannel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingActionControlService(delegate = replayFake, channel = replayChannel)
            replaying.handleAuthorizationDecision(testAction(), testDecision, ConversationContext.default())

            assertFalse(replayChannel.passthroughMode)
            assertEquals(1, replayFake.handleCallCount) // still delegates to real service

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on different action type`() = runBlocking {
        val file = Files.createTempFile("session-ac-div-", ".jsonl")
        try {
            // Record with CONTACT_USER
            val recFake = FakeActionControlService()
            val recChannel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingActionControlService(delegate = recFake, channel = recChannel)
                .handleAuthorizationDecision(
                    testAction(ActionType.CONTACT_USER), testDecision, ConversationContext.default(),
                )
            recChannel.close()

            // Replay with different action type
            val replayFake = FakeActionControlService()
            val replayChannel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingActionControlService(delegate = replayFake, channel = replayChannel)
            replaying.handleAuthorizationDecision(
                testAction(ActionType.WEB_SEARCH), testDecision, ConversationContext.default(),
            )

            assertTrue(replayChannel.passthroughMode)
            assertEquals(1, replayFake.handleCallCount) // fell back to live

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private class ApprovalCapableFakeActionControlService : ActionControlService {
        var authorizeCallCount = 0
            private set
        var denyCallCount = 0
            private set
        var handleCallCount = 0
            private set

        override suspend fun handleAuthorizationDecision(
            action: PendingAction,
            decision: AuthorizationDecision,
            conversationContext: ConversationContext,
        ): ActionControlDecisionResult {
            handleCallCount++
            return ActionControlDecisionResult.Refused("Test refused", "TEST_REFUSED")
        }

        override suspend fun authorizeStagedAction(
            stagedActionId: String,
            grantedBy: ConversationSecurityContext,
            expectedActionHash: String?,
        ): ActionControlDecisionResult {
            authorizeCallCount++
            val staged = StagedAction(
                id = stagedActionId,
                preparedActionId = "prepared-$stagedActionId",
                rootInputId = "root-1",
                actionType = ActionType.CONTACT_USER,
                summary = "test",
                payload = "{}",
                conversationContext = ConversationContext.default(),
                commitMode = CommitMode.APPROVAL_BACKED,
                status = StagedActionStatus.COMPLETED,
                actionHash = expectedActionHash ?: "hash-1",
                statusReason = "approved",
            )
            val authorization = CommitAuthorization(
                id = "auth-$authorizeCallCount",
                stagedActionId = stagedActionId,
                commitMode = CommitMode.APPROVAL_BACKED,
                grantedByPrincipalId = grantedBy.principal.id,
                grantedByChannelId = grantedBy.channel.channelId,
                policyVersion = "test",
                actionHash = expectedActionHash ?: "hash-1",
            )
            val outcome = ActionOutcome(statusSummary = "done", executionStatus = ActionExecutionStatus.SUCCESS, plannerSignal = "done")
            val receipt = ActionReceipt(
                id = "receipt-$authorizeCallCount",
                stagedActionId = stagedActionId,
                authorizationId = authorization.id,
                rootInputId = "root-1",
                actionType = ActionType.CONTACT_USER,
                executionStatus = ActionExecutionStatus.SUCCESS,
                statusSummary = "done",
            )
            return ActionControlDecisionResult.Executed(
                stagedAction = staged,
                authorization = authorization,
                receipt = receipt,
                outcome = outcome,
                executedAction = PendingAction(
                    id = 1L, urgency = Urgency.MEDIUM, type = ActionType.CONTACT_USER,
                    payload = "{}", summary = "test", rootInputId = "root-1",
                    conversationContext = ConversationContext.default(),
                ),
            )
        }

        override suspend fun denyStagedAction(
            stagedActionId: String,
            deniedBy: ConversationSecurityContext,
            reason: String,
            reasonCode: String?,
        ): ActionControlDecisionResult {
            denyCallCount++
            return ActionControlDecisionResult.Cancelled(
                stagedAction = StagedAction(
                    id = stagedActionId,
                    preparedActionId = "prepared-$stagedActionId",
                    rootInputId = "root-1",
                    actionType = ActionType.CONTACT_USER,
                    summary = "test",
                    payload = "{}",
                    conversationContext = ConversationContext.default(),
                    commitMode = CommitMode.APPROVAL_BACKED,
                    status = StagedActionStatus.CANCELLED,
                    actionHash = "hash-1",
                    statusReason = reason,
                    statusReasonCode = reasonCode,
                ),
                ledgerEntry = ActionLedgerEntry(
                    id = "ledger-$denyCallCount",
                    kind = ActionLedgerKind.CANCELLED,
                    importance = ActionRecordImportance.SIGNAL,
                    actionType = ActionType.CONTACT_USER,
                    summary = reason,
                    rootInputId = "root-1",
                    stagedActionId = stagedActionId,
                    reasonCode = reasonCode,
                    conversationContext = ConversationContext.default(),
                ),
            )
        }

        override suspend fun processAutonomousStagedActions(limit: Int) = emptyList<ActionControlDecisionResult.Executed>()
        override suspend fun recordBypassExecution(action: PendingAction, conversationContext: ConversationContext, outcome: ActionOutcome, reason: String, reasonCode: String?): ActionReceipt? = null
        override fun recordLedgerEntry(action: PendingAction, conversationContext: ConversationContext, kind: ActionLedgerKind, importance: ActionRecordImportance, summary: String, reasonCode: String?, source: String?, stagedActionId: String?, authorizationId: String?, receiptId: String?): ActionLedgerEntry? = null
        override fun stagedActions(limit: Int, includeTerminal: Boolean) = emptyList<StagedAction>()
        override fun stagedAction(id: String): StagedAction? = null
        override fun receipts(limit: Int) = emptyList<ActionReceipt>()
        override fun receipt(id: String): ActionReceipt? = null
        override fun ledgerEntries(limit: Int) = emptyList<ActionLedgerEntry>()
        override fun ledgerEntry(id: String): ActionLedgerEntry? = null
    }

    @Test
    fun `RECORD mode records user approval via authorizeStagedAction`() = runBlocking {
        val file = Files.createTempFile("session-ac-approve-", ".jsonl")
        try {
            val fake = ApprovalCapableFakeActionControlService()
            val channel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recording = RecordingActionControlService(delegate = fake, channel = channel)
            val grantedBy = ConversationSecurityContexts.ownerDirect(
                provider = "webapp", channelId = "chat-1", principalId = "owner",
            )
            val result = recording.authorizeStagedAction("staged-1", grantedBy, "hash-1")
            assertTrue(result is ActionControlDecisionResult.Executed)
            assertEquals(1, fake.authorizeCallCount)

            channel.close()
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            val mapper = jacksonObjectMapper()
            val entry = mapper.readValue<Map<String, Any>>(lines[0])
            @Suppress("UNCHECKED_CAST")
            val data = entry["data"] as Map<String, Any>
            assertEquals(true, data["user_decision"])
            assertEquals(true, data["approved"])
            assertEquals("staged-1", data["staged_action_id"])
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `RECORD mode records user denial via denyStagedAction`() = runBlocking {
        val file = Files.createTempFile("session-ac-deny-", ".jsonl")
        try {
            val fake = ApprovalCapableFakeActionControlService()
            val channel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recording = RecordingActionControlService(delegate = fake, channel = channel)
            val deniedBy = ConversationSecurityContexts.ownerDirect(
                provider = "webapp", channelId = "chat-1", principalId = "owner",
            )
            val result = recording.denyStagedAction("staged-1", deniedBy, "Owner denied from chat.", "OWNER_DENIED")
            assertTrue(result is ActionControlDecisionResult.Cancelled)
            assertEquals(1, fake.denyCallCount)

            channel.close()
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            val mapper = jacksonObjectMapper()
            val entry = mapper.readValue<Map<String, Any>>(lines[0])
            @Suppress("UNCHECKED_CAST")
            val data = entry["data"] as Map<String, Any>
            assertEquals(true, data["user_decision"])
            assertEquals(false, data["approved"])
            assertEquals("staged-1", data["staged_action_id"])
            assertEquals("Owner denied from chat.", data["reason"])
            assertEquals("OWNER_DENIED", data["reason_code"])
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on different authorization progress`() = runBlocking {
        val file = Files.createTempFile("session-ac-divprog-", ".jsonl")
        try {
            val recFake = FakeActionControlService()
            val recChannel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingActionControlService(delegate = recFake, channel = recChannel)
                .handleAuthorizationDecision(testAction(), testDecision, ConversationContext.default())
            recChannel.close()

            // Replay with different decision progress
            val replayFake = FakeActionControlService()
            val replayChannel = RecordReplayChannel(
                channelName = "action_control",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val differentDecision = AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_COMMIT,
                commitMode = CommitMode.POLICY_AUTONOMOUS,
                reason = "Auto commit",
            )
            RecordingActionControlService(delegate = replayFake, channel = replayChannel)
                .handleAuthorizationDecision(testAction(), differentDecision, ConversationContext.default())

            assertTrue(replayChannel.passthroughMode)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
