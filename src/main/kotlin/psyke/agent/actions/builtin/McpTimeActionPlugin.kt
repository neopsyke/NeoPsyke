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
        )
    )

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
}

class McpTimeActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        McpTimeActionPlugin(tool = context.mcpTimeTool)
}
