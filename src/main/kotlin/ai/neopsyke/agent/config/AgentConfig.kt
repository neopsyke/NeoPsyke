package ai.neopsyke.agent.config

import ai.neopsyke.agent.assignments.AssignmentConfig
import ai.neopsyke.agent.model.PolicyScope
import ai.neopsyke.dashboard.InnerVoiceConfig

data class AgentConfig(
    /** Top-level policy scope applied to all channels unless overridden per-channel. */
    val policyScope: PolicyScope = PolicyScope.DEFAULT,
    val persona: PersonaConfig = PersonaConfig(),
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
    val assignment: AssignmentConfig = AssignmentConfig(),
    val loopDelayMs: Int = 0,
    val maxPendingContinuations: Int = 64,
    val maxPendingActions: Int = 32,
    val maxPendingInputs: Int = 32,
    val searchResultCount: Int = 5,
    // ── Agent-global fields (cross-cutting, not subsystem-specific) ──
    val maxActionPayloadChars: Int = 4_000,
    val maxActionSummaryChars: Int = 180,
    val maxLlmPromptTokens: Int = 2_400,
    val llmRetryAttempts: Int = 2,
)

data class PersonaConfig(
    val name: String = DEFAULT_NAME,
) {
    init {
        require(name.isNotBlank()) { "agent persona name must not be blank" }
        require(name.length <= MAX_NAME_CHARS) { "agent persona name must be at most $MAX_NAME_CHARS characters" }
        require(name.none { it == '\n' || it == '\r' || it.isISOControl() }) {
            "agent persona name must not contain control characters"
        }
    }

    companion object {
        const val DEFAULT_NAME: String = "Neo"
        const val MAX_NAME_CHARS: Int = 64
    }
}
