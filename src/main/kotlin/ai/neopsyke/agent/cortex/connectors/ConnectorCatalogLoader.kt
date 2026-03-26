package ai.neopsyke.agent.cortex.connectors

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

object ConnectorRuntimePaths {
    private const val CONNECTORS_DIR_NAME: String = "connectors"
    private const val BUNDLES_DIR_NAME: String = "bundles"
    private const val INSTALLED_DIR_NAME: String = "installed"

    fun curatedConnectorsDir(catalogRoot: Path): Path = catalogRoot.resolve(CONNECTORS_DIR_NAME)

    fun curatedBundlesDir(catalogRoot: Path): Path = catalogRoot.resolve(BUNDLES_DIR_NAME)

    fun installedStateDir(stateRoot: Path): Path = stateRoot.resolve(INSTALLED_DIR_NAME)
}

object CuratedConnectorCatalogLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(catalogRoot: Path): CuratedConnectorCatalogLoadResult {
        if (!Files.exists(catalogRoot)) {
            return CuratedConnectorCatalogLoadResult(catalog = CuratedConnectorCatalog.empty())
        }

        val warnings = mutableListOf<String>()
        val connectors = linkedMapOf<String, CuratedConnectorManifest>()
        val bundles = linkedMapOf<String, ConnectorBundleManifest>()

        listYamlFiles(ConnectorRuntimePaths.curatedConnectorsDir(catalogRoot)).forEach { file ->
            val manifest = try {
                readYaml<CuratedConnectorManifest>(file)
            } catch (ex: Exception) {
                warnings += "Failed to load curated connector manifest ${file.name}: ${ex.message}"
                return@forEach
            }
            if (connectors.containsKey(manifest.connectorId)) {
                warnings += "Duplicate curated connector id=${manifest.connectorId} in ${file.name}; keeping first."
                return@forEach
            }
            connectors[manifest.connectorId] = manifest
        }

        listYamlFiles(ConnectorRuntimePaths.curatedBundlesDir(catalogRoot)).forEach { file ->
            val manifest = try {
                readYaml<ConnectorBundleManifest>(file)
            } catch (ex: Exception) {
                warnings += "Failed to load connector bundle manifest ${file.name}: ${ex.message}"
                return@forEach
            }
            if (bundles.containsKey(manifest.bundleId)) {
                warnings += "Duplicate connector bundle id=${manifest.bundleId} in ${file.name}; keeping first."
                return@forEach
            }
            bundles[manifest.bundleId] = manifest
        }

        return CuratedConnectorCatalogLoadResult(
            catalog = CuratedConnectorCatalog(
                connectors = connectors.toMap(),
                bundles = bundles.toMap(),
            ),
            warnings = warnings.toList(),
        )
    }

    private inline fun <reified T> readYaml(path: Path): T =
        Files.newBufferedReader(path).use { reader ->
            mapper.readValue<T>(reader)
        }

    private fun listYamlFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) {
            return emptyList()
        }
        Files.list(dir).use { files ->
            return files
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.extension.lowercase() in YAML_EXTENSIONS
                }
                .sorted()
                .toList()
        }
    }

    private val YAML_EXTENSIONS: Set<String> = setOf("yaml", "yml")
}

object InstalledConnectorStateLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(stateRoot: Path): InstalledConnectorStateLoadResult {
        val installedDir = ConnectorRuntimePaths.installedStateDir(stateRoot)
        if (!Files.exists(installedDir)) {
            return InstalledConnectorStateLoadResult(connectors = emptyMap())
        }

        val warnings = mutableListOf<String>()
        val states = linkedMapOf<String, InstalledConnectorState>()
        Files.list(installedDir).use { files ->
            files
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.extension.lowercase() in YAML_EXTENSIONS
                }
                .sorted()
                .forEach { file ->
                    val state = try {
                        Files.newBufferedReader(file).use { reader ->
                            mapper.readValue<InstalledConnectorState>(reader)
                        }
                    } catch (ex: Exception) {
                        warnings += "Failed to load installed connector state ${file.name}: ${ex.message}"
                        return@forEach
                    }
                    if (states.containsKey(state.connectorId)) {
                        warnings += "Duplicate installed connector state id=${state.connectorId} in ${file.name}; keeping first."
                        return@forEach
                    }
                    states[state.connectorId] = state
                }
        }

        return InstalledConnectorStateLoadResult(
            connectors = states.toMap(),
            warnings = warnings.toList(),
        )
    }

    private val YAML_EXTENSIONS: Set<String> = setOf("yaml", "yml")
}
