package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginHealth
import ai.neopsyke.agent.cortex.motor.actions.ActionPromptDescriptors
import ai.neopsyke.agent.cortex.motor.actions.ActionSuperegoDirectives
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SourceDescriptor
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.ActionPayloadSecurity
import ai.neopsyke.agent.support.ExternalContentPipeline
import ai.neopsyke.agent.cortex.motor.actions.fetch.FetchErrorCategory
import ai.neopsyke.agent.cortex.motor.actions.fetch.FetchTool

class WebsiteFetchActionPlugin(
    private val tool: FetchTool?,
) : AgentActionPlugin {
    private val promptDescriptor = ActionPromptDescriptors.load(ActionType.WEBSITE_FETCH.id)
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.WEBSITE_FETCH,
        dispatchable = true,
        plannerDescription = promptDescriptor.plannerDescription,
        payloadGuidance = promptDescriptor.payloadGuidance,
        payloadSchemaExample = promptDescriptor.payloadSchemaExample,
        requiresFollowUpThought = true,
        followUpPrefix = promptDescriptor.followUpPrefix ?: "Fetch completed.",
        superegoDirectives = ActionSuperegoDirectives.forAction(ActionType.WEBSITE_FETCH),
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
        val maxFetchChars = config.builtinTools.websiteFetch.maxChars
        if (requestedMaxChars != null && requestedMaxChars !in ActionPayloadSecurity.WEBSITE_FETCH_MIN_MAX_CHARS..maxFetchChars) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "website_fetch_max_chars_out_of_bounds",
                reason = "WEBSITE_FETCH max_chars must be between ${ActionPayloadSecurity.WEBSITE_FETCH_MIN_MAX_CHARS} and $maxFetchChars."
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
            ?: return ActionOutcome(
                statusSummary = "Fetch tool is not configured.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val outcome = active.fetchWithOutcome(action.payload)
        val artifact = if (outcome.errorCategory == FetchErrorCategory.NONE) {
            listOf(
                ExternalContentPipeline.ingest(
                    text = outcome.message,
                    maxChars = MAX_FETCH_ARTIFACT_CHARS,
                    source = SourceDescriptor(
                        provider = "website_fetch",
                        contentKind = ContentKind.DOCUMENT,
                        objectType = "fetched_page",
                        sourceRef = action.rootInputId,
                    ),
                )
            )
        } else {
            emptyList()
        }
        return ActionOutcome(
            statusSummary = outcome.message,
            executionStatus = if (outcome.errorCategory == FetchErrorCategory.NONE) {
                ActionExecutionStatus.SUCCESS
            } else {
                ActionExecutionStatus.FAILED
            },
            effects = if (outcome.errorCategory == FetchErrorCategory.NONE) {
                setOf(ActionEffect.TASK_PROGRESS, ActionEffect.EVIDENCE_GATHERED)
            } else {
                emptySet()
            },
            actionErrorCategory = when (outcome.errorCategory) {
               FetchErrorCategory.NON_RETRYABLE -> "non_retryable"
               FetchErrorCategory.RETRYABLE -> "retryable"
                else -> null
            },
            fetchErrorCategory = outcome.errorCategory.name.lowercase(),
            resultArtifacts = artifact,
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
        const val MAX_FETCH_ARTIFACT_CHARS: Int = 4_000
    }
}

class WebsiteFetchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        WebsiteFetchActionPlugin(tool = context.fetchTool)
}
