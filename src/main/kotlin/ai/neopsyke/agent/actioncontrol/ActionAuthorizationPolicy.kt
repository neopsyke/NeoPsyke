package ai.neopsyke.agent.actioncontrol

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PrincipalRole
import java.nio.file.Files
import java.nio.file.Path

interface ActionAuthorizationPolicy {
    fun policyVersion(): String

    fun authorize(
        action: PendingAction,
        conversationContext: ConversationContext,
        actionRegistry: ActionRegistry = ActionRegistry.empty(),
    ): AuthorizationDecision
}

data class ActionSecurityPolicyConfig(
    val policyVersion: String = DEFAULT_POLICY_VERSION,
    val actions: Map<String, ActionSecurityActionRule> = emptyMap(),
    val publicCommit: ActionSecurityPublicCommitConfig = ActionSecurityPublicCommitConfig(),
) {
    companion object {
        const val DEFAULT_POLICY_VERSION: String = "builtin-defaults-v1"

        fun default(): ActionSecurityPolicyConfig =
            ActionSecurityPolicyConfig(
                actions = mapOf(
                    ActionType.CONTACT_USER.id to ActionSecurityActionRule(
                        directCommitEnabled = true,
                        autonomousCommitEnabled = true,
                    ),
                    ActionType.GOAL_OPERATION.id to ActionSecurityActionRule(
                        directCommitEnabled = true,
                        autonomousCommitEnabled = true,
                        recurringRequiresApproval = true,
                    ),
                    "email_send" to ActionSecurityActionRule(
                        directCommitEnabled = false,
                        autonomousCommitEnabled = false,
                    ),
                    ActionType.REFLECT_INTERNAL.id to ActionSecurityActionRule(
                        directCommitEnabled = true,
                        autonomousCommitEnabled = true,
                    ),
                    ActionType.REFLECT_EVIDENCE.id to ActionSecurityActionRule(
                        directCommitEnabled = true,
                        autonomousCommitEnabled = true,
                    ),
                )
            )
    }
}

data class ActionSecurityActionRule(
    val directCommitEnabled: Boolean? = null,
    val autonomousCommitEnabled: Boolean? = null,
    val recurringRequiresApproval: Boolean? = null,
)

data class ActionSecurityPublicCommitConfig(
    val enabledTargets: List<ActionSecurityTargetMatcher> = emptyList(),
)

data class ActionSecurityTargetMatcher(
    val provider: String? = null,
    val accountId: String? = null,
    val channelId: String? = null,
)

object ActionSecurityPolicyLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(path: Path): ActionSecurityPolicyConfig {
        if (!Files.exists(path)) {
            return ActionSecurityPolicyConfig.default()
        }
        Files.newBufferedReader(path).use { reader ->
            return mapper.readValue<ActionSecurityPolicyConfig>(reader)
        }
    }
}

class ConfiguredActionAuthorizationPolicy(
    private val config: ActionSecurityPolicyConfig = ActionSecurityPolicyConfig.default(),
) : ActionAuthorizationPolicy {
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun policyVersion(): String = config.policyVersion

    override fun authorize(
        action: PendingAction,
        conversationContext: ConversationContext,
        actionRegistry: ActionRegistry,
    ): AuthorizationDecision {
        val descriptor = actionRegistry.descriptor(action.type)
            ?: return stage(
                commitMode = CommitMode.APPROVAL_BACKED,
                reason = "No explicit action contract was found; staging for approval.",
                reasonCode = "LEGACY_ACTION_CONTRACT_MISSING",
            )
        val contract = descriptor.contract
        val rule = config.actions[action.type.id]
        val principalRole = conversationContext.security.principal.role
        val instructionTrust = conversationContext.security.instructionTrust

        if (!contract.allowedInstructionTrust.contains(instructionTrust)) {
            if (principalRole == PrincipalRole.EXTERNAL_PARTICIPANT ||
                principalRole == PrincipalRole.UNAUTHENTICATED_EXTERNAL
            ) {
                return stage(
                    commitMode = CommitMode.APPROVAL_BACKED,
                    reason = "Untrusted instruction for '${action.type.id}' may be staged for owner approval.",
                    reasonCode = "POLICY_UNTRUSTED_STAGE_OWNER_APPROVAL",
                )
            }
            return deny(
                reason = "Action '${action.type.id}' is not allowed for instruction trust ${instructionTrust.name.lowercase()}.",
                reasonCode = "POLICY_INSTRUCTION_TRUST_DENY",
            )
        }

        if (!contract.allowedArgumentDataTrust.contains(action.argumentDataTrust)) {
            return deny(
                reason = "Action '${action.type.id}' is not allowed for argument data trust ${action.argumentDataTrust.name.lowercase()}.",
                reasonCode = "POLICY_ARGUMENT_DATA_TRUST_DENY",
            )
        }

        classifyGoalDeleteIntent(action, actionRegistry)?.let { deleteIntent ->
            when (deleteIntent) {
                GoalDeleteIntent.DELETE_ALL -> {
                    return stage(
                        commitMode = CommitMode.APPROVAL_BACKED,
                        reason = "Deleting all goals always requires explicit owner reapproval.",
                        reasonCode = "POLICY_GOAL_DELETE_ALL_STAGE_REQUIRED",
                    )
                }

                GoalDeleteIntent.DELETE_AMBIGUOUS -> {
                    return stage(
                        commitMode = CommitMode.APPROVAL_BACKED,
                        reason = "Ambiguous goal deletion requests require staged owner approval.",
                        reasonCode = "POLICY_GOAL_DELETE_AMBIGUOUS_STAGE_REQUIRED",
                    )
                }

                GoalDeleteIntent.DELETE_EXACT -> {
                    if (!isOwnerVerifiedDirectChannel(conversationContext)) {
                        return stage(
                            commitMode = CommitMode.APPROVAL_BACKED,
                            reason = "Goal deletion may direct commit only from an owner-verified direct channel.",
                            reasonCode = "POLICY_GOAL_DELETE_OWNER_DIRECT_REQUIRED",
                        )
                    }
                }
            }
        }

        if (isRecurringGoalMutation(action) && (rule?.recurringRequiresApproval != false)) {
            return stage(
                commitMode = CommitMode.APPROVAL_BACKED,
                reason = "Recurring goal mutations require staged approval.",
                reasonCode = "POLICY_RECURRING_GOAL_STAGE_REQUIRED",
            )
        }

        if ((principalRole == PrincipalRole.EXTERNAL_PARTICIPANT ||
                principalRole == PrincipalRole.UNAUTHENTICATED_EXTERNAL) &&
            contract.effectClass != ActionEffectClass.OBSERVE
        ) {
            return stage(
                commitMode = CommitMode.APPROVAL_BACKED,
                reason = "External participants may propose '${action.type.id}', but owner approval is required before commit.",
                reasonCode = "POLICY_EXTERNAL_STAGE_OWNER_APPROVAL",
            )
        }

        if (contract.effectClass == ActionEffectClass.COMMIT_PUBLIC &&
            !isPublicCommitAutonomyAllowed(conversationContext)
        ) {
            return stage(
                commitMode = CommitMode.APPROVAL_BACKED,
                reason = "Public commit action '${action.type.id}' requires explicit enablement and owner approval.",
                reasonCode = "POLICY_PUBLIC_COMMIT_OWNER_APPROVAL",
            )
        }

        if (contract.effectClass == ActionEffectClass.CONTROL_PLANE &&
            instructionTrust != InstructionTrust.TRUSTED_INSTRUCTION &&
            principalRole != PrincipalRole.EXTERNAL_PARTICIPANT &&
            principalRole != PrincipalRole.UNAUTHENTICATED_EXTERNAL
        ) {
            return deny(
                reason = "Control-plane action '${action.type.id}' requires trusted instruction.",
                reasonCode = "POLICY_CONTROL_PLANE_TRUST_REQUIRED",
            )
        }

        if (contract.effectClass == ActionEffectClass.OBSERVE) {
            return AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_COMMIT,
                commitMode = CommitMode.POLICY_AUTONOMOUS,
                policyVersion = policyVersion(),
                reason = "Observe-class actions may direct commit without staging.",
                reasonCode = "POLICY_OBSERVE_DIRECT_COMMIT",
            )
        }

        val directCommitAllowed = rule?.directCommitEnabled ?: contract.directCommitAllowed
        val autonomousCommitEnabled = rule?.autonomousCommitEnabled ?: contract.supportsAutonomousCommit

        return if (directCommitAllowed) {
            AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_COMMIT,
                commitMode = CommitMode.POLICY_AUTONOMOUS,
                policyVersion = policyVersion(),
                reason = "Action policy allows direct commit.",
                reasonCode = "POLICY_DIRECT_COMMIT_ALLOWED",
            )
        } else {
            stage(
                commitMode = if (autonomousCommitEnabled) {
                    CommitMode.POLICY_AUTONOMOUS
                } else {
                    CommitMode.APPROVAL_BACKED
                },
                reason = "Action policy requires staged authorization before commit.",
                reasonCode = "POLICY_STAGE_REQUIRED",
            )
        }
    }

    private fun isRecurringGoalMutation(action: PendingAction): Boolean {
        if (action.type != ActionType.GOAL_OPERATION) {
            return false
        }
        val node = goalOperationNode(action, ActionRegistry.empty()) ?: return false
        val operation = node.path("operation").asText("").trim().lowercase()
        val cronExpression = node.path("cron_expression").asText(node.path("cronExpression").asText("")).trim()
        return cronExpression.isNotBlank() && operation in setOf("create", "revise_plan")
    }

    private fun classifyGoalDeleteIntent(
        action: PendingAction,
        actionRegistry: ActionRegistry,
    ): GoalDeleteIntent? {
        if (action.type != ActionType.GOAL_OPERATION) {
            return null
        }
        val node = goalOperationNode(action, actionRegistry) ?: return null
        val operation = node.path("operation").asText("").trim().lowercase()
        val goalId = node.path("goal_id").asText(node.path("goalId").asText("")).trim()
        return when (operation) {
            "delete_all" -> GoalDeleteIntent.DELETE_ALL
            "delete" -> if (goalId.isNotBlank()) GoalDeleteIntent.DELETE_EXACT else GoalDeleteIntent.DELETE_AMBIGUOUS
            else -> null
        }
    }

    private fun goalOperationNode(
        action: PendingAction,
        actionRegistry: ActionRegistry,
    ) = runCatching {
        mapper.readTree(actionRegistry.repairPlannerPayload(ActionType.GOAL_OPERATION, action.payload))
    }.getOrNull()

    private fun isOwnerVerifiedDirectChannel(conversationContext: ConversationContext): Boolean =
        conversationContext.security.principal.role == PrincipalRole.OWNER &&
            conversationContext.security.instructionTrust == InstructionTrust.TRUSTED_INSTRUCTION &&
            conversationContext.security.channel.surface == ChannelSurface.DIRECT

    private fun isPublicCommitAutonomyAllowed(conversationContext: ConversationContext): Boolean {
        if (conversationContext.security.principal.role != PrincipalRole.OWNER) {
            return false
        }
        val channel = conversationContext.security.channel
        return config.publicCommit.enabledTargets.any { matcher ->
            matches(matcher.provider, channel.provider) &&
                matches(matcher.accountId, channel.accountId) &&
                matches(matcher.channelId, channel.channelId)
        }
    }

    private fun matches(expected: String?, actual: String?): Boolean =
        expected.isNullOrBlank() || expected == actual

    private fun deny(reason: String, reasonCode: String): AuthorizationDecision =
        AuthorizationDecision(
            progress = AuthorizationProgress.DENY,
            commitMode = CommitMode.NOT_APPLICABLE,
            policyVersion = policyVersion(),
            reason = reason,
            reasonCode = reasonCode,
        )

    private fun stage(
        commitMode: CommitMode,
        reason: String,
        reasonCode: String,
    ): AuthorizationDecision =
        AuthorizationDecision(
            progress = AuthorizationProgress.ALLOW_STAGE,
            commitMode = commitMode,
            policyVersion = policyVersion(),
            reason = reason,
            reasonCode = reasonCode,
        )

    private enum class GoalDeleteIntent {
        DELETE_EXACT,
        DELETE_AMBIGUOUS,
        DELETE_ALL,
    }
}
