package psyke.agent.actions.builtin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import psyke.agent.actions.ActionCapability
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionDeterministicReview
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionPluginHealth
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
import psyke.agent.support.ActionPayloadSecurity

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
            "Allow WEBSITE_FETCH for public websites by default.",
            "Deny WEBSITE_FETCH when payload includes unencrypted API keys, tokens, cookies, private keys, or other secrets.",
            "Deny WEBSITE_FETCH when payload includes personal/sensitive data unless the user explicitly provided it for this task.",
            "For WEBSITE_FETCH, allow only public HTTPS pages; deny auth/account/payment/admin endpoints and URLs with obvious secret query params."
        ),
        capabilities = setOf(ActionCapability.GATHERS_EVIDENCE)
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val parsed = try {
            strictMapper.readValue<FetchValidationPayload>(action.payload)
        } catch (_: Exception) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_payload_invalid_json",
                reason = "WEBSITE_FETCH payload must be JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}."
            )
        }
        val url = parsed.url?.trim().orEmpty()
        if (url.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_url_missing",
                reason = "WEBSITE_FETCH payload is missing required url."
            )
        }
        if (!ActionPayloadSecurity.isPublicHttpsUrl(url)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_url_blocked",
                reason = "WEBSITE_FETCH URL must be a public HTTPS URL and must not target private/local hosts."
            )
        }
        if (ActionPayloadSecurity.hasSensitiveEndpoint(url)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_sensitive_endpoint",
                reason = "WEBSITE_FETCH URL targets a sensitive endpoint (auth/account/payment/admin)."
            )
        }
        if (ActionPayloadSecurity.hasSensitiveQueryParams(url)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_sensitive_query_params",
                reason = "WEBSITE_FETCH URL contains sensitive query parameters."
            )
        }
        val requestedMaxChars = parsed.maxChars
        if (requestedMaxChars != null && requestedMaxChars !in ActionPayloadSecurity.WEBSITE_FETCH_MIN_MAX_CHARS..config.fetchMaxChars) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_max_chars_out_of_bounds",
                reason = "WEBSITE_FETCH max_chars must be between ${ActionPayloadSecurity.WEBSITE_FETCH_MIN_MAX_CHARS} and ${config.fetchMaxChars}."
            )
        }
        if (ActionPayloadSecurity.containsSecretExfilIntent(action.payload) || ActionPayloadSecurity.containsInlineSecretMaterial(action.payload)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_secret_exfil",
                reason = "WEBSITE_FETCH payload appears to request credential or secret exfiltration."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun healthCheck(): ActionPluginHealth {
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

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
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

    private data class FetchValidationPayload(
        val url: String? = null,
        @field:JsonProperty("max_chars")
        val maxChars: Int? = null,
    )

    private companion object {
        val mapper: ObjectMapper = jacksonObjectMapper()
        val strictMapper: ObjectMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

class WebsiteFetchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        WebsiteFetchActionPlugin(tool = context.fetchTool)
}
