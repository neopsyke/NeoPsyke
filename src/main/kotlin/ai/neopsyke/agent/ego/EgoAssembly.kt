package ai.neopsyke.agent.ego

import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.memory.episodic.DeterministicLogbookSummarizer
import ai.neopsyke.agent.memory.episodic.Logbook
import ai.neopsyke.agent.memory.episodic.LogbookSummarizer
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.NoopHippocampus
import ai.neopsyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import ai.neopsyke.agent.memory.shortterm.MemoryStore
import ai.neopsyke.agent.memory.workspace.TaskWorkspaceStore
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.agent.tools.mcp.FetchTool
import ai.neopsyke.agent.tools.mcp.McpTimeTool
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation

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
        )
        return EgoAssembly(
            ego = ego,
            motorCortex = motorCortex,
            actionRegistry = actionRegistry,
            memory = memory,
        )
    }
}
