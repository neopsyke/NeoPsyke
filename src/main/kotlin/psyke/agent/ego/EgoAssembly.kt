package psyke.agent.ego

import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.ActionRegistry
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.config.AgentConfig
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.memory.episodic.DeterministicLogbookSummarizer
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.LogbookSummarizer
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.LongTermMemoryAdvisor
import psyke.agent.memory.longterm.NoopHippocampus
import psyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import psyke.agent.memory.shortterm.MemoryStore
import psyke.agent.memory.workspace.TaskWorkspaceStore
import psyke.agent.project.NoopProjectsGateway
import psyke.agent.project.ProjectsGateway
import psyke.agent.superego.Superego
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation

data class EgoAssembly(
    val ego: Ego,
    val motorCortex: MotorCortex,
    val actionRegistry: ActionRegistry,
    val memory: MemoryCoordinator,
) : AutoCloseable {
    override fun close() {
        actionRegistry.close()
    }
}

object EgoAssembler {
    fun buildMemoryCoordinator(
        config: AgentConfig,
        instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
        hippocampus: Hippocampus = NoopHippocampus,
        longTermMemoryAdvisor: LongTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
        logbook: Logbook? = null,
        logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
        runId: String? = null,
    ): MemoryCoordinator =
        MemoryCoordinator(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = longTermMemoryAdvisor,
            config = config,
            instrumentation = instrumentation,
            initialMemoryStore = MemoryStore(config.memory.maxShortTermContextChars),
            logbook = logbook,
            logbookSummarizer = logbookSummarizer,
            runId = runId,
        )

    fun assemble(
        config: AgentConfig,
        plannerFactory: (MotorCortex) -> Ego.Planner,
        superegoFactory: (ActionRegistry) -> Superego,
        sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
        instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
        hippocampus: Hippocampus = NoopHippocampus,
        metaReasoner: MetaReasoner = NoopMetaReasoner,
        longTermMemoryAdvisor: LongTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
        taskWorkspaceStore: TaskWorkspaceStore = TaskWorkspaceStore(config.memory.taskWorkspace),
        taskWorkspaceFinalizer: TaskWorkspaceFinalizer = NoopTaskWorkspaceFinalizer,
        logbook: Logbook? = null,
        logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
        runId: String? = null,
        webSearchActionHandler: WebSearchActionHandler? = null,
        mcpTimeTool: McpTimeTool? = null,
        fetchTool: FetchTool? = null,
        projectsGateway: ProjectsGateway = NoopProjectsGateway,
        output: (String) -> Unit = {},
    ): EgoAssembly {
        val memory = buildMemoryCoordinator(
            config = config,
            instrumentation = instrumentation,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = longTermMemoryAdvisor,
            logbook = logbook,
            logbookSummarizer = logbookSummarizer,
            runId = runId,
        )
        val actionRegistry = ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = webSearchActionHandler,
                mcpTimeTool = mcpTimeTool,
                fetchTool = fetchTool,
                output = output,
                reflectionMemoryRecorder = memory,
                projectsGateway = projectsGateway,
            )
        )
        val motorCortex = MotorCortex(actionRegistry = actionRegistry)
        val ego = Ego(
            planner = plannerFactory(motorCortex),
            superego = superegoFactory(actionRegistry),
            motorCortex = motorCortex,
            config = config,
            memory = memory,
            metaReasoner = metaReasoner,
            sensoryCortex = sensoryCortex,
            taskWorkspaceStore = taskWorkspaceStore,
            taskWorkspaceFinalizer = taskWorkspaceFinalizer,
            instrumentation = instrumentation,
            projectRegistry = projectsGateway,
            projectsGateway = projectsGateway,
        )
        return EgoAssembly(
            ego = ego,
            motorCortex = motorCortex,
            actionRegistry = actionRegistry,
            memory = memory,
        )
    }
}
