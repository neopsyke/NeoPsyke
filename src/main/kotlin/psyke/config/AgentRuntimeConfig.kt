package psyke.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.agent.core.AgentConfig
import psyke.agent.core.LogbookConfig
import psyke.agent.core.MemoryConfig
import psyke.agent.core.MetaReasonerConfig
import psyke.dashboard.InnerVoiceConfig
import psyke.agent.core.PlannerConfig
import psyke.agent.core.SuperegoConfig
import psyke.agent.core.TaskWorkspaceConfig
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
    val planner: AgentRuntimeYamlPlanner? = AgentRuntimeYamlPlanner(),
    val superego: AgentRuntimeYamlSuperego? = AgentRuntimeYamlSuperego(),
    val memory: AgentRuntimeYamlMemory? = AgentRuntimeYamlMemory(),
    val metaReasoner: AgentRuntimeYamlMetaReasoner? = AgentRuntimeYamlMetaReasoner(),
    val logbook: AgentRuntimeYamlLogbook? = AgentRuntimeYamlLogbook(),
    val innerVoice: AgentRuntimeYamlInnerVoice? = AgentRuntimeYamlInnerVoice(),
    val runtime: AgentRuntimeYamlRuntime? = AgentRuntimeYamlRuntime(),
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
    val twoStageSkipForAnswerActions: Boolean? = null,
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
    val taskWorkspace: AgentRuntimeYamlTaskWorkspace? = AgentRuntimeYamlTaskWorkspace(),
)

private data class AgentRuntimeYamlTaskWorkspace(
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

private data class AgentRuntimeYamlInnerVoice(
    val enabled: Boolean? = null,
    val maxContentChars: Int? = null,
    val maxEventsPerSession: Int? = null,
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
        val yaml = readYaml(resolveConfigPath(env, defaultPath)) ?: AgentRuntimeYamlConfig()
        val appYaml = yaml.app ?: AgentRuntimeYamlApp()
        val evalYaml = yaml.eval ?: AgentRuntimeYamlEval()
        val agentYaml = yaml.agent ?: AgentRuntimeYamlAgent()
        val plannerYaml = agentYaml.planner ?: AgentRuntimeYamlPlanner()
        val superegoYaml = agentYaml.superego ?: AgentRuntimeYamlSuperego()
        val memoryYaml = agentYaml.memory ?: AgentRuntimeYamlMemory()
        val taskWorkspaceYaml = memoryYaml.taskWorkspace ?: AgentRuntimeYamlTaskWorkspace()
        val metaReasonerYaml = agentYaml.metaReasoner ?: AgentRuntimeYamlMetaReasoner()
        val logbookYaml = agentYaml.logbook ?: AgentRuntimeYamlLogbook()
        val innerVoiceYaml = agentYaml.innerVoice ?: AgentRuntimeYamlInnerVoice()
        val runtimeYaml = agentYaml.runtime ?: AgentRuntimeYamlRuntime()

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
                maxActionPayloadChars = readPositiveInt(
                    env["EGO_MAX_ACTION_PAYLOAD_CHARS"],
                    plannerYaml.maxActionPayloadChars,
                    defaults.planner.maxActionPayloadChars
                ),
                maxActionSummaryChars = readPositiveInt(
                    env["EGO_MAX_ACTION_SUMMARY_CHARS"],
                    plannerYaml.maxActionSummaryChars,
                    defaults.planner.maxActionSummaryChars
                ),
                maxPromptTokens = readPositiveInt(
                    env["EGO_MAX_PROMPT_TOKENS"],
                    plannerYaml.maxPromptTokens,
                    defaults.planner.maxPromptTokens
                ),
                maxCompletionTokens = readPositiveInt(
                    env["EGO_MAX_COMPLETION_TOKENS"],
                    plannerYaml.maxCompletionTokens,
                    defaults.planner.maxCompletionTokens
                ),
                llmRetryAttempts = readPositiveInt(
                    env["EGO_LLM_RETRY_ATTEMPTS"],
                    plannerYaml.llmRetryAttempts,
                    defaults.planner.llmRetryAttempts
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
                twoStageSkipForAnswerActions = readBoolean(
                    env["EGO_SUPEREGO_TWO_STAGE_SKIP_FOR_ANSWER_ACTIONS"],
                    superegoYaml.twoStageSkipForAnswerActions,
                    defaults.superego.twoStageSkipForAnswerActions
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
                taskWorkspace = TaskWorkspaceConfig(
                    enabled = readBoolean(
                        env["EGO_TASK_WORKSPACE_ENABLED"],
                        taskWorkspaceYaml.enabled,
                        defaults.memory.taskWorkspace.enabled
                    ),
                    activationMinPlanSteps = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_ACTIVATION_MIN_PLAN_STEPS"],
                        taskWorkspaceYaml.activationMinPlanSteps,
                        defaults.memory.taskWorkspace.activationMinPlanSteps
                    ),
                    maxPromptTokens = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_PROMPT_TOKENS"],
                        taskWorkspaceYaml.maxPromptTokens,
                        defaults.memory.taskWorkspace.maxPromptTokens
                    ),
                    maxSections = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_SECTIONS"],
                        taskWorkspaceYaml.maxSections,
                        defaults.memory.taskWorkspace.maxSections
                    ),
                    maxSectionChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_SECTION_CHARS"],
                        taskWorkspaceYaml.maxSectionChars,
                        defaults.memory.taskWorkspace.maxSectionChars
                    ),
                    maxSectionSummaryChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_SECTION_SUMMARY_CHARS"],
                        taskWorkspaceYaml.maxSectionSummaryChars,
                        defaults.memory.taskWorkspace.maxSectionSummaryChars
                    ),
                    maxEvidenceItems = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_EVIDENCE_ITEMS"],
                        taskWorkspaceYaml.maxEvidenceItems,
                        defaults.memory.taskWorkspace.maxEvidenceItems
                    ),
                    maxEvidenceChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_EVIDENCE_CHARS"],
                        taskWorkspaceYaml.maxEvidenceChars,
                        defaults.memory.taskWorkspace.maxEvidenceChars
                    ),
                    finalCompilationMaxChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_FINAL_COMPILATION_MAX_CHARS"],
                        taskWorkspaceYaml.finalCompilationMaxChars,
                        defaults.memory.taskWorkspace.finalCompilationMaxChars
                    ),
                    finalPassRewriteEnabled = readBoolean(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_REWRITE_ENABLED"],
                        taskWorkspaceYaml.finalPassRewriteEnabled,
                        defaults.memory.taskWorkspace.finalPassRewriteEnabled
                    ),
                    finalPassMaxTokens = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_MAX_TOKENS"],
                        taskWorkspaceYaml.finalPassMaxTokens,
                        defaults.memory.taskWorkspace.finalPassMaxTokens
                    ),
                    finalPassMinWorkspaceConfidence = readProbability(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_MIN_WORKSPACE_CONFIDENCE"],
                        taskWorkspaceYaml.finalPassMinWorkspaceConfidence,
                        defaults.memory.taskWorkspace.finalPassMinWorkspaceConfidence
                    ),
                    finalPassMinModelConfidence = readProbability(
                        env["EGO_TASK_WORKSPACE_FINAL_PASS_MIN_MODEL_CONFIDENCE"],
                        taskWorkspaceYaml.finalPassMinModelConfidence,
                        defaults.memory.taskWorkspace.finalPassMinModelConfidence
                    ),
                    debugCaptureEnabled = readBoolean(
                        env["EGO_TASK_WORKSPACE_DEBUG_CAPTURE_ENABLED"],
                        taskWorkspaceYaml.debugCaptureEnabled,
                        defaults.memory.taskWorkspace.debugCaptureEnabled
                    ),
                    maxActiveTasks = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_MAX_ACTIVE_TASKS"],
                        taskWorkspaceYaml.maxActiveTasks,
                        defaults.memory.taskWorkspace.maxActiveTasks
                    ),
                    digestMaxEntries = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_DIGEST_MAX_ENTRIES"],
                        taskWorkspaceYaml.digestMaxEntries,
                        defaults.memory.taskWorkspace.digestMaxEntries
                    ),
                    digestMaxChars = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_DIGEST_MAX_CHARS"],
                        taskWorkspaceYaml.digestMaxChars,
                        defaults.memory.taskWorkspace.digestMaxChars
                    ),
                    digestMaxPromptTokens = readPositiveInt(
                        env["EGO_TASK_WORKSPACE_DIGEST_MAX_PROMPT_TOKENS"],
                        taskWorkspaceYaml.digestMaxPromptTokens,
                        defaults.memory.taskWorkspace.digestMaxPromptTokens
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
                    env["PSYKE_LOGBOOK_ENABLED"],
                    logbookYaml.enabled,
                    defaults.logbook.enabled
                ),
                maxSummaryChars = readPositiveInt(
                    env["PSYKE_LOGBOOK_MAX_SUMMARY_CHARS"],
                    logbookYaml.maxSummaryChars,
                    defaults.logbook.maxSummaryChars
                ),
                maxKeywordsPerEntry = readPositiveInt(
                    env["PSYKE_LOGBOOK_MAX_KEYWORDS"],
                    logbookYaml.maxKeywordsPerEntry,
                    defaults.logbook.maxKeywordsPerEntry
                ),
                maxEntriesPerQuery = readPositiveInt(
                    env["PSYKE_LOGBOOK_MAX_ENTRIES_PER_QUERY"],
                    logbookYaml.maxEntriesPerQuery,
                    defaults.logbook.maxEntriesPerQuery
                ),
                retentionDays = readPositiveInt(
                    env["PSYKE_LOGBOOK_RETENTION_DAYS"],
                    logbookYaml.retentionDays,
                    defaults.logbook.retentionDays
                ),
                dbPath = readNonBlank(
                    env["PSYKE_LOGBOOK_DB_PATH"],
                    logbookYaml.dbPath,
                    defaults.logbook.dbPath
                ),
                episodicRecallMaxChars = readPositiveInt(
                    env["PSYKE_LOGBOOK_EPISODIC_RECALL_MAX_CHARS"],
                    logbookYaml.episodicRecallMaxChars,
                    defaults.logbook.episodicRecallMaxChars
                ),
                episodicRecallMaxResults = readPositiveInt(
                    env["PSYKE_LOGBOOK_EPISODIC_RECALL_MAX_RESULTS"],
                    logbookYaml.episodicRecallMaxResults,
                    defaults.logbook.episodicRecallMaxResults
                ),
                useLlmSummarizer = readBoolean(
                    env["PSYKE_LOGBOOK_USE_LLM_SUMMARIZER"],
                    logbookYaml.useLlmSummarizer,
                    defaults.logbook.useLlmSummarizer
                )
            ),
            innerVoice = InnerVoiceConfig(
                enabled = readBoolean(
                    env["PSYKE_INNER_VOICE_ENABLED"],
                    innerVoiceYaml.enabled,
                    defaults.innerVoice.enabled
                ),
                maxContentChars = readPositiveInt(
                    env["PSYKE_INNER_VOICE_MAX_CONTENT_CHARS"],
                    innerVoiceYaml.maxContentChars,
                    defaults.innerVoice.maxContentChars
                ),
                maxEventsPerSession = readPositiveInt(
                    env["PSYKE_INNER_VOICE_MAX_EVENTS_PER_SESSION"],
                    innerVoiceYaml.maxEventsPerSession,
                    defaults.innerVoice.maxEventsPerSession
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

    private fun readNonBlank(env: String?, yaml: String?, fallback: String): String =
        firstNonBlank(env, yaml) ?: fallback

    private fun parseBoolean(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
