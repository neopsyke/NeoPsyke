package ai.neopsyke.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
        val base = YamlConfigSources.loadYamlConfig<McpRuntimeConfig>(
            mapper = mapper,
            env = env,
            envKey = "NEOPSYKE_MCP_CONFIG_FILE",
            defaultPath = defaultPath,
            bundledResourceName = "mcp-runtime.yaml",
        ) ?: throw IllegalStateException("Missing bundled or external mcp-runtime.yaml configuration.")
        validate(base)
        return base.applyEnvOverrides(env)
    }

    private fun validate(config: McpRuntimeConfig) {
        validateCapability(name = "time", capability = config.time)
        validateCapability(name = "fetch", capability = config.fetch)
    }

    private fun validateCapability(name: String, capability: McpCapabilityConfig) {
        if (capability.mode.isBlank()) {
            throw IllegalStateException("mcp-runtime.yaml is missing required field: $name.mode")
        }
        if (capability.provider.isBlank()) {
            throw IllegalStateException("mcp-runtime.yaml is missing required field: $name.provider")
        }
        if (capability.mode.equals("stdio", ignoreCase = true) && capability.command.isBlank()) {
            throw IllegalStateException("mcp-runtime.yaml requires $name.command when $name.mode=stdio")
        }
    }

    private fun McpRuntimeConfig.applyEnvOverrides(env: Map<String, String>): McpRuntimeConfig =
        copy(
            time = time.applyEnvOverrides(env, prefix = "MCP_TIME", commandEnv = "MCP_TIME_SERVER_CMD"),
            fetch = fetch.applyEnvOverrides(env, prefix = "WEBSITE_FETCH", commandEnv = "WEBSITE_FETCH_SERVER_CMD"),
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
