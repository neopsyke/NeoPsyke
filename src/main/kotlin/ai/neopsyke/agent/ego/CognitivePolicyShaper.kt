package ai.neopsyke.agent.ego

import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionPlanningDefinition
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.PrincipalRole

internal object CognitivePolicyShaper {
    fun opportunityCommitModes(
        securityContext: CognitiveThreadSecurityContext,
    ): Set<CommitMode> {
        val nonObserveModes = when {
            isEmergencyOverride(securityContext) -> setOf(
                CommitMode.POLICY_AUTONOMOUS,
                CommitMode.APPROVAL_BACKED,
            )
            isRestrictedByPolicyScope(securityContext) -> setOf(CommitMode.APPROVAL_BACKED)
            isExternal(securityContext.principalRole) -> setOf(CommitMode.APPROVAL_BACKED)
            securityContext.channelSurface in setOf(ChannelSurface.GROUP, ChannelSurface.SHARED_WORKSPACE) ->
                setOf(CommitMode.APPROVAL_BACKED)
            else -> setOf(
                CommitMode.POLICY_AUTONOMOUS,
                CommitMode.APPROVAL_BACKED,
            )
        }
        return setOf(CommitMode.NOT_APPLICABLE) + nonObserveModes
    }

    fun shapePlannerActions(
        conversationContext: ConversationContext,
        threadSecurityContext: CognitiveThreadSecurityContext,
        descriptors: List<ActionDescriptor>,
        disabledActions: Set<ActionType>,
    ): PlannerActionSurface {
        val visibleDefinitions = descriptors
            .filterNot { it.actionType in disabledActions }
            .mapNotNull { descriptor -> shapeDescriptor(conversationContext, threadSecurityContext, descriptor) }

        val availableActions = visibleDefinitions.map { it.actionType }.toSet()
        val dispatchableActions = visibleDefinitions
            .filter { definition ->
                descriptors.firstOrNull { it.actionType == definition.actionType }?.dispatchable == true
            }
            .map { it.actionType }
            .toSet()
        return PlannerActionSurface(
            availableActions = availableActions,
            dispatchableActions = dispatchableActions,
            actionDefinitions = visibleDefinitions,
        )
    }

    private fun shapeDescriptor(
        conversationContext: ConversationContext,
        threadSecurityContext: CognitiveThreadSecurityContext,
        descriptor: ActionDescriptor,
    ): ActionPlanningDefinition? {
        if (shouldHideFromPlanner(conversationContext, threadSecurityContext, descriptor)) {
            return null
        }
        val directCommitAllowed = allowsDirectCommit(threadSecurityContext, descriptor)
        val supportsAutonomousCommit = supportsAutonomousCommit(threadSecurityContext, descriptor)
        return ActionPlanningDefinition(
            actionType = descriptor.actionType,
            description = descriptor.plannerDescription,
            payloadGuidance = descriptor.payloadGuidance,
            payloadSchemaExample = descriptor.payloadSchemaExample,
            effectClass = descriptor.effectClass,
            directCommitAllowed = directCommitAllowed,
            supportsAutonomousCommit = supportsAutonomousCommit,
            allowedInstructionTrust = descriptor.allowedInstructionTrust,
        )
    }

    private fun shouldHideFromPlanner(
        conversationContext: ConversationContext,
        threadSecurityContext: CognitiveThreadSecurityContext,
        descriptor: ActionDescriptor,
    ): Boolean {
        val principalRole = threadSecurityContext.principalRole
        val channelSurface = threadSecurityContext.channelSurface
        return when (descriptor.effectClass) {
            ActionEffectClass.CONTROL_PLANE ->
                !isControlPlaneAllowed(conversationContext, threadSecurityContext)
            ActionEffectClass.COMMIT_PUBLIC ->
                principalRole == PrincipalRole.UNAUTHENTICATED_EXTERNAL
            else -> false
        } || (
            isRestrictedByPolicyScope(threadSecurityContext) &&
                descriptor.effectClass == ActionEffectClass.CONTROL_PLANE &&
                channelSurface != ChannelSurface.ADMIN
            )
    }

    private fun allowsDirectCommit(
        securityContext: CognitiveThreadSecurityContext,
        descriptor: ActionDescriptor,
    ): Boolean {
        if (!descriptor.directCommitAllowed) return false
        if (descriptor.effectClass == ActionEffectClass.OBSERVE) return true
        if (isRestrictedByPolicyScope(securityContext)) return false
        if (isExternal(securityContext.principalRole)) return false
        return when (descriptor.effectClass) {
            ActionEffectClass.CONTROL_PLANE ->
                isEmergencyOverride(securityContext) ||
                    securityContext.channelSurface == ChannelSurface.ADMIN
            ActionEffectClass.COMMIT_PUBLIC ->
                securityContext.channelSurface == ChannelSurface.DIRECT && !isRestrictedByPolicyScope(securityContext)
            ActionEffectClass.COMMIT_PRIVATE,
            ActionEffectClass.COMMIT_STATEFUL,
            -> securityContext.channelSurface == ChannelSurface.DIRECT || isEmergencyOverride(securityContext)
            ActionEffectClass.OBSERVE -> true
        }
    }

    private fun supportsAutonomousCommit(
        securityContext: CognitiveThreadSecurityContext,
        descriptor: ActionDescriptor,
    ): Boolean {
        if (!descriptor.supportsAutonomousCommit) return false
        if (descriptor.effectClass == ActionEffectClass.OBSERVE) return true
        if (isRestrictedByPolicyScope(securityContext)) return false
        if (isExternal(securityContext.principalRole)) return false
        return when (descriptor.effectClass) {
            ActionEffectClass.CONTROL_PLANE ->
                isEmergencyOverride(securityContext) &&
                    securityContext.principalRole in setOf(
                        PrincipalRole.SYSTEM_INTERNAL,
                        PrincipalRole.ADMIN_CONTROL,
                        PrincipalRole.OWNER,
                    )
            ActionEffectClass.COMMIT_PUBLIC ->
                false
            ActionEffectClass.COMMIT_PRIVATE,
            ActionEffectClass.COMMIT_STATEFUL,
            -> securityContext.channelSurface in setOf(ChannelSurface.DIRECT, ChannelSurface.AUTOMATION) ||
                isEmergencyOverride(securityContext)
            ActionEffectClass.OBSERVE -> true
        }
    }

    private fun isControlPlaneAllowed(
        conversationContext: ConversationContext,
        securityContext: CognitiveThreadSecurityContext,
    ): Boolean =
        conversationContext.security.instructionTrust == ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION &&
            securityContext.principalRole in setOf(
                PrincipalRole.OWNER,
                PrincipalRole.SYSTEM_INTERNAL,
                PrincipalRole.ADMIN_CONTROL,
                PrincipalRole.APPROVED_AUTOMATION,
            ) &&
            securityContext.channelSurface in setOf(
                ChannelSurface.DIRECT,
                ChannelSurface.AUTOMATION,
                ChannelSurface.ADMIN,
            )

    private fun isExternal(principalRole: PrincipalRole): Boolean =
        principalRole == PrincipalRole.EXTERNAL_PARTICIPANT ||
            principalRole == PrincipalRole.UNAUTHENTICATED_EXTERNAL

    private fun isEmergencyOverride(securityContext: CognitiveThreadSecurityContext): Boolean =
        securityContext.policyScopeId == POLICY_SCOPE_EMERGENCY_OVERRIDE

    private fun isRestrictedByPolicyScope(securityContext: CognitiveThreadSecurityContext): Boolean =
        securityContext.policyScopeId == POLICY_SCOPE_DEPLOYMENT_RESTRICTED

    private const val POLICY_SCOPE_DEPLOYMENT_RESTRICTED: String = "deployment-restricted"
    private const val POLICY_SCOPE_EMERGENCY_OVERRIDE: String = "emergency-override"
}

internal data class PlannerActionSurface(
    val availableActions: Set<ActionType>,
    val dispatchableActions: Set<ActionType>,
    val actionDefinitions: List<ActionPlanningDefinition>,
)
