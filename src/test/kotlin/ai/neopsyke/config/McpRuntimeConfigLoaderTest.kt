package ai.neopsyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRuntimeConfigLoaderTest {
    @Test
    fun `load falls back to defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-missing")
        val config = McpRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        assertTrue(config.time.enabled)
        assertEquals("stdio", config.time.mode)
        assertEquals("uvx mcp-server-time", config.time.command)
        assertTrue(config.fetch.enabled)
        assertTrue(config.memory.enabled)
        assertEquals("java -jar mcp-memory-pgvector/build/libs/mcp-memory-pgvector-0.1.0-all.jar", config.memory.command)
    }

    @Test
    fun `load reads yaml values for all capabilities`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-yaml")
        val yamlPath = tempDir.resolve("mcp-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            time:
              enabled: false
              mode: stdio
              provider: custom-time
              command: "time-cmd --serve"
            fetch:
              enabled: true
              mode: stdio
              provider: custom-fetch
              command: "fetch-cmd --serve"
            memory:
              enabled: true
              mode: stdio
              provider: custom-memory
              command: "memory-cmd --serve"
              fallback_commands:
                - "memory-alt --serve"
            """.trimIndent()
        )
        val config = McpRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertEquals(false, config.time.enabled)
        assertEquals("custom-time", config.time.provider)
        assertEquals("time-cmd --serve", config.time.command)
        assertEquals("custom-fetch", config.fetch.provider)
        assertEquals("fetch-cmd --serve", config.fetch.command)
        assertEquals("custom-memory", config.memory.provider)
        assertEquals("memory-cmd --serve", config.memory.command)
        assertEquals(listOf("memory-alt --serve"), config.memory.fallbackCommands)
    }

    @Test
    fun `env overrides yaml for command mode provider and enabled`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-env")
        val yamlPath = tempDir.resolve("base.yaml")
        Files.writeString(
            yamlPath,
            """
            memory:
              enabled: false
              mode: stdio
              provider: yaml-provider
              command: "yaml-memory --serve"
            """.trimIndent()
        )
        val config = McpRuntimeConfigLoader.load(
            env = mapOf(
                "MCP_MEMORY_ENABLED" to "true",
                "MCP_MEMORY_MODE" to "stdio",
                "MCP_MEMORY_PROVIDER" to "env-provider",
                "MCP_MEMORY_SERVER_CMD" to "env-memory --serve"
            ),
            defaultPath = yamlPath
        )

        assertEquals(true, config.memory.enabled)
        assertEquals("stdio", config.memory.mode)
        assertEquals("env-provider", config.memory.provider)
        assertEquals("env-memory --serve", config.memory.command)
    }
}
