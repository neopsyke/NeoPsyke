package ai.neopsyke.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import java.nio.file.Paths

enum class MemoryMode {
    OFF,
    DEFAULT,
    EXTERNAL;

    companion object {
        fun parse(raw: String?): MemoryMode? =
            when (raw?.trim()?.lowercase()) {
                "off" -> OFF
                "default" -> DEFAULT
                "external" -> EXTERNAL
                else -> null
            }
    }
}

data class DefaultMemoryProviderConfig(
    val provider: String = "neopsyke-pgvector-memory",
    val transport: String = "http",
    val baseUrl: String = "http://127.0.0.1:7841",
    val command: String =
        "java -jar .neopsyke/providers/neopsyke-pgvector-memory/current/neopsyke-pgvector-memory-all.jar --transport=http --port=7841",
    val startupTimeoutMs: Long = 12_000,
    val healthTimeoutMs: Long = 3_000,
    val bootstrapEnabled: Boolean = true,
    val releaseApiUrl: String = "https://api.github.com/repos/neopsyke/neopsyke-pgvector-memory/releases/tags/v0.1.0",
    val downloadTimeoutMs: Long = 30_000,
    val namespace: String = "neopsyke",
)

data class ExternalMemoryProviderConfig(
    val provider: String = "stub",
    val transport: String = "http",
    val baseUrl: String = "",
    val namespace: String = "neopsyke",
)

data class MemoryRuntimeConfig(
    val mode: MemoryMode = MemoryMode.DEFAULT,
    val defaultProvider: DefaultMemoryProviderConfig = DefaultMemoryProviderConfig(),
    val externalProvider: ExternalMemoryProviderConfig = ExternalMemoryProviderConfig(),
) {
    companion object {
        fun defaults(): MemoryRuntimeConfig = MemoryRuntimeConfig()
    }
}

object MemoryRuntimeConfigLoader {
    private val mapper = JsonMapper.builder(YAMLFactory())
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
        .registerKotlinModule()

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("memory-runtime.yaml"),
    ): MemoryRuntimeConfig {
        val base = YamlConfigSources.loadYamlConfig<MemoryRuntimeConfig>(
            mapper = mapper,
            env = env,
            envKey = "NEOPSYKE_MEMORY_CONFIG_FILE",
            defaultPath = defaultPath,
            bundledResourceName = "memory-runtime.yaml",
        ) ?: throw IllegalStateException("Missing bundled or external memory-runtime.yaml configuration.")
        validate(base)
        return base.applyEnvOverrides(env)
    }

    private fun validate(config: MemoryRuntimeConfig) {
        requireField(config.defaultProvider.provider.isNotBlank(), "memory-runtime.yaml defaultProvider.provider")
        requireField(config.defaultProvider.transport.isNotBlank(), "memory-runtime.yaml defaultProvider.transport")
        requireField(config.defaultProvider.baseUrl.isNotBlank(), "memory-runtime.yaml defaultProvider.baseUrl")
        requireField(config.defaultProvider.command.isNotBlank(), "memory-runtime.yaml defaultProvider.command")
        requireField(config.externalProvider.provider.isNotBlank(), "memory-runtime.yaml externalProvider.provider")
        requireField(config.externalProvider.transport.isNotBlank(), "memory-runtime.yaml externalProvider.transport")
    }

    private fun requireField(condition: Boolean, fieldName: String) {
        if (!condition) {
            throw IllegalStateException("Missing or blank required field: $fieldName")
        }
    }

    private fun MemoryRuntimeConfig.applyEnvOverrides(env: Map<String, String>): MemoryRuntimeConfig {
        val namespaceOverride = env["MEMORY_DEFAULT_NAMESPACE"]?.trim().orEmpty().ifBlank { null }
        return copy(
            mode = MemoryMode.parse(env["NEOPSYKE_MEMORY_MODE"]) ?: mode,
            defaultProvider = defaultProvider.copy(
                provider = env["NEOPSYKE_MEMORY_DEFAULT_PROVIDER"]?.trim().orEmpty().ifBlank { null } ?: defaultProvider.provider,
                transport = env["NEOPSYKE_MEMORY_DEFAULT_TRANSPORT"]?.trim().orEmpty().ifBlank { null } ?: defaultProvider.transport,
                baseUrl = env["NEOPSYKE_MEMORY_DEFAULT_BASE_URL"]?.trim().orEmpty().ifBlank { null } ?: defaultProvider.baseUrl,
                command = env["NEOPSYKE_MEMORY_DEFAULT_COMMAND"]?.trim().orEmpty().ifBlank { null } ?: defaultProvider.command,
                startupTimeoutMs = env["NEOPSYKE_MEMORY_DEFAULT_STARTUP_TIMEOUT_MS"]?.trim()?.toLongOrNull()
                    ?: defaultProvider.startupTimeoutMs,
                healthTimeoutMs = env["NEOPSYKE_MEMORY_DEFAULT_HEALTH_TIMEOUT_MS"]?.trim()?.toLongOrNull()
                    ?: defaultProvider.healthTimeoutMs,
                bootstrapEnabled = env["NEOPSYKE_MEMORY_DEFAULT_BOOTSTRAP_ENABLED"]?.trim()?.toBooleanStrictOrNull()
                    ?: defaultProvider.bootstrapEnabled,
                releaseApiUrl = env["NEOPSYKE_MEMORY_DEFAULT_RELEASE_API_URL"]?.trim().orEmpty().ifBlank { null }
                    ?: defaultProvider.releaseApiUrl,
                downloadTimeoutMs = env["NEOPSYKE_MEMORY_DEFAULT_DOWNLOAD_TIMEOUT_MS"]?.trim()?.toLongOrNull()
                    ?: defaultProvider.downloadTimeoutMs,
                namespace = namespaceOverride ?: defaultProvider.namespace,
            ),
            externalProvider = externalProvider.copy(
                provider = env["NEOPSYKE_MEMORY_EXTERNAL_PROVIDER"]?.trim().orEmpty().ifBlank { null } ?: externalProvider.provider,
                transport = env["NEOPSYKE_MEMORY_EXTERNAL_TRANSPORT"]?.trim().orEmpty().ifBlank { null } ?: externalProvider.transport,
                baseUrl = env["NEOPSYKE_MEMORY_EXTERNAL_BASE_URL"]?.trim().orEmpty().ifBlank { null } ?: externalProvider.baseUrl,
                namespace = namespaceOverride ?: externalProvider.namespace,
            )
        )
    }
}
