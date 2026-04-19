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
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.PolicyScope
import ai.neopsyke.agent.model.PrincipalRef
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.ChannelRef
import ai.neopsyke.agent.model.TransportClass
import java.time.Instant
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

        assertFalse(shaped.availableActions.contains(ActionType.ASSIGNMENT_OPERATION))
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
            policyScope = PolicyScope.DEPLOYMENT_RESTRICTED,
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
    fun `full autonomy exposes trusted control plane actions`() {
        val context = conversationContext(
            role = PrincipalRole.ADMIN_CONTROL,
            surface = ChannelSurface.ADMIN,
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScope = PolicyScope.FULL_AUTONOMY,
        )
        val security = CognitiveThreadSecurityContext.fromConversation(context.security)

        val shaped = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = context,
            threadSecurityContext = security,
            descriptors = descriptors(),
            disabledActions = emptySet(),
        )

        val assignmentOperation = shaped.actionDefinitions.first { it.actionType == ActionType.ASSIGNMENT_OPERATION }
        assertTrue(shaped.availableActions.contains(ActionType.ASSIGNMENT_OPERATION))
        assertTrue(assignmentOperation.directCommitAllowed)
        assertTrue(assignmentOperation.supportsAutonomousCommit)
        assertEquals(
            setOf(CommitMode.NOT_APPLICABLE, CommitMode.POLICY_AUTONOMOUS, CommitMode.APPROVAL_BACKED),
            CognitivePolicyShaper.opportunityCommitModes(security),
        )
    }

    @Test
    fun `observe only opportunity contracts strip prepare and stage commit modes`() {
        val context = conversationContext(
            role = PrincipalRole.EXTERNAL_PARTICIPANT,
            surface = ChannelSurface.GROUP,
            instructionTrust = InstructionTrust.UNTRUSTED_INSTRUCTION,
        )
        val security = CognitiveThreadSecurityContext.fromConversation(context.security)

        val plannerSurface = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = context,
            threadSecurityContext = security,
            descriptors = listOf(descriptors().first()),
            disabledActions = emptySet(),
        )
        val shaped = CognitivePolicyShaper.shapeOpportunityContract(
            opportunity = opportunity(
                context = context,
                security = security,
                allowedIntentions = setOf(IntentionKind.OBSERVE, IntentionKind.PREPARE),
                allowedCommitModes = setOf(CommitMode.NOT_APPLICABLE, CommitMode.APPROVAL_BACKED),
            ),
            plannerActionSurface = plannerSurface,
            implementedAvailableActions = setOf(ActionType.WEB_SEARCH),
        )

        assertEquals(setOf(ActionType.WEB_SEARCH), shaped.availableActions)
        assertEquals(setOf(IntentionKind.OBSERVE), shaped.allowedIntentions)
        assertEquals(setOf(CommitMode.NOT_APPLICABLE), shaped.allowedCommitModes)
    }

    @Test
    fun `assignment opportunity contracts keep commit progression when policy allows control plane work`() {
        val context = conversationContext(
            role = PrincipalRole.ADMIN_CONTROL,
            surface = ChannelSurface.ADMIN,
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScope = PolicyScope.FULL_AUTONOMY,
        )
        val security = CognitiveThreadSecurityContext.fromConversation(context.security)

        val plannerSurface = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = context,
            threadSecurityContext = security,
            descriptors = descriptors(),
            disabledActions = emptySet(),
        )
        val shaped = CognitivePolicyShaper.shapeOpportunityContract(
            opportunity = opportunity(
                context = context,
                security = security,
                kind = OpportunityKind.RESUME,
                allowedIntentions = setOf(
                    IntentionKind.PREPARE,
                    IntentionKind.STAGE,
                    IntentionKind.COMMIT,
                ),
                allowedCommitModes = setOf(
                    CommitMode.APPROVAL_BACKED,
                    CommitMode.POLICY_AUTONOMOUS,
                ),
            ),
            plannerActionSurface = plannerSurface,
            implementedAvailableActions = setOf(ActionType.ASSIGNMENT_OPERATION),
        )

        assertEquals(setOf(ActionType.ASSIGNMENT_OPERATION), shaped.availableActions)
        assertTrue(IntentionKind.PREPARE in shaped.allowedIntentions)
        assertTrue(IntentionKind.STAGE in shaped.allowedIntentions)
        assertTrue(IntentionKind.COMMIT in shaped.allowedIntentions)
        assertEquals(
            setOf(CommitMode.APPROVAL_BACKED, CommitMode.POLICY_AUTONOMOUS),
            shaped.allowedCommitModes,
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
                actionType = ActionType.ASSIGNMENT_OPERATION,
                plannerDescription = "mutate assignments",
                payloadGuidance = "assignment operation payload",
                effectClass = ActionEffectClass.CONTROL_PLANE,
                directCommitAllowed = true,
                supportsAutonomousCommit = true,
                allowedInstructionTrust = setOf(InstructionTrust.TRUSTED_INSTRUCTION),
            ),
        )

    private fun opportunity(
        context: ConversationContext,
        security: CognitiveThreadSecurityContext,
        kind: OpportunityKind = OpportunityKind.RESPOND,
        allowedIntentions: Set<IntentionKind>,
        allowedCommitModes: Set<CommitMode>,
    ): Opportunity =
        Opportunity(
            id = RootInputIds.next(),
            cognitiveThreadId = RootInputIds.next(),
            kind = kind,
            summary = "test opportunity",
            salience = 1.0,
            createdAt = Instant.now(),
            conversationContext = context,
            securityContext = security,
            allowedIntentions = allowedIntentions,
            allowedCommitModes = allowedCommitModes,
        )

    private fun conversationContext(
        role: PrincipalRole,
        surface: ChannelSurface,
        instructionTrust: InstructionTrust,
        policyScope: PolicyScope = PolicyScope.DEFAULT,
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
                policyScope = policyScope,
            ),
        )
}
