package psyke.agent.actions.builtin

import psyke.agent.actions.ActionCapability
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionDeterministicReview
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext

class AnswerActionPlugin(
    private val output: (String) -> Unit,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.ANSWER,
        dispatchable = true,
        plannerDescription = "answer: payload is text to deliver to the interlocutor (responses, proactive messages, or any direct communication).",
        payloadGuidance = "Plain text; concise unless detail is warranted.",
        payloadSchemaExample = """Thanks for the context. Here's the result...""",
        requiresFollowUpThought = false,
        followUpPrefix = "Answer delivered.",
        superegoDirectives = listOf(
            "Allow ANSWER by default when it does not violate the general directives."
        ),
        capabilities = setOf(ActionCapability.PRODUCES_USER_OUTPUT)
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        if (action.payload.trim().isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "answer_payload_blank",
                reason = "ANSWER payload must not be blank."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        output("ego> ${action.payload}")
        return ActionOutcome(
            statusSummary = "Answer delivered to interlocutor.",
            assistantOutput = action.payload
        )
    }
}

class AnswerActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        AnswerActionPlugin(output = context.output)
}

