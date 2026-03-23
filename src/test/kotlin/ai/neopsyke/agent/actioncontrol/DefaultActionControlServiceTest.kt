package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.model.Urgency
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
