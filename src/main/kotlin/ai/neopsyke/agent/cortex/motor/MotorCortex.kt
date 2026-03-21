package ai.neopsyke.agent.cortex.motor

import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.project.NoopGoalsGateway
import ai.neopsyke.agent.project.GoalsGateway
import ai.neopsyke.agent.tools.mcp.FetchTool
import ai.neopsyke.agent.tools.mcp.McpTimeTool

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
        projectsGateway: GoalsGateway = NoopGoalsGateway,
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

    fun plannerDescriptors(): List<ai.neopsyke.agent.actions.ActionDescriptor> =
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
