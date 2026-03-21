package ai.neopsyke.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

data class RuntimeDefaultsConfig(
    @JsonProperty("metrics_db")
    val metricsDb: String = ".neopsyke/metrics.db",
)

object RuntimeDefaultsConfigLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun resolveMetricsDbPath(
        env: Map<String, String> = System.getenv(),
        cwd: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath(),
    ): Path {
        env["NEOPSYKE_METRICS_DB"]?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
            return expandPath(explicit, cwd)
        }

        val defaults = loadOrCreate(env, cwd)
        return expandPath(defaults.metricsDb.ifBlank { ".neopsyke/metrics.db" }, cwd)
    }

    fun loadOrCreate(
        env: Map<String, String> = System.getenv(),
        cwd: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath(),
    ): RuntimeDefaultsConfig {
        val path = resolveConfigPath(env, cwd)
        if (!Files.exists(path)) {
            val defaults = RuntimeDefaultsConfig()
            writeConfig(path, defaults)
            return defaults
        }

        return try {
            Files.newBufferedReader(path).use { reader ->
                mapper.readValue<RuntimeDefaultsConfig>(reader)
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to parse runtime defaults at $path. Rewriting with defaults." }
            val defaults = RuntimeDefaultsConfig()
            writeConfig(path, defaults)
            defaults
        }
    }

    private fun resolveConfigPath(env: Map<String, String>, cwd: Path): Path {
        val configured = env["NEOPSYKE_RUNTIME_DEFAULTS_FILE"]?.trim().orEmpty()
        if (configured.isBlank()) {
            return cwd.resolve(".neopsyke").resolve("runtime-defaults.yaml")
        }
        return expandPath(configured, cwd)
    }

    private fun writeConfig(path: Path, config: RuntimeDefaultsConfig) {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { writer ->
                mapper.writeValue(writer, config)
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to write runtime defaults config at $path." }
        }
    }

    private fun expandPath(rawPath: String, cwd: Path): Path {
        val trimmed = rawPath.trim()
        if (trimmed.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"), trimmed.removePrefix("~/")).normalize().toAbsolutePath()
        }
        if (trimmed == "~") {
            return Paths.get(System.getProperty("user.home")).normalize().toAbsolutePath()
        }
        val candidate = Paths.get(trimmed)
        return if (candidate.isAbsolute) {
            candidate.normalize()
        } else {
            cwd.resolve(candidate).normalize().toAbsolutePath()
        }
    }
}
