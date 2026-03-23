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

        assertEquals(false, settings.agentConfig.memory.scratchpad.enabled)
        assertEquals(false, settings.agentConfig.memory.scratchpad.debugCaptureEnabled)
        assertEquals(4, settings.agentConfig.memory.scratchpad.digestMaxEntries)
        assertEquals(true, settings.agentConfig.memory.longTermMemoryPromptCompressionEnabled)

        assertEquals(true, settings.agentConfig.metaReasoner.dynamicCompletionEnabled)
        assertEquals(640, settings.agentConfig.metaReasoner.dynamicCompletionHardMaxTokens)

        assertEquals(true, settings.agentConfig.logbook.enabled)
        assertEquals(200, settings.agentConfig.logbook.maxSummaryChars)
        assertEquals(12, settings.agentConfig.logbook.maxKeywordsPerEntry)
        assertEquals(true, settings.agentConfig.actionControl.enabled)
        assertEquals(".neopsyke/action-control.db", settings.agentConfig.actionControl.dbPath)
        assertEquals("action-security.yaml", settings.agentConfig.actionControl.policyPath)
        assertEquals(true, settings.agentConfig.actionControl.autonomousWorkerEnabled)
        assertEquals(500L, settings.agentConfig.actionControl.autonomousWorkerPollMs)
        assertEquals(16, settings.agentConfig.actionControl.autonomousWorkerBatchSize)
        assertEquals(false, settings.agentConfig.connectors.enabled)
        assertEquals("connectors/catalog", settings.agentConfig.connectors.curatedCatalogPath)
        assertEquals(".neopsyke/connectors", settings.agentConfig.connectors.installStateDir)
        assertEquals(true, settings.agentConfig.connectors.failClosed)
        assertEquals(true, settings.agentConfig.connectors.pinningEnabled)
        assertEquals(5_000L, settings.agentConfig.connectors.startupTimeoutMs)
        assertEquals(5_000L, settings.agentConfig.connectors.healthTimeoutMs)
        assertTrue(settings.agentConfig.connectors.allowedConnectorIds.isEmpty())
        assertTrue(settings.agentConfig.connectors.enabledBundleIds.isEmpty())
        assertEquals(false, settings.agentConfig.connectors.allowThirdPartyConnectors)
        assertEquals(false, settings.agentConfig.nativeIntegrations.telegram.enabled)
        assertEquals("/api/channels/telegram/webhook", settings.agentConfig.nativeIntegrations.telegram.webhookPath)
        assertEquals("TELEGRAM_BOT_TOKEN", settings.agentConfig.nativeIntegrations.telegram.botTokenHandle)
        assertEquals("TELEGRAM_WEBHOOK_SECRET", settings.agentConfig.nativeIntegrations.telegram.webhookSecretHandle)
        assertEquals(false, settings.agentConfig.nativeIntegrations.googleWorkspace.enabled)
        assertEquals(".neopsyke/auth/google", settings.agentConfig.nativeIntegrations.googleWorkspace.tokenStoreDir)
        assertEquals(
            "GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET",
            settings.agentConfig.nativeIntegrations.googleWorkspace.oauthTokenEncryptionSecretHandle,
        )
        assertEquals(600L, settings.agentConfig.nativeIntegrations.googleWorkspace.oauthStateTtlSeconds)
        assertEquals(
            setOf(
                "https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/calendar.readonly",
            ),
            settings.agentConfig.nativeIntegrations.googleWorkspace.scopes,
        )

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
                scratchpad:
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
              action_control:
                enabled: true
                db_path: /tmp/neopsyke-action-control.db
                policy_path: /tmp/neopsyke-action-security.yaml
                authorization_ttl_ms: 555000
                max_inspect_results: 75
                autonomous_worker_enabled: false
                autonomous_worker_poll_ms: 4321
                autonomous_worker_batch_size: 9
              connectors:
                enabled: true
                curated_catalog_path: /tmp/neopsyke-connectors/catalog
                install_state_dir: /tmp/neopsyke-connectors/state
                fail_closed: false
                pinning_enabled: false
                startup_timeout_ms: 6123
                health_timeout_ms: 7123
                allowed_connector_ids:
                  - gmail
                  - telegram
                enabled_bundle_ids:
                  - morning-briefing
                allow_third_party_connectors: true
              native_integrations:
                telegram:
                  enabled: true
                  webhook_path: /hooks/telegram
                  owner_chat_id: 1234
                  owner_user_id: 5678
                  bot_token_handle: TELEGRAM_TOKEN_HANDLE
                  webhook_secret_handle: TELEGRAM_SECRET_HANDLE
                  policy_scope_id: telegram-owner-direct
                  session_id_prefix: telegram-owner
                  require_direct_chat: true
                  drop_unauthorized_messages: false
                google_workspace:
                  enabled: true
                  token_store_dir: /tmp/neopsyke-google-auth
                  allowed_owner_email: owner@example.com
                  oauth_client_id_handle: GOOGLE_CLIENT_ID_HANDLE
                  oauth_client_secret_handle: GOOGLE_CLIENT_SECRET_HANDLE
                  oauth_state_signing_secret_handle: GOOGLE_STATE_HANDLE
                  oauth_token_encryption_secret_handle: GOOGLE_TOKEN_ENCRYPTION_HANDLE
                  callback_path: /oauth/google/callback
                  authorization_base_url: https://accounts.example.test/auth
                  token_base_url: https://accounts.example.test/token
                  require_pkce: false
                  require_refresh_token: false
                  oauth_state_ttl_seconds: 120
                  scopes:
                    - https://www.googleapis.com/auth/gmail.readonly
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

        assertEquals(true, settings.agentConfig.memory.scratchpad.enabled)
        assertEquals(300, settings.agentConfig.memory.scratchpad.maxPromptTokens)
        assertEquals(false, settings.agentConfig.memory.scratchpad.finalPassRewriteEnabled)
        assertEquals(190, settings.agentConfig.memory.scratchpad.finalPassMaxTokens)
        assertEquals(0.42, settings.agentConfig.memory.scratchpad.finalPassMinWorkspaceConfidence)
        assertEquals(0.61, settings.agentConfig.memory.scratchpad.finalPassMinModelConfidence)
        assertEquals(false, settings.agentConfig.memory.scratchpad.debugCaptureEnabled)
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
        assertEquals(true, settings.agentConfig.actionControl.enabled)
        assertEquals("/tmp/neopsyke-action-control.db", settings.agentConfig.actionControl.dbPath)
        assertEquals("/tmp/neopsyke-action-security.yaml", settings.agentConfig.actionControl.policyPath)
        assertEquals(555000, settings.agentConfig.actionControl.authorizationTtlMs)
        assertEquals(75, settings.agentConfig.actionControl.maxInspectResults)
        assertEquals(false, settings.agentConfig.actionControl.autonomousWorkerEnabled)
        assertEquals(4321L, settings.agentConfig.actionControl.autonomousWorkerPollMs)
        assertEquals(9, settings.agentConfig.actionControl.autonomousWorkerBatchSize)
        assertEquals(true, settings.agentConfig.connectors.enabled)
        assertEquals("/tmp/neopsyke-connectors/catalog", settings.agentConfig.connectors.curatedCatalogPath)
        assertEquals("/tmp/neopsyke-connectors/state", settings.agentConfig.connectors.installStateDir)
        assertEquals(false, settings.agentConfig.connectors.failClosed)
        assertEquals(false, settings.agentConfig.connectors.pinningEnabled)
        assertEquals(6123L, settings.agentConfig.connectors.startupTimeoutMs)
        assertEquals(7123L, settings.agentConfig.connectors.healthTimeoutMs)
        assertEquals(setOf("gmail", "telegram"), settings.agentConfig.connectors.allowedConnectorIds)
        assertEquals(setOf("morning-briefing"), settings.agentConfig.connectors.enabledBundleIds)
        assertEquals(true, settings.agentConfig.connectors.allowThirdPartyConnectors)
        assertEquals(true, settings.agentConfig.nativeIntegrations.telegram.enabled)
        assertEquals("/hooks/telegram", settings.agentConfig.nativeIntegrations.telegram.webhookPath)
        assertEquals("1234", settings.agentConfig.nativeIntegrations.telegram.ownerChatId)
        assertEquals("5678", settings.agentConfig.nativeIntegrations.telegram.ownerUserId)
        assertEquals("TELEGRAM_TOKEN_HANDLE", settings.agentConfig.nativeIntegrations.telegram.botTokenHandle)
        assertEquals("TELEGRAM_SECRET_HANDLE", settings.agentConfig.nativeIntegrations.telegram.webhookSecretHandle)
        assertEquals("telegram-owner-direct", settings.agentConfig.nativeIntegrations.telegram.policyScopeId)
        assertEquals("telegram-owner", settings.agentConfig.nativeIntegrations.telegram.sessionIdPrefix)
        assertEquals(true, settings.agentConfig.nativeIntegrations.telegram.requireDirectChat)
        assertEquals(false, settings.agentConfig.nativeIntegrations.telegram.dropUnauthorizedMessages)
        assertEquals(true, settings.agentConfig.nativeIntegrations.googleWorkspace.enabled)
        assertEquals("/tmp/neopsyke-google-auth", settings.agentConfig.nativeIntegrations.googleWorkspace.tokenStoreDir)
        assertEquals("owner@example.com", settings.agentConfig.nativeIntegrations.googleWorkspace.allowedOwnerEmail)
        assertEquals("GOOGLE_CLIENT_ID_HANDLE", settings.agentConfig.nativeIntegrations.googleWorkspace.oauthClientIdHandle)
        assertEquals("GOOGLE_CLIENT_SECRET_HANDLE", settings.agentConfig.nativeIntegrations.googleWorkspace.oauthClientSecretHandle)
        assertEquals("GOOGLE_STATE_HANDLE", settings.agentConfig.nativeIntegrations.googleWorkspace.oauthStateSigningSecretHandle)
        assertEquals(
            "GOOGLE_TOKEN_ENCRYPTION_HANDLE",
            settings.agentConfig.nativeIntegrations.googleWorkspace.oauthTokenEncryptionSecretHandle,
        )
        assertEquals("/oauth/google/callback", settings.agentConfig.nativeIntegrations.googleWorkspace.callbackPath)
        assertEquals("https://accounts.example.test/auth", settings.agentConfig.nativeIntegrations.googleWorkspace.authorizationBaseUrl)
        assertEquals("https://accounts.example.test/token", settings.agentConfig.nativeIntegrations.googleWorkspace.tokenBaseUrl)
        assertEquals(false, settings.agentConfig.nativeIntegrations.googleWorkspace.requirePkce)
        assertEquals(false, settings.agentConfig.nativeIntegrations.googleWorkspace.requireRefreshToken)
        assertEquals(120L, settings.agentConfig.nativeIntegrations.googleWorkspace.oauthStateTtlSeconds)
        assertEquals(
            setOf("https://www.googleapis.com/auth/gmail.readonly"),
            settings.agentConfig.nativeIntegrations.googleWorkspace.scopes,
        )

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
                scratchpad:
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
                "EGO_SCRATCHPAD_ENABLED" to "true",
                "EGO_SCRATCHPAD_FINAL_PASS_REWRITE_ENABLED" to "false",
                "EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED" to "true",
                "NEOPSYKE_CONNECTORS_ENABLED" to "true",
                "NEOPSYKE_CONNECTORS_CATALOG_PATH" to "/env/catalog",
                "NEOPSYKE_CONNECTORS_STATE_DIR" to "/env/state",
                "NEOPSYKE_CONNECTORS_FAIL_CLOSED" to "true",
                "NEOPSYKE_CONNECTORS_PINNING_ENABLED" to "true",
                "NEOPSYKE_CONNECTORS_STARTUP_TIMEOUT_MS" to "8111",
                "NEOPSYKE_CONNECTORS_HEALTH_TIMEOUT_MS" to "8222",
                "NEOPSYKE_CONNECTORS_ALLOWED_IDS" to "gmail, telegram",
                "NEOPSYKE_CONNECTORS_ENABLED_BUNDLES" to "morning-briefing, inbox-management",
                "NEOPSYKE_CONNECTORS_ALLOW_THIRD_PARTY" to "false",
                "NEOPSYKE_TELEGRAM_ENABLED" to "true",
                "NEOPSYKE_TELEGRAM_WEBHOOK_PATH" to "/env/telegram/webhook",
                "NEOPSYKE_TELEGRAM_OWNER_CHAT_ID" to "999",
                "NEOPSYKE_TELEGRAM_OWNER_USER_ID" to "111",
                "NEOPSYKE_TELEGRAM_BOT_TOKEN_HANDLE" to "ENV_TELEGRAM_TOKEN",
                "NEOPSYKE_TELEGRAM_WEBHOOK_SECRET_HANDLE" to "ENV_TELEGRAM_SECRET",
                "NEOPSYKE_GOOGLE_WORKSPACE_ENABLED" to "true",
                "NEOPSYKE_GOOGLE_TOKEN_STORE_DIR" to "/env/google-auth",
                "NEOPSYKE_GOOGLE_ALLOWED_OWNER_EMAIL" to "env-owner@example.com",
                "NEOPSYKE_GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET_HANDLE" to "ENV_GOOGLE_TOKEN_ENCRYPTION",
                "NEOPSYKE_GOOGLE_OAUTH_STATE_TTL_SECONDS" to "180",
                "NEOPSYKE_GOOGLE_SCOPES" to "https://www.googleapis.com/auth/gmail.readonly, https://www.googleapis.com/auth/calendar.readonly",
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
        assertEquals(true, settings.agentConfig.memory.scratchpad.enabled)
        assertEquals(false, settings.agentConfig.memory.scratchpad.finalPassRewriteEnabled)
        assertEquals(true, settings.agentConfig.memory.scratchpad.debugCaptureEnabled)
        assertEquals(true, settings.agentConfig.connectors.enabled)
        assertEquals("/env/catalog", settings.agentConfig.connectors.curatedCatalogPath)
        assertEquals("/env/state", settings.agentConfig.connectors.installStateDir)
        assertEquals(true, settings.agentConfig.connectors.failClosed)
        assertEquals(true, settings.agentConfig.connectors.pinningEnabled)
        assertEquals(8111L, settings.agentConfig.connectors.startupTimeoutMs)
        assertEquals(8222L, settings.agentConfig.connectors.healthTimeoutMs)
        assertEquals(setOf("gmail", "telegram"), settings.agentConfig.connectors.allowedConnectorIds)
        assertEquals(setOf("morning-briefing", "inbox-management"), settings.agentConfig.connectors.enabledBundleIds)
        assertEquals(false, settings.agentConfig.connectors.allowThirdPartyConnectors)
        assertEquals(true, settings.agentConfig.nativeIntegrations.telegram.enabled)
        assertEquals("/env/telegram/webhook", settings.agentConfig.nativeIntegrations.telegram.webhookPath)
        assertEquals("999", settings.agentConfig.nativeIntegrations.telegram.ownerChatId)
        assertEquals("111", settings.agentConfig.nativeIntegrations.telegram.ownerUserId)
        assertEquals("ENV_TELEGRAM_TOKEN", settings.agentConfig.nativeIntegrations.telegram.botTokenHandle)
        assertEquals("ENV_TELEGRAM_SECRET", settings.agentConfig.nativeIntegrations.telegram.webhookSecretHandle)
        assertEquals(true, settings.agentConfig.nativeIntegrations.googleWorkspace.enabled)
        assertEquals("/env/google-auth", settings.agentConfig.nativeIntegrations.googleWorkspace.tokenStoreDir)
        assertEquals("env-owner@example.com", settings.agentConfig.nativeIntegrations.googleWorkspace.allowedOwnerEmail)
        assertEquals(
            "ENV_GOOGLE_TOKEN_ENCRYPTION",
            settings.agentConfig.nativeIntegrations.googleWorkspace.oauthTokenEncryptionSecretHandle,
        )
        assertEquals(180L, settings.agentConfig.nativeIntegrations.googleWorkspace.oauthStateTtlSeconds)
        assertEquals(
            setOf(
                "https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/calendar.readonly",
            ),
            settings.agentConfig.nativeIntegrations.googleWorkspace.scopes,
        )
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
              scratchpad_enabled: true
              scratchpad_debug_capture_enabled: true
              superego_dynamic_completion_enabled: false
            """.trimIndent()
        )

        val settings = AgentRuntimeSettingsLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertEquals(180, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(false, settings.agentConfig.memory.scratchpad.enabled)
        assertEquals(false, settings.agentConfig.memory.scratchpad.debugCaptureEnabled)
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
