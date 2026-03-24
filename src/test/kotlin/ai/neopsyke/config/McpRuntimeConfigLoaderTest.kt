package ai.neopsyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpRuntimeConfigLoaderTest {
    @Test
    fun `load falls back to bundled defaults when config file is missing`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-missing")
        val config = McpRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = tempDir.resolve("missing.yaml")
        )

        assertTrue(config.time.enabled)
        assertEquals("stdio", config.time.mode)
        assertEquals("uvx mcp-server-time", config.time.command)
        assertTrue(config.fetch.enabled)
    }

    @Test
    fun `load reads yaml values for time and fetch capabilities`() {
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
    }

    @Test
    fun `env overrides yaml for fetch command mode provider and enabled`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-env")
        val yamlPath = tempDir.resolve("base.yaml")
        Files.writeString(
            yamlPath,
            """
            fetch:
              enabled: false
              mode: stdio
              provider: yaml-provider
              command: "yaml-fetch --serve"
            """.trimIndent()
        )
        val config = McpRuntimeConfigLoader.load(
            env = mapOf(
                "WEBSITE_FETCH_ENABLED" to "true",
                "WEBSITE_FETCH_MODE" to "stdio",
                "WEBSITE_FETCH_PROVIDER" to "env-provider",
                "WEBSITE_FETCH_SERVER_CMD" to "env-fetch --serve"
            ),
            defaultPath = yamlPath
        )

        assertEquals(true, config.fetch.enabled)
        assertEquals("stdio", config.fetch.mode)
        assertEquals("env-provider", config.fetch.provider)
        assertEquals("env-fetch --serve", config.fetch.command)
    }

    @Test
    fun `partial external yaml overlays bundled mcp defaults`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-overlay")
        val yamlPath = tempDir.resolve("mcp-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            fetch:
              provider: custom-fetch
            """.trimIndent()
        )

        val config = McpRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = yamlPath
        )

        assertTrue(config.time.enabled)
        assertEquals("mcp-server-time", config.time.provider)
        assertEquals("uvx mcp-server-time", config.time.command)
        assertEquals("native", config.fetch.mode)
        assertEquals("custom-fetch", config.fetch.provider)
    }

    @Test
    fun `stdio capability without command fails validation`() {
        val tempDir = Files.createTempDirectory("neopsyke-mcp-config-invalid")
        val yamlPath = tempDir.resolve("mcp-runtime.yaml")
        Files.writeString(
            yamlPath,
            """
            time:
              command: ""
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalStateException> {
            McpRuntimeConfigLoader.load(
                env = emptyMap(),
                defaultPath = yamlPath
            )
        }

        assertTrue(error.message!!.contains("time.command"))
    }

    @Test
    fun `external mcp example overlay loads`() {
        val config = McpRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = java.nio.file.Path.of("examples/runtime-config/mcp-runtime.external.example.yaml")
        )

        assertTrue(config.time.enabled)
        assertEquals("mcp-server-time", config.time.provider)
        assertTrue(config.fetch.enabled)
        assertEquals("native-jvm", config.fetch.provider)
    }
}
