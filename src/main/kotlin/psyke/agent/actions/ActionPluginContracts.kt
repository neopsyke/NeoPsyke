package psyke.agent.actions

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool

data class ActionDescriptor(
    val actionType: ActionType,
    val dispatchable: Boolean = true,
    val plannerDescription: String,
    val payloadGuidance: String,
    val payloadSchemaExample: String? = null,
    val requiresFollowUpThought: Boolean = false,
    val followUpPrefix: String = "Action completed.",
    val superegoDirectives: List<String> = emptyList(),
)

data class ActionPluginHealth(
    val available: Boolean,
    val detail: String,
)

data class ActionDeterministicReview(
    val allow: Boolean,
    val reason: String = "",
    val ruleId: String? = null,
    val reasonCode: String? = null,
)

data class ActionExecutionContext(
    val searchResultCount: Int,
)

data class ActionPluginFactoryContext(
    val config: AgentConfig,
    val webSearchActionHandler: WebSearchActionHandler?,
    val mcpTimeTool: McpTimeTool?,
    val fetchTool: FetchTool?,
    val output: (String) -> Unit,
    val env: Map<String, String> = System.getenv(),
)

interface AgentActionPlugin : AutoCloseable {
    val descriptor: ActionDescriptor

    fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome

    fun healthCheck(): ActionPluginHealth =
        ActionPluginHealth(available = true, detail = "Action plugin configured.")

    fun deterministicReview(
        @Suppress("UNUSED_PARAMETER") action: PendingAction,
        @Suppress("UNUSED_PARAMETER") context: SuperegoContext,
        @Suppress("UNUSED_PARAMETER") config: AgentConfig,
    ): ActionDeterministicReview? = null

    fun repairPlannerPayload(raw: String): String = raw

    override fun close() {}
}

interface AgentActionPluginFactory {
    fun create(context: ActionPluginFactoryContext): AgentActionPlugin
}

