package psyke.agent.actions.builtin

import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction

class AnswerActionPlugin(
    private val output: (String) -> Unit,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.ANSWER,
        dispatchable = true,
        plannerDescription = "answer: payload is the exact answer text for the interlocutor.",
        payloadGuidance = "Plain text answer; concise unless user asks for detail.",
        payloadSchemaExample = """Thanks for the context. Here's the result...""",
        requiresFollowUpThought = false,
        followUpPrefix = "Answer delivered.",
        superegoDirectives = listOf(
            "Allow ANSWER by default when it does not violate the general directives."
        )
    )

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

