package ai.neopsyke.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.agent.config.ConnectorRuntimeConfig
import ai.neopsyke.agent.config.LogbookConfig
import ai.neopsyke.agent.config.MemoryConfig
import ai.neopsyke.agent.config.MetaReasonerConfig
import ai.neopsyke.agent.config.NativeIntegrationsConfig
import ai.neopsyke.agent.config.GoogleWorkspaceConfig
import ai.neopsyke.agent.config.TelegramChannelConfig
import ai.neopsyke.agent.config.TelegramIngressMode
import ai.neopsyke.dashboard.InnerVoiceConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.goal.GoalConfig
import ai.neopsyke.agent.config.SuperegoConfig
import ai.neopsyke.agent.config.ScratchpadConfig
import java.nio.file.Path
import java.nio.file.Paths

data class AgentRuntimeSettings(
    val agentConfig: AgentConfig,
    val dashboardEnabled: Boolean,
    val dashboardPort: Int,
    val evalMaxRawResponseChars: Int,
    val evalDefaultStage: String? = null,
)

private data class AgentRuntimeYamlConfig(
    val app: AgentRuntimeYamlApp? = null,
    val eval: AgentRuntimeYamlEval? = null,
    val agent: AgentRuntimeYamlAgent? = null,
)

private data class AgentRuntimeYamlApp(
    val dashboardEnabled: Boolean? = null,
    val dashboardPort: Int? = null,
)

private data class AgentRuntimeYamlEval(
    val maxRawResponseChars: Int? = null,
    val defaultStage: String? = null,
)

private data class AgentRuntimeYamlAgent(
    val planner: AgentRuntimeYamlPlanner? = null,
    val superego: AgentRuntimeYamlSuperego? = null,
    val memory: AgentRuntimeYamlMemory? = null,
    val metaReasoner: AgentRuntimeYamlMetaReasoner? = null,
    val logbook: AgentRuntimeYamlLogbook? = null,
    val actionControl: AgentRuntimeYamlActionControl? = null,
    val connectors: AgentRuntimeYamlConnectors? = null,
    val nativeIntegrations: AgentRuntimeYamlNativeIntegrations? = null,
    val innerVoice: AgentRuntimeYamlInnerVoice? = null,
    val goals: AgentRuntimeYamlGoals? = null,
    val runtime: AgentRuntimeYamlRuntime? = null,
)

private data class AgentRuntimeYamlPlanner(
    val maxLoopStepsPerInput: Int? = null,
    val maxThoughtPasses: Int? = null,
    val maxThoughtChars: Int? = null,
    val maxInputChars: Int? = null,
    val maxActionPayloadChars: Int? = null,
    val maxActionSummaryChars: Int? = null,
    val maxPromptTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val llmRetryAttempts: Int? = null,
    val maxRunTotalTokens: Int? = null,
    val maxRunTokensPerProvider: Int? = null,
    val maxRunTokensPerRole: Int? = null,
    val maxPlanSteps: Int? = null,
    val maxPlanStepDescriptionChars: Int? = null,
    val maxPlansPerInput: Int? = null,
    val actionRetryBudgetNonRetryableFailures: Int? = null,
    val actionRetryCooldownSteps: Int? = null,
    val actionVerifierEnabled: Boolean? = null,
)

private data class AgentRuntimeYamlSuperego(
    val maxCompletionTokens: Int? = null,
    val dynamicCompletionEnabled: Boolean? = null,
    val dynamicCompletionHardMaxTokens: Int? = null,
    val dynamicPromptToCompletionRatio: Double? = null,
    val dynamicCompletionMinPromptTokens: Int? = null,
    val twoStageReviewEnabled: Boolean? = null,
    val twoStageLowConfidenceThreshold: Double? = null,
    val twoStageEscalateOnMediumPolicyRisk: Boolean? = null,
    val twoStageSkipForContactUserActions: Boolean? = null,
    val twoStageSkipForWebSearchActions: Boolean? = null,
)

private data class AgentRuntimeYamlMemory(
    val maxShortTermContextChars: Int? = null,
    val maxShortTermContextPromptTokens: Int? = null,
    val longTermMemoryRecallMaxItems: Int? = null,
    val longTermMemoryRecallMaxChars: Int? = null,
    val longTermMemoryPromptCompressionEnabled: Boolean? = null,
    val longTermMemoryPromptDialogueMaxChars: Int? = null,
    val longTermMemoryPromptRecallMaxChars: Int? = null,
    val longTermMemoryAssessEverySteps: Int? = null,
    val longTermMemoryAssessCooldownSteps: Int? = null,
    val longTermMemoryMinConfidence: Double? = null,
    val longTermMemoryMaxTokens: Int? = null,
    val longTermMemoryDynamicCompletionEnabled: Boolean? = null,
    val longTermMemoryDynamicCompletionHardMaxTokens: Int? = null,
    val longTermMemoryDynamicPromptToCompletionRatio: Double? = null,
    val longTermMemoryDynamicCompletionMinPromptTokens: Int? = null,
    val longTermMemoryMaxSummaryChars: Int? = null,
    val longTermMemoryForceAssessOnAllowedAction: Boolean? = null,
    val longTermMemoryForceAssessOnTerminalAnswer: Boolean? = null,
    val longTermMemoryParseFallbackDisableAfter: Int? = null,
    val longTermMemoryRecallEchoMinSummaryChars: Int? = null,
    val longTermMemoryRecallEchoMinTokenLength: Int? = null,
    val longTermMemoryRecallEchoMinTokenCount: Int? = null,
    val longTermMemoryRecallEchoTokenOverlapThreshold: Double? = null,
    val mcpMemoryCallTimeoutMs: Long? = null,
    val scratchpad: AgentRuntimeYamlScratchpad? = null,
)

private data class AgentRuntimeYamlScratchpad(
    val enabled: Boolean? = null,
    val activationMinPlanSteps: Int? = null,
    val maxPromptTokens: Int? = null,
    val maxSections: Int? = null,
    val maxSectionChars: Int? = null,
    val maxSectionSummaryChars: Int? = null,
    val maxEvidenceItems: Int? = null,
    val maxEvidenceChars: Int? = null,
    val finalCompilationMaxChars: Int? = null,
    val finalPassRewriteEnabled: Boolean? = null,
    val finalPassMaxTokens: Int? = null,
    val finalPassMinWorkspaceConfidence: Double? = null,
    val finalPassMinModelConfidence: Double? = null,
    val debugCaptureEnabled: Boolean? = null,
    val maxActiveTasks: Int? = null,
    val digestMaxEntries: Int? = null,
    val digestMaxChars: Int? = null,
    val digestMaxPromptTokens: Int? = null,
)

private data class AgentRuntimeYamlMetaReasoner(
    val deliberationPressureAssessmentMinStep: Int? = null,
    val deliberationPressureAssessmentEverySteps: Int? = null,
    val deliberationPressureAssessmentThreshold: Double? = null,
    val cooldownSteps: Int? = null,
    val maxTokens: Int? = null,
    val dynamicCompletionEnabled: Boolean? = null,
    val dynamicCompletionHardMaxTokens: Int? = null,
    val dynamicPromptToCompletionRatio: Double? = null,
    val dynamicCompletionMinPromptTokens: Int? = null,
    val forcedTerminalPressureThreshold: Double? = null,
    val forcedTerminalStaleStreakThreshold: Int? = null,
)

private data class AgentRuntimeYamlLogbook(
    val enabled: Boolean? = null,
    val maxSummaryChars: Int? = null,
    val maxKeywordsPerEntry: Int? = null,
    val maxEntriesPerQuery: Int? = null,
    val retentionDays: Int? = null,
    val dbPath: String? = null,
    val episodicRecallMaxChars: Int? = null,
    val episodicRecallMaxResults: Int? = null,
    val useLlmSummarizer: Boolean? = null,
)

private data class AgentRuntimeYamlActionControl(
    val enabled: Boolean? = null,
    val dbPath: String? = null,
    val policyPath: String? = null,
    val authorizationTtlMs: Long? = null,
    val maxInspectResults: Int? = null,
    val autonomousWorkerEnabled: Boolean? = null,
    val autonomousWorkerPollMs: Long? = null,
    val autonomousWorkerBatchSize: Int? = null,
    val observePerTypePerRootInput: Int? = null,
    val contactUserPerRootInput: Int? = null,
    val reflectionFamilyPerRootInput: Int? = null,
    val reflectEvidencePerRootInput: Int? = null,
    val goalOperationPerRootInput: Int? = null,
    val commitPrivatePerTypePerRootInput: Int? = null,
    val commitStatefulPerTypePerRootInput: Int? = null,
    val commitPublicPerTypePerRootInput: Int? = null,
    val controlPlanePerTypePerRootInput: Int? = null,
)

private data class AgentRuntimeYamlConnectors(
    val enabled: Boolean? = null,
    val curatedCatalogPath: String? = null,
    val installStateDir: String? = null,
    val failClosed: Boolean? = null,
    val pinningEnabled: Boolean? = null,
    val startupTimeoutMs: Long? = null,
    val healthTimeoutMs: Long? = null,
    val allowedConnectorIds: List<String>? = null,
    val enabledBundleIds: List<String>? = null,
    val allowThirdPartyConnectors: Boolean? = null,
)

private data class AgentRuntimeYamlNativeIntegrations(
    val telegram: AgentRuntimeYamlTelegram? = null,
    val googleWorkspace: AgentRuntimeYamlGoogleWorkspace? = null,
)

private data class AgentRuntimeYamlTelegram(
    val enabled: Boolean? = null,
    val mode: String? = null,
    val webhookPath: String? = null,
    val ownerChatId: String? = null,
    val ownerUserId: String? = null,
    val botTokenHandle: String? = null,
    val webhookSecretHandle: String? = null,
    val policyScopeId: String? = null,
    val sessionIdPrefix: String? = null,
    val requireDirectChat: Boolean? = null,
    val dropUnauthorizedMessages: Boolean? = null,
    val pollTimeoutSeconds: Int? = null,
    val pollRetryDelayMs: Long? = null,
)

private data class AgentRuntimeYamlGoogleWorkspace(
    val enabled: Boolean? = null,
    val tokenStoreDir: String? = null,
    val allowedOwnerEmail: String? = null,
    val publicBaseUrl: String? = null,
    val oauthStartPath: String? = null,
    val oauthClientIdHandle: String? = null,
    val oauthClientSecretHandle: String? = null,
    val oauthStateSigningSecretHandle: String? = null,
    val oauthTokenEncryptionSecretHandle: String? = null,
    val callbackPath: String? = null,
    val authorizationBaseUrl: String? = null,
    val tokenBaseUrl: String? = null,
    val requirePkce: Boolean? = null,
    val requireRefreshToken: Boolean? = null,
    val oauthStateTtlSeconds: Long? = null,
    val scopes: List<String>? = null,
)

private data class AgentRuntimeYamlInnerVoice(
    val enabled: Boolean? = null,
    val maxContentChars: Int? = null,
    val maxEventsPerSession: Int? = null,
)

private data class AgentRuntimeYamlGoals(
    val enabled: Boolean? = null,
    val workspaceRoot: String? = null,
    val maxActiveGoals: Int? = null,
    val maxStepsPerPlan: Int? = null,
    val actionsPerCycle: Int? = null,
    val snapshotEveryNEvents: Int? = null,
    val timerResolutionMs: Long? = null,
    val conditionCheckIntervalMs: Long? = null,
    val completedGoalRetentionDays: Int? = null,
    val maxWorkspaceBytes: Long? = null,
)

private data class AgentRuntimeYamlRuntime(
    val loopDelayMs: Int? = null,
    val maxPendingThoughts: Int? = null,
    val maxPendingActions: Int? = null,
    val maxPendingInputs: Int? = null,
    val searchResultCount: Int? = null,
    val mcpCallTimeoutMs: Long? = null,
    val fetchMaxChars: Int? = null,
)

object AgentRuntimeSettingsLoader {
    private const val DEFAULT_DASHBOARD_PORT: Int = 8787

    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("agent-runtime.yaml"),
    ): AgentRuntimeSettings {
        val defaults = AgentConfig()
        val yaml = YamlConfigSources.loadYamlConfig<AgentRuntimeYamlConfig>(
            mapper = mapper,
            env = env,
            envKey = "NEOPSYKE_AGENT_CONFIG_FILE",
            defaultPath = defaultPath,
            bundledResourceName = "agent-runtime.yaml",
        ) ?: throw IllegalStateException("Missing bundled or external agent-runtime.yaml configuration.")
        validate(yaml)
        val appYaml = yaml.app!!
        val evalYaml = yaml.eval!!
        val agentYaml = yaml.agent!!
        val plannerYaml = agentYaml.planner!!
        val superegoYaml = agentYaml.superego!!
        val memoryYaml = agentYaml.memory!!
        val scratchpadYaml = memoryYaml.scratchpad!!
        val metaReasonerYaml = agentYaml.metaReasoner!!
        val logbookYaml = agentYaml.logbook!!
        val actionControlYaml = agentYaml.actionControl!!
        val connectorsYaml = agentYaml.connectors!!
        val nativeIntegrationsYaml = agentYaml.nativeIntegrations!!
        val telegramYaml = nativeIntegrationsYaml.telegram!!
        val googleWorkspaceYaml = nativeIntegrationsYaml.googleWorkspace!!
        val innerVoiceYaml = agentYaml.innerVoice!!
        val goalsYaml = agentYaml.goals!!
        val runtimeYaml = agentYaml.runtime!!

        val mcpCallTimeoutMs = readPositiveLong(
            env = env["MCP_CALL_TIMEOUT_MS"],
            yaml = runtimeYaml.mcpCallTimeoutMs,
            fallback = defaults.mcpCallTimeoutMs
        )

        val agentConfig = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = readPositiveInt(
                    env["EGO_MAX_LOOP_STEPS"],
                    plannerYaml.maxLoopStepsPerInput,
                    defaults.planner.maxLoopStepsPerInput
                ),
                maxThoughtPasses = readPositiveInt(
                    env["EGO_MAX_THOUGHT_PASSES"],
                    plannerYaml.maxThoughtPasses,
                    defaults.planner.maxThoughtPasses
                ),
                maxThoughtChars = readPositiveInt(
                    env["EGO_MAX_THOUGHT_CHARS"],
                    plannerYaml.maxThoughtChars,
                    defaults.planner.maxThoughtChars
                ),
                maxInputChars = readPositiveInt(
                    env["EGO_MAX_INPUT_CHARS"],
                    plannerYaml.maxInputChars,
                    defaults.planner.maxInputChars
                ),
                maxCompletionTokens = readPositiveInt(
                    env["EGO_MAX_COMPLETION_TOKENS"],
                    plannerYaml.maxCompletionTokens,
                    defaults.planner.maxCompletionTokens
                ),
                maxRunTotalTokens = readNonNegativeInt(
                    env["EGO_MAX_RUN_TOTAL_TOKENS"],
                    plannerYaml.maxRunTotalTokens,
                    defaults.planner.maxRunTotalTokens
                ),
                maxRunTokensPerProvider = readNonNegativeInt(
                    env["EGO_MAX_RUN_TOKENS_PER_PROVIDER"],
                    plannerYaml.maxRunTokensPerProvider,
                    defaults.planner.maxRunTokensPerProvider
                ),
                maxRunTokensPerRole = readNonNegativeInt(
                    env["EGO_MAX_RUN_TOKENS_PER_ROLE"],
                    plannerYaml.maxRunTokensPerRole,
                    defaults.planner.maxRunTokensPerRole
                ),
                maxPlanSteps = readPositiveInt(
                    env["EGO_MAX_PLAN_STEPS"],
                    plannerYaml.maxPlanSteps,
                    defaults.planner.maxPlanSteps
                ),
                maxPlanStepDescriptionChars = readPositiveInt(
                    env["EGO_MAX_PLAN_STEP_DESC_CHARS"],
                    plannerYaml.maxPlanStepDescriptionChars,
                    defaults.planner.maxPlanStepDescriptionChars
                ),
                maxPlansPerInput = readPositiveInt(
                    env["EGO_MAX_PLANS_PER_INPUT"],
                    plannerYaml.maxPlansPerInput,
                    defaults.planner.maxPlansPerInput
                ),
                actionRetryBudgetNonRetryableFailures = readNonNegativeInt(
                    env["EGO_ACTION_RETRY_BUDGET_NON_RETRYABLE_FAILURES"],
                    plannerYaml.actionRetryBudgetNonRetryableFailures,
                    defaults.planner.actionRetryBudgetNonRetryableFailures
                ),
                actionRetryCooldownSteps = readNonNegativeInt(
                    env["EGO_ACTION_RETRY_COOLDOWN_STEPS"],
                    plannerYaml.actionRetryCooldownSteps,
                    defaults.planner.actionRetryCooldownSteps
                ),
                actionVerifierEnabled = readBoolean(
                    env["EGO_ACTION_VERIFIER_ENABLED"],
                    plannerYaml.actionVerifierEnabled,
                    defaults.planner.actionVerifierEnabled
                ),
            ),
            superego = SuperegoConfig(
                maxCompletionTokens = readPositiveInt(
                    env["EGO_SUPEREGO_MAX_COMPLETION_TOKENS"],
                    superegoYaml.maxCompletionTokens,
                    defaults.superego.maxCompletionTokens
                ),
                dynamicCompletionEnabled = readBoolean(
                    env["EGO_SUPEREGO_DYNAMIC_COMPLETION_ENABLED"],
                    superegoYaml.dynamicCompletionEnabled,
                    defaults.superego.dynamicCompletionEnabled
                ),
                dynamicCompletionHardMaxTokens = readPositiveInt(
                    env["EGO_SUPEREGO_DYNAMIC_COMPLETION_HARD_MAX_TOKENS"],
                    superegoYaml.dynamicCompletionHardMaxTokens,
                    defaults.superego.dynamicCompletionHardMaxTokens
                ),
                dynamicPromptToCompletionRatio = readProbability(
                    env["EGO_SUPEREGO_DYNAMIC_PROMPT_TO_COMPLETION_RATIO"],
                    superegoYaml.dynamicPromptToCompletionRatio,
                    defaults.superego.dynamicPromptToCompletionRatio
                ),
                dynamicCompletionMinPromptTokens = readPositiveInt(
                    env["EGO_SUPEREGO_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS"],
                    superegoYaml.dynamicCompletionMinPromptTokens,
                    defaults.superego.dynamicCompletionMinPromptTokens
                ),
                twoStageReviewEnabled = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_REVIEW_ENABLED"],
                    superegoYaml.twoStageReviewEnabled,
                    defaults.superego.twoStageReviewEnabled
                ),
                twoStageLowConfidenceThreshold = readProbability(
                    env["EGO_SUPEREGO_TWO_STAGE_LOW_CONFIDENCE_THRESHOLD"],
                    superegoYaml.twoStageLowConfidenceThreshold,
                    defaults.superego.twoStageLowConfidenceThreshold
                ),
                twoStageEscalateOnMediumPolicyRisk = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_ESCALATE_ON_MEDIUM_POLICY_RISK"],
                    superegoYaml.twoStageEscalateOnMediumPolicyRisk,
                    defaults.superego.twoStageEscalateOnMediumPolicyRisk
                ),
                twoStageSkipForContactUserActions = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_SKIP_FOR_ANSWER_ACTIONS"],
                    superegoYaml.twoStageSkipForContactUserActions,
                    defaults.superego.twoStageSkipForContactUserActions
                ),
                twoStageSkipForWebSearchActions = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_SKIP_FOR_WEB_SEARCH_ACTIONS"],
                    superegoYaml.twoStageSkipForWebSearchActions,
                    defaults.superego.twoStageSkipForWebSearchActions
                ),
            ),
            memory = MemoryConfig(
                maxShortTermContextChars = readPositiveInt(
                    env["EGO_SHORT_TERM_CONTEXT_MAX_CHARS"],
                    memoryYaml.maxShortTermContextChars,
                    defaults.memory.maxShortTermContextChars
                ),
                maxShortTermContextPromptTokens = readPositiveInt(
                    env["EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS"],
                    memoryYaml.maxShortTermContextPromptTokens,
                    defaults.memory.maxShortTermContextPromptTokens
                ),
                scratchpad = ScratchpadConfig(
                    enabled = readBoolean(
                        env["EGO_SCRATCHPAD_ENABLED"],
                        scratchpadYaml.enabled,
                        defaults.memory.scratchpad.enabled
                    ),
                    activationMinPlanSteps = readPositiveInt(
                        env["EGO_SCRATCHPAD_ACTIVATION_MIN_PLAN_STEPS"],
                        scratchpadYaml.activationMinPlanSteps,
                        defaults.memory.scratchpad.activationMinPlanSteps
                    ),
                    maxPromptTokens = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_PROMPT_TOKENS"],
                        scratchpadYaml.maxPromptTokens,
                        defaults.memory.scratchpad.maxPromptTokens
                    ),
                    maxSections = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_SECTIONS"],
                        scratchpadYaml.maxSections,
                        defaults.memory.scratchpad.maxSections
                    ),
                    maxSectionChars = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_SECTION_CHARS"],
                        scratchpadYaml.maxSectionChars,
                        defaults.memory.scratchpad.maxSectionChars
                    ),
                    maxSectionSummaryChars = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_SECTION_SUMMARY_CHARS"],
                        scratchpadYaml.maxSectionSummaryChars,
                        defaults.memory.scratchpad.maxSectionSummaryChars
                    ),
                    maxEvidenceItems = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_EVIDENCE_ITEMS"],
                        scratchpadYaml.maxEvidenceItems,
                        defaults.memory.scratchpad.maxEvidenceItems
                    ),
                    maxEvidenceChars = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_EVIDENCE_CHARS"],
                        scratchpadYaml.maxEvidenceChars,
                        defaults.memory.scratchpad.maxEvidenceChars
                    ),
                    finalCompilationMaxChars = readPositiveInt(
                        env["EGO_SCRATCHPAD_FINAL_COMPILATION_MAX_CHARS"],
                        scratchpadYaml.finalCompilationMaxChars,
                        defaults.memory.scratchpad.finalCompilationMaxChars
                    ),
                    finalPassRewriteEnabled = readBoolean(
                        env["EGO_SCRATCHPAD_FINAL_PASS_REWRITE_ENABLED"],
                        scratchpadYaml.finalPassRewriteEnabled,
                        defaults.memory.scratchpad.finalPassRewriteEnabled
                    ),
                    finalPassMaxTokens = readPositiveInt(
                        env["EGO_SCRATCHPAD_FINAL_PASS_MAX_TOKENS"],
                        scratchpadYaml.finalPassMaxTokens,
                        defaults.memory.scratchpad.finalPassMaxTokens
                    ),
                    finalPassMinWorkspaceConfidence = readProbability(
                        env["EGO_SCRATCHPAD_FINAL_PASS_MIN_WORKSPACE_CONFIDENCE"],
                        scratchpadYaml.finalPassMinWorkspaceConfidence,
                        defaults.memory.scratchpad.finalPassMinWorkspaceConfidence
                    ),
                    finalPassMinModelConfidence = readProbability(
                        env["EGO_SCRATCHPAD_FINAL_PASS_MIN_MODEL_CONFIDENCE"],
                        scratchpadYaml.finalPassMinModelConfidence,
                        defaults.memory.scratchpad.finalPassMinModelConfidence
                    ),
                    debugCaptureEnabled = readBoolean(
                        env["EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED"],
                        scratchpadYaml.debugCaptureEnabled,
                        defaults.memory.scratchpad.debugCaptureEnabled
                    ),
                    maxActiveTasks = readPositiveInt(
                        env["EGO_SCRATCHPAD_MAX_ACTIVE_TASKS"],
                        scratchpadYaml.maxActiveTasks,
                        defaults.memory.scratchpad.maxActiveTasks
                    ),
                    digestMaxEntries = readPositiveInt(
                        env["EGO_SCRATCHPAD_DIGEST_MAX_ENTRIES"],
                        scratchpadYaml.digestMaxEntries,
                        defaults.memory.scratchpad.digestMaxEntries
                    ),
                    digestMaxChars = readPositiveInt(
                        env["EGO_SCRATCHPAD_DIGEST_MAX_CHARS"],
                        scratchpadYaml.digestMaxChars,
                        defaults.memory.scratchpad.digestMaxChars
                    ),
                    digestMaxPromptTokens = readPositiveInt(
                        env["EGO_SCRATCHPAD_DIGEST_MAX_PROMPT_TOKENS"],
                        scratchpadYaml.digestMaxPromptTokens,
                        defaults.memory.scratchpad.digestMaxPromptTokens
                    ),
                ),
                longTermMemoryRecallMaxItems = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS"],
                    memoryYaml.longTermMemoryRecallMaxItems,
                    defaults.memory.longTermMemoryRecallMaxItems
                ),
                longTermMemoryRecallMaxChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS"],
                    memoryYaml.longTermMemoryRecallMaxChars,
                    defaults.memory.longTermMemoryRecallMaxChars
                ),
                longTermMemoryPromptCompressionEnabled = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_PROMPT_COMPRESSION_ENABLED"],
                    memoryYaml.longTermMemoryPromptCompressionEnabled,
                    defaults.memory.longTermMemoryPromptCompressionEnabled
                ),
                longTermMemoryPromptDialogueMaxChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_PROMPT_DIALOGUE_MAX_CHARS"],
                    memoryYaml.longTermMemoryPromptDialogueMaxChars,
                    defaults.memory.longTermMemoryPromptDialogueMaxChars
                ),
                longTermMemoryPromptRecallMaxChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_PROMPT_RECALL_MAX_CHARS"],
                    memoryYaml.longTermMemoryPromptRecallMaxChars,
                    defaults.memory.longTermMemoryPromptRecallMaxChars
                ),
                longTermMemoryAssessEverySteps = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS"],
                    memoryYaml.longTermMemoryAssessEverySteps,
                    defaults.memory.longTermMemoryAssessEverySteps
                ),
                longTermMemoryAssessCooldownSteps = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS"],
                    memoryYaml.longTermMemoryAssessCooldownSteps,
                    defaults.memory.longTermMemoryAssessCooldownSteps
                ),
                longTermMemoryMinConfidence = readProbability(
                    env["EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE"],
                    memoryYaml.longTermMemoryMinConfidence,
                    defaults.memory.longTermMemoryMinConfidence
                ),
                longTermMemoryMaxTokens = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_MAX_TOKENS"],
                    memoryYaml.longTermMemoryMaxTokens,
                    defaults.memory.longTermMemoryMaxTokens
                ),
                longTermMemoryDynamicCompletionEnabled = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_ENABLED"],
                    memoryYaml.longTermMemoryDynamicCompletionEnabled,
                    defaults.memory.longTermMemoryDynamicCompletionEnabled
                ),
                longTermMemoryDynamicCompletionHardMaxTokens = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_HARD_MAX_TOKENS"],
                    memoryYaml.longTermMemoryDynamicCompletionHardMaxTokens,
                    defaults.memory.longTermMemoryDynamicCompletionHardMaxTokens
                ),
                longTermMemoryDynamicPromptToCompletionRatio = readProbability(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_PROMPT_TO_COMPLETION_RATIO"],
                    memoryYaml.longTermMemoryDynamicPromptToCompletionRatio,
                    defaults.memory.longTermMemoryDynamicPromptToCompletionRatio
                ),
                longTermMemoryDynamicCompletionMinPromptTokens = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS"],
                    memoryYaml.longTermMemoryDynamicCompletionMinPromptTokens,
                    defaults.memory.longTermMemoryDynamicCompletionMinPromptTokens
                ),
                longTermMemoryMaxSummaryChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS"],
                    memoryYaml.longTermMemoryMaxSummaryChars,
                    defaults.memory.longTermMemoryMaxSummaryChars
                ),
                longTermMemoryForceAssessOnAllowedAction = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION"],
                    memoryYaml.longTermMemoryForceAssessOnAllowedAction,
                    defaults.memory.longTermMemoryForceAssessOnAllowedAction
                ),
                longTermMemoryForceAssessOnTerminalAnswer = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER"],
                    memoryYaml.longTermMemoryForceAssessOnTerminalAnswer,
                    defaults.memory.longTermMemoryForceAssessOnTerminalAnswer
                ),
                longTermMemoryParseFallbackDisableAfter = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER"],
                    memoryYaml.longTermMemoryParseFallbackDisableAfter,
                    defaults.memory.longTermMemoryParseFallbackDisableAfter
                ),
                longTermMemoryRecallEchoMinSummaryChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS"],
                    memoryYaml.longTermMemoryRecallEchoMinSummaryChars,
                    defaults.memory.longTermMemoryRecallEchoMinSummaryChars
                ),
                longTermMemoryRecallEchoMinTokenLength = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH"],
                    memoryYaml.longTermMemoryRecallEchoMinTokenLength,
                    defaults.memory.longTermMemoryRecallEchoMinTokenLength
                ),
                longTermMemoryRecallEchoMinTokenCount = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT"],
                    memoryYaml.longTermMemoryRecallEchoMinTokenCount,
                    defaults.memory.longTermMemoryRecallEchoMinTokenCount
                ),
                longTermMemoryRecallEchoTokenOverlapThreshold = readProbability(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD"],
                    memoryYaml.longTermMemoryRecallEchoTokenOverlapThreshold,
                    defaults.memory.longTermMemoryRecallEchoTokenOverlapThreshold
                ),
                mcpMemoryCallTimeoutMs = readPositiveLong(
                    env = env["MCP_MEMORY_CALL_TIMEOUT_MS"],
                    yaml = memoryYaml.mcpMemoryCallTimeoutMs,
                    fallback = mcpCallTimeoutMs
                ),
            ),
            metaReasoner = MetaReasonerConfig(
                deliberationPressureAssessmentMinStep = readPositiveInt(
                    env["EGO_PRESSURE_MIN_STEP"],
                    metaReasonerYaml.deliberationPressureAssessmentMinStep,
                    defaults.metaReasoner.deliberationPressureAssessmentMinStep
                ),
                deliberationPressureAssessmentEverySteps = readPositiveInt(
                    env["EGO_PRESSURE_ASSESS_EVERY_STEPS"],
                    metaReasonerYaml.deliberationPressureAssessmentEverySteps,
                    defaults.metaReasoner.deliberationPressureAssessmentEverySteps
                ),
                deliberationPressureAssessmentThreshold = readProbability(
                    env["EGO_PRESSURE_ASSESS_THRESHOLD"],
                    metaReasonerYaml.deliberationPressureAssessmentThreshold,
                    defaults.metaReasoner.deliberationPressureAssessmentThreshold
                ),
                cooldownSteps = readPositiveInt(
                    env["EGO_META_REASONER_COOLDOWN_STEPS"],
                    metaReasonerYaml.cooldownSteps,
                    defaults.metaReasoner.cooldownSteps
                ),
                maxTokens = readPositiveInt(
                    env["EGO_META_REASONER_MAX_TOKENS"],
                    metaReasonerYaml.maxTokens,
                    defaults.metaReasoner.maxTokens
                ),
                dynamicCompletionEnabled = readBoolean(
                    env["EGO_META_REASONER_DYNAMIC_COMPLETION_ENABLED"],
                    metaReasonerYaml.dynamicCompletionEnabled,
                    defaults.metaReasoner.dynamicCompletionEnabled
                ),
                dynamicCompletionHardMaxTokens = readPositiveInt(
                    env["EGO_META_REASONER_DYNAMIC_COMPLETION_HARD_MAX_TOKENS"],
                    metaReasonerYaml.dynamicCompletionHardMaxTokens,
                    defaults.metaReasoner.dynamicCompletionHardMaxTokens
                ),
                dynamicPromptToCompletionRatio = readProbability(
                    env["EGO_META_REASONER_DYNAMIC_PROMPT_TO_COMPLETION_RATIO"],
                    metaReasonerYaml.dynamicPromptToCompletionRatio,
                    defaults.metaReasoner.dynamicPromptToCompletionRatio
                ),
                dynamicCompletionMinPromptTokens = readPositiveInt(
                    env["EGO_META_REASONER_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS"],
                    metaReasonerYaml.dynamicCompletionMinPromptTokens,
                    defaults.metaReasoner.dynamicCompletionMinPromptTokens
                ),
                forcedTerminalPressureThreshold = readProbability(
                    env["EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD"],
                    metaReasonerYaml.forcedTerminalPressureThreshold,
                    defaults.metaReasoner.forcedTerminalPressureThreshold
                ),
                forcedTerminalStaleStreakThreshold = readPositiveInt(
                    env["EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD"],
                    metaReasonerYaml.forcedTerminalStaleStreakThreshold,
                    defaults.metaReasoner.forcedTerminalStaleStreakThreshold
                ),
            ),
            logbook = LogbookConfig(
                enabled = readBoolean(
                    env["NEOPSYKE_LOGBOOK_ENABLED"],
                    logbookYaml.enabled,
                    defaults.logbook.enabled
                ),
                maxSummaryChars = readPositiveInt(
                    env["NEOPSYKE_LOGBOOK_MAX_SUMMARY_CHARS"],
                    logbookYaml.maxSummaryChars,
                    defaults.logbook.maxSummaryChars
                ),
                maxKeywordsPerEntry = readPositiveInt(
                    env["NEOPSYKE_LOGBOOK_MAX_KEYWORDS"],
                    logbookYaml.maxKeywordsPerEntry,
                    defaults.logbook.maxKeywordsPerEntry
                ),
                maxEntriesPerQuery = readPositiveInt(
                    env["NEOPSYKE_LOGBOOK_MAX_ENTRIES_PER_QUERY"],
                    logbookYaml.maxEntriesPerQuery,
                    defaults.logbook.maxEntriesPerQuery
                ),
                retentionDays = readPositiveInt(
                    env["NEOPSYKE_LOGBOOK_RETENTION_DAYS"],
                    logbookYaml.retentionDays,
                    defaults.logbook.retentionDays
                ),
                dbPath = readNonBlank(
                    env["NEOPSYKE_LOGBOOK_DB_PATH"],
                    logbookYaml.dbPath,
                    defaults.logbook.dbPath
                ),
                episodicRecallMaxChars = readPositiveInt(
                    env["NEOPSYKE_LOGBOOK_EPISODIC_RECALL_MAX_CHARS"],
                    logbookYaml.episodicRecallMaxChars,
                    defaults.logbook.episodicRecallMaxChars
                ),
                episodicRecallMaxResults = readPositiveInt(
                    env["NEOPSYKE_LOGBOOK_EPISODIC_RECALL_MAX_RESULTS"],
                    logbookYaml.episodicRecallMaxResults,
                    defaults.logbook.episodicRecallMaxResults
                ),
                useLlmSummarizer = readBoolean(
                    env["NEOPSYKE_LOGBOOK_USE_LLM_SUMMARIZER"],
                    logbookYaml.useLlmSummarizer,
                    defaults.logbook.useLlmSummarizer
                )
            ),
            actionControl = ActionControlConfig(
                enabled = readBoolean(
                    env["NEOPSYKE_ACTION_CONTROL_ENABLED"],
                    actionControlYaml.enabled,
                    defaults.actionControl.enabled
                ),
                dbPath = readNonBlank(
                    env["NEOPSYKE_ACTION_CONTROL_DB_PATH"],
                    actionControlYaml.dbPath,
                    defaults.actionControl.dbPath
                ),
                policyPath = readNonBlank(
                    env["NEOPSYKE_ACTION_SECURITY_POLICY_FILE"],
                    actionControlYaml.policyPath,
                    defaults.actionControl.policyPath
                ),
                authorizationTtlMs = readPositiveLong(
                    env["NEOPSYKE_ACTION_CONTROL_AUTH_TTL_MS"],
                    actionControlYaml.authorizationTtlMs,
                    defaults.actionControl.authorizationTtlMs
                ),
                maxInspectResults = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_MAX_INSPECT_RESULTS"],
                    actionControlYaml.maxInspectResults,
                    defaults.actionControl.maxInspectResults
                ),
                autonomousWorkerEnabled = readBoolean(
                    env["NEOPSYKE_ACTION_CONTROL_AUTONOMOUS_WORKER_ENABLED"],
                    actionControlYaml.autonomousWorkerEnabled,
                    defaults.actionControl.autonomousWorkerEnabled
                ),
                autonomousWorkerPollMs = readPositiveLong(
                    env["NEOPSYKE_ACTION_CONTROL_AUTONOMOUS_WORKER_POLL_MS"],
                    actionControlYaml.autonomousWorkerPollMs,
                    defaults.actionControl.autonomousWorkerPollMs
                ),
                autonomousWorkerBatchSize = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_AUTONOMOUS_WORKER_BATCH_SIZE"],
                    actionControlYaml.autonomousWorkerBatchSize,
                    defaults.actionControl.autonomousWorkerBatchSize
                ),
                observePerTypePerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_OBSERVE_PER_TYPE_PER_ROOT_INPUT"],
                    actionControlYaml.observePerTypePerRootInput,
                    defaults.actionControl.observePerTypePerRootInput
                ),
                contactUserPerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_CONTACT_USER_PER_ROOT_INPUT"],
                    actionControlYaml.contactUserPerRootInput,
                    defaults.actionControl.contactUserPerRootInput
                ),
                reflectionFamilyPerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_REFLECTION_FAMILY_PER_ROOT_INPUT"],
                    actionControlYaml.reflectionFamilyPerRootInput,
                    defaults.actionControl.reflectionFamilyPerRootInput
                ),
                reflectEvidencePerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_REFLECT_EVIDENCE_PER_ROOT_INPUT"],
                    actionControlYaml.reflectEvidencePerRootInput,
                    defaults.actionControl.reflectEvidencePerRootInput
                ),
                goalOperationPerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_GOAL_OPERATION_PER_ROOT_INPUT"],
                    actionControlYaml.goalOperationPerRootInput,
                    defaults.actionControl.goalOperationPerRootInput
                ),
                commitPrivatePerTypePerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_COMMIT_PRIVATE_PER_TYPE_PER_ROOT_INPUT"],
                    actionControlYaml.commitPrivatePerTypePerRootInput,
                    defaults.actionControl.commitPrivatePerTypePerRootInput
                ),
                commitStatefulPerTypePerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_COMMIT_STATEFUL_PER_TYPE_PER_ROOT_INPUT"],
                    actionControlYaml.commitStatefulPerTypePerRootInput,
                    defaults.actionControl.commitStatefulPerTypePerRootInput
                ),
                commitPublicPerTypePerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_COMMIT_PUBLIC_PER_TYPE_PER_ROOT_INPUT"],
                    actionControlYaml.commitPublicPerTypePerRootInput,
                    defaults.actionControl.commitPublicPerTypePerRootInput
                ),
                controlPlanePerTypePerRootInput = readPositiveInt(
                    env["NEOPSYKE_ACTION_CONTROL_CONTROL_PLANE_PER_TYPE_PER_ROOT_INPUT"],
                    actionControlYaml.controlPlanePerTypePerRootInput,
                    defaults.actionControl.controlPlanePerTypePerRootInput
                ),
            ),
            connectors = ConnectorRuntimeConfig(
                enabled = readBoolean(
                    env["NEOPSYKE_CONNECTORS_ENABLED"],
                    connectorsYaml.enabled,
                    defaults.connectors.enabled
                ),
                curatedCatalogPath = readNonBlank(
                    env["NEOPSYKE_CONNECTORS_CATALOG_PATH"],
                    connectorsYaml.curatedCatalogPath,
                    defaults.connectors.curatedCatalogPath
                ),
                installStateDir = readNonBlank(
                    env["NEOPSYKE_CONNECTORS_STATE_DIR"],
                    connectorsYaml.installStateDir,
                    defaults.connectors.installStateDir
                ),
                failClosed = readBoolean(
                    env["NEOPSYKE_CONNECTORS_FAIL_CLOSED"],
                    connectorsYaml.failClosed,
                    defaults.connectors.failClosed
                ),
                pinningEnabled = readBoolean(
                    env["NEOPSYKE_CONNECTORS_PINNING_ENABLED"],
                    connectorsYaml.pinningEnabled,
                    defaults.connectors.pinningEnabled
                ),
                startupTimeoutMs = readPositiveLong(
                    env["NEOPSYKE_CONNECTORS_STARTUP_TIMEOUT_MS"],
                    connectorsYaml.startupTimeoutMs,
                    defaults.connectors.startupTimeoutMs
                ),
                healthTimeoutMs = readPositiveLong(
                    env["NEOPSYKE_CONNECTORS_HEALTH_TIMEOUT_MS"],
                    connectorsYaml.healthTimeoutMs,
                    defaults.connectors.healthTimeoutMs
                ),
                allowedConnectorIds = readStringSet(
                    env["NEOPSYKE_CONNECTORS_ALLOWED_IDS"],
                    connectorsYaml.allowedConnectorIds,
                    defaults.connectors.allowedConnectorIds
                ),
                enabledBundleIds = readStringSet(
                    env["NEOPSYKE_CONNECTORS_ENABLED_BUNDLES"],
                    connectorsYaml.enabledBundleIds,
                    defaults.connectors.enabledBundleIds
                ),
                allowThirdPartyConnectors = readBoolean(
                    env["NEOPSYKE_CONNECTORS_ALLOW_THIRD_PARTY"],
                    connectorsYaml.allowThirdPartyConnectors,
                    defaults.connectors.allowThirdPartyConnectors
                ),
            ),
            nativeIntegrations = NativeIntegrationsConfig(
                telegram = TelegramChannelConfig(
                    enabled = readBoolean(
                        env["NEOPSYKE_TELEGRAM_ENABLED"],
                        telegramYaml.enabled,
                        defaults.nativeIntegrations.telegram.enabled
                    ),
                    mode = readTelegramIngressMode(
                        env["NEOPSYKE_TELEGRAM_MODE"],
                        telegramYaml.mode,
                        defaults.nativeIntegrations.telegram.mode
                    ),
                    webhookPath = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_WEBHOOK_PATH"],
                        telegramYaml.webhookPath,
                        defaults.nativeIntegrations.telegram.webhookPath
                    ),
                    ownerChatId = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_OWNER_CHAT_ID"],
                        telegramYaml.ownerChatId,
                        defaults.nativeIntegrations.telegram.ownerChatId
                    ),
                    ownerUserId = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_OWNER_USER_ID"],
                        telegramYaml.ownerUserId,
                        defaults.nativeIntegrations.telegram.ownerUserId
                    ),
                    botTokenHandle = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_BOT_TOKEN_HANDLE"],
                        telegramYaml.botTokenHandle,
                        defaults.nativeIntegrations.telegram.botTokenHandle
                    ),
                    webhookSecretHandle = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_WEBHOOK_SECRET_HANDLE"],
                        telegramYaml.webhookSecretHandle,
                        defaults.nativeIntegrations.telegram.webhookSecretHandle
                    ),
                    policyScopeId = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_POLICY_SCOPE_ID"],
                        telegramYaml.policyScopeId,
                        defaults.nativeIntegrations.telegram.policyScopeId
                    ),
                    sessionIdPrefix = readNonBlank(
                        env["NEOPSYKE_TELEGRAM_SESSION_ID_PREFIX"],
                        telegramYaml.sessionIdPrefix,
                        defaults.nativeIntegrations.telegram.sessionIdPrefix
                    ),
                    requireDirectChat = readBoolean(
                        env["NEOPSYKE_TELEGRAM_REQUIRE_DIRECT_CHAT"],
                        telegramYaml.requireDirectChat,
                        defaults.nativeIntegrations.telegram.requireDirectChat
                    ),
                    dropUnauthorizedMessages = readBoolean(
                        env["NEOPSYKE_TELEGRAM_DROP_UNAUTHORIZED_MESSAGES"],
                        telegramYaml.dropUnauthorizedMessages,
                        defaults.nativeIntegrations.telegram.dropUnauthorizedMessages
                    ),
                    pollTimeoutSeconds = readPositiveInt(
                        env["NEOPSYKE_TELEGRAM_POLL_TIMEOUT_SECONDS"],
                        telegramYaml.pollTimeoutSeconds,
                        defaults.nativeIntegrations.telegram.pollTimeoutSeconds
                    ),
                    pollRetryDelayMs = readPositiveLong(
                        env["NEOPSYKE_TELEGRAM_POLL_RETRY_DELAY_MS"],
                        telegramYaml.pollRetryDelayMs,
                        defaults.nativeIntegrations.telegram.pollRetryDelayMs
                    ),
                ),
                googleWorkspace = GoogleWorkspaceConfig(
                    enabled = readBoolean(
                        env["NEOPSYKE_GOOGLE_WORKSPACE_ENABLED"],
                        googleWorkspaceYaml.enabled,
                        defaults.nativeIntegrations.googleWorkspace.enabled
                    ),
                    tokenStoreDir = readNonBlank(
                        env["NEOPSYKE_GOOGLE_TOKEN_STORE_DIR"],
                        googleWorkspaceYaml.tokenStoreDir,
                        defaults.nativeIntegrations.googleWorkspace.tokenStoreDir
                    ),
                    allowedOwnerEmail = readNonBlank(
                        env["NEOPSYKE_GOOGLE_ALLOWED_OWNER_EMAIL"],
                        googleWorkspaceYaml.allowedOwnerEmail,
                        defaults.nativeIntegrations.googleWorkspace.allowedOwnerEmail
                    ),
                    publicBaseUrl = readNonBlank(
                        env["NEOPSYKE_GOOGLE_PUBLIC_BASE_URL"],
                        googleWorkspaceYaml.publicBaseUrl,
                        defaults.nativeIntegrations.googleWorkspace.publicBaseUrl
                    ),
                    oauthStartPath = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_START_PATH"],
                        googleWorkspaceYaml.oauthStartPath,
                        defaults.nativeIntegrations.googleWorkspace.oauthStartPath
                    ),
                    oauthClientIdHandle = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_CLIENT_ID_HANDLE"],
                        googleWorkspaceYaml.oauthClientIdHandle,
                        defaults.nativeIntegrations.googleWorkspace.oauthClientIdHandle
                    ),
                    oauthClientSecretHandle = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_CLIENT_SECRET_HANDLE"],
                        googleWorkspaceYaml.oauthClientSecretHandle,
                        defaults.nativeIntegrations.googleWorkspace.oauthClientSecretHandle
                    ),
                    oauthStateSigningSecretHandle = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_STATE_SIGNING_SECRET_HANDLE"],
                        googleWorkspaceYaml.oauthStateSigningSecretHandle,
                        defaults.nativeIntegrations.googleWorkspace.oauthStateSigningSecretHandle
                    ),
                    oauthTokenEncryptionSecretHandle = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET_HANDLE"],
                        googleWorkspaceYaml.oauthTokenEncryptionSecretHandle,
                        defaults.nativeIntegrations.googleWorkspace.oauthTokenEncryptionSecretHandle
                    ),
                    callbackPath = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_CALLBACK_PATH"],
                        googleWorkspaceYaml.callbackPath,
                        defaults.nativeIntegrations.googleWorkspace.callbackPath
                    ),
                    authorizationBaseUrl = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_AUTH_BASE_URL"],
                        googleWorkspaceYaml.authorizationBaseUrl,
                        defaults.nativeIntegrations.googleWorkspace.authorizationBaseUrl
                    ),
                    tokenBaseUrl = readNonBlank(
                        env["NEOPSYKE_GOOGLE_OAUTH_TOKEN_BASE_URL"],
                        googleWorkspaceYaml.tokenBaseUrl,
                        defaults.nativeIntegrations.googleWorkspace.tokenBaseUrl
                    ),
                    requirePkce = readBoolean(
                        env["NEOPSYKE_GOOGLE_OAUTH_REQUIRE_PKCE"],
                        googleWorkspaceYaml.requirePkce,
                        defaults.nativeIntegrations.googleWorkspace.requirePkce
                    ),
                    requireRefreshToken = readBoolean(
                        env["NEOPSYKE_GOOGLE_OAUTH_REQUIRE_REFRESH_TOKEN"],
                        googleWorkspaceYaml.requireRefreshToken,
                        defaults.nativeIntegrations.googleWorkspace.requireRefreshToken
                    ),
                    oauthStateTtlSeconds = readPositiveLong(
                        env["NEOPSYKE_GOOGLE_OAUTH_STATE_TTL_SECONDS"],
                        googleWorkspaceYaml.oauthStateTtlSeconds,
                        defaults.nativeIntegrations.googleWorkspace.oauthStateTtlSeconds
                    ),
                    scopes = readStringSet(
                        env["NEOPSYKE_GOOGLE_SCOPES"],
                        googleWorkspaceYaml.scopes,
                        defaults.nativeIntegrations.googleWorkspace.scopes
                    ),
                ),
            ),
            innerVoice = InnerVoiceConfig(
                enabled = readBoolean(
                    env["NEOPSYKE_INNER_VOICE_ENABLED"],
                    innerVoiceYaml.enabled,
                    defaults.innerVoice.enabled
                ),
                maxContentChars = readPositiveInt(
                    env["NEOPSYKE_INNER_VOICE_MAX_CONTENT_CHARS"],
                    innerVoiceYaml.maxContentChars,
                    defaults.innerVoice.maxContentChars
                ),
                maxEventsPerSession = readPositiveInt(
                    env["NEOPSYKE_INNER_VOICE_MAX_EVENTS_PER_SESSION"],
                    innerVoiceYaml.maxEventsPerSession,
                    defaults.innerVoice.maxEventsPerSession
                ),
            ),
            goals = GoalConfig(
                enabled = readBoolean(
                    env["NEOPSYKE_GOALS_ENABLED"],
                    yaml = goalsYaml.enabled,
                    fallback = defaults.goals.enabled
                ),
                workspaceRoot = readPath(
                    env["NEOPSYKE_GOALS_WORKSPACE_ROOT"],
                    yaml = goalsYaml.workspaceRoot,
                    fallback = defaults.goals.workspaceRoot
                ),
                maxActiveGoals = readPositiveInt(
                    env["NEOPSYKE_GOALS_MAX_ACTIVE_GOALS"],
                    yaml = goalsYaml.maxActiveGoals,
                    fallback = defaults.goals.maxActiveGoals
                ),
                maxStepsPerPlan = readPositiveInt(
                    env["NEOPSYKE_GOALS_MAX_STEPS_PER_PLAN"],
                    yaml = goalsYaml.maxStepsPerPlan,
                    fallback = defaults.goals.maxStepsPerPlan
                ),
                actionsPerCycle = readPositiveInt(
                    env["NEOPSYKE_GOALS_ACTIONS_PER_CYCLE"],
                    yaml = goalsYaml.actionsPerCycle,
                    fallback = defaults.goals.actionsPerCycle
                ),
                snapshotEveryNEvents = readPositiveInt(
                    env["NEOPSYKE_GOALS_SNAPSHOT_EVERY_N_EVENTS"],
                    yaml = goalsYaml.snapshotEveryNEvents,
                    fallback = defaults.goals.snapshotEveryNEvents
                ),
                timerResolutionMs = readPositiveLong(
                    env["NEOPSYKE_GOALS_TIMER_RESOLUTION_MS"],
                    yaml = goalsYaml.timerResolutionMs,
                    fallback = defaults.goals.timerResolutionMs
                ),
                conditionCheckIntervalMs = readPositiveLong(
                    env["NEOPSYKE_GOALS_CONDITION_CHECK_INTERVAL_MS"],
                    yaml = goalsYaml.conditionCheckIntervalMs,
                    fallback = defaults.goals.conditionCheckIntervalMs
                ),
                completedGoalRetentionDays = readPositiveInt(
                    env["NEOPSYKE_GOALS_COMPLETED_RETENTION_DAYS"],
                    yaml = goalsYaml.completedGoalRetentionDays,
                    fallback = defaults.goals.completedGoalRetentionDays
                ),
                maxWorkspaceBytes = readPositiveLong(
                    env["NEOPSYKE_GOALS_MAX_WORKSPACE_BYTES"],
                    yaml = goalsYaml.maxWorkspaceBytes,
                    fallback = defaults.goals.maxWorkspaceBytes
                ),
            ),
            loopDelayMs = readNonNegativeInt(
                env["EGO_LOOP_DELAY_MS"],
                runtimeYaml.loopDelayMs,
                defaults.loopDelayMs
            ),
            maxPendingThoughts = readPositiveInt(
                env["EGO_MAX_PENDING_THOUGHTS"],
                runtimeYaml.maxPendingThoughts,
                defaults.maxPendingThoughts
            ),
            maxPendingActions = readPositiveInt(
                env["EGO_MAX_PENDING_ACTIONS"],
                runtimeYaml.maxPendingActions,
                defaults.maxPendingActions
            ),
            maxPendingInputs = readPositiveInt(
                env["EGO_MAX_PENDING_INPUTS"],
                runtimeYaml.maxPendingInputs,
                defaults.maxPendingInputs
            ),
            searchResultCount = readPositiveInt(
                env["EGO_SEARCH_RESULT_COUNT"],
                runtimeYaml.searchResultCount,
                defaults.searchResultCount
            ),
            mcpCallTimeoutMs = mcpCallTimeoutMs,
            fetchMaxChars = readPositiveInt(
                env["WEBSITE_FETCH_MAX_CHARS"],
                runtimeYaml.fetchMaxChars,
                defaults.fetchMaxChars
            ),
            maxActionPayloadChars = readPositiveInt(
                env["EGO_MAX_ACTION_PAYLOAD_CHARS"],
                plannerYaml.maxActionPayloadChars,
                defaults.maxActionPayloadChars
            ),
            maxActionSummaryChars = readPositiveInt(
                env["EGO_MAX_ACTION_SUMMARY_CHARS"],
                plannerYaml.maxActionSummaryChars,
                defaults.maxActionSummaryChars
            ),
            maxLlmPromptTokens = readPositiveInt(
                env["EGO_MAX_PROMPT_TOKENS"],
                plannerYaml.maxPromptTokens,
                defaults.maxLlmPromptTokens
            ),
            llmRetryAttempts = readPositiveInt(
                env["EGO_LLM_RETRY_ATTEMPTS"],
                plannerYaml.llmRetryAttempts,
                defaults.llmRetryAttempts
            ),
        )

        return AgentRuntimeSettings(
            agentConfig = agentConfig,
            dashboardEnabled = readBoolean(
                env = env["NEOPSYKE_DASHBOARD_ENABLED"],
                yaml = appYaml.dashboardEnabled,
                fallback = true
            ),
            dashboardPort = readPositiveInt(
                env = env["NEOPSYKE_DASHBOARD_PORT"],
                yaml = appYaml.dashboardPort,
                fallback = DEFAULT_DASHBOARD_PORT
            ),
            evalMaxRawResponseChars = readPositiveInt(
                env = env["NEOPSYKE_EVAL_MAX_RAW_RESPONSE_CHARS"],
                yaml = evalYaml.maxRawResponseChars,
                fallback = Int.MAX_VALUE
            ),
            evalDefaultStage = firstNonBlank(env["NEOPSYKE_EVAL_STAGE"], evalYaml.defaultStage)
        )
    }

    private fun validate(yaml: AgentRuntimeYamlConfig) {
        requireSection(yaml.app, "app")
        requireSection(yaml.eval, "eval")
        val agent = yaml.agent ?: throw IllegalStateException("agent-runtime.yaml is missing required section: agent")
        requireSection(agent.planner, "agent.planner")
        requireSection(agent.superego, "agent.superego")
        val memory = agent.memory ?: throw IllegalStateException("agent-runtime.yaml is missing required section: agent.memory")
        requireSection(memory.scratchpad, "agent.memory.scratchpad")
        requireSection(agent.metaReasoner, "agent.meta_reasoner")
        requireSection(agent.logbook, "agent.logbook")
        requireSection(agent.actionControl, "agent.action_control")
        requireSection(agent.connectors, "agent.connectors")
        val nativeIntegrations = agent.nativeIntegrations
            ?: throw IllegalStateException("agent-runtime.yaml is missing required section: agent.native_integrations")
        requireSection(nativeIntegrations.telegram, "agent.native_integrations.telegram")
        requireSection(nativeIntegrations.googleWorkspace, "agent.native_integrations.google_workspace")
        requireSection(agent.innerVoice, "agent.inner_voice")
        requireSection(agent.goals, "agent.goals")
        requireSection(agent.runtime, "agent.runtime")
    }

    private fun requireSection(value: Any?, path: String) {
        if (value == null) {
            throw IllegalStateException("agent-runtime.yaml is missing required section: $path")
        }
    }

    private fun readPositiveInt(env: String?, yaml: Int?, fallback: Int): Int =
        env?.toIntOrNull()?.takeIf { it > 0 } ?: yaml?.takeIf { it > 0 } ?: fallback

    private fun readNonNegativeInt(env: String?, yaml: Int?, fallback: Int): Int =
        env?.toIntOrNull()?.takeIf { it >= 0 } ?: yaml?.takeIf { it >= 0 } ?: fallback

    private fun readPositiveLong(env: String?, yaml: Long?, fallback: Long): Long =
        env?.toLongOrNull()?.takeIf { it > 0 } ?: yaml?.takeIf { it > 0 } ?: fallback

    private fun readProbability(env: String?, yaml: Double?, fallback: Double): Double =
        env?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: yaml?.takeIf { it in 0.0..1.0 } ?: fallback

    private fun readBoolean(env: String?, yaml: Boolean?, fallback: Boolean): Boolean =
        parseBoolean(env) ?: yaml ?: fallback

    private fun readPath(env: String?, yaml: String?, fallback: Path): Path {
        val configured = firstNonBlank(env, yaml) ?: return fallback
        return try {
            Paths.get(configured)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun readNonBlank(env: String?, yaml: String?, fallback: String): String =
        firstNonBlank(env, yaml) ?: fallback

    private fun readStringSet(env: String?, yaml: List<String>?, fallback: Set<String>): Set<String> {
        val envValues = env
            ?.split(',')
            ?.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
            ?.toSet()
        if (!envValues.isNullOrEmpty()) {
            return envValues
        }
        val yamlValues = yaml
            ?.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
            ?.toSet()
        return if (!yamlValues.isNullOrEmpty()) yamlValues else fallback
    }

    private fun parseBoolean(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

    private fun readTelegramIngressMode(
        env: String?,
        yaml: String?,
        fallback: TelegramIngressMode,
    ): TelegramIngressMode {
        val raw = firstNonBlank(env, yaml) ?: return fallback
        return when (raw.trim().lowercase()) {
            "webhook" -> TelegramIngressMode.WEBHOOK
            "polling" -> TelegramIngressMode.POLLING
            else -> fallback
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
