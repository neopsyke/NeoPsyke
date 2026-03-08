package psyke.agent.cortex.motor

import psyke.agent.actions.ActionRegistry
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.PendingAction
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool

data class ActionImplementationStatus(
    val actionType: ActionType,
    val dispatchable: Boolean,
    val available: Boolean,
    val detail: String,
)

class MotorCortex(
    private val actionRegistry: ActionRegistry,
) {
    constructor(
        webSearchActionHandler: WebSearchActionHandler,
        mcpTimeTool: McpTimeTool? = null,
        fetchTool: FetchTool? = null,
        output: (String) -> Unit = ::println,
        config: AgentConfig = AgentConfig(),
    ) : this(
        actionRegistry = ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = webSearchActionHandler,
                mcpTimeTool = mcpTimeTool,
                fetchTool = fetchTool,
                output = output
            )
        )
    )

    @Volatile
    private var lastStatusSnapshot: List<ActionImplementationStatus>? = null

    fun startupSmokeTest(): List<ActionImplementationStatus> {
        val statuses = buildStatusSnapshot()
        lastStatusSnapshot = statuses
        return statuses
    }

    fun actionImplementationStatuses(): List<ActionImplementationStatus> {
        return lastStatusSnapshot ?: buildStatusSnapshot()
    }

    fun availableActionTypes(): Set<ActionType> =
        actionImplementationStatuses()
            .filter { it.dispatchable && it.available }
            .map { it.actionType }
            .toSet()

    fun dispatchableActionTypes(): Set<ActionType> =
        actionImplementationStatuses()
            .filter { it.dispatchable }
            .map { it.actionType }
            .toSet()

    fun plannerDescriptors(): List<psyke.agent.actions.ActionDescriptor> =
        actionRegistry.descriptors()
            .filter { it.dispatchable }
            .sortedBy { it.actionType.id }

    fun requiresFollowUpThought(actionType: ActionType): Boolean =
        actionRegistry.requiresFollowUpThought(actionType)

    fun followUpPrefix(actionType: ActionType): String =
        actionRegistry.followUpPrefix(actionType)

    fun repairPlannerPayload(actionType: ActionType, raw: String): String =
        actionRegistry.repairPlannerPayload(actionType, raw)

    fun execute(action: PendingAction, searchResultCount: Int): ActionOutcome {
        return actionRegistry.execute(action, searchResultCount)
    }

    private fun buildStatusSnapshot(): List<ActionImplementationStatus> =
        actionRegistry.descriptors()
            .sortedBy { it.actionType.id }
            .map { descriptor ->
                val health = actionRegistry.healthCheck(descriptor.actionType)
                ActionImplementationStatus(
                    actionType = descriptor.actionType,
                    dispatchable = descriptor.dispatchable,
                    available = health.available,
                    detail = health.detail
                )
            }
}
