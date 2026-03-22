package ai.neopsyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryRuntimeConfigLoaderTest {
    @Test
    fun `load falls back to defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("neopsyke-memory-config-missing")
        val config = MemoryRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        assertEquals(MemoryMode.DEFAULT, config.mode)
        assertEquals("neopsyke-pgvector-memory", config.defaultProvider.provider)
        assertEquals("http://127.0.0.1:7841", config.defaultProvider.baseUrl)
        assertEquals(
            "java -jar .neopsyke/providers/neopsyke-pgvector-memory/current/neopsyke-pgvector-memory-all.jar --transport=http --port=7841",
            config.defaultProvider.command
        )
        assertEquals("neopsyke", config.defaultProvider.namespace)
    }

    @Test
    fun `load reads yaml values for default and external providers`() {
        val tempDir = Files.createTempDirectory("neopsyke-memory-config-yaml")
        val yamlPath = tempDir.resolve("memory-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            mode: external
            defaultProvider:
              provider: provider-a
              transport: http
              baseUrl: http://127.0.0.1:9001
              command: "provider-a --serve"
              startupTimeoutMs: 1000
              healthTimeoutMs: 2000
              namespace: local
            externalProvider:
              provider: mem0
              transport: http
              baseUrl: https://mem.example
              namespace: external
            """.trimIndent()
        )

        val config = MemoryRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertEquals(MemoryMode.EXTERNAL, config.mode)
        assertEquals("provider-a", config.defaultProvider.provider)
        assertEquals("http://127.0.0.1:9001", config.defaultProvider.baseUrl)
        assertEquals("mem0", config.externalProvider.provider)
        assertEquals("https://mem.example", config.externalProvider.baseUrl)
    }

    @Test
    fun `env overrides yaml for mode namespace and provider transport values`() {
        val tempDir = Files.createTempDirectory("neopsyke-memory-config-env")
        val yamlPath = tempDir.resolve("base.yaml")
        Files.writeString(
            yamlPath,
            """
            mode: "off"
            defaultProvider:
              provider: yaml-provider
              transport: http
              baseUrl: http://127.0.0.1:7777
              command: "yaml-provider --serve"
              startupTimeoutMs: 1000
              healthTimeoutMs: 1000
              namespace: yamlspace
            externalProvider:
              provider: stub
              transport: http
              baseUrl: ""
              namespace: yamlspace
            """.trimIndent()
        )

        val config = MemoryRuntimeConfigLoader.load(
            env = mapOf(
                "NEOPSYKE_MEMORY_MODE" to "default",
                "NEOPSYKE_MEMORY_DEFAULT_PROVIDER" to "env-provider",
                "NEOPSYKE_MEMORY_DEFAULT_TRANSPORT" to "http",
                "NEOPSYKE_MEMORY_DEFAULT_BASE_URL" to "http://127.0.0.1:8123",
                "NEOPSYKE_MEMORY_DEFAULT_COMMAND" to "env-provider --serve",
                "MEMORY_DEFAULT_NAMESPACE" to "freud-eval"
            ),
            defaultPath = yamlPath
        )

        assertEquals(MemoryMode.DEFAULT, config.mode)
        assertEquals("env-provider", config.defaultProvider.provider)
        assertEquals("http://127.0.0.1:8123", config.defaultProvider.baseUrl)
        assertEquals("env-provider --serve", config.defaultProvider.command)
        assertEquals("freud-eval", config.defaultProvider.namespace)
    }

    @Test
    fun `env overrides yaml for external provider http settings`() {
        val tempDir = Files.createTempDirectory("neopsyke-memory-config-external-env")
        val yamlPath = tempDir.resolve("base.yaml")
        Files.writeString(
            yamlPath,
            """
            mode: "off"
            externalProvider:
              provider: yaml-external
              transport: http
              baseUrl: http://127.0.0.1:9999
              namespace: yamlspace
            """.trimIndent()
        )

        val config = MemoryRuntimeConfigLoader.load(
            env = mapOf(
                "NEOPSYKE_MEMORY_MODE" to "external",
                "NEOPSYKE_MEMORY_EXTERNAL_PROVIDER" to "mem0-http",
                "NEOPSYKE_MEMORY_EXTERNAL_TRANSPORT" to "http",
                "NEOPSYKE_MEMORY_EXTERNAL_BASE_URL" to "https://memory.example",
                "MEMORY_DEFAULT_NAMESPACE" to "external-space",
            ),
            defaultPath = yamlPath
        )

        assertEquals(MemoryMode.EXTERNAL, config.mode)
        assertEquals("mem0-http", config.externalProvider.provider)
        assertEquals("http", config.externalProvider.transport)
        assertEquals("https://memory.example", config.externalProvider.baseUrl)
        assertEquals("external-space", config.externalProvider.namespace)
    }
}
