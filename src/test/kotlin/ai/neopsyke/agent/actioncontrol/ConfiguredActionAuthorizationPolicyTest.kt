package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfiguredActionAuthorizationPolicyTest {

    private fun registry(config: AgentConfig = AgentConfig()): ActionRegistry =
        ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
            )
        )

    private fun ownerContext(): ConversationContext =
        ConversationContext(
            sessionId = "owner-session",
            interlocutor = Interlocutor.named("owner"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "webapp",
                channelId = "owner-session",
            ),
        )

    private fun externalContext(): ConversationContext =
        ConversationContext(
            sessionId = "shared-session",
            interlocutor = Interlocutor.named("teammate"),
            security = ConversationSecurityContexts.externalParticipant(
                provider = "slack",
                channelId = "team-channel",
            ),
        )

    @Test
    fun `owner contact_user is allowed to direct commit`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 1,
                urgency = Urgency.MEDIUM,
                type = ActionType.CONTACT_USER,
                payload = "hello",
                summary = "reply",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_COMMIT, decision.progress)
        assertEquals(CommitMode.POLICY_AUTONOMOUS, decision.commitMode)
    }

    @Test
    fun `external email_send is staged for owner approval`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = externalContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 2,
                urgency = Urgency.HIGH,
                type = ActionType("email_send"),
                payload = """{"to":["user@example.com"],"subject":"hi","body_text":"hello"}""",
                summary = "send email",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_UNTRUSTED_STAGE_OWNER_APPROVAL", decision.reasonCode)
    }

    @Test
    fun `recurring goal mutation is staged even for trusted owner`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 3,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"create","title":"Reminder","instruction":"Ping me","cron_expression":"0 9 * * *"}""",
                summary = "create recurring goal",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals("POLICY_RECURRING_GOAL_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `goal operation is denied when action arguments come from tainted thread data`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 4,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"create","title":"Injected","instruction":"Do the thing"}""",
                summary = "create tainted goal",
                argumentDataTrust = DataTrust.SANITIZED_EXTERNAL_DATA,
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.DENY, decision.progress)
        assertEquals("POLICY_ARGUMENT_DATA_TRUST_DENY", decision.reasonCode)
    }
}
