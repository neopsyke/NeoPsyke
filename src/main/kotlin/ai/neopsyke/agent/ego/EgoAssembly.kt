package ai.neopsyke.agent.ego

import ai.neopsyke.agent.actioncontrol.ActionControlService
import ai.neopsyke.agent.actioncontrol.NoopActionControlService
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.InMemoryEvidenceArtifactStore
import ai.neopsyke.agent.actions.ConversationOutputGateway
import ai.neopsyke.agent.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.NoopHippocampus
import ai.neopsyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.DeterministicLogbookSummarizer
import ai.neopsyke.agent.memory.longterm.Logbook
import ai.neopsyke.agent.memory.longterm.LogbookSummarizer
import ai.neopsyke.agent.memory.shortterm.MemoryStore
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.goal.NoopGoalsGateway
import ai.neopsyke.agent.goal.GoalsGateway
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.agent.tools.mcp.FetchTool
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation

data class EgoAssembly(
    val ego: Ego,
    val motorCortex: MotorCortex,
    val actionRegistry: ActionRegistry,
    val actionControlService: ActionControlService,
    val memory: MemorySystem,
) : AutoCloseable {
    override fun close() {
        actionRegistry.close()
    }
}

object EgoAssembler {
    fun buildMemorySystem(
        config: AgentConfig,
        instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
        hippocampus: Hippocampus = NoopHippocampus,
        longTermMemoryAdvisor: LongTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
        logbook: Logbook? = null,
        logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
        runId: String? = null,
    ): MemorySystem =
        MemorySystem(
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
        scratchpadStore: ScratchpadStore = ScratchpadStore(config.memory.scratchpad),
        scratchpadFinalizer: ScratchpadFinalizer = NoopScratchpadFinalizer,
        logbook: Logbook? = null,
        logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
        runId: String? = null,
        webSearchActionHandler: WebSearchActionHandler? = null,
        fetchTool: FetchTool? = null,
        goalsGateway: GoalsGateway = NoopGoalsGateway,
        actionControlServiceFactory: (MotorCortex) -> ActionControlService = { NoopActionControlService },
        output: (String) -> Unit = {},
        conversationOutput: ConversationOutputGateway = RoutedConversationOutputGateway(fallbackOutput = output),
    ): EgoAssembly {
        val memory = buildMemorySystem(
            config = config,
            instrumentation = instrumentation,
            hippocampus = hippocampus,
            longTermMemoryAdvisor = longTermMemoryAdvisor,
            logbook = logbook,
            logbookSummarizer = logbookSummarizer,
            runId = runId,
        )
        val evidenceArtifactStore = InMemoryEvidenceArtifactStore()
        val actionRegistry = ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = webSearchActionHandler,
                fetchTool = fetchTool,
                output = output,
                conversationOutput = conversationOutput,
                evidenceArtifactStore = evidenceArtifactStore,
                reflectionMemoryRecorder = memory,
                goalsGateway = goalsGateway,
            )
        )
        val motorCortex = MotorCortex(actionRegistry = actionRegistry)
        val actionControlService = actionControlServiceFactory(motorCortex)
        val ego = Ego(
            planner = plannerFactory(motorCortex),
            superego = superegoFactory(actionRegistry),
            motorCortex = motorCortex,
            config = config,
            memory = memory,
            metaReasoner = metaReasoner,
            sensoryCortex = sensoryCortex,
            scratchpadStore = scratchpadStore,
            scratchpadFinalizer = scratchpadFinalizer,
            instrumentation = instrumentation,
            actionControlService = actionControlService,
            goalRegistry = goalsGateway,
            goalsGateway = goalsGateway,
            evidenceArtifactStore = evidenceArtifactStore,
        )
        return EgoAssembly(
            ego = ego,
            motorCortex = motorCortex,
            actionRegistry = actionRegistry,
            actionControlService = actionControlService,
            memory = memory,
        )
    }
}
