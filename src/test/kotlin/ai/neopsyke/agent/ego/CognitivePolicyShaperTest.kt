package ai.neopsyke.agent.ego

import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContext
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PrincipalRef
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.ChannelRef
import ai.neopsyke.agent.model.TransportClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CognitivePolicyShaperTest {
    @Test
    fun `external participant hides control plane actions and limits commit modes`() {
        val context = conversationContext(
            role = PrincipalRole.EXTERNAL_PARTICIPANT,
            surface = ChannelSurface.GROUP,
            instructionTrust = InstructionTrust.UNTRUSTED_INSTRUCTION,
        )
        val security = CognitiveThreadSecurityContext.fromConversation(context.security)

        val shaped = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = context,
            threadSecurityContext = security,
            descriptors = descriptors(),
            disabledActions = emptySet(),
        )

        assertFalse(shaped.availableActions.contains(ActionType.GOAL_OPERATION))
        assertTrue(shaped.availableActions.contains(ActionType.WEB_SEARCH))
        assertEquals(
            setOf(CommitMode.NOT_APPLICABLE, CommitMode.APPROVAL_BACKED),
            CognitivePolicyShaper.opportunityCommitModes(security),
        )
    }

    @Test
    fun `deployment restricted scope disables autonomous private commit at planner surface`() {
        val context = conversationContext(
            role = PrincipalRole.OWNER,
            surface = ChannelSurface.DIRECT,
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScopeId = "deployment-restricted",
        )
        val security = CognitiveThreadSecurityContext.fromConversation(context.security)

        val shaped = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = context,
            threadSecurityContext = security,
            descriptors = descriptors(),
            disabledActions = emptySet(),
        )

        val contactUser = shaped.actionDefinitions.first { it.actionType == ActionType.CONTACT_USER }
        assertFalse(contactUser.directCommitAllowed)
        assertFalse(contactUser.supportsAutonomousCommit)
        assertEquals(
            setOf(CommitMode.NOT_APPLICABLE, CommitMode.APPROVAL_BACKED),
            CognitivePolicyShaper.opportunityCommitModes(security),
        )
    }

    @Test
    fun `emergency override exposes trusted control plane actions`() {
        val context = conversationContext(
            role = PrincipalRole.ADMIN_CONTROL,
            surface = ChannelSurface.ADMIN,
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScopeId = "emergency-override",
        )
        val security = CognitiveThreadSecurityContext.fromConversation(context.security)

        val shaped = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = context,
            threadSecurityContext = security,
            descriptors = descriptors(),
            disabledActions = emptySet(),
        )

        val goalOperation = shaped.actionDefinitions.first { it.actionType == ActionType.GOAL_OPERATION }
        assertTrue(shaped.availableActions.contains(ActionType.GOAL_OPERATION))
        assertTrue(goalOperation.directCommitAllowed)
        assertTrue(goalOperation.supportsAutonomousCommit)
        assertEquals(
            setOf(CommitMode.NOT_APPLICABLE, CommitMode.POLICY_AUTONOMOUS, CommitMode.APPROVAL_BACKED),
            CognitivePolicyShaper.opportunityCommitModes(security),
        )
    }

    private fun descriptors(): List<ActionDescriptor> =
        listOf(
            ActionDescriptor(
                actionType = ActionType.WEB_SEARCH,
                plannerDescription = "search the web",
                payloadGuidance = "query",
                effectClass = ActionEffectClass.OBSERVE,
                directCommitAllowed = true,
                supportsAutonomousCommit = true,
            ),
            ActionDescriptor(
                actionType = ActionType.CONTACT_USER,
                plannerDescription = "respond to the user",
                payloadGuidance = "message",
                effectClass = ActionEffectClass.COMMIT_PRIVATE,
                directCommitAllowed = true,
                supportsAutonomousCommit = true,
            ),
            ActionDescriptor(
                actionType = ActionType.GOAL_OPERATION,
                plannerDescription = "mutate goals",
                payloadGuidance = "goal operation payload",
                effectClass = ActionEffectClass.CONTROL_PLANE,
                directCommitAllowed = true,
                supportsAutonomousCommit = true,
                allowedInstructionTrust = setOf(InstructionTrust.TRUSTED_INSTRUCTION),
            ),
        )

    private fun conversationContext(
        role: PrincipalRole,
        surface: ChannelSurface,
        instructionTrust: InstructionTrust,
        policyScopeId: String = ConversationSecurityContext.DEFAULT_POLICY_SCOPE_ID,
    ): ConversationContext =
        ConversationContext(
            sessionId = "policy-test",
            interlocutor = Interlocutor.named("policy-test"),
            security = ConversationSecurityContext(
                principal = PrincipalRef(id = role.name.lowercase(), role = role),
                channel = ChannelRef(
                    provider = "test",
                    surface = surface,
                    transport = TransportClass.CHAT,
                    channelId = "policy-test",
                ),
                instructionTrust = instructionTrust,
                policyScopeId = policyScopeId,
            ),
        )
}
