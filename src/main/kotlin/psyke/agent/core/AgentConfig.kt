package psyke.agent.core

data class AgentConfig(
    val planner: PlannerConfig = PlannerConfig(),
    val superego: SuperegoConfig = SuperegoConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val metaReasoner: MetaReasonerConfig = MetaReasonerConfig(),
    val loopDelayMs: Int = 0,
    val maxPendingThoughts: Int = 64,
    val maxPendingActions: Int = 32,
    val maxPendingInputs: Int = 32,
    val searchResultCount: Int = 5,
    val mcpCallTimeoutMs: Long = 8_000,
    val fetchMaxChars: Int = 4_000,
) {
    companion object {
        fun fromEnv(): AgentConfig =
            AgentConfig.fromResolvedEnv()

        private fun fromResolvedEnv(): AgentConfig {
            val mcpCallTimeoutMs = readLong("MCP_CALL_TIMEOUT_MS", 8000)
            return AgentConfig(
                planner = PlannerConfig(
                    maxLoopStepsPerInput = readInt("EGO_MAX_LOOP_STEPS", 180),
                    maxThoughtPasses = readInt("EGO_MAX_THOUGHT_PASSES", 5),
                    maxThoughtChars = readInt("EGO_MAX_THOUGHT_CHARS", 600),
                    maxInputChars = readInt("EGO_MAX_INPUT_CHARS", 2000),
                    maxActionPayloadChars = readInt("EGO_MAX_ACTION_PAYLOAD_CHARS", 4000),
                    maxActionSummaryChars = readInt("EGO_MAX_ACTION_SUMMARY_CHARS", 180),
                    maxPromptTokens = readInt("EGO_MAX_PROMPT_TOKENS", 2400),
                    maxCompletionTokens = readInt("EGO_MAX_COMPLETION_TOKENS", 900),
                    llmRetryAttempts = readInt("EGO_LLM_RETRY_ATTEMPTS", 2),
                    maxRunTotalTokens = readNonNegativeInt("EGO_MAX_RUN_TOTAL_TOKENS", 0),
                    maxRunTokensPerProvider = readNonNegativeInt("EGO_MAX_RUN_TOKENS_PER_PROVIDER", 0),
                    maxRunTokensPerRole = readNonNegativeInt("EGO_MAX_RUN_TOKENS_PER_ROLE", 0),
                    maxPlanSteps = readInt("EGO_MAX_PLAN_STEPS", 6),
                    maxPlanStepDescriptionChars = readInt("EGO_MAX_PLAN_STEP_DESC_CHARS", 120),
                    maxPlansPerInput = readInt("EGO_MAX_PLANS_PER_INPUT", 2),
                    planEmissionPressureThreshold = readDouble("EGO_PLAN_EMISSION_PRESSURE_THRESHOLD", 0.55),
                ),
                superego = SuperegoConfig(
                    maxCompletionTokens = readInt("EGO_SUPEREGO_MAX_COMPLETION_TOKENS", 192),
                    dynamicCompletionEnabled = readBoolean("EGO_SUPEREGO_DYNAMIC_COMPLETION_ENABLED", true),
                    dynamicCompletionHardMaxTokens = readInt("EGO_SUPEREGO_DYNAMIC_COMPLETION_HARD_MAX_TOKENS", 640),
                    dynamicPromptToCompletionRatio = readRatio("EGO_SUPEREGO_DYNAMIC_PROMPT_TO_COMPLETION_RATIO", 0.10),
                    dynamicCompletionMinPromptTokens = readInt("EGO_SUPEREGO_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS", 160),
                    twoStageReviewEnabled = readBoolean("EGO_SUPEREGO_TWO_STAGE_REVIEW_ENABLED", false),
                    twoStageLowConfidenceThreshold = readDouble("EGO_SUPEREGO_TWO_STAGE_LOW_CONFIDENCE_THRESHOLD", 0.70),
                    twoStageEscalateOnMediumPolicyRisk =
                        readBoolean("EGO_SUPEREGO_TWO_STAGE_ESCALATE_ON_MEDIUM_POLICY_RISK", true),
                ),
                memory = MemoryConfig(
                    maxShortTermContextChars = readInt("EGO_SHORT_TERM_CONTEXT_MAX_CHARS", 20000),
                    maxShortTermContextPromptTokens = readInt("EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS", 384),
                    taskWorkspace = TaskWorkspaceConfig(
                        enabled = readBoolean("EGO_TASK_WORKSPACE_ENABLED", false),
                        maxPromptTokens = readInt("EGO_TASK_WORKSPACE_MAX_PROMPT_TOKENS", 220),
                        maxSections = readInt("EGO_TASK_WORKSPACE_MAX_SECTIONS", 10),
                        maxSectionChars = readInt("EGO_TASK_WORKSPACE_MAX_SECTION_CHARS", 1200),
                        maxSectionSummaryChars = readInt("EGO_TASK_WORKSPACE_MAX_SECTION_SUMMARY_CHARS", 180),
                        maxEvidenceItems = readInt("EGO_TASK_WORKSPACE_MAX_EVIDENCE_ITEMS", 8),
                        maxEvidenceChars = readInt("EGO_TASK_WORKSPACE_MAX_EVIDENCE_CHARS", 220),
                        finalCompilationMaxChars = readInt("EGO_TASK_WORKSPACE_FINAL_COMPILATION_MAX_CHARS", 2800),
                        finalPassRewriteEnabled = readBoolean("EGO_TASK_WORKSPACE_FINAL_PASS_REWRITE_ENABLED", true),
                        finalPassMaxTokens = readInt("EGO_TASK_WORKSPACE_FINAL_PASS_MAX_TOKENS", 260),
                        finalPassMinWorkspaceConfidence =
                            readDouble("EGO_TASK_WORKSPACE_FINAL_PASS_MIN_WORKSPACE_CONFIDENCE", 0.35),
                        finalPassMinModelConfidence =
                            readDouble("EGO_TASK_WORKSPACE_FINAL_PASS_MIN_MODEL_CONFIDENCE", 0.55),
                        maxActiveTasks = readInt("EGO_TASK_WORKSPACE_MAX_ACTIVE_TASKS", 32),
                    ),
                    longTermMemoryRecallMaxItems = readInt("EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS", 4),
                    longTermMemoryRecallMaxChars = readInt("EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS", 1200),
                    longTermMemoryPromptCompressionEnabled =
                        readBoolean("EGO_LONG_TERM_MEMORY_PROMPT_COMPRESSION_ENABLED", true),
                    longTermMemoryPromptDialogueMaxChars =
                        readInt("EGO_LONG_TERM_MEMORY_PROMPT_DIALOGUE_MAX_CHARS", 1100),
                    longTermMemoryPromptRecallMaxChars =
                        readInt("EGO_LONG_TERM_MEMORY_PROMPT_RECALL_MAX_CHARS", 900),
                    longTermMemoryAssessEverySteps = readInt("EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS", 16),
                    longTermMemoryAssessCooldownSteps = readInt("EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS", 8),
                    longTermMemoryMinConfidence = readDouble("EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE", 0.65),
                    longTermMemoryMaxTokens = readInt("EGO_LONG_TERM_MEMORY_MAX_TOKENS", 320),
                    longTermMemoryDynamicCompletionEnabled = readBoolean("EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_ENABLED", true),
                    longTermMemoryDynamicCompletionHardMaxTokens =
                        readInt("EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_HARD_MAX_TOKENS", 512),
                    longTermMemoryDynamicPromptToCompletionRatio =
                        readRatio("EGO_LONG_TERM_MEMORY_DYNAMIC_PROMPT_TO_COMPLETION_RATIO", 0.08),
                    longTermMemoryDynamicCompletionMinPromptTokens =
                        readInt("EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS", 160),
                    longTermMemoryMaxSummaryChars = readInt("EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS", 320),
                    longTermMemoryForceAssessOnAllowedAction = readBoolean("EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION", false),
                    longTermMemoryForceAssessOnTerminalAnswer = readBoolean("EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER", true),
                    longTermMemoryParseFallbackDisableAfter = readInt("EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER", 2),
                    longTermMemoryRecallEchoMinSummaryChars = readInt("EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS", 16),
                    longTermMemoryRecallEchoMinTokenLength = readInt("EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH", 3),
                    longTermMemoryRecallEchoMinTokenCount = readInt("EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT", 4),
                    longTermMemoryRecallEchoTokenOverlapThreshold =
                        readDouble("EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD", 0.85),
                    mcpMemoryCallTimeoutMs = readLong("MCP_MEMORY_CALL_TIMEOUT_MS", mcpCallTimeoutMs),
                ),
                metaReasoner = MetaReasonerConfig(
                    deliberationPressureAssessmentMinStep = readInt("EGO_PRESSURE_MIN_STEP", 16),
                    deliberationPressureAssessmentEverySteps = readInt("EGO_PRESSURE_ASSESS_EVERY_STEPS", 8),
                    deliberationPressureAssessmentThreshold = readDouble("EGO_PRESSURE_ASSESS_THRESHOLD", 0.68),
                    cooldownSteps = readInt("EGO_META_REASONER_COOLDOWN_STEPS", 6),
                    maxTokens = readInt("EGO_META_REASONER_MAX_TOKENS", 120),
                    forcedTerminalPressureThreshold = readDouble("EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD", 0.98),
                    forcedTerminalStaleStreakThreshold = readInt("EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD", 8),
                ),
                loopDelayMs = readNonNegativeInt("EGO_LOOP_DELAY_MS", 0),
                maxPendingThoughts = readInt("EGO_MAX_PENDING_THOUGHTS", 64),
                maxPendingActions = readInt("EGO_MAX_PENDING_ACTIONS", 32),
                maxPendingInputs = readInt("EGO_MAX_PENDING_INPUTS", 32),
                searchResultCount = readInt("EGO_SEARCH_RESULT_COUNT", 5),
                mcpCallTimeoutMs = mcpCallTimeoutMs,
                fetchMaxChars = readInt("MCP_FETCH_MAX_CHARS", 4000),
            )
        }

        private fun readInt(name: String, fallback: Int): Int =
            System.getenv(name)?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

        private fun readNonNegativeInt(name: String, fallback: Int): Int =
            System.getenv(name)?.toIntOrNull()?.takeIf { it >= 0 } ?: fallback

        private fun readLong(name: String, fallback: Long): Long =
            System.getenv(name)?.toLongOrNull()?.takeIf { it > 0 } ?: fallback

        private fun readDouble(name: String, fallback: Double): Double =
            System.getenv(name)?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: fallback

        private fun readBoolean(name: String, fallback: Boolean): Boolean =
            System.getenv(name)?.trim()?.lowercase()?.let { it == "1" || it == "true" || it == "yes" } ?: fallback

        private fun readRatio(name: String, fallback: Double): Double =
            System.getenv(name)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: fallback
    }
}
