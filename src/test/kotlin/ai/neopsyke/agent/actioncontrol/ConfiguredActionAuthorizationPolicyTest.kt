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
import ai.neopsyke.agent.model.GroundingMetadata
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
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_UNTRUSTED_STAGE_OWNER_APPROVAL", decision.reasonCode)
    }

    @Test
    fun `recurring assignment mutation is staged even for trusted owner`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 3,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"create","title":"Reminder","instruction":"Ping me","cron_expression":"0 9 * * *"}""",
                summary = "create recurring assignment",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals("POLICY_RECURRING_ASSIGNMENT_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `assignment operation is denied when action arguments come from tainted thread data`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 4,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"create","title":"Injected","instruction":"Do the thing"}""",
                summary = "create tainted assignment",
                argumentDataTrust = DataTrust.SANITIZED_EXTERNAL_DATA,
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.DENY, decision.progress)
        assertEquals("POLICY_ARGUMENT_DATA_TRUST_DENY", decision.reasonCode)
    }

    @Test
    fun `exact assignment delete is allowed only for owner direct channels`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 5,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete","work_item_id":"assignment-123"}""",
                summary = "delete assignment",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete_all"}""",
                summary = "delete all assignments",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_ASSIGNMENT_DELETE_ALL_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `delete all via typed command payload is staged`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        // New planner emits canonical "command":"delete_all" instead of text heuristics
        val decision = policy.authorize(
            action = PendingAction(
                id = 61,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete_all"}""",
                summary = "delete all assignments",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_ASSIGNMENT_DELETE_ALL_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `stage intention forces staged progression even when direct commit is otherwise allowed`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 300,
                urgency = Urgency.MEDIUM,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete","work_item_id":"assignment-123"}""",
                summary = "stage assignment delete",
                conversationContext = context,
                intentionKind = IntentionKind.STAGE,
                requestedCommitMode = CommitMode.POLICY_AUTONOMOUS,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete","work_item_id":"assignment-123"}""",
                summary = "request approval for assignment delete",
                conversationContext = context,
                intentionKind = IntentionKind.REQUEST_AUTHORIZATION,
                requestedCommitMode = CommitMode.APPROVAL_BACKED,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete","work_item_id":"assignment-123"}""",
                summary = "commit assignment delete without stage",
                conversationContext = context,
                intentionKind = IntentionKind.COMMIT,
                requestedCommitMode = CommitMode.APPROVAL_BACKED,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.DENY, decision.progress)
        assertEquals("POLICY_COMMIT_INTENTION_REQUIRES_AUTHORIZATION", decision.reasonCode)
    }

    @Test
    fun `ambiguous assignment delete is staged even for trusted owner direct channel`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 7,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete"}""",
                summary = "delete some assignment",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_ASSIGNMENT_DELETE_AMBIGUOUS_STAGE_REQUIRED", decision.reasonCode)
    }

    @Test
    fun `exact assignment delete from external participant is staged`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = externalContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 8,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete","work_item_id":"assignment-123"}""",
                summary = "delete assignment from external",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_UNTRUSTED_STAGE_OWNER_APPROVAL", decision.reasonCode)
    }

    @Test
    fun `exact assignment delete from owner group channel is staged`() {
        val policy = ConfiguredActionAuthorizationPolicy()
        val context = ownerGroupContext()
        val decision = policy.authorize(
            action = PendingAction(
                id = 9,
                urgency = Urgency.HIGH,
                type = ActionType.ASSIGNMENT_OPERATION,
                payload = """{"command":"delete","work_item_id":"assignment-123"}""",
                summary = "delete assignment from owner group channel",
                conversationContext = context,
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            conversationContext = context,
            actionRegistry = registry(),
        )

        assertEquals(AuthorizationProgress.ALLOW_STAGE, decision.progress)
        assertEquals(CommitMode.APPROVAL_BACKED, decision.commitMode)
        assertEquals("POLICY_ASSIGNMENT_DELETE_OWNER_DIRECT_REQUIRED", decision.reasonCode)
    }
}
