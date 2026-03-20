package ai.neopsyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRuntimeSettingsLoaderTest {
    @Test
    fun `load falls back to defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("neopsyke-agent-runtime-missing")
        val settings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        assertEquals(180, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(5, settings.agentConfig.planner.maxThoughtPasses)
        assertEquals(6, settings.agentConfig.planner.maxPlanSteps)
        assertEquals(120, settings.agentConfig.planner.maxPlanStepDescriptionChars)
        assertEquals(2, settings.agentConfig.planner.maxPlansPerInput)
        assertEquals(3, settings.agentConfig.planner.actionRetryBudgetNonRetryableFailures)
        assertEquals(10, settings.agentConfig.planner.actionRetryCooldownSteps)

        assertEquals(192, settings.agentConfig.superego.maxCompletionTokens)
        assertEquals(true, settings.agentConfig.superego.dynamicCompletionEnabled)
        assertEquals(true, settings.agentConfig.superego.twoStageSkipForContactUserActions)
        assertEquals(true, settings.agentConfig.superego.twoStageSkipForWebSearchActions)

        assertEquals(false, settings.agentConfig.memory.taskWorkspace.enabled)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.debugCaptureEnabled)
        assertEquals(4, settings.agentConfig.memory.taskWorkspace.digestMaxEntries)
        assertEquals(true, settings.agentConfig.memory.longTermMemoryPromptCompressionEnabled)

        assertEquals(true, settings.agentConfig.metaReasoner.dynamicCompletionEnabled)
        assertEquals(640, settings.agentConfig.metaReasoner.dynamicCompletionHardMaxTokens)

        assertEquals(true, settings.agentConfig.logbook.enabled)
        assertEquals(200, settings.agentConfig.logbook.maxSummaryChars)
        assertEquals(12, settings.agentConfig.logbook.maxKeywordsPerEntry)

        assertEquals(0, settings.agentConfig.loopDelayMs)
        assertEquals(64, settings.agentConfig.maxPendingThoughts)
        assertEquals(32, settings.agentConfig.maxPendingActions)
        assertEquals(32, settings.agentConfig.maxPendingInputs)
        assertEquals(5, settings.agentConfig.searchResultCount)
        assertEquals(8_000, settings.agentConfig.mcpCallTimeoutMs)
        assertEquals(4_000, settings.agentConfig.fetchMaxChars)

        assertTrue(settings.dashboardEnabled)
        assertEquals(8787, settings.dashboardPort)
        assertEquals(Int.MAX_VALUE, settings.evalMaxRawResponseChars)
        assertNull(settings.evalDefaultStage)
    }

    @Test
    fun `load reads grouped yaml values for all domains`() {
        val tempDir = Files.createTempDirectory("neopsyke-agent-runtime-yaml")
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
              planner:
                max_loop_steps_per_input: 21
                max_prompt_tokens: 999
                max_run_total_tokens: 5000
                max_run_tokens_per_provider: 3200
                max_run_tokens_per_role: 1800
                llm_retry_attempts: 4
                max_plan_steps: 9
                max_plan_step_description_chars: 140
                max_plans_per_input: 3
                action_retry_budget_non_retryable_failures: 7
                action_retry_cooldown_steps: 15
              superego:
                dynamic_completion_enabled: false
                dynamic_completion_hard_max_tokens: 700
                dynamic_prompt_to_completion_ratio: 0.21
                dynamic_completion_min_prompt_tokens: 110
                two_stage_review_enabled: true
                two_stage_low_confidence_threshold: 0.74
                two_stage_escalate_on_medium_policy_risk: false
                two_stage_skip_for_contact_user_actions: false
                two_stage_skip_for_web_search_actions: false
              memory:
                long_term_memory_prompt_compression_enabled: true
                long_term_memory_prompt_dialogue_max_chars: 780
                long_term_memory_prompt_recall_max_chars: 640
                long_term_memory_dynamic_completion_enabled: false
                long_term_memory_dynamic_completion_hard_max_tokens: 480
                long_term_memory_dynamic_prompt_to_completion_ratio: 0.12
                long_term_memory_dynamic_completion_min_prompt_tokens: 90
                task_workspace:
                  enabled: true
                  max_prompt_tokens: 300
                  final_pass_rewrite_enabled: false
                  final_pass_max_tokens: 190
                  final_pass_min_workspace_confidence: 0.42
                  final_pass_min_model_confidence: 0.61
                  debug_capture_enabled: false
              meta_reasoner:
                dynamic_completion_enabled: false
                dynamic_completion_hard_max_tokens: 700
                dynamic_prompt_to_completion_ratio: 0.22
                dynamic_completion_min_prompt_tokens: 130
              logbook:
                enabled: false
                max_summary_chars: 222
                max_keywords_per_entry: 7
                max_entries_per_query: 9
                retention_days: 31
                db_path: /tmp/neopsyke.logbook.db
                episodic_recall_max_chars: 333
                episodic_recall_max_results: 11
                use_llm_summarizer: true
              runtime:
                loop_delay_ms: 9
                max_pending_thoughts: 11
                max_pending_actions: 12
                max_pending_inputs: 13
                search_result_count: 8
                mcp_call_timeout_ms: 12345
                fetch_max_chars: 7777
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
        assertEquals(999, settings.agentConfig.maxLlmPromptTokens)
        assertEquals(5000, settings.agentConfig.planner.maxRunTotalTokens)
        assertEquals(3200, settings.agentConfig.planner.maxRunTokensPerProvider)
        assertEquals(1800, settings.agentConfig.planner.maxRunTokensPerRole)
        assertEquals(4, settings.agentConfig.llmRetryAttempts)
        assertEquals(9, settings.agentConfig.planner.maxPlanSteps)
        assertEquals(140, settings.agentConfig.planner.maxPlanStepDescriptionChars)
        assertEquals(3, settings.agentConfig.planner.maxPlansPerInput)
        assertEquals(7, settings.agentConfig.planner.actionRetryBudgetNonRetryableFailures)
        assertEquals(15, settings.agentConfig.planner.actionRetryCooldownSteps)

        assertEquals(false, settings.agentConfig.superego.dynamicCompletionEnabled)
        assertEquals(700, settings.agentConfig.superego.dynamicCompletionHardMaxTokens)
        assertEquals(0.21, settings.agentConfig.superego.dynamicPromptToCompletionRatio)
        assertEquals(110, settings.agentConfig.superego.dynamicCompletionMinPromptTokens)
        assertEquals(true, settings.agentConfig.superego.twoStageReviewEnabled)
        assertEquals(0.74, settings.agentConfig.superego.twoStageLowConfidenceThreshold)
        assertEquals(false, settings.agentConfig.superego.twoStageEscalateOnMediumPolicyRisk)
        assertEquals(false, settings.agentConfig.superego.twoStageSkipForContactUserActions)
        assertEquals(false, settings.agentConfig.superego.twoStageSkipForWebSearchActions)

        assertEquals(true, settings.agentConfig.memory.taskWorkspace.enabled)
        assertEquals(300, settings.agentConfig.memory.taskWorkspace.maxPromptTokens)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.finalPassRewriteEnabled)
        assertEquals(190, settings.agentConfig.memory.taskWorkspace.finalPassMaxTokens)
        assertEquals(0.42, settings.agentConfig.memory.taskWorkspace.finalPassMinWorkspaceConfidence)
        assertEquals(0.61, settings.agentConfig.memory.taskWorkspace.finalPassMinModelConfidence)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.debugCaptureEnabled)
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

        assertEquals(false, settings.agentConfig.logbook.enabled)
        assertEquals(222, settings.agentConfig.logbook.maxSummaryChars)
        assertEquals(7, settings.agentConfig.logbook.maxKeywordsPerEntry)
        assertEquals(9, settings.agentConfig.logbook.maxEntriesPerQuery)
        assertEquals(31, settings.agentConfig.logbook.retentionDays)
        assertEquals("/tmp/neopsyke.logbook.db", settings.agentConfig.logbook.dbPath)
        assertEquals(333, settings.agentConfig.logbook.episodicRecallMaxChars)
        assertEquals(11, settings.agentConfig.logbook.episodicRecallMaxResults)
        assertEquals(true, settings.agentConfig.logbook.useLlmSummarizer)

        assertEquals(9, settings.agentConfig.loopDelayMs)
        assertEquals(11, settings.agentConfig.maxPendingThoughts)
        assertEquals(12, settings.agentConfig.maxPendingActions)
        assertEquals(13, settings.agentConfig.maxPendingInputs)
        assertEquals(8, settings.agentConfig.searchResultCount)
        assertEquals(12345, settings.agentConfig.mcpCallTimeoutMs)
        assertEquals(7777, settings.agentConfig.fetchMaxChars)
    }

    @Test
    fun `env overrides grouped yaml values`() {
        val tempDir = Files.createTempDirectory("neopsyke-agent-runtime-env")
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
              planner:
                max_loop_steps_per_input: 21
                llm_retry_attempts: 4
                max_run_total_tokens: 5000
              memory:
                task_workspace:
                  enabled: false
                  final_pass_rewrite_enabled: true
                  debug_capture_enabled: false
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
                "NEOPSYKE_DASHBOARD_ENABLED" to "true",
                "NEOPSYKE_DASHBOARD_PORT" to "9900",
                "NEOPSYKE_EVAL_MAX_RAW_RESPONSE_CHARS" to "5555",
                "NEOPSYKE_EVAL_STAGE" to "env-stage"
            ),
            defaultPath = yamlPath
        )

        assertEquals(77, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(3, settings.agentConfig.llmRetryAttempts)
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
    fun `legacy flat agent keys are ignored`() {
        val tempDir = Files.createTempDirectory("neopsyke-agent-runtime-flat")
        val yamlPath = tempDir.resolve("agent-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            agent:
              max_loop_steps_per_input: 9
              task_workspace_enabled: true
              task_workspace_debug_capture_enabled: true
              superego_dynamic_completion_enabled: false
            """.trimIndent()
        )

        val settings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertEquals(180, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.enabled)
        assertEquals(false, settings.agentConfig.memory.taskWorkspace.debugCaptureEnabled)
        assertEquals(true, settings.agentConfig.superego.dynamicCompletionEnabled)
    }

    @Test
    fun `load tolerates comments-only eval section`() {
        val tempDir = Files.createTempDirectory("neopsyke-agent-runtime-empty-eval")
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
              planner:
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
        assertNull(settings.evalDefaultStage)
    }
}
