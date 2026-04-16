package ai.neopsyke.agent.ego

import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.NoopActionControlService
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.ContactChannelPolicy
import ai.neopsyke.agent.cortex.motor.actions.InMemoryEvidenceArtifactStore
import ai.neopsyke.agent.cortex.motor.actions.ConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
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
import ai.neopsyke.agent.durablework.NoopDurableWorkGateway
import ai.neopsyke.agent.durablework.DurableWorkGateway
import ai.neopsyke.agent.ego.planner.NoopPlanRefiner
import ai.neopsyke.agent.ego.planner.PlanRefiner
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.agent.cortex.motor.actions.fetch.FetchTool
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

    data class PlannerBuildResult(
        val planner: Ego.Planner,
        val planRefiner: PlanRefiner = NoopPlanRefiner(),
    )

    fun assemble(
        config: AgentConfig,
        plannerFactory: (MotorCortex) -> PlannerBuildResult,
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
        durableWorkGateway: DurableWorkGateway = NoopDurableWorkGateway,
        actionControlServiceFactory: (MotorCortex) -> ActionControlService = { NoopActionControlService },
        output: (String) -> Unit = {},
        conversationOutput: ConversationOutputGateway = RoutedConversationOutputGateway(fallbackOutput = output),
        contactChannelPolicy: ContactChannelPolicy? = null,
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
                durableWorkGateway = durableWorkGateway,
                contactChannelPolicy = contactChannelPolicy,
            )
        )
        val motorCortex = MotorCortex(actionRegistry = actionRegistry)
        val actionControlService = actionControlServiceFactory(motorCortex)
        val plannerBuild = plannerFactory(motorCortex)
        val ego = Ego(
            planner = plannerBuild.planner,
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
            goalRegistry = durableWorkGateway,
            durableWorkGateway = durableWorkGateway,
            evidenceArtifactStore = evidenceArtifactStore,
            planRefiner = plannerBuild.planRefiner,
            contactChannelSupplier = contactChannelPolicy?.let { { it.availableChannels() } } ?: { emptySet() },
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
