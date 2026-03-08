package psyke.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class McpCapabilityConfig(
    val enabled: Boolean = true,
    val mode: String = "stdio",
    val provider: String = "auto",
    val command: String = "",
    @JsonProperty("fallback_commands")
    val fallbackCommands: List<String> = emptyList(),
)

data class McpRuntimeConfig(
    val time: McpCapabilityConfig = defaults().time,
    val fetch: McpCapabilityConfig = defaults().fetch,
    val memory: McpCapabilityConfig = defaults().memory,
) {
    companion object {
        fun defaults(): McpRuntimeConfig =
            McpRuntimeConfig(
                time = McpCapabilityConfig(
                    enabled = true,
                    mode = "stdio",
                    provider = "mcp-server-time",
                    command = "uvx mcp-server-time",
                    fallbackCommands = listOf("python -m mcp_server_time")
                ),
                fetch = McpCapabilityConfig(
                    enabled = true,
                    mode = "native",
                    provider = "native-jvm",
                ),
                memory = McpCapabilityConfig(
                    enabled = true,
                    mode = "stdio",
                    provider = "pgvector",
                    command = "java -jar mcp-memory-pgvector/build/libs/mcp-memory-pgvector-0.1.0-all.jar",
                )
            )
    }
}

object McpRuntimeConfigLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("mcp-runtime.yaml"),
    ): McpRuntimeConfig {
        val filePath = resolveConfigPath(env, defaultPath)
        val base = readYaml(filePath) ?: McpRuntimeConfig.defaults()
        return base.applyEnvOverrides(env)
    }

    private fun resolveConfigPath(env: Map<String, String>, defaultPath: Path): Path {
        val configured = env["PSYKE_MCP_CONFIG_FILE"]?.trim().orEmpty()
        if (configured.isBlank()) {
            return defaultPath
        }
        return Paths.get(configured)
    }

    private fun readYaml(path: Path): McpRuntimeConfig? {
        if (!Files.exists(path)) {
            return null
        }
        Files.newBufferedReader(path).use { reader ->
            return mapper.readValue<McpRuntimeConfig>(reader)
        }
    }

    private fun McpRuntimeConfig.applyEnvOverrides(env: Map<String, String>): McpRuntimeConfig =
        copy(
            time = time.applyEnvOverrides(env, prefix = "MCP_TIME", commandEnv = "MCP_TIME_SERVER_CMD"),
            fetch = fetch.applyEnvOverrides(env, prefix = "WEBSITE_FETCH", commandEnv = "WEBSITE_FETCH_SERVER_CMD"),
            memory = memory.applyEnvOverrides(env, prefix = "MCP_MEMORY", commandEnv = "MCP_MEMORY_SERVER_CMD")
        )

    private fun McpCapabilityConfig.applyEnvOverrides(
        env: Map<String, String>,
        prefix: String,
        commandEnv: String,
    ): McpCapabilityConfig {
        val enabledOverride = parseBoolean(env["${prefix}_ENABLED"])
        val modeOverride = env["${prefix}_MODE"]?.trim().orEmpty().ifBlank { null }
        val providerOverride = env["${prefix}_PROVIDER"]?.trim().orEmpty().ifBlank { null }
        val commandOverride = env[commandEnv]?.trim().orEmpty().ifBlank { null }
        return copy(
            enabled = enabledOverride ?: enabled,
            mode = modeOverride ?: mode,
            provider = providerOverride ?: provider,
            command = commandOverride ?: command
        )
    }

    private fun parseBoolean(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
}
