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

        assertEquals(180, settings.agentConfig.maxLoopStepsPerInput)
        assertEquals(0, settings.agentConfig.loopDelayMs)
        assertEquals(2, settings.agentConfig.llmRetryAttempts)
        assertEquals(192, settings.agentConfig.superegoMaxCompletionTokens)
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
        assertEquals(21, settings.agentConfig.maxLoopStepsPerInput)
        assertEquals(999, settings.agentConfig.maxPromptTokens)
        assertEquals(4, settings.agentConfig.llmRetryAttempts)
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

        assertEquals(77, settings.agentConfig.maxLoopStepsPerInput)
        assertEquals(3, settings.agentConfig.llmRetryAttempts)
        assertEquals(true, settings.dashboardEnabled)
        assertEquals(9900, settings.dashboardPort)
        assertEquals(5555, settings.evalMaxRawResponseChars)
        assertEquals("env-stage", settings.evalDefaultStage)
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
        assertEquals(42, settings.agentConfig.maxLoopStepsPerInput)
        assertEquals(Int.MAX_VALUE, settings.evalMaxRawResponseChars)
        assertEquals(null, settings.evalDefaultStage)
    }
}
