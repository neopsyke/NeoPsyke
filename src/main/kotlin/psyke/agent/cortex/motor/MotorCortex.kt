package psyke.agent.cortex.motor

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool
import psyke.agent.tools.mcp.ToolHealthStatus

data class ActionImplementationStatus(
    val actionType: ActionType,
    val available: Boolean,
    val detail: String,
)

class MotorCortex(
    private val webSearchActionHandler: WebSearchActionHandler,
    private val mcpTimeTool: McpTimeTool? = null,
    private val fetchTool: FetchTool? = null,
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
    private var lastStatusSnapshot: List<ActionImplementationStatus>? = null

    fun startupSmokeTest(): List<ActionImplementationStatus> {
        val webSearchHealth = webSearchActionHandler.healthCheck()
        val statuses = listOf(
            ActionImplementationStatus(
                actionType = ActionType.ANSWER,
                available = true,
                detail = "Output channel is configured."
            ),
            ActionImplementationStatus(
                actionType = ActionType.WEB_SEARCH,
                available = webSearchHealth.available,
                detail = webSearchHealth.detail
            ),
            actionStatusFromTool(
                actionType = ActionType.MCP_TIME,
                configured = mcpTimeTool != null,
                toolStatus = mcpTimeTool?.healthCheck(),
                notConfiguredDetail = "MCP time tool not configured."
            ),
            actionStatusFromTool(
                actionType = ActionType.WEBSITE_FETCH,
                configured = fetchTool != null,
                toolStatus = fetchTool?.healthCheck(),
                notConfiguredDetail = "Fetch tool not configured."
            )
        )
        lastStatusSnapshot = statuses
        return statuses
    }

    fun actionImplementationStatuses(): List<ActionImplementationStatus> {
        val webSearchHealth = webSearchActionHandler.healthCheck()
        return lastStatusSnapshot ?: listOf(
            ActionImplementationStatus(
                actionType = ActionType.ANSWER,
                available = true,
                detail = "Output channel is configured."
            ),
            ActionImplementationStatus(
                actionType = ActionType.WEB_SEARCH,
                available = webSearchHealth.available,
                detail = webSearchHealth.detail
            ),
            ActionImplementationStatus(
                actionType = ActionType.MCP_TIME,
                available = mcpTimeTool != null,
                detail = if (mcpTimeTool != null) "MCP time tool configured (not smoke-tested)." else "MCP time tool not configured."
            ),
            ActionImplementationStatus(
                actionType = ActionType.WEBSITE_FETCH,
                available = fetchTool != null,
                detail = if (fetchTool != null) "Fetch tool configured (not smoke-tested)." else "Fetch tool not configured."
            )
        )
    }

    fun availableActionTypes(): Set<ActionType> =
        actionImplementationStatuses()
            .filter { it.available }
            .map { it.actionType }
            .toSet()

    fun execute(action: PendingAction, searchResultCount: Int): ActionOutcome {
        return when (action.type) {
            ActionType.ANSWER -> {
                output("ego> ${action.payload}")
                ActionOutcome(
                    statusSummary = "Answer delivered to interlocutor.",
                    assistantOutput = action.payload
                )
            }

            ActionType.WEB_SEARCH -> {
                webSearchActionHandler.execute(action.payload, searchResultCount)
            }

            ActionType.MCP_TIME -> {
                val status = mcpTimeTool?.getCurrentTime(action.payload)
                    ?: "MCP time tool is not configured."
                ActionOutcome(statusSummary = status)
            }

            ActionType.WEBSITE_FETCH -> {
                if (fetchTool == null) {
                    ActionOutcome(statusSummary = "Fetch tool is not configured.")
                } else {
                    val outcome = fetchTool.fetchWithOutcome(action.payload)
                    ActionOutcome(
                        statusSummary = outcome.message,
                        fetchErrorCategory = outcome.errorCategory.name.lowercase()
                    )
                }
            }

            ActionType.MEMORY -> {
                // Memory operations are handled internally by McpHippocampus, not dispatched here.
                ActionOutcome(statusSummary = "Memory action is not executable via MotorCortex.")
            }
        }
    }

    private fun actionStatusFromTool(
        actionType: ActionType,
        configured: Boolean,
        toolStatus: ToolHealthStatus?,
        notConfiguredDetail: String,
    ): ActionImplementationStatus {
        if (!configured) {
            return ActionImplementationStatus(
                actionType = actionType,
                available = false,
                detail = notConfiguredDetail
            )
        }
        if (toolStatus == null) {
            return ActionImplementationStatus(
                actionType = actionType,
                available = false,
                detail = "Health status unavailable."
            )
        }
        return ActionImplementationStatus(
            actionType = actionType,
            available = toolStatus.available,
            detail = toolStatus.detail
        )
    }
}
