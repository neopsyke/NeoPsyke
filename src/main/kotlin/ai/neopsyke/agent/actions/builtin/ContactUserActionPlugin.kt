package ai.neopsyke.agent.actions.builtin

import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext

class ContactUserActionPlugin(
    private val output: (String) -> Unit,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.CONTACT_USER,
        dispatchable = true,
        plannerDescription = "contact_user: payload is text to deliver to the interlocutor (responses, proactive messages, or any direct communication).",
        payloadGuidance = "Plain text; concise unless detail is warranted.",
        payloadSchemaExample = """Thanks for the context. Here's the result...""",
        requiresFollowUpThought = false,
        followUpPrefix = "Message delivered.",
        superegoDirectives = listOf(
            "Allow CONTACT_USER by default when it does not violate the general directives."
        ),
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

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        output("ego> ${action.payload}")
        return ActionOutcome(
            statusSummary = "Message delivered to interlocutor.",
            assistantOutput = action.payload,
            executionStatus = ActionExecutionStatus.SUCCESS,
            effects = setOf(ActionEffect.TASK_PROGRESS, ActionEffect.USER_MESSAGE_DELIVERED),
        )
    }
}

class ContactUserActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ContactUserActionPlugin(output = context.output)
}
