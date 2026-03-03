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
        assertEquals(192, settings.agentConfig.superego.maxCompletionTokens)
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
              llm_retry_attempts: 4
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
        assertEquals(4, settings.agentConfig.planner.llmRetryAttempts)
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
              llm_retry_attempts: 4
            """.trimIndent()
        )

        val settings = AgentRuntimeSettingsLoader.load(
            env = mapOf(
                "EGO_MAX_LOOP_STEPS" to "77",
                "EGO_LLM_RETRY_ATTEMPTS" to "3",
                "PSYKE_DASHBOARD_ENABLED" to "true",
                "PSYKE_DASHBOARD_PORT" to "9900",
                "PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS" to "5555",
                "PSYKE_EVAL_STAGE" to "env-stage"
            ),
            defaultPath = yamlPath
        )

        assertEquals(77, settings.agentConfig.planner.maxLoopStepsPerInput)
        assertEquals(3, settings.agentConfig.planner.llmRetryAttempts)
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
