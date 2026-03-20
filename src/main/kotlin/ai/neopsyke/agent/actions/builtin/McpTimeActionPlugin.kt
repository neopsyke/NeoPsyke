package ai.neopsyke.agent.actions.builtin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginHealth
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.ActionPayloadSecurity

class McpTimeActionPlugin(
    private val tool: ai.neopsyke.agent.tools.mcp.McpTimeTool?,
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
            "Allow MCP_TIME"
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
        val success = status.startsWith("MCP time result:", ignoreCase = true)
        return ActionOutcome(
            statusSummary = status,
            executionStatus = if (success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
            effects = if (success) setOf(ActionEffect.TASK_PROGRESS, ActionEffect.EVIDENCE_GATHERED) else emptySet(),
        )
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
