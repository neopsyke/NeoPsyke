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
        assertEquals(LlmProvider.GROQ, resolved.provider)
        assertEquals("https://api.groq.com/openai/v1", resolved.baseUrl)
        assertEquals("GROQ_API_KEY", resolved.apiKeyEnvVar)
        assertEquals("openai/gpt-oss-20b", resolved.egoModel)
        assertEquals("openai/gpt-oss-20b", resolved.superegoModel)
        assertEquals(LlmProvider.GROQ, resolved.webSearchProvider)
        assertEquals("https://api.groq.com/openai/v1", resolved.webSearchBaseUrl)
        assertEquals("GROQ_API_KEY", resolved.webSearchApiKeyEnvVar)
        assertEquals("groq/compound-mini", resolved.webSearchModel)
    }

    @Test
    fun `load reads yaml values`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-yaml")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            provider: mistral
            base_url: https://custom.mistral.test/v1
            api_key_env: CUSTOM_MISTRAL_KEY
            models:
              ego: mistral-small-latest
              superego: mistral-medium-latest
              meta_reasoner: mistral-large-latest
              memory_consolidation: mistral-medium-latest
              web_search: mistral-small-latest
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.MISTRAL, resolved.provider)
        assertEquals("https://custom.mistral.test/v1", resolved.baseUrl)
        assertEquals("CUSTOM_MISTRAL_KEY", resolved.apiKeyEnvVar)
        assertEquals("mistral-small-latest", resolved.egoModel)
        assertEquals("mistral-medium-latest", resolved.superegoModel)
        assertEquals("mistral-large-latest", resolved.metaReasonerModel)
        assertEquals("mistral-medium-latest", resolved.memoryConsolidationModel)
        assertEquals(LlmProvider.MISTRAL, resolved.webSearchProvider)
        assertEquals("https://custom.mistral.test/v1", resolved.webSearchBaseUrl)
        assertEquals("CUSTOM_MISTRAL_KEY", resolved.webSearchApiKeyEnvVar)
        assertEquals("mistral-small-latest", resolved.webSearchModel)
    }

    @Test
    fun `env overrides yaml for provider model and api key`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-env")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            provider: groq
            models:
              ego: openai/gpt-oss-20b
              superego: openai/gpt-oss-20b
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "LLM_PROVIDER" to "mistral",
                "LLM_EGO_MODEL" to "mistral-small-latest",
                "LLM_SUPEREGO_MODEL" to "mistral-medium-latest",
                "MISTRAL_API_KEY" to "mistral-key"
            ),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.MISTRAL, resolved.provider)
        assertEquals("mistral-key", resolved.apiKey)
        assertEquals("mistral-small-latest", resolved.egoModel)
        assertEquals("mistral-medium-latest", resolved.superegoModel)
        assertEquals("https://api.mistral.ai/v1", resolved.baseUrl)
        assertEquals(LlmProvider.MISTRAL, resolved.webSearchProvider)
        assertEquals("https://api.mistral.ai/v1", resolved.webSearchBaseUrl)
        assertEquals("MISTRAL_API_KEY", resolved.webSearchApiKeyEnvVar)
    }

    @Test
    fun `web search provider can be configured independently from primary provider`() {
        val tempDir = Files.createTempDirectory("psyke-llm-config-web-search-provider")
        val yamlPath = tempDir.resolve("llm-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            provider: groq
            models:
              ego: openai/gpt-oss-20b
              superego: openai/gpt-oss-20b
              meta_reasoner: openai/gpt-oss-20b
              memory_consolidation: openai/gpt-oss-20b
            web_search:
              provider: mistral
              model: mistral-small-latest
            """.trimIndent()
        )

        val config = LlmRuntimeConfigLoader.load(
            env = mapOf(
                "GROQ_API_KEY" to "groq-key",
                "MISTRAL_API_KEY" to "mistral-key"
            ),
            defaultPath = yamlPath
        )

        val resolved = assertNotNull(config)
        assertEquals(LlmProvider.GROQ, resolved.provider)
        assertEquals("groq-key", resolved.apiKey)
        assertEquals("openai/gpt-oss-20b", resolved.egoModel)
        assertEquals(LlmProvider.MISTRAL, resolved.webSearchProvider)
        assertEquals("mistral-key", resolved.webSearchApiKey)
        assertEquals("MISTRAL_API_KEY", resolved.webSearchApiKeyEnvVar)
        assertEquals("https://api.mistral.ai/v1", resolved.webSearchBaseUrl)
        assertEquals("mistral-small-latest", resolved.webSearchModel)
    }
}
