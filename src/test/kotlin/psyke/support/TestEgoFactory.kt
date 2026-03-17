package psyke.support

import psyke.agent.config.AgentConfig
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.ego.Ego
import psyke.agent.ego.EgoAssembler
import psyke.agent.ego.MetaReasoner
import psyke.agent.ego.NoopMetaReasoner
import psyke.agent.ego.NoopTaskWorkspaceFinalizer
import psyke.agent.ego.TaskWorkspaceFinalizer
import psyke.agent.memory.episodic.DeterministicLogbookSummarizer
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.LogbookSummarizer
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.LongTermMemoryAdvisor
import psyke.agent.memory.longterm.NoopHippocampus
import psyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import psyke.agent.project.NoopProjectsGateway
import psyke.agent.project.ProjectsGateway
import psyke.agent.superego.Superego
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation

fun buildTestEgo(
    planner: Ego.Planner,
    superego: Superego,
    motorCortex: MotorCortex,
    config: AgentConfig,
    hippocampus: Hippocampus = NoopHippocampus,
    metaReasoner: MetaReasoner = NoopMetaReasoner,
    longTermMemoryAdvisor: LongTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
    sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    taskWorkspaceFinalizer: TaskWorkspaceFinalizer = NoopTaskWorkspaceFinalizer,
    instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    logbook: Logbook? = null,
    logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
    runId: String? = null,
    projectsGateway: ProjectsGateway = NoopProjectsGateway,
): Ego {
    val memory = EgoAssembler.buildMemoryCoordinator(
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
