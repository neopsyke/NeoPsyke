package psyke.agent.actions.builtin

import psyke.agent.actions.ActionCapability
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionDeterministicReview
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionPluginHealth
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import psyke.agent.support.ActionPayloadSecurity

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
        ),
        capabilities = setOf(ActionCapability.GATHERS_EVIDENCE)
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val payload = action.payload.trim()
        if (payload.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "web_search_payload_blank",
                reason = "WEB_SEARCH payload must not be blank."
            )
        }
        if (ActionPayloadSecurity.containsSecretExfilIntent(payload)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "web_search_secret_exfil",
                reason = "WEB_SEARCH payload appears to request credential or secret exfiltration."
            )
        }
        if (ActionPayloadSecurity.containsSensitivePiiExfilIntent(payload)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "web_search_sensitive_pii_exfil",
                reason = "WEB_SEARCH payload appears to request sensitive personal data exfiltration."
            )
        }
        if (ActionPayloadSecurity.containsInlineSecretMaterial(payload)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "web_search_inline_secret_material",
                reason = "WEB_SEARCH payload contains inline secret-like material."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun healthCheck(): ActionPluginHealth {
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

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val active = handler
            ?: return ActionOutcome(statusSummary = "Web search action handler is not configured.")
        return active.execute(action.payload, context.searchResultCount)
    }
}

class WebSearchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        WebSearchActionPlugin(handler = context.webSearchActionHandler)
}

