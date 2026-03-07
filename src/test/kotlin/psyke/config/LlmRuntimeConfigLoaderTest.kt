package psyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LlmRuntimeConfigLoaderTest {
    @Test
    fun `load falls back to defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-missing")
        val config = LlmRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.GROQ, resolved.planner.provider)
        assertEquals("https://api.groq.com/openai/v1", resolved.planner.baseUrl)
        assertEquals("GROQ_API_KEY", resolved.planner.apiKeyEnvVar)
        assertEquals("openai/gpt-oss-20b", resolved.planner.model)
        assertEquals("openai/gpt-oss-20b", resolved.superego.model)
        assertEquals("openai/gpt-oss-20b", resolved.actionVerifier.model)
        assertEquals("openai/gpt-oss-20b", resolved.metaReasoner.model)
        assertEquals("openai/gpt-oss-20b", resolved.memoryAdvisor.model)
        assertEquals(LlmProvider.GROQ, resolved.webSearch.provider)
        assertEquals("https://api.groq.com/openai/v1", resolved.webSearch.baseUrl)
        assertEquals("GROQ_API_KEY", resolved.webSearch.apiKeyEnvVar)
        assertEquals("openai/gpt-oss-20b", resolved.webSearch.model)
    }

    @Test
    fun `load reads role based yaml values`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-yaml")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            providers:
              groq:
                api_key_env: CUSTOM_GROQ_KEY
                base_url: https://custom.groq.test/openai/v1
              google:
                api_key_env: CUSTOM_GOOGLE_KEY
                base_url: https://custom.google.test/v1beta/openai/
            cognitive_roles:
              planner:
                provider: google
                model: gemini-3.1-flash-lite-preview
              action_verifier:
                provider: groq
                model: openai/gpt-oss-20b
              superego:
                provider: groq
                model: openai/gpt-oss-safeguard-20b
              meta_reasoner:
                provider: google
                model: gemini-3.1-flash-lite-preview
              memory_advisor:
                provider: groq
                model: openai/gpt-oss-20b
            web_search:
              provider: groq
              model: groq/compound-mini
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "CUSTOM_GROQ_KEY" to "groq-key",
                "CUSTOM_GOOGLE_KEY" to "google-key"
            ),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.GOOGLE, resolved.planner.provider)
        assertEquals("gemini-3.1-flash-lite-preview", resolved.planner.model)
        assertEquals("google-key", resolved.planner.apiKey)
        assertEquals("https://custom.google.test/v1beta/openai/", resolved.planner.baseUrl)

        assertEquals(LlmProvider.GROQ, resolved.actionVerifier.provider)
        assertEquals("openai/gpt-oss-20b", resolved.actionVerifier.model)
        assertEquals(LlmProvider.GROQ, resolved.superego.provider)
        assertEquals("openai/gpt-oss-safeguard-20b", resolved.superego.model)
        assertEquals(LlmProvider.GROQ, resolved.memoryAdvisor.provider)
        assertEquals("openai/gpt-oss-20b", resolved.memoryAdvisor.model)

        assertEquals(LlmProvider.GROQ, resolved.webSearch.provider)
        assertEquals("groq/compound-mini", resolved.webSearch.model)
        assertEquals("groq-key", resolved.webSearch.apiKey)
    }

    @Test
    fun `env provider model overrides are ignored and keys come from configured api_key_env`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-no-env-routing-overrides")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            providers:
              mistral:
                api_key_env: CUSTOM_MISTRAL_KEY
            cognitive_roles:
              planner:
                provider: mistral
                model: mistral-small-latest
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "LLM_PROVIDER" to "groq",
                "LLM_EGO_MODEL" to "openai/gpt-oss-120b",
                "MISTRAL_API_KEY" to "wrong-key",
                "CUSTOM_MISTRAL_KEY" to "right-key"
            ),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.MISTRAL, resolved.planner.provider)
        assertEquals("mistral-small-latest", resolved.planner.model)
        assertEquals("right-key", resolved.planner.apiKey)
        assertEquals("CUSTOM_MISTRAL_KEY", resolved.planner.apiKeyEnvVar)
    }

    @Test
    fun `legacy yaml shape still works and maps models to cognitive roles`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-legacy")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            provider: groq
            models:
              ego: openai/gpt-oss-20b
              superego: openai/gpt-oss-safeguard-20b
              meta_reasoner: openai/gpt-oss-20b
              memory_consolidation: openai/gpt-oss-20b
              web_search: groq/compound-mini
            web_search:
              provider: groq
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf("GROQ_API_KEY" to "groq-key"),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.GROQ, resolved.planner.provider)
        assertEquals("openai/gpt-oss-20b", resolved.planner.model)
        assertEquals("openai/gpt-oss-safeguard-20b", resolved.superego.model)
        assertEquals("openai/gpt-oss-20b", resolved.metaReasoner.model)
        assertEquals("openai/gpt-oss-20b", resolved.memoryAdvisor.model)
        assertEquals("openai/gpt-oss-20b", resolved.actionVerifier.model)
        assertEquals("groq/compound-mini", resolved.webSearch.model)
    }
}
