package ai.neopsyke.agent.cortex.motor.actions.control

import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.control.ConfiguredActionAuthorizationPolicy
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.ChannelRef
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PrincipalRef
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.TransportClass
import ai.neopsyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfiguredActionAuthorizationPolicyTest {

    private fun registry(config: AgentConfig = AgentConfig()): ActionRegistry =
        ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = null,
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

    private fun ownerGroupContext(): ConversationContext =
        ConversationContext(
            sessionId = "owner-group-session",
            interlocutor = Interlocutor.named("owner"),
            security = ConversationSecurityContext(
                principal = PrincipalRef(id = "owner", role = PrincipalRole.OWNER, label = "Owner"),
                channel = ChannelRef(
                    provider = "slack",
                    surface = ChannelSurface.GROUP,
                    transport = TransportClass.CHAT,
                    channelId = "team-channel",
                ),
                instructionTrust = ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION,
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

    @Test
    fun `exact goal delete is allowed only for owner direct channels`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 5,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete","goal_id":"goal-123"}""",
                summary = "delete goal",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_COMMIT, decision.progress)
        assertEquals(CommitMode.POLICY_AUTONOMOUS, decision.commitMode)
    }

    @Test
    fun `delete all is always staged even for trusted owner direct channel`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 6,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete_all"}""",
                summary = "delete all goals",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_GOAL_DELETE_ALL_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `delete all alias payload is normalized and staged`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 61,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"revise","instruction":"Delete all existing goals"}""",
                summary = "delete all goals via alias",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_GOAL_DELETE_ALL_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `stage intention forces staged progression even when direct commit is otherwise allowed`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 300,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete","goal_id":"goal-123"}""",
                summary = "stage goal delete",
                conversationContext = context,
                intentionKind = IntentionKind.STAGE,
                requestedCommitMode = CommitMode.POLICY_AUTONOMOUS,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.POLICY_AUTONOMOUS, decision.commitMode)
        assertEquals("POLICY_INTENTION_STAGE", decision.reasonCode)
    }

    @Test
    fun `request authorization intention stages approval backed authorization`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 301,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete","goal_id":"goal-123"}""",
                summary = "request approval for goal delete",
                conversationContext = context,
                intentionKind = IntentionKind.REQUEST_AUTHORIZATION,
                requestedCommitMode = CommitMode.APPROVAL_BACKED,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_INTENTION_REQUEST_AUTHORIZATION", decision.reasonCode)
    }

    @Test
    fun `commit intention rejects approval backed commit without prior authorization`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 302,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete","goal_id":"goal-123"}""",
                summary = "commit goal delete without stage",
                conversationContext = context,
                intentionKind = IntentionKind.COMMIT,
                requestedCommitMode = CommitMode.APPROVAL_BACKED,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.DENY, decision.progress)
        assertEquals("POLICY_COMMIT_INTENTION_REQUIRES_AUTHORIZATION", decision.reasonCode)
    }

    @Test
    fun `ambiguous goal delete is staged even for trusted owner direct channel`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 7,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete"}""",
                summary = "delete some goal",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_GOAL_DELETE_AMBIGUOUS_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `exact goal delete from external participant is staged`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = externalContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 8,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete","goal_id":"goal-123"}""",
                summary = "delete goal from external",
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
    fun `exact goal delete from owner group channel is staged`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerGroupContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 9,
                urgency = Urgency.HIGH,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete","goal_id":"goal-123"}""",
                summary = "delete goal from owner group channel",
                conversationContext = context,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_GOAL_DELETE_OWNER_DIRECT_REQUIRED", decision.reasonCode)
    }
}
