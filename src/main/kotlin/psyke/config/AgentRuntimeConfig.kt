package psyke.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.agent.core.AgentConfig
import psyke.agent.core.MemoryConfig
import psyke.agent.core.MetaReasonerConfig
import psyke.agent.core.PlannerConfig
import psyke.agent.core.SuperegoConfig
import java.nio.file.Files
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
    val app: AgentRuntimeYamlApp? = AgentRuntimeYamlApp(),
    val eval: AgentRuntimeYamlEval? = AgentRuntimeYamlEval(),
    val agent: AgentRuntimeYamlAgent? = AgentRuntimeYamlAgent(),
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
    val maxLoopStepsPerInput: Int? = null,
    val loopDelayMs: Int? = null,
    val maxThoughtPasses: Int? = null,
    val maxPendingThoughts: Int? = null,
    val maxPendingActions: Int? = null,
    val maxPendingInputs: Int? = null,
    val maxInputChars: Int? = null,
    val maxShortTermContextChars: Int? = null,
    val maxShortTermContextPromptTokens: Int? = null,
    val taskWorkspaceEnabled: Boolean? = null,
    val taskWorkspaceMaxPromptTokens: Int? = null,
    val taskWorkspaceMaxSections: Int? = null,
    val taskWorkspaceMaxSectionChars: Int? = null,
    val taskWorkspaceMaxSectionSummaryChars: Int? = null,
    val taskWorkspaceMaxEvidenceItems: Int? = null,
    val taskWorkspaceMaxEvidenceChars: Int? = null,
    val taskWorkspaceFinalCompilationMaxChars: Int? = null,
    val taskWorkspaceFinalPassRewriteEnabled: Boolean? = null,
    val taskWorkspaceFinalPassMaxTokens: Int? = null,
    val taskWorkspaceFinalPassMinWorkspaceConfidence: Double? = null,
    val taskWorkspaceFinalPassMinModelConfidence: Double? = null,
    val taskWorkspaceDebugCaptureEnabled: Boolean? = null,
    val taskWorkspaceMaxActiveTasks: Int? = null,
    val maxThoughtChars: Int? = null,
    val maxActionPayloadChars: Int? = null,
    val maxActionSummaryChars: Int? = null,
    val maxPromptTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val llmRetryAttempts: Int? = null,
    val maxRunTotalTokens: Int? = null,
    val maxRunTokensPerProvider: Int? = null,
    val maxRunTokensPerRole: Int? = null,
    val superegoMaxCompletionTokens: Int? = null,
    val superegoDynamicCompletionEnabled: Boolean? = null,
    val superegoDynamicCompletionHardMaxTokens: Int? = null,
    val superegoDynamicPromptToCompletionRatio: Double? = null,
    val superegoDynamicCompletionMinPromptTokens: Int? = null,
    val superegoTwoStageReviewEnabled: Boolean? = null,
    val superegoTwoStageLowConfidenceThreshold: Double? = null,
    val superegoTwoStageEscalateOnMediumPolicyRisk: Boolean? = null,
    val superegoTwoStageSkipForWebSearchActions: Boolean? = null,
    val searchResultCount: Int? = null,
    val mcpCallTimeoutMs: Long? = null,
    val fetchMaxChars: Int? = null,
    val mcpMemoryCallTimeoutMs: Long? = null,
    val longTermMemoryRecallMaxItems: Int? = null,
    val longTermMemoryRecallMaxChars: Int? = null,
    val longTermMemoryPromptCompressionEnabled: Boolean? = null,
    val longTermMemoryPromptDialogueMaxChars: Int? = null,
    val longTermMemoryPromptRecallMaxChars: Int? = null,
    val deliberationPressureAssessmentMinStep: Int? = null,
    val deliberationPressureAssessmentEverySteps: Int? = null,
    val deliberationPressureAssessmentThreshold: Double? = null,
    val metaReasonerCooldownSteps: Int? = null,
    val metaReasonerMaxTokens: Int? = null,
    val metaReasonerDynamicCompletionEnabled: Boolean? = null,
    val metaReasonerDynamicCompletionHardMaxTokens: Int? = null,
    val metaReasonerDynamicPromptToCompletionRatio: Double? = null,
    val metaReasonerDynamicCompletionMinPromptTokens: Int? = null,
    val longTermMemoryAssessEverySteps: Int? = null,
    val longTermMemoryAssessCooldownSteps: Int? = null,
    val longTermMemoryMinConfidence: Double? = null,
    val longTermMemoryMaxTokens: Int? = null,
    val longTermMemoryDynamicCompletionEnabled: Boolean? = null,
    val longTermMemoryDynamicCompletionHardMaxTokens: Int? = null,
    val longTermMemoryDynamicPromptToCompletionRatio: Double? = null,
    val longTermMemoryDynamicCompletionMinPromptTokens: Int? = null,
    val longTermMemoryMaxSummaryChars: Int? = null,
    val forcedTerminalPressureThreshold: Double? = null,
    val forcedTerminalStaleStreakThreshold: Int? = null,
    val longTermMemoryForceAssessOnAllowedAction: Boolean? = null,
    val longTermMemoryForceAssessOnTerminalAnswer: Boolean? = null,
    val longTermMemoryParseFallbackDisableAfter: Int? = null,
    val longTermMemoryRecallEchoMinSummaryChars: Int? = null,
    val longTermMemoryRecallEchoMinTokenLength: Int? = null,
    val longTermMemoryRecallEchoMinTokenCount: Int? = null,
    val longTermMemoryRecallEchoTokenOverlapThreshold: Double? = null,
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
        val yaml = readYaml(resolveConfigPath(env, defaultPath)) ?: AgentRuntimeYamlConfig()
        val appYaml = yaml.app ?: AgentRuntimeYamlApp()
        val evalYaml = yaml.eval ?: AgentRuntimeYamlEval()
        val agentYaml = yaml.agent ?: AgentRuntimeYamlAgent()

        val mcpCallTimeoutMs = readPositiveLong(
            env = env["MCP_CALL_TIMEOUT_MS"],
            yaml = agentYaml.mcpCallTimeoutMs,
            fallback = defaults.mcpCallTimeoutMs
        )

        val agentConfig = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = readPositiveInt(env["EGO_MAX_LOOP_STEPS"], agentYaml.maxLoopStepsPerInput, defaults.planner.maxLoopStepsPerInput),
                maxThoughtPasses = readPositiveInt(env["EGO_MAX_THOUGHT_PASSES"], agentYaml.maxThoughtPasses, defaults.planner.maxThoughtPasses),
                maxThoughtChars = readPositiveInt(null, agentYaml.maxThoughtChars, defaults.planner.maxThoughtChars),
                maxInputChars = readPositiveInt(null, agentYaml.maxInputChars, defaults.planner.maxInputChars),
                maxActionPayloadChars = readPositiveInt(
                    env["EGO_MAX_ACTION_PAYLOAD_CHARS"],
                    agentYaml.maxActionPayloadChars,
                    defaults.planner.maxActionPayloadChars
                ),
                maxActionSummaryChars = readPositiveInt(null, agentYaml.maxActionSummaryChars, defaults.planner.maxActionSummaryChars),
                maxPromptTokens = readPositiveInt(env["EGO_MAX_PROMPT_TOKENS"], agentYaml.maxPromptTokens, defaults.planner.maxPromptTokens),
                maxCompletionTokens = readPositiveInt(
                    env["EGO_MAX_COMPLETION_TOKENS"],
                    agentYaml.maxCompletionTokens,
                    defaults.planner.maxCompletionTokens
                ),
                llmRetryAttempts = readPositiveInt(env["EGO_LLM_RETRY_ATTEMPTS"], agentYaml.llmRetryAttempts, defaults.planner.llmRetryAttempts),
                maxRunTotalTokens = readNonNegativeInt(
                    env["EGO_MAX_RUN_TOTAL_TOKENS"],
                    agentYaml.maxRunTotalTokens,
                    defaults.planner.maxRunTotalTokens
                ),
                maxRunTokensPerProvider = readNonNegativeInt(
                    env["EGO_MAX_RUN_TOKENS_PER_PROVIDER"],
                    agentYaml.maxRunTokensPerProvider,
                    defaults.planner.maxRunTokensPerProvider
                ),
                maxRunTokensPerRole = readNonNegativeInt(
                    env["EGO_MAX_RUN_TOKENS_PER_ROLE"],
                    agentYaml.maxRunTokensPerRole,
                    defaults.planner.maxRunTokensPerRole
                ),
            ),
            superego = SuperegoConfig(
                maxCompletionTokens = readPositiveInt(
                    env["EGO_SUPEREGO_MAX_COMPLETION_TOKENS"],
                    agentYaml.superegoMaxCompletionTokens,
                    defaults.superego.maxCompletionTokens
                ),
                dynamicCompletionEnabled = readBoolean(
                    env["EGO_SUPEREGO_DYNAMIC_COMPLETION_ENABLED"],
                    agentYaml.superegoDynamicCompletionEnabled,
                    defaults.superego.dynamicCompletionEnabled
                ),
                dynamicCompletionHardMaxTokens = readPositiveInt(
                    env["EGO_SUPEREGO_DYNAMIC_COMPLETION_HARD_MAX_TOKENS"],
                    agentYaml.superegoDynamicCompletionHardMaxTokens,
                    defaults.superego.dynamicCompletionHardMaxTokens
                ),
                dynamicPromptToCompletionRatio = readProbability(
                    env["EGO_SUPEREGO_DYNAMIC_PROMPT_TO_COMPLETION_RATIO"],
                    agentYaml.superegoDynamicPromptToCompletionRatio,
                    defaults.superego.dynamicPromptToCompletionRatio
                ),
                dynamicCompletionMinPromptTokens = readPositiveInt(
                    env["EGO_SUPEREGO_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS"],
                    agentYaml.superegoDynamicCompletionMinPromptTokens,
                    defaults.superego.dynamicCompletionMinPromptTokens
                ),
                twoStageReviewEnabled = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_REVIEW_ENABLED"],
                    agentYaml.superegoTwoStageReviewEnabled,
                    defaults.superego.twoStageReviewEnabled
                ),
                twoStageLowConfidenceThreshold = readProbability(
                    env["EGO_SUPEREGO_TWO_STAGE_LOW_CONFIDENCE_THRESHOLD"],
                    agentYaml.superegoTwoStageLowConfidenceThreshold,
                    defaults.superego.twoStageLowConfidenceThreshold
                ),
                twoStageEscalateOnMediumPolicyRisk = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_ESCALATE_ON_MEDIUM_POLICY_RISK"],
                    agentYaml.superegoTwoStageEscalateOnMediumPolicyRisk,
                    defaults.superego.twoStageEscalateOnMediumPolicyRisk
                ),
                twoStageSkipForWebSearchActions = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_SKIP_FOR_WEB_SEARCH_ACTIONS"],
                    agentYaml.superegoTwoStageSkipForWebSearchActions,
                    defaults.superego.twoStageSkipForWebSearchActions
                ),
            ),
            memory = MemoryConfig(
                maxShortTermContextChars = readPositiveInt(
                    env["EGO_SHORT_TERM_CONTEXT_MAX_CHARS"],
                    agentYaml.maxShortTermContextChars,
                    defaults.memory.maxShortTermContextChars
                ),
                maxShortTermContextPromptTokens = readPositiveInt(
                    env["EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS"],
                    agentYaml.maxShortTermContextPromptTokens,
                    defaults.memory.maxShortTermContextPromptTokens
                ),
                taskWorkspace = psyke.agent.core.TaskWorkspaceConfig(
                    enabled = readBoolean(
                        env["EGO_TASK_WORKSPACE_ENABLED"],
                        agentYaml.taskWorkspaceEnabled,
                        defaults.memory.taskWorkspace.enabled
                    ),
                    maxPromptTokens = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_PROMPT_TOKENS"],
                        agentYaml.taskWorkspaceMaxPromptTokens,
                        defaults.memory.taskWorkspace.maxPromptTokens
                    ),
                    maxSections = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_SECTIONS"],
                        agentYaml.taskWorkspaceMaxSections,
                        defaults.memory.taskWorkspace.maxSections
                    ),
                    maxSectionChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_SECTION_CHARS"],
                        agentYaml.taskWorkspaceMaxSectionChars,
                        defaults.memory.taskWorkspace.maxSectionChars
                    ),
                    maxSectionSummaryChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_SECTION_SUMMARY_CHARS"],
                        agentYaml.taskWorkspaceMaxSectionSummaryChars,
                        defaults.memory.taskWorkspace.maxSectionSummaryChars
                    ),
                    maxEvidenceItems = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_EVIDENCE_ITEMS"],
                        agentYaml.taskWorkspaceMaxEvidenceItems,
                        defaults.memory.taskWorkspace.maxEvidenceItems
                    ),
                    maxEvidenceChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_EVIDENCE_CHARS"],
                        agentYaml.taskWorkspaceMaxEvidenceChars,
                        defaults.memory.taskWorkspace.maxEvidenceChars
                    ),
                    finalCompilationMaxChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_FINAL_COMPILATION_MAX_CHARS"],
                        agentYaml.taskWorkspaceFinalCompilationMaxChars,
                        defaults.memory.taskWorkspace.finalCompilationMaxChars
                    ),
                    finalPassRewriteEnabled = readBoolean(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_REWRITE_ENABLED"],
                        agentYaml.taskWorkspaceFinalPassRewriteEnabled,
                        defaults.memory.taskWorkspace.finalPassRewriteEnabled
                    ),
                    finalPassMaxTokens = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_MAX_TOKENS"],
                        agentYaml.taskWorkspaceFinalPassMaxTokens,
                        defaults.memory.taskWorkspace.finalPassMaxTokens
                    ),
                    finalPassMinWorkspaceConfidence = readProbability(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_MIN_WORKSPACE_CONFIDENCE"],
                        agentYaml.taskWorkspaceFinalPassMinWorkspaceConfidence,
                        defaults.memory.taskWorkspace.finalPassMinWorkspaceConfidence
                    ),
                    finalPassMinModelConfidence = readProbability(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_MIN_MODEL_CONFIDENCE"],
                        agentYaml.taskWorkspaceFinalPassMinModelConfidence,
                        defaults.memory.taskWorkspace.finalPassMinModelConfidence
                    ),
                    debugCaptureEnabled = readBoolean(
                        env["EGO_TASK_WORKSPACE_DEBUG_CAPTURE_ENABLED"],
                        agentYaml.taskWorkspaceDebugCaptureEnabled,
                        defaults.memory.taskWorkspace.debugCaptureEnabled
                    ),
                    maxActiveTasks = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_ACTIVE_TASKS"],
                        agentYaml.taskWorkspaceMaxActiveTasks,
                        defaults.memory.taskWorkspace.maxActiveTasks
                    ),
                ),
                longTermMemoryRecallMaxItems = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS"],
                    agentYaml.longTermMemoryRecallMaxItems,
                    defaults.memory.longTermMemoryRecallMaxItems
                ),
                longTermMemoryRecallMaxChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS"],
                    agentYaml.longTermMemoryRecallMaxChars,
                    defaults.memory.longTermMemoryRecallMaxChars
                ),
                longTermMemoryPromptCompressionEnabled = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_PROMPT_COMPRESSION_ENABLED"],
                    agentYaml.longTermMemoryPromptCompressionEnabled,
                    defaults.memory.longTermMemoryPromptCompressionEnabled
                ),
                longTermMemoryPromptDialogueMaxChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_PROMPT_DIALOGUE_MAX_CHARS"],
                    agentYaml.longTermMemoryPromptDialogueMaxChars,
                    defaults.memory.longTermMemoryPromptDialogueMaxChars
                ),
                longTermMemoryPromptRecallMaxChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_PROMPT_RECALL_MAX_CHARS"],
                    agentYaml.longTermMemoryPromptRecallMaxChars,
                    defaults.memory.longTermMemoryPromptRecallMaxChars
                ),
                longTermMemoryAssessEverySteps = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS"],
                    agentYaml.longTermMemoryAssessEverySteps,
                    defaults.memory.longTermMemoryAssessEverySteps
                ),
                longTermMemoryAssessCooldownSteps = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS"],
                    agentYaml.longTermMemoryAssessCooldownSteps,
                    defaults.memory.longTermMemoryAssessCooldownSteps
                ),
                longTermMemoryMinConfidence = readProbability(
                    env["EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE"],
                    agentYaml.longTermMemoryMinConfidence,
                    defaults.memory.longTermMemoryMinConfidence
                ),
                longTermMemoryMaxTokens = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_MAX_TOKENS"],
                    agentYaml.longTermMemoryMaxTokens,
                    defaults.memory.longTermMemoryMaxTokens
                ),
                longTermMemoryDynamicCompletionEnabled = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_ENABLED"],
                    agentYaml.longTermMemoryDynamicCompletionEnabled,
                    defaults.memory.longTermMemoryDynamicCompletionEnabled
                ),
                longTermMemoryDynamicCompletionHardMaxTokens = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_HARD_MAX_TOKENS"],
                    agentYaml.longTermMemoryDynamicCompletionHardMaxTokens,
                    defaults.memory.longTermMemoryDynamicCompletionHardMaxTokens
                ),
                longTermMemoryDynamicPromptToCompletionRatio = readProbability(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_PROMPT_TO_COMPLETION_RATIO"],
                    agentYaml.longTermMemoryDynamicPromptToCompletionRatio,
                    defaults.memory.longTermMemoryDynamicPromptToCompletionRatio
                ),
                longTermMemoryDynamicCompletionMinPromptTokens = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS"],
                    agentYaml.longTermMemoryDynamicCompletionMinPromptTokens,
                    defaults.memory.longTermMemoryDynamicCompletionMinPromptTokens
                ),
                longTermMemoryMaxSummaryChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS"],
                    agentYaml.longTermMemoryMaxSummaryChars,
                    defaults.memory.longTermMemoryMaxSummaryChars
                ),
                longTermMemoryForceAssessOnAllowedAction = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION"],
                    agentYaml.longTermMemoryForceAssessOnAllowedAction,
                    defaults.memory.longTermMemoryForceAssessOnAllowedAction
                ),
                longTermMemoryForceAssessOnTerminalAnswer = readBoolean(
                    env["EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER"],
                    agentYaml.longTermMemoryForceAssessOnTerminalAnswer,
                    defaults.memory.longTermMemoryForceAssessOnTerminalAnswer
                ),
                longTermMemoryParseFallbackDisableAfter = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER"],
                    agentYaml.longTermMemoryParseFallbackDisableAfter,
                    defaults.memory.longTermMemoryParseFallbackDisableAfter
                ),
                longTermMemoryRecallEchoMinSummaryChars = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS"],
                    agentYaml.longTermMemoryRecallEchoMinSummaryChars,
                    defaults.memory.longTermMemoryRecallEchoMinSummaryChars
                ),
                longTermMemoryRecallEchoMinTokenLength = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH"],
                    agentYaml.longTermMemoryRecallEchoMinTokenLength,
                    defaults.memory.longTermMemoryRecallEchoMinTokenLength
                ),
                longTermMemoryRecallEchoMinTokenCount = readPositiveInt(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT"],
                    agentYaml.longTermMemoryRecallEchoMinTokenCount,
                    defaults.memory.longTermMemoryRecallEchoMinTokenCount
                ),
                longTermMemoryRecallEchoTokenOverlapThreshold = readProbability(
                    env["EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD"],
                    agentYaml.longTermMemoryRecallEchoTokenOverlapThreshold,
                    defaults.memory.longTermMemoryRecallEchoTokenOverlapThreshold
                ),
                mcpMemoryCallTimeoutMs = readPositiveLong(
                    env = env["MCP_MEMORY_CALL_TIMEOUT_MS"],
                    yaml = agentYaml.mcpMemoryCallTimeoutMs,
                    fallback = mcpCallTimeoutMs
                ),
            ),
            metaReasoner = MetaReasonerConfig(
                deliberationPressureAssessmentMinStep = readPositiveInt(
                    env["EGO_PRESSURE_MIN_STEP"],
                    agentYaml.deliberationPressureAssessmentMinStep,
                    defaults.metaReasoner.deliberationPressureAssessmentMinStep
                ),
                deliberationPressureAssessmentEverySteps = readPositiveInt(
                    env["EGO_PRESSURE_ASSESS_EVERY_STEPS"],
                    agentYaml.deliberationPressureAssessmentEverySteps,
                    defaults.metaReasoner.deliberationPressureAssessmentEverySteps
                ),
                deliberationPressureAssessmentThreshold = readProbability(
                    env["EGO_PRESSURE_ASSESS_THRESHOLD"],
                    agentYaml.deliberationPressureAssessmentThreshold,
                    defaults.metaReasoner.deliberationPressureAssessmentThreshold
                ),
                cooldownSteps = readPositiveInt(
                    env["EGO_META_REASONER_COOLDOWN_STEPS"],
                    agentYaml.metaReasonerCooldownSteps,
                    defaults.metaReasoner.cooldownSteps
                ),
                maxTokens = readPositiveInt(
                    env["EGO_META_REASONER_MAX_TOKENS"],
                    agentYaml.metaReasonerMaxTokens,
                    defaults.metaReasoner.maxTokens
                ),
                dynamicCompletionEnabled = readBoolean(
                    env["EGO_META_REASONER_DYNAMIC_COMPLETION_ENABLED"],
                    agentYaml.metaReasonerDynamicCompletionEnabled,
                    defaults.metaReasoner.dynamicCompletionEnabled
                ),
                dynamicCompletionHardMaxTokens = readPositiveInt(
                    env["EGO_META_REASONER_DYNAMIC_COMPLETION_HARD_MAX_TOKENS"],
                    agentYaml.metaReasonerDynamicCompletionHardMaxTokens,
                    defaults.metaReasoner.dynamicCompletionHardMaxTokens
                ),
                dynamicPromptToCompletionRatio = readProbability(
                    env["EGO_META_REASONER_DYNAMIC_PROMPT_TO_COMPLETION_RATIO"],
                    agentYaml.metaReasonerDynamicPromptToCompletionRatio,
                    defaults.metaReasoner.dynamicPromptToCompletionRatio
                ),
                dynamicCompletionMinPromptTokens = readPositiveInt(
                    env["EGO_META_REASONER_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS"],
                    agentYaml.metaReasonerDynamicCompletionMinPromptTokens,
                    defaults.metaReasoner.dynamicCompletionMinPromptTokens
                ),
                forcedTerminalPressureThreshold = readProbability(
                    env["EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD"],
                    agentYaml.forcedTerminalPressureThreshold,
                    defaults.metaReasoner.forcedTerminalPressureThreshold
                ),
                forcedTerminalStaleStreakThreshold = readPositiveInt(
                    env["EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD"],
                    agentYaml.forcedTerminalStaleStreakThreshold,
                    defaults.metaReasoner.forcedTerminalStaleStreakThreshold
                ),
            ),
            loopDelayMs = readNonNegativeInt(env["EGO_LOOP_DELAY_MS"], agentYaml.loopDelayMs, defaults.loopDelayMs),
            maxPendingThoughts = readPositiveInt(null, agentYaml.maxPendingThoughts, defaults.maxPendingThoughts),
            maxPendingActions = readPositiveInt(null, agentYaml.maxPendingActions, defaults.maxPendingActions),
            maxPendingInputs = readPositiveInt(null, agentYaml.maxPendingInputs, defaults.maxPendingInputs),
            searchResultCount = readPositiveInt(env["EGO_SEARCH_RESULT_COUNT"], agentYaml.searchResultCount, defaults.searchResultCount),
            mcpCallTimeoutMs = mcpCallTimeoutMs,
            fetchMaxChars = readPositiveInt(env["MCP_FETCH_MAX_CHARS"], agentYaml.fetchMaxChars, defaults.fetchMaxChars),
        )

        return AgentRuntimeSettings(
            agentConfig = agentConfig,
            dashboardEnabled = readBoolean(
                env = env["PSYKE_DASHBOARD_ENABLED"],
                yaml = appYaml.dashboardEnabled,
                fallback = true
            ),
            dashboardPort = readPositiveInt(
                env = env["PSYKE_DASHBOARD_PORT"],
                yaml = appYaml.dashboardPort,
                fallback = DEFAULT_DASHBOARD_PORT
            ),
            evalMaxRawResponseChars = readPositiveInt(
                env = env["PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS"],
                yaml = evalYaml.maxRawResponseChars,
                fallback = Int.MAX_VALUE
            ),
            evalDefaultStage = firstNonBlank(env["PSYKE_EVAL_STAGE"], evalYaml.defaultStage)
        )
    }

    private fun resolveConfigPath(env: Map<String, String>, defaultPath: Path): Path {
        val configured = env["PSYKE_AGENT_CONFIG_FILE"]?.trim().orEmpty()
        if (configured.isBlank()) {
            return defaultPath
        }
        return Paths.get(configured)
    }

    private fun readYaml(path: Path): AgentRuntimeYamlConfig? {
        if (!Files.exists(path)) {
            return null
        }
        Files.newBufferedReader(path).use { reader ->
            return mapper.readValue<AgentRuntimeYamlConfig>(reader)
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

    private fun parseBoolean(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
