package psyke.agent.core

import psyke.dashboard.InnerVoiceConfig

data class AgentConfig(
    val planner: PlannerConfig = PlannerConfig(),
    val superego: SuperegoConfig = SuperegoConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val metaReasoner: MetaReasonerConfig = MetaReasonerConfig(),
    val logbook: LogbookConfig = LogbookConfig(),
    val innerVoice: InnerVoiceConfig = InnerVoiceConfig(),
    val loopDelayMs: Int = 0,
    val maxPendingThoughts: Int = 64,
    val maxPendingActions: Int = 32,
    val maxPendingInputs: Int = 32,
    val searchResultCount: Int = 5,
    val mcpCallTimeoutMs: Long = 8_000,
    val fetchMaxChars: Int = 4_000,
)
