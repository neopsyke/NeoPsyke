package psyke.agent.actions.builtin

import com.fasterxml.jackson.databind.DeserializationFeature
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
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import psyke.agent.support.ActionPayloadSecurity

class McpTimeActionPlugin(
    private val tool: psyke.agent.tools.mcp.McpTimeTool?,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.MCP_TIME,
        dispatchable = true,
        plannerDescription = "mcp_time: payload must be JSON like {\"timezone\":\"Europe/Berlin\"} (timezone required).",
        payloadGuidance = "JSON object with required timezone field (IANA timezone, for example Europe/Berlin).",
        payloadSchemaExample = """{"timezone":"Europe/Berlin"}""",
        requiresFollowUpThought = true,
        followUpPrefix = "MCP time lookup completed.",
        superegoDirectives = listOf(
            "Allow MCP_TIME for benign time/date lookup payloads."
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
                ruleId = "mcp_time_timezone_missing",
                reason = "MCP_TIME payload must include a timezone, for example {\"timezone\":\"Europe/Berlin\"}."
            )
        }
        val parsed = try {
            mapper.readValue<McpTimePayload>(payload)
        } catch (_: Exception) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "mcp_time_payload_invalid_json",
                reason = "MCP_TIME payload must be JSON like {\"timezone\":\"Europe/Berlin\"}."
            )
        }
        val timezone = parsed.timezone?.trim().orEmpty()
        if (timezone.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "mcp_time_timezone_missing",
                reason = "MCP_TIME payload must include a non-empty timezone."
            )
        }
        if (!ActionPayloadSecurity.TIMEZONE_REGEX.matches(timezone)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "mcp_time_timezone_invalid",
                reason = "MCP_TIME timezone contains invalid characters."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun healthCheck(): ActionPluginHealth {
        val active = tool
            ?: return ActionPluginHealth(
                available = false,
                detail = "MCP time tool not configured."
            )
        val status = active.healthCheck()
        return ActionPluginHealth(
            available = status.available,
            detail = status.detail
        )
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val status = tool?.getCurrentTime(action.payload)
            ?: "MCP time tool is not configured."
        return ActionOutcome(statusSummary = status)
    }

    private data class McpTimePayload(
        val timezone: String? = null,
    )

    private companion object {
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

class McpTimeActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        McpTimeActionPlugin(tool = context.mcpTimeTool)
}
