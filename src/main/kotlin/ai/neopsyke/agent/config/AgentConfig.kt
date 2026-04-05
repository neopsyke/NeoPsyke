package ai.neopsyke.agent.config

import ai.neopsyke.agent.goal.GoalConfig
import ai.neopsyke.agent.model.PolicyScope
import ai.neopsyke.dashboard.InnerVoiceConfig

data class AgentConfig(
    /** Top-level policy scope applied to all channels unless overridden per-channel. */
    val policyScope: PolicyScope = PolicyScope.DEFAULT,
    val planner: PlannerConfig = PlannerConfig(),
    val superego: SuperegoConfig = SuperegoConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val metaReasoner: MetaReasonerConfig = MetaReasonerConfig(),
    val logbook: LogbookConfig = LogbookConfig(),
    val actionControl: ActionControlConfig = ActionControlConfig(),
    val approvals: ApprovalRuntimeConfig = ApprovalRuntimeConfig(),
    val connectors: ConnectorRuntimeConfig = ConnectorRuntimeConfig(),
    val builtinTools: BuiltinToolsConfig = BuiltinToolsConfig(),
    val nativeIntegrations: NativeIntegrationsConfig = NativeIntegrationsConfig(),
    val innerVoice: InnerVoiceConfig = InnerVoiceConfig(),
    val goals: GoalConfig = GoalConfig(),
    val loopDelayMs: Int = 0,
    val maxPendingThoughts: Int = 64,
    val maxPendingActions: Int = 32,
    val maxPendingInputs: Int = 32,
    val searchResultCount: Int = 5,
    // ── Agent-global fields (cross-cutting, not subsystem-specific) ──
    val maxActionPayloadChars: Int = 4_000,
    val maxActionSummaryChars: Int = 180,
    val maxLlmPromptTokens: Int = 2_400,
    val llmRetryAttempts: Int = 2,
)
