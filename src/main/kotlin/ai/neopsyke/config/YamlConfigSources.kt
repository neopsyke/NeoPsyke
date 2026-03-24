package ai.neopsyke.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object YamlConfigSources {
    private data class ConfigPath(
        val path: Path,
        val explicitOverride: Boolean,
    )

    inline fun <reified T> loadYamlConfig(
        mapper: ObjectMapper,
        env: Map<String, String>,
        envKey: String,
        defaultPath: Path,
        bundledResourceName: String,
    ): T? =
        loadYamlConfig(
            mapper = mapper,
            env = env,
            envKey = envKey,
            defaultPath = defaultPath,
            bundledResourceName = bundledResourceName,
            targetClass = T::class.java
        )

    fun <T> loadYamlConfig(
        mapper: ObjectMapper,
        env: Map<String, String>,
        envKey: String,
        defaultPath: Path,
        bundledResourceName: String,
        targetClass: Class<T>,
    ): T? {
        val configPath = resolveConfigPath(env = env, envKey = envKey, defaultPath = defaultPath)
        val bundled = readBundledResourceTree(bundledResourceName = bundledResourceName, mapper = mapper)
        val external = readPathTree(path = configPath.path, mapper = mapper)
        if (configPath.explicitOverride && external == null) {
            throw IllegalStateException("Configured $envKey file not found: ${configPath.path}")
        }

        val merged = when {
            bundled != null && external != null -> deepMerge(base = bundled, overlay = external)
            external != null -> external
            bundled != null -> bundled
            else -> null
        } ?: return null

        return mapper.treeToValue(merged, targetClass)
    }

    private fun resolveConfigPath(
        env: Map<String, String>,
        envKey: String,
        defaultPath: Path,
    ): ConfigPath {
        val configured = env[envKey]?.trim().orEmpty()
        return if (configured.isBlank()) {
            ConfigPath(path = defaultPath, explicitOverride = false)
        } else {
            ConfigPath(path = Paths.get(configured), explicitOverride = true)
        }
    }

    private fun readPathTree(path: Path, mapper: ObjectMapper): JsonNode? {
        if (!Files.exists(path)) return null
        val content = Files.readString(path)
        if (content.isBlank()) {
            throw IllegalStateException("Configuration file is empty: $path")
        }
        return mapper.readTree(content)
    }

    private fun readBundledResourceTree(
        bundledResourceName: String,
        mapper: ObjectMapper,
    ): JsonNode? {
        val classLoader = Thread.currentThread().contextClassLoader ?: YamlConfigSources::class.java.classLoader
        classLoader.getResourceAsStream(bundledResourceName)?.use { input ->
            val content = input.bufferedReader().readText()
            if (content.isBlank()) {
                throw IllegalStateException("Bundled configuration resource is empty: $bundledResourceName")
            }
            return mapper.readTree(content)
        }
        return null
    }

    private fun deepMerge(base: JsonNode, overlay: JsonNode): JsonNode {
        if (base is ObjectNode && overlay is ObjectNode) {
            val merged = base.deepCopy<ObjectNode>()
            val fields = overlay.fields()
            while (fields.hasNext()) {
                val (fieldName, overlayValue) = fields.next()
                if (overlayValue.isNull) {
                    continue
                }
                val baseValue = merged.get(fieldName)
                val mergedValue = if (baseValue != null && baseValue.isObject && overlayValue.isObject) {
                    deepMerge(baseValue, overlayValue)
                } else {
                    overlayValue.deepCopy<JsonNode>()
                }
                merged.set<JsonNode>(fieldName, mergedValue)
            }
            return merged
        }
        return overlay.deepCopy<JsonNode>()
    }
}
