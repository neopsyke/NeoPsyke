package ai.neopsyke.support

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.EgoAssembler
import ai.neopsyke.agent.ego.MetaReasoner
import ai.neopsyke.agent.ego.NoopMetaReasoner
import ai.neopsyke.agent.ego.NoopScratchpadFinalizer
import ai.neopsyke.agent.ego.ScratchpadFinalizer
import ai.neopsyke.agent.memory.episodic.DeterministicLogbookSummarizer
import ai.neopsyke.agent.memory.episodic.Logbook
import ai.neopsyke.agent.memory.episodic.LogbookSummarizer
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.NoopHippocampus
import ai.neopsyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import ai.neopsyke.agent.project.NoopProjectsGateway
import ai.neopsyke.agent.project.ProjectsGateway
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation

fun buildTestEgo(
    planner: Ego.Planner,
    superego: Superego,
    motorCortex: MotorCortex,
    config: AgentConfig,
    hippocampus: Hippocampus = NoopHippocampus,
    metaReasoner: MetaReasoner = NoopMetaReasoner,
    longTermMemoryAdvisor: LongTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
    sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    taskWorkspaceFinalizer: ScratchpadFinalizer = NoopScratchpadFinalizer,
    instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    logbook: Logbook? = null,
    logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
    runId: String? = null,
    projectsGateway: ProjectsGateway = NoopProjectsGateway,
): Ego {
    val memory = EgoAssembler.buildMemorySystem(
        config = config,
        instrumentation = instrumentation,
        hippocampus = hippocampus,
        longTermMemoryAdvisor = longTermMemoryAdvisor,
        logbook = logbook,
        logbookSummarizer = logbookSummarizer,
        runId = runId,
    )
    return Ego(
        planner = planner,
        superego = superego,
        motorCortex = motorCortex,
        config = config,
        memory = memory,
        metaReasoner = metaReasoner,
        sensoryCortex = sensoryCortex,
        taskWorkspaceFinalizer = taskWorkspaceFinalizer,
        instrumentation = instrumentation,
        projectRegistry = projectsGateway,
        projectsGateway = projectsGateway,
    )
}
