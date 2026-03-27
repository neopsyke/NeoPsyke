package ai.neopsyke.session

import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.model.ActionLedgerEntry
import ai.neopsyke.agent.model.ActionLedgerKind
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.ActionRecordImportance
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.Urgency
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
