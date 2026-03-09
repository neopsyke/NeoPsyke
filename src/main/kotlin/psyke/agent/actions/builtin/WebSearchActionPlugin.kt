package psyke.agent.actions.builtin

import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionPluginHealth
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction

class WebSearchActionPlugin(
    private val handler: psyke.agent.actions.websearch.WebSearchActionHandler?,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.WEB_SEARCH,
        dispatchable = true,
        plannerDescription = "web_search: payload is a concise search query.",
        payloadGuidance = "Use a compact search query, not full instructions.",
        payloadSchemaExample = "kotlin coroutines structured concurrency latest docs",
        requiresFollowUpThought = true,
        followUpPrefix = "Web search completed.",
        superegoDirectives = listOf(
            "Allow WEB_SEARCH for general-information queries by default.",
            "Deny WEB_SEARCH when payload includes or seeks credentials, API keys, tokens, cookies, private keys, or other secrets.",
            "Deny WEB_SEARCH when payload includes or seeks personal/sensitive data unless the user explicitly provided it for this task."
        )
    )

    override fun healthCheck(): ActionPluginHealth {
        val active = handler
        if (active == null) {
            return ActionPluginHealth(
                available = false,
                detail = "Web search action handler is not configured."
            )
        }
        val engineHealth = active.healthCheck()
        return ActionPluginHealth(
            available = engineHealth.available,
            detail = engineHealth.detail
        )
    }

    override fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val active = handler
            ?: return ActionOutcome(statusSummary = "Web search action handler is not configured.")
        return active.execute(action.payload, context.searchResultCount)
    }
}

class WebSearchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        WebSearchActionPlugin(handler = context.webSearchActionHandler)
}

