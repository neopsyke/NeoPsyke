package ai.neopsyke.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmRuntimeConfigLoaderTest {
    @Test
    fun `load falls back to bundled defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-missing")
        val config = LlmRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        val resolved = config
        assertEquals(LlmProvider.GROQ, resolved.planner.provider)
        assertEquals("openai/gpt-oss-120b", resolved.planner.model)
        assertEquals(LlmProvider.OPENAI, resolved.actionVerifier.provider)
        assertEquals(LlmProvider.OPENAI, resolved.approvalInterpreter.provider)
        assertEquals("gpt-5-nano", resolved.approvalInterpreter.model)
        assertEquals(LlmProvider.GROQ, resolved.webSearch.provider)
        assertTrue(resolved.modelCatalog.profiles(LlmProvider.ANTHROPIC).isNotEmpty())
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
                default_model: openai/gpt-oss-20b
                default_web_search_model: groq/compound-mini
              google:
                api_key_env: CUSTOM_GOOGLE_KEY
                base_url: https://custom.google.test/v1beta/openai/
                default_model: gemini-2.5-flash
                default_web_search_model: gemini-2.5-flash
              openai:
                api_key_env: OPENAI_API_KEY
                base_url: https://api.openai.com/v1
                default_model: gpt-4o-mini
                default_web_search_model: gpt-4o-mini
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

        val resolved = config
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
        assertEquals(LlmProvider.OPENAI, resolved.approvalInterpreter.provider)
        assertEquals("gpt-5-nano", resolved.approvalInterpreter.model)

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
                base_url: https://api.mistral.ai/v1
                default_model: mistral-small-2506
                default_web_search_model: mistral-small-2506
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

        val resolved = config
        assertEquals(LlmProvider.MISTRAL, resolved.planner.provider)
        assertEquals("mistral-small-2506", resolved.planner.model)
        assertEquals("right-key", resolved.planner.apiKey)
        assertEquals("CUSTOM_MISTRAL_KEY", resolved.planner.apiKeyEnvVar)
    }

    @Test
    fun `partial external yaml overlays bundled providers and omitted roles`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-overlay")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            providers:
              anthropic:
                base_url: https://anthropic-proxy.test/v1
            cognitive_roles:
              planner:
                provider: anthropic
                model: claude-sonnet-4-20250514
              meta_reasoner:
                provider: anthropic
                model: claude-sonnet-4-20250514
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "ANTHROPIC_API_KEY" to "anthropic-key",
                "GROQ_API_KEY" to "groq-key",
                "OPENAI_API_KEY" to "openai-key"
            ),
            defaultPath = yamlPath
        )

        assertEquals(LlmProvider.ANTHROPIC, config.planner.provider)
        assertEquals("anthropic-key", config.planner.apiKey)
        assertEquals("https://anthropic-proxy.test/v1", config.planner.baseUrl)
        assertEquals(LlmProvider.OPENAI, config.actionVerifier.provider)
        assertEquals("gpt-4o-mini", config.actionVerifier.model)
        assertEquals(LlmProvider.GROQ, config.superego.provider)
        assertEquals("openai/gpt-oss-120b", config.superego.model)
        assertEquals(LlmProvider.ANTHROPIC, config.metaReasoner.provider)
        assertEquals("claude-sonnet-4-20250514", config.metaReasoner.model)
        assertEquals(LlmProvider.GROQ, config.memoryAdvisor.provider)
        assertEquals(LlmProvider.GROQ, config.webSearch.provider)
        assertEquals("groq/compound-mini", config.webSearch.model)
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
                default_model: gpt-4o-mini
                default_web_search_model: gpt-4o-mini
              groq:
                api_key_env: GROQ_API_KEY
                base_url: https://api.groq.com/openai/v1
                default_model: openai/gpt-oss-20b
                default_web_search_model: groq/compound-mini
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

        val resolved = config
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
    fun `load supports anthropic and ollama providers`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-anthropic-ollama")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            providers:
              anthropic:
                api_key_env: CUSTOM_ANTHROPIC_KEY
                base_url: https://anthropic-proxy.test/v1
                default_model: claude-sonnet-4-20250514
                default_web_search_model: claude-sonnet-4-20250514
              ollama:
                api_key_env: OLLAMA_API_KEY
                base_url: http://ollama.test:11434/api
                default_model: gpt-oss
                default_web_search_model: gpt-oss
              groq:
                api_key_env: GROQ_API_KEY
                base_url: https://api.groq.com/openai/v1
                default_model: openai/gpt-oss-20b
                default_web_search_model: groq/compound-mini
            cognitive_roles:
              planner:
                provider: anthropic
                model: claude-sonnet-4-20250514
              action_verifier:
                provider: ollama
                model: gpt-oss
              superego:
                provider: anthropic
                model: claude-sonnet-4-20250514
              meta_reasoner:
                provider: anthropic
                model: claude-sonnet-4-20250514
              memory_advisor:
                provider: ollama
                model: gpt-oss
            web_search:
              provider: groq
              model: groq/compound-mini
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "CUSTOM_ANTHROPIC_KEY" to "anthropic-key",
                "GROQ_API_KEY" to "groq-key"
            ),
            defaultPath = yamlPath
        )

        val resolved = config
        assertEquals(LlmProvider.ANTHROPIC, resolved.planner.provider)
        assertEquals("anthropic-key", resolved.planner.apiKey)
        assertEquals("https://anthropic-proxy.test/v1", resolved.planner.baseUrl)
        assertEquals(LlmProvider.OLLAMA, resolved.actionVerifier.provider)
        assertEquals("http://ollama.test:11434/api", resolved.actionVerifier.baseUrl)
        assertEquals("", resolved.actionVerifier.apiKey)
        assertEquals("OLLAMA_API_KEY", resolved.actionVerifier.apiKeyEnvVar)
        assertTrue(resolved.modelCatalog.profiles(LlmProvider.ANTHROPIC).isNotEmpty())
    }

    @Test
    fun `load applies model catalog overrides for token weights`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-catalog")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            provider: openai
            providers:
              openai:
                api_key_env: OPENAI_API_KEY
                base_url: https://api.openai.com/v1
                default_model: gpt-4o-mini
                default_web_search_model: gpt-4o-mini
            cognitive_roles:
              planner:
                provider: openai
                model: gpt-4o-mini
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
                  metadata_updated_at: 2026-03-24
                - model: gpt-5-nano
                  tier: light
                  token_weight: 0.95
                  metadata_updated_at: 2026-03-24
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf("OPENAI_API_KEY" to "openai-key"),
            defaultPath = yamlPath
        )

        val resolved = config
        assertEquals(1.55, resolved.modelCatalog.tokenWeightFor(resolved.superego))
        assertEquals(0.95, resolved.modelCatalog.tokenWeightFor(resolved.memoryAdvisor))
        assertEquals("2026-03-24", resolved.modelCatalog.profileFor(resolved.superego)?.metadataUpdatedAt)
        assertTrue(resolved.modelCatalog.profiles(LlmProvider.MISTRAL).isNotEmpty())
    }

    @Test
    fun `missing explicit override path fails with a clear error`() {
        val error = assertFailsWith<IllegalStateException> {
            LlmRuntimeConfigLoader.load(
                env = mapOf("NEOPSYKE_LLM_CONFIG_FILE" to "/nonexistent/llm-runtime.yaml"),
                defaultPath = Path.of("llm-runtime.yaml")
            )
        }

        assertTrue(error.message!!.contains("NEOPSYKE_LLM_CONFIG_FILE"))
    }

    @Test
    fun `invalid provider values fail validation`() {
        val tempDir = Files.createTempDirectory("neopsyke-llm-config-invalid-provider")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            cognitive_roles:
              planner:
                provider: not-a-provider
                model: some-model
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalStateException> {
            LlmRuntimeConfigLoader.load(
                env = mapOf(
                    "GROQ_API_KEY" to "groq-key",
                    "OPENAI_API_KEY" to "openai-key"
                ),
                defaultPath = yamlPath
            )
        }

        assertTrue(error.message!!.contains("cognitive_roles.planner.provider"))
    }

    @Test
    fun `checked in llm runtime configs load successfully`() {
        val appConfig = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "ANTHROPIC_API_KEY" to "anthropic-key",
                "GROQ_API_KEY" to "groq-key",
                "GOOGLE_API_KEY" to "google-key",
                "MISTRAL_API_KEY" to "mistral-key",
                "OPENAI_API_KEY" to "openai-key"
            ),
            defaultPath = Path.of("config/llm-runtime.yaml")
        )
        val lowLlmConfig = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "ANTHROPIC_API_KEY" to "anthropic-key",
                "GROQ_API_KEY" to "groq-key",
                "GOOGLE_API_KEY" to "google-key",
                "MISTRAL_API_KEY" to "mistral-key",
                "OPENAI_API_KEY" to "openai-key"
            ),
            defaultPath = Path.of("freud/config/llm-low-llm.yaml")
        )
        val highLlmConfig = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "ANTHROPIC_API_KEY" to "anthropic-key",
                "GROQ_API_KEY" to "groq-key",
                "GOOGLE_API_KEY" to "google-key",
                "MISTRAL_API_KEY" to "mistral-key",
                "OPENAI_API_KEY" to "openai-key"
            ),
            defaultPath = Path.of("freud/config/llm-high-llm.yaml")
        )

        assertTrue(appConfig.modelCatalog.profiles(LlmProvider.ANTHROPIC).isNotEmpty())
        assertTrue(appConfig.modelCatalog.profiles(LlmProvider.OLLAMA).isNotEmpty())
        assertTrue(lowLlmConfig.modelCatalog.profiles(LlmProvider.ANTHROPIC).isNotEmpty())
        assertTrue(highLlmConfig.modelCatalog.profiles(LlmProvider.OLLAMA).isNotEmpty())
    }

    @Test
    fun `external llm example overlay loads`() {
        val config = LlmRuntimeConfigLoader.load(
            env = mapOf("GOOGLE_API_KEY" to "google-key"),
            defaultPath = Path.of("examples/runtime-config/llm-runtime.external.example.yaml")
        )

        assertEquals(LlmProvider.GOOGLE, config.planner.provider)
        assertEquals("gemini-2.5-flash", config.planner.model)
        assertEquals(LlmProvider.GOOGLE, config.webSearch.provider)
        assertEquals("gemini-2.5-pro", config.superegoEscalation?.model)
    }
}
