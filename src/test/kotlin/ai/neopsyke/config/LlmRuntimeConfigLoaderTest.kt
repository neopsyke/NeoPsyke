package ai.neopsyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmRuntimeConfigLoaderTest {
    @Test
    fun `load falls back to defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-missing")
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
        assertTrue(resolved.modelCatalog.profiles(LlmProvider.MISTRAL).any { it.model == "mistral-large-2512" })
    }

    @Test
    fun `load reads role based yaml values`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-yaml")
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
              meta_reasoner_fallback:
                provider: openai
                model: gpt-5-mini
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
                "CUSTOM_GOOGLE_KEY" to "google-key",
                "OPENAI_API_KEY" to "openai-key"
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
        assertEquals(LlmProvider.OPENAI, resolved.metaReasonerFallback?.provider)
        assertEquals("gpt-5-mini", resolved.metaReasonerFallback?.model)

        assertEquals(LlmProvider.GROQ, resolved.webSearch.provider)
        assertEquals("groq/compound-mini", resolved.webSearch.model)
        assertEquals("groq-key", resolved.webSearch.apiKey)
    }

    @Test
    fun `env provider model overrides are ignored and keys come from configured api_key_env`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-no-env-routing-overrides")
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
                model: mistral-small-2506
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
        assertEquals("mistral-small-2506", resolved.planner.model)
        assertEquals("right-key", resolved.planner.apiKey)
        assertEquals("CUSTOM_MISTRAL_KEY", resolved.planner.apiKeyEnvVar)
    }

    @Test
    fun `legacy yaml shape still works and maps models to cognitive roles`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-legacy")
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

    @Test
    fun `load supports openai provider for all cognitive roles`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-openai")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            providers:
              openai:
                api_key_env: CUSTOM_OPENAI_KEY
                base_url: https://openai-proxy.test/v1
            cognitive_roles:
              planner:
                provider: openai
                model: gpt-4o-mini
              action_verifier:
                provider: openai
                model: gpt-4o-mini
              superego:
                provider: openai
                model: gpt-4.1-mini
              meta_reasoner:
                provider: openai
                model: gpt-4o-mini
              memory_advisor:
                provider: openai
                model: gpt-4o-mini
            web_search:
              provider: groq
              model: groq/compound-mini
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "CUSTOM_OPENAI_KEY" to "openai-key",
                "GROQ_API_KEY" to "groq-key"
            ),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.OPENAI, resolved.planner.provider)
        assertEquals(LlmProvider.OPENAI, resolved.actionVerifier.provider)
        assertEquals(LlmProvider.OPENAI, resolved.superego.provider)
        assertEquals(LlmProvider.OPENAI, resolved.metaReasoner.provider)
        assertEquals(LlmProvider.OPENAI, resolved.memoryAdvisor.provider)
        assertEquals("openai-key", resolved.planner.apiKey)
        assertEquals("https://openai-proxy.test/v1", resolved.planner.baseUrl)
        assertEquals("gpt-4.1-mini", resolved.superego.model)
        assertEquals(LlmProvider.GROQ, resolved.webSearch.provider)
    }

    @Test
    fun `load applies model catalog overrides for token weights`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-catalog")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            cognitive_roles:
              superego:
                provider: openai
                model: gpt-5-mini
              memory_advisor:
                provider: openai
                model: gpt-5-nano
            model_catalog:
              openai:
                - model: gpt-5-mini
                  tier: medium
                  token_weight: 1.55
                - model: gpt-5-nano
                  tier: light
                  token_weight: 0.95
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf("OPENAI_API_KEY" to "openai-key"),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(1.55, resolved.modelCatalog.tokenWeightFor(resolved.superego))
        assertEquals(0.95, resolved.modelCatalog.tokenWeightFor(resolved.memoryAdvisor))
        // Unconfigured providers keep default catalog entries.
        assertTrue(resolved.modelCatalog.profiles(LlmProvider.MISTRAL).isNotEmpty())
    }
}
