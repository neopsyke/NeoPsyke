package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPromptDescriptors
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.ConversationOutputGateway
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.TextSecurity

class ContactUserActionPlugin(
    private val conversationOutput: ConversationOutputGateway,
) : AgentActionPlugin {
    private val promptDescriptor = ActionPromptDescriptors.load(ActionType.CONTACT_USER.id)
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.CONTACT_USER,
        dispatchable = true,
        plannerDescription = promptDescriptor.plannerDescription,
        payloadGuidance = promptDescriptor.payloadGuidance,
        payloadSchemaExample = promptDescriptor.payloadSchemaExample,
        requiresFollowUpThought = false,
        followUpPrefix = promptDescriptor.followUpPrefix ?: "Message delivered.",
        superegoDirectives = promptDescriptor.superegoDirectives,
        capabilities = setOf(ActionCapability.PRODUCES_USER_OUTPUT),
        effectClass = ActionEffectClass.COMMIT_PRIVATE,
        directCommitAllowed = true,
        supportsAutonomousCommit = true,
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        if (action.payload.trim().isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "contact_user_payload_blank",
                reason = "CONTACT_USER payload must not be blank."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override fun repairPlannerPayload(raw: String): String =
        TextSecurity.clamp(raw.trim(), MAX_MESSAGE_CHARS)

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val delivery = conversationOutput.deliver(
            text = action.payload,
            conversationContext = context.conversationContext,
        )
        if (!delivery.delivered) {
            return ActionOutcome(
                statusSummary = "Message delivery failed: ${delivery.detail}",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "message_delivery_failed",
            )
        }
        return ActionOutcome(
            statusSummary = "Message delivered to interlocutor.",
            executionStatus = ActionExecutionStatus.SUCCESS,
            effects = setOf(ActionEffect.TASK_PROGRESS, ActionEffect.USER_MESSAGE_DELIVERED),
        )
    }
}

class ContactUserActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ContactUserActionPlugin(conversationOutput = context.conversationOutput)
}

private const val MAX_MESSAGE_CHARS: Int = 8_000
