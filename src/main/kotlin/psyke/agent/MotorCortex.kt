package psyke.agent

import psyke.agent.actions.websearch.WebSearchActionHandler

data class ActionImplementationStatus(
    val actionType: ActionType,
    val available: Boolean,
    val detail: String,
)

class MotorCortex(
    private val webSearchActionHandler: WebSearchActionHandler,
    private val mcpTimeTool: McpTimeTool? = null,
    private val mcpFetchTool: McpFetchTool? = null,
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
                actionType = ActionType.MCP_FETCH,
                configured = mcpFetchTool != null,
                toolStatus = mcpFetchTool?.healthCheck(),
                notConfiguredDetail = "MCP fetch tool not configured."
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
                actionType = ActionType.MCP_FETCH,
                available = mcpFetchTool != null,
                detail = if (mcpFetchTool != null) "MCP fetch tool configured (not smoke-tested)." else "MCP fetch tool not configured."
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

            ActionType.MCP_FETCH -> {
                val status = mcpFetchTool?.fetch(action.payload)
                    ?: "MCP fetch tool is not configured."
                ActionOutcome(statusSummary = status)
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
