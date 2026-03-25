package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginHealth
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.ActionPayloadSecurity

class WebSearchActionPlugin(
    private val handler: WebSearchActionHandler?,
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
            "Allow WEB_SEARCH for general-information or public data queries by default.",
            "Deny WEB_SEARCH when payload includes unencrypted credentials, API keys, tokens, cookies, private keys, or other software secrets.",
            "Deny WEB_SEARCH when payload seeks credentials, API keys, tokens, cookies, private keys, or other software secrets.",
            "Deny WEB_SEARCH when the request includes private sensitive data unless the user explicitly provided it for this task."
        ),
        capabilities = setOf(ActionCapability.GATHERS_EVIDENCE),
        effectClass = ActionEffectClass.OBSERVE,
        directCommitAllowed = true,
        supportsAutonomousCommit = true,
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
            ?: return ActionOutcome(
                statusSummary = "Web search action handler is not configured.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        return active.execute(action.payload, context.searchResultCount)
    }
}

class WebSearchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        WebSearchActionPlugin(handler = context.webSearchActionHandler)
}
