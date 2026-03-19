package psyke.agent.cortex.motor

import psyke.agent.actions.ActionCapability
import psyke.agent.actions.ActionRegistry
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.ReflectionMemoryRecorder
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.PendingAction
import psyke.agent.project.NoopProjectsGateway
import psyke.agent.project.ProjectsGateway
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
        reflectionMemoryRecorder: ReflectionMemoryRecorder,
        config: AgentConfig = AgentConfig(),
        projectsGateway: ProjectsGateway = NoopProjectsGateway,
    ) : this(
        actionRegistry = ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = webSearchActionHandler,
                mcpTimeTool = mcpTimeTool,
                fetchTool = fetchTool,
                output = output,
                reflectionMemoryRecorder = reflectionMemoryRecorder,
                projectsGateway = projectsGateway,
            )
        )
    )

    @Volatile
    private var lastStatusSnapshot: List<ActionImplementationStatus>? = null

    suspend fun startupSmokeTest(): List<ActionImplementationStatus> {
        val statuses = buildStatusSnapshot()
        lastStatusSnapshot = statuses
        return statuses
    }

    suspend fun actionImplementationStatuses(): List<ActionImplementationStatus> {
        return lastStatusSnapshot ?: buildStatusSnapshot()
    }

    suspend fun availableActionTypes(): Set<ActionType> =
        actionImplementationStatuses()
            .filter { it.dispatchable && it.available }
            .map { it.actionType }
            .toSet()

    suspend fun dispatchableActionTypes(): Set<ActionType> =
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

    fun hasCapability(actionType: ActionType, capability: ActionCapability): Boolean =
        actionRegistry.hasCapability(actionType, capability)

    fun actionTypesWithCapability(capability: ActionCapability): Set<ActionType> =
        actionRegistry.actionTypesWithCapability(capability)

    suspend fun execute(action: PendingAction, searchResultCount: Int): ActionOutcome {
        return actionRegistry.execute(action, searchResultCount)
    }

    private suspend fun buildStatusSnapshot(): List<ActionImplementationStatus> =
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
