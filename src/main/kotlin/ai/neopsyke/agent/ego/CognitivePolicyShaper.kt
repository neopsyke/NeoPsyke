package ai.neopsyke.agent.ego

import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionPlanningDefinition
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.PolicyScope
import ai.neopsyke.agent.model.PrincipalRole

/**
 * Shapes the action surface and commit-mode options available to Ego based on
 * the thread's security context.
 *
 * ## Policy scopes
 *
 * | Scope | Purpose |
 * |---|---|
 * | `"default"` | Normal local operation. Standard channel/principal/action rules apply. |
 * | `"deployment-restricted"` | Future use: restricts all commit modes to approval-backed only. Intended for non-local deployments (staging, hosted) where autonomous execution must be gated. Currently a single-value toggle; will be replaced with a richer deployment-level enum when deployment targets are introduced. |
 * | `"full-autonomy"` | Widens the autonomous action surface. See [isFullAutonomy] for details and warnings. |
 */
internal object CognitivePolicyShaper {
    /**
     * Tracks whether the full-autonomy warning has already been emitted in this
     * JVM session to avoid spamming stdout on every opportunity.
     */
    @Volatile private var fullAutonomyWarningEmitted: Boolean = false

    fun opportunityCommitModes(
        securityContext: CognitiveThreadSecurityContext,
    ): Set<CommitMode> {
        val nonObserveModes = when {
            isFullAutonomy(securityContext) -> setOf(
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
        return PlannerActionSurface(
            availableActions = availableActions,
            actionDefinitions = visibleDefinitions,
        )
    }

    fun shapeOpportunityContract(
        opportunity: Opportunity,
        plannerActionSurface: PlannerActionSurface,
        implementedAvailableActions: Set<ActionType>,
    ): Opportunity {
        val availableActions = plannerActionSurface.availableActions intersect implementedAvailableActions
        val actionDefinitions = plannerActionSurface.actionDefinitions
            .filter { definition -> definition.actionType in availableActions }
        val allowedCommitModes = shapeOpportunityCommitModes(
            baseCommitModes = opportunity.allowedCommitModes,
            availableDefinitions = actionDefinitions,
        )
        val allowedIntentions = shapeOpportunityIntentions(
            baseIntentions = opportunity.allowedIntentions,
            availableDefinitions = actionDefinitions,
            allowedCommitModes = allowedCommitModes,
        )
        val metadata = opportunity.metadata + opportunityPolicyMetadata(
            opportunity = opportunity,
            availableActions = availableActions,
            availableDefinitions = actionDefinitions,
            allowedIntentions = allowedIntentions,
            allowedCommitModes = allowedCommitModes,
        )
        return opportunity.copy(
            allowedIntentions = allowedIntentions,
            allowedCommitModes = allowedCommitModes,
            availableActions = availableActions,
            actionDefinitions = actionDefinitions,
            metadata = metadata,
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
                isFullAutonomy(securityContext) ||
                    securityContext.channelSurface == ChannelSurface.ADMIN
            ActionEffectClass.COMMIT_PUBLIC ->
                securityContext.channelSurface == ChannelSurface.DIRECT && !isRestrictedByPolicyScope(securityContext)
            ActionEffectClass.COMMIT_PRIVATE,
            ActionEffectClass.COMMIT_STATEFUL,
            -> securityContext.channelSurface == ChannelSurface.DIRECT || isFullAutonomy(securityContext)
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
                isFullAutonomy(securityContext) &&
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
                isFullAutonomy(securityContext)
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

    /**
     * Full-autonomy mode widens the autonomous action surface beyond what the
     * channel/principal combination would normally allow.
     *
     * **WARNING — USE AT YOUR OWN RISK:**
     * - The agent may execute side-effecting actions without human approval.
     * - Depending on available actions and tools, this could execute destructive
     *   tasks, spend significant LLM tokens, or disclose sensitive information.
     * - Recommended to run in a sandboxed or containerised environment when
     *   this scope is active.
     *
     * A stdout notice is emitted the first time this scope is seen in a
     * session so the operator is aware.
     */
    private fun isFullAutonomy(securityContext: CognitiveThreadSecurityContext): Boolean {
        val active = securityContext.policyScope == PolicyScope.FULL_AUTONOMY
        if (active && !fullAutonomyWarningEmitted) {
            fullAutonomyWarningEmitted = true
            println(
                """
                |
                |  [!] Policy scope: full-autonomy
                |
                |  The agent may execute side-effecting and control-plane actions
                |  without human approval. Channel, principal, and per-action
                |  policies still apply.
                |
                |  Be aware: token spend, destructive tasks, and information
                |  disclosure depend on available actions and tools.
                |  Running sandboxed or containerised is recommended.
                |
                |  Change policy_scope_id in agent-runtime.yaml to disable.
                |
                """.trimMargin(),
            )
        }
        return active
    }

    /**
     * Deployment-restricted scope forces all commit modes to approval-backed
     * only. Intended for future non-local deployments (staging, hosted) where
     * autonomous execution must be gated by an operator.
     *
     * Currently a single-value toggle. Will be replaced with a richer
     * deployment-level enum when deployment targets are introduced.
     */
    private fun isRestrictedByPolicyScope(securityContext: CognitiveThreadSecurityContext): Boolean =
        securityContext.policyScope == PolicyScope.DEPLOYMENT_RESTRICTED

    private fun shapeOpportunityIntentions(
        baseIntentions: Set<IntentionKind>,
        availableDefinitions: List<ActionPlanningDefinition>,
        allowedCommitModes: Set<CommitMode>,
    ): Set<IntentionKind> {
        if (availableDefinitions.isEmpty()) {
            return emptySet()
        }
        val hasObserveOnly = availableDefinitions.all { it.effectClass == ActionEffectClass.OBSERVE }
        val hasSideEffecting = availableDefinitions.any { it.effectClass != ActionEffectClass.OBSERVE }
        val hasDirectCommit = availableDefinitions.any { it.directCommitAllowed }
        val hasAutonomousCommit = availableDefinitions.any { it.supportsAutonomousCommit }
        val approvalBackedAllowed = CommitMode.APPROVAL_BACKED in allowedCommitModes
        return buildSet {
            if (IntentionKind.OBSERVE in baseIntentions) add(IntentionKind.OBSERVE)
            if (IntentionKind.PREPARE in baseIntentions && !hasObserveOnly) add(IntentionKind.PREPARE)
            if (IntentionKind.STAGE in baseIntentions && hasSideEffecting && (approvalBackedAllowed || hasAutonomousCommit)) {
                add(IntentionKind.STAGE)
            }
            if (IntentionKind.REQUEST_AUTHORIZATION in baseIntentions && hasSideEffecting && approvalBackedAllowed) {
                add(IntentionKind.REQUEST_AUTHORIZATION)
            }
            if (IntentionKind.COMMIT in baseIntentions && (hasDirectCommit || hasAutonomousCommit || hasObserveOnly)) {
                add(IntentionKind.COMMIT)
            }
        }
    }

    private fun shapeOpportunityCommitModes(
        baseCommitModes: Set<CommitMode>,
        availableDefinitions: List<ActionPlanningDefinition>,
    ): Set<CommitMode> {
        if (availableDefinitions.isEmpty()) {
            return baseCommitModes.intersect(setOf(CommitMode.NOT_APPLICABLE))
                .ifEmpty { setOf(CommitMode.NOT_APPLICABLE) }
        }
        val hasSideEffecting = availableDefinitions.any { it.effectClass != ActionEffectClass.OBSERVE }
        val hasAutonomousCommit = availableDefinitions.any { it.supportsAutonomousCommit }
        val hasDirectCommit = availableDefinitions.any { it.directCommitAllowed }
        return buildSet {
            if (CommitMode.NOT_APPLICABLE in baseCommitModes) add(CommitMode.NOT_APPLICABLE)
            if (CommitMode.APPROVAL_BACKED in baseCommitModes && hasSideEffecting) add(CommitMode.APPROVAL_BACKED)
            if (CommitMode.POLICY_AUTONOMOUS in baseCommitModes && hasAutonomousCommit) {
                add(CommitMode.POLICY_AUTONOMOUS)
            }
            if (CommitMode.ADMIN_OVERRIDE in baseCommitModes && hasDirectCommit) add(CommitMode.ADMIN_OVERRIDE)
        }.ifEmpty { baseCommitModes.intersect(setOf(CommitMode.NOT_APPLICABLE)).ifEmpty { setOf(CommitMode.NOT_APPLICABLE) } }
    }

    private fun opportunityPolicyMetadata(
        opportunity: Opportunity,
        availableActions: Set<ActionType>,
        availableDefinitions: List<ActionPlanningDefinition>,
        allowedIntentions: Set<IntentionKind>,
        allowedCommitModes: Set<CommitMode>,
    ): Map<String, String> {
        val hasSideEffecting = availableDefinitions.any { it.effectClass != ActionEffectClass.OBSERVE }
        val surfaceKind = when {
            availableDefinitions.isEmpty() -> "no_available_actions"
            availableDefinitions.all { it.effectClass == ActionEffectClass.OBSERVE } -> "observe_only"
            hasSideEffecting -> "side_effecting"
            else -> "mixed"
        }
        return mapOf(
            META_POLICY_SCOPE_ID to opportunity.securityContext.policyScope.id,
            META_SURFACE_KIND to surfaceKind,
            META_AVAILABLE_ACTION_COUNT to availableActions.size.toString(),
            META_DIRECT_COMMIT_AVAILABLE to availableDefinitions.any { it.directCommitAllowed }.toString(),
            META_AUTONOMOUS_COMMIT_AVAILABLE to availableDefinitions.any { it.supportsAutonomousCommit }.toString(),
            META_APPROVAL_BACKED_AVAILABLE to (CommitMode.APPROVAL_BACKED in allowedCommitModes).toString(),
            META_ALLOWED_INTENTIONS to allowedIntentions.joinToString(",") { it.name.lowercase() },
            META_ALLOWED_COMMIT_MODES to allowedCommitModes.joinToString(",") { it.name.lowercase() },
        )
    }

    private const val META_POLICY_SCOPE_ID: String = "policy_scope_id"
    private const val META_SURFACE_KIND: String = "surface_kind"
    private const val META_AVAILABLE_ACTION_COUNT: String = "available_action_count"
    private const val META_DIRECT_COMMIT_AVAILABLE: String = "direct_commit_available"
    private const val META_AUTONOMOUS_COMMIT_AVAILABLE: String = "autonomous_commit_available"
    private const val META_APPROVAL_BACKED_AVAILABLE: String = "approval_backed_available"
    private const val META_ALLOWED_INTENTIONS: String = "allowed_intentions"
    private const val META_ALLOWED_COMMIT_MODES: String = "allowed_commit_modes"
}

internal data class PlannerActionSurface(
    val availableActions: Set<ActionType>,
    val actionDefinitions: List<ActionPlanningDefinition>,
)
