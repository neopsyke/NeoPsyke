package psyke.agent.actions.builtin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionPluginHealth
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction

class WebsiteFetchActionPlugin(
    private val tool: psyke.agent.tools.mcp.FetchTool?,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.WEBSITE_FETCH,
        dispatchable = true,
        plannerDescription = "website_fetch: payload is JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}.",
        payloadGuidance = "JSON object with public https URL. max_chars optional.",
        payloadSchemaExample = """{"url":"https://example.com","max_chars":1200}""",
        requiresFollowUpThought = true,
        followUpPrefix = "Fetch completed.",
        superegoDirectives = listOf(
            "Deny WEBSITE_FETCH when payload includes or seeks credentials, API keys, tokens, cookies, private keys, or other secrets.",
            "Deny WEBSITE_FETCH when payload includes or seeks personal/sensitive data unless the user explicitly provided it for this task.",
            "For WEBSITE_FETCH, allow only public informational HTTPS pages; deny auth/account/payment/admin/metadata endpoints and URLs with obvious secret query params."
        )
    )

    override fun healthCheck(): ActionPluginHealth {
        val active = tool
            ?: return ActionPluginHealth(
                available = false,
                detail = "Fetch tool not configured."
            )
        val status = active.healthCheck()
        return ActionPluginHealth(
            available = status.available,
            detail = status.detail
        )
    }

    override fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val active = tool
            ?: return ActionOutcome(statusSummary = "Fetch tool is not configured.")
        val outcome = active.fetchWithOutcome(action.payload)
        return ActionOutcome(
            statusSummary = outcome.message,
            actionErrorCategory = when (outcome.errorCategory) {
                psyke.agent.tools.mcp.FetchErrorCategory.NON_RETRYABLE -> "non_retryable"
                psyke.agent.tools.mcp.FetchErrorCategory.RETRYABLE -> "retryable"
                else -> null
            },
            fetchErrorCategory = outcome.errorCategory.name.lowercase()
        )
    }

    override fun repairPlannerPayload(raw: String): String {
        if (raw.isBlank()) return raw
        try {
            mapper.readTree(raw)
            return raw
        } catch (_: Exception) {
            // fall through
        }
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return mapper.writeValueAsString(mapOf("url" to trimmed))
        }
        return raw
    }

    private companion object {
        val mapper: ObjectMapper = jacksonObjectMapper()
    }
}

class WebsiteFetchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        WebsiteFetchActionPlugin(tool = context.fetchTool)
}
