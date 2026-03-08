package psyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeSettingsLoaderTest {
    @Test
    fun `load falls back to defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("psyke-agent-runtime-missing")
        val settings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        assertEquals(180, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(0, settings.agentConfig.loopDelayMs)
        assertEquals(2, settings.agentConfig.planner.llmRetryAttempts)
        assertEquals(0, settings.agentConfig.planner.maxRunTotalTokens)
        assertEquals(0, settings.agentConfig.planner.maxRunTokensPerProvider)
        assertEquals(0, settings.agentConfig.planner.maxRunTokensPerRole)
        assertEquals(192, settings.agentConfig.superego.maxCompletionTokens)
        assertEquals(true, settings.agentConfig.superego.dynamicCompletionEnabled)
        assertEquals(640, settings.agentConfig.superego.dynamicCompletionHardMaxTokens)
        assertEquals(0.10, settings.agentConfig.superego.dynamicPromptToCompletionRatio)
        assertEquals(160, settings.agentConfig.superego.dynamicCompletionMinPromptTokens)
        assertEquals(false, settings.agentConfig.superego.twoStageReviewEnabled)
        assertEquals(0.60, settings.agentConfig.superego.twoStageLowConfidenceThreshold)
        assertEquals(true, settings.agentConfig.superego.twoStageEscalateOnMediumPolicyRisk)
        assertEquals(true, settings.agentConfig.superego.twoStageSkipForWebSearchActions)
        assertEquals(320, settings.agentConfig.memory.longTermMemoryMaxTokens)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.enabled)
        assertEquals(220, settings.agentConfig.memory.taskWorkspace.maxPromptTokens)
        assertEquals(true, settings.agentConfig.memory.taskWorkspace.finalPassRewriteEnabled)
        assertEquals(260, settings.agentConfig.memory.taskWorkspace.finalPassMaxTokens)
        assertEquals(0.35, settings.agentConfig.memory.taskWorkspace.finalPassMinWorkspaceConfidence)
        assertEquals(0.55, settings.agentConfig.memory.taskWorkspace.finalPassMinModelConfidence)
        assertEquals(true, settings.agentConfig.memory.taskWorkspace.debugCaptureEnabled)
        assertEquals(true, settings.agentConfig.memory.longTermMemoryPromptCompressionEnabled)
        assertEquals(1100, settings.agentConfig.memory.longTermMemoryPromptDialogueMaxChars)
        assertEquals(900, settings.agentConfig.memory.longTermMemoryPromptRecallMaxChars)
        assertEquals(true, settings.agentConfig.memory.longTermMemoryDynamicCompletionEnabled)
        assertEquals(512, settings.agentConfig.memory.longTermMemoryDynamicCompletionHardMaxTokens)
        assertEquals(0.08, settings.agentConfig.memory.longTermMemoryDynamicPromptToCompletionRatio)
        assertEquals(160, settings.agentConfig.memory.longTermMemoryDynamicCompletionMinPromptTokens)
        assertEquals(true, settings.agentConfig.metaReasoner.dynamicCompletionEnabled)
        assertEquals(640, settings.agentConfig.metaReasoner.dynamicCompletionHardMaxTokens)
        assertEquals(0.10, settings.agentConfig.metaReasoner.dynamicPromptToCompletionRatio)
        assertEquals(160, settings.agentConfig.metaReasoner.dynamicCompletionMinPromptTokens)
        assertEquals(true, settings.agentConfig.memory.longTermMemoryForceAssessOnTerminalAnswer)
        assertTrue(settings.dashboardEnabled)
        assertEquals(8787, settings.dashboardPort)
        assertEquals(Int.MAX_VALUE, settings.evalMaxRawResponseChars)
        assertEquals(null, settings.evalDefaultStage)
    }

    @Test
    fun `load reads yaml values for agent app and eval sections`() {
        val tempDir = Files.createTempDirectory("psyke-agent-runtime-yaml")
        val yamlPath = tempDir.resolve("agent-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            app:
              dashboard_enabled: false
              dashboard_port: 9200
            eval:
              max_raw_response_chars: 4444
              default_stage: local
            agent:
              max_loop_steps_per_input: 21
              max_prompt_tokens: 999
              max_run_total_tokens: 5000
              max_run_tokens_per_provider: 3200
              max_run_tokens_per_role: 1800
              llm_retry_attempts: 4
              task_workspace_enabled: true
              task_workspace_max_prompt_tokens: 300
              task_workspace_final_pass_rewrite_enabled: false
              task_workspace_final_pass_max_tokens: 190
              task_workspace_final_pass_min_workspace_confidence: 0.42
              task_workspace_final_pass_min_model_confidence: 0.61
              task_workspace_debug_capture_enabled: false
              superego_dynamic_completion_enabled: false
              superego_dynamic_completion_hard_max_tokens: 700
              superego_dynamic_prompt_to_completion_ratio: 0.21
              superego_dynamic_completion_min_prompt_tokens: 110
              superego_two_stage_review_enabled: true
              superego_two_stage_low_confidence_threshold: 0.74
              superego_two_stage_escalate_on_medium_policy_risk: false
              long_term_memory_prompt_compression_enabled: true
              long_term_memory_prompt_dialogue_max_chars: 780
              long_term_memory_prompt_recall_max_chars: 640
              long_term_memory_dynamic_completion_enabled: false
              long_term_memory_dynamic_completion_hard_max_tokens: 480
              long_term_memory_dynamic_prompt_to_completion_ratio: 0.12
              long_term_memory_dynamic_completion_min_prompt_tokens: 90
              meta_reasoner_dynamic_completion_enabled: false
              meta_reasoner_dynamic_completion_hard_max_tokens: 700
              meta_reasoner_dynamic_prompt_to_completion_ratio: 0.22
              meta_reasoner_dynamic_completion_min_prompt_tokens: 130
            """.trimIndent()
        )

        val settings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertEquals(false, settings.dashboardEnabled)
        assertEquals(9200, settings.dashboardPort)
        assertEquals(4444, settings.evalMaxRawResponseChars)
        assertEquals("local", settings.evalDefaultStage)
        assertEquals(21, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(999, settings.agentConfig.planner.maxPromptTokens)
        assertEquals(5000, settings.agentConfig.planner.maxRunTotalTokens)
        assertEquals(3200, settings.agentConfig.planner.maxRunTokensPerProvider)
        assertEquals(1800, settings.agentConfig.planner.maxRunTokensPerRole)
        assertEquals(4, settings.agentConfig.planner.llmRetryAttempts)
        assertEquals(true, settings.agentConfig.memory.taskWorkspace.enabled)
        assertEquals(300, settings.agentConfig.memory.taskWorkspace.maxPromptTokens)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.finalPassRewriteEnabled)
        assertEquals(190, settings.agentConfig.memory.taskWorkspace.finalPassMaxTokens)
        assertEquals(0.42, settings.agentConfig.memory.taskWorkspace.finalPassMinWorkspaceConfidence)
        assertEquals(0.61, settings.agentConfig.memory.taskWorkspace.finalPassMinModelConfidence)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.debugCaptureEnabled)
        assertEquals(false, settings.agentConfig.superego.dynamicCompletionEnabled)
        assertEquals(700, settings.agentConfig.superego.dynamicCompletionHardMaxTokens)
        assertEquals(0.21, settings.agentConfig.superego.dynamicPromptToCompletionRatio)
        assertEquals(110, settings.agentConfig.superego.dynamicCompletionMinPromptTokens)
        assertEquals(true, settings.agentConfig.superego.twoStageReviewEnabled)
        assertEquals(0.74, settings.agentConfig.superego.twoStageLowConfidenceThreshold)
        assertEquals(false, settings.agentConfig.superego.twoStageEscalateOnMediumPolicyRisk)
        assertEquals(true, settings.agentConfig.memory.longTermMemoryPromptCompressionEnabled)
        assertEquals(780, settings.agentConfig.memory.longTermMemoryPromptDialogueMaxChars)
        assertEquals(640, settings.agentConfig.memory.longTermMemoryPromptRecallMaxChars)
        assertEquals(false, settings.agentConfig.memory.longTermMemoryDynamicCompletionEnabled)
        assertEquals(480, settings.agentConfig.memory.longTermMemoryDynamicCompletionHardMaxTokens)
        assertEquals(0.12, settings.agentConfig.memory.longTermMemoryDynamicPromptToCompletionRatio)
        assertEquals(90, settings.agentConfig.memory.longTermMemoryDynamicCompletionMinPromptTokens)
        assertEquals(false, settings.agentConfig.metaReasoner.dynamicCompletionEnabled)
        assertEquals(700, settings.agentConfig.metaReasoner.dynamicCompletionHardMaxTokens)
        assertEquals(0.22, settings.agentConfig.metaReasoner.dynamicPromptToCompletionRatio)
        assertEquals(130, settings.agentConfig.metaReasoner.dynamicCompletionMinPromptTokens)
    }

    @Test
    fun `env overrides yaml values`() {
        val tempDir = Files.createTempDirectory("psyke-agent-runtime-env")
        val yamlPath = tempDir.resolve("agent-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            app:
              dashboard_enabled: false
              dashboard_port: 9200
            eval:
              max_raw_response_chars: 4444
              default_stage: yaml-stage
            agent:
              max_loop_steps_per_input: 21
              max_run_total_tokens: 5000
              llm_retry_attempts: 4
            """.trimIndent()
        )

        val settings = AgentRuntimeSettingsLoader.load(
            env = mapOf(
                "EGO_MAX_LOOP_STEPS" to "77",
                "EGO_LLM_RETRY_ATTEMPTS" to "3",
                "EGO_MAX_RUN_TOTAL_TOKENS" to "7000",
                "EGO_TASK_WORKSPACE_ENABLED" to "true",
                "EGO_TASK_WORKSPACE_FINAL_PASS_REWRITE_ENABLED" to "false",
                "EGO_TASK_WORKSPACE_DEBUG_CAPTURE_ENABLED" to "true",
                "PSYKE_DASHBOARD_ENABLED" to "true",
                "PSYKE_DASHBOARD_PORT" to "9900",
                "PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS" to "5555",
                "PSYKE_EVAL_STAGE" to "env-stage"
            ),
            defaultPath = yamlPath
        )

        assertEquals(77, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(3, settings.agentConfig.planner.llmRetryAttempts)
        assertEquals(7000, settings.agentConfig.planner.maxRunTotalTokens)
        assertEquals(true, settings.agentConfig.memory.taskWorkspace.enabled)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.finalPassRewriteEnabled)
        assertEquals(true, settings.agentConfig.memory.taskWorkspace.debugCaptureEnabled)
        assertEquals(true, settings.dashboardEnabled)
        assertEquals(9900, settings.dashboardPort)
        assertEquals(5555, settings.evalMaxRawResponseChars)
        assertEquals("env-stage", settings.evalDefaultStage)
    }

    @Test
    fun `terminal-answer memory force flag supports yaml and env override`() {
        val tempDir = Files.createTempDirectory("psyke-agent-runtime-terminal-force")
        val yamlPath = tempDir.resolve("agent-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            agent:
              long_term_memory_force_assess_on_terminal_answer: false
            """.trimIndent()
        )

        val yamlSettings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )
        assertEquals(false, yamlSettings.agentConfig.memory.longTermMemoryForceAssessOnTerminalAnswer)

        val envSettings = AgentRuntimeSettingsLoader.load(
            env = mapOf("EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER" to "true"),
            defaultPath = yamlPath
        )
        assertEquals(true, envSettings.agentConfig.memory.longTermMemoryForceAssessOnTerminalAnswer)
    }

    @Test
    fun `memory recall echo thresholds support yaml and env override`() {
        val tempDir = Files.createTempDirectory("psyke-agent-runtime-recall-echo")
        val yamlPath = tempDir.resolve("agent-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            agent:
              long_term_memory_recall_echo_min_summary_chars: 20
              long_term_memory_recall_echo_min_token_length: 4
              long_term_memory_recall_echo_min_token_count: 5
              long_term_memory_recall_echo_token_overlap_threshold: 0.9
            """.trimIndent()
        )

        val yamlSettings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )
        assertEquals(20, yamlSettings.agentConfig.memory.longTermMemoryRecallEchoMinSummaryChars)
        assertEquals(4, yamlSettings.agentConfig.memory.longTermMemoryRecallEchoMinTokenLength)
        assertEquals(5, yamlSettings.agentConfig.memory.longTermMemoryRecallEchoMinTokenCount)
        assertEquals(0.9, yamlSettings.agentConfig.memory.longTermMemoryRecallEchoTokenOverlapThreshold)

        val envSettings = AgentRuntimeSettingsLoader.load(
            env = mapOf(
                "EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS" to "22",
                "EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH" to "5",
                "EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT" to "6",
                "EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD" to "0.92"
            ),
            defaultPath = yamlPath
        )
        assertEquals(22, envSettings.agentConfig.memory.longTermMemoryRecallEchoMinSummaryChars)
        assertEquals(5, envSettings.agentConfig.memory.longTermMemoryRecallEchoMinTokenLength)
        assertEquals(6, envSettings.agentConfig.memory.longTermMemoryRecallEchoMinTokenCount)
        assertEquals(0.92, envSettings.agentConfig.memory.longTermMemoryRecallEchoTokenOverlapThreshold)
    }

    @Test
    fun `load tolerates comments-only eval section`() {
        val tempDir = Files.createTempDirectory("psyke-agent-runtime-empty-eval")
        val yamlPath = tempDir.resolve("agent-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            app:
              dashboard_enabled: true
            eval:
              # default_stage: local
              # max_raw_response_chars: 2048
            agent:
              max_loop_steps_per_input: 42
            """.trimIndent()
        )

        val settings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertEquals(true, settings.dashboardEnabled)
        assertEquals(42, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(Int.MAX_VALUE, settings.evalMaxRawResponseChars)
        assertEquals(null, settings.evalDefaultStage)
    }
}
