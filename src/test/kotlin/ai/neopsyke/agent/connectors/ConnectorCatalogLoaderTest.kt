package ai.neopsyke.agent.cortex.connectors

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.ConnectorRuntimeConfig
import ai.neopsyke.agent.cortex.connectors.ConnectorActionPluginLoader
import ai.neopsyke.agent.cortex.connectors.ConnectorCapabilityDescriptor
import ai.neopsyke.agent.cortex.connectors.ConnectorRuntimePaths
import ai.neopsyke.agent.cortex.connectors.ConnectorToolDescriptorPinning
import ai.neopsyke.agent.cortex.connectors.CuratedConnectorCatalogLoader
import ai.neopsyke.agent.cortex.connectors.InstalledConnectorStateLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectorCatalogLoaderTest {
    @Test
    fun `catalog loader reads curated connectors and bundles`() {
        val tempDir = Files.createTempDirectory("neopsyke-connector-catalog")
        val connectorsDir = ConnectorRuntimePaths.curatedConnectorsDir(tempDir)
        val bundlesDir = ConnectorRuntimePaths.curatedBundlesDir(tempDir)
        Files.createDirectories(connectorsDir)
        Files.createDirectories(bundlesDir)

        Files.writeString(
            connectorsDir.resolve("gmail.yaml"),
            """
            connector_id: gmail
            display_name: Gmail
            vendor: google
            trust_tier: FIRST_PARTY_CURATED
            mode: stdio
            command: uvx gmail-connector
            allowed_tools:
              - gmail.search_messages
              - gmail.get_message
            action_manifests:
              - action_type: gmail_observe_search
                tool_name: gmail.search_messages
                planner_description: Search Gmail messages
                payload_guidance: JSON search payload
            """.trimIndent()
        )
        Files.writeString(
            bundlesDir.resolve("morning-briefing.yaml"),
            """
            bundle_id: morning-briefing
            display_name: Morning Briefing
            connector_ids:
              - gmail
              - telegram
            """.trimIndent()
        )

        val result = CuratedConnectorCatalogLoader.load(tempDir)

        assertTrue(result.warnings.isEmpty())
        assertEquals("Gmail", result.catalog.connector("gmail")?.displayName)
        assertEquals(
            setOf("gmail", "telegram"),
            result.catalog.bundle("morning-briefing")?.connectorIds,
        )
    }

    @Test
    fun `installed connector state loader reads local installed state`() {
        val tempDir = Files.createTempDirectory("neopsyke-connector-state")
        val installedDir = ConnectorRuntimePaths.installedStateDir(tempDir)
        Files.createDirectories(installedDir)
        Files.writeString(
            installedDir.resolve("gmail.yaml"),
            """
            connector_id: gmail
            enabled: true
            command_override: /opt/connectors/gmail
            version: 1.2.3
            tool_description_digest: abc123
            """.trimIndent()
        )

        val result = InstalledConnectorStateLoader.load(tempDir)

        assertTrue(result.warnings.isEmpty())
        val gmail = result.connectors.getValue("gmail")
        assertEquals(true, gmail.enabled)
        assertEquals("/opt/connectors/gmail", gmail.commandOverride)
        assertEquals("abc123", gmail.toolDescriptionDigest)
    }

    @Test
    fun `tool descriptor pinning fingerprint is stable across ordering`() {
        val first = listOf(
            ConnectorCapabilityDescriptor("b", "second", """{"type":"object"}"""),
            ConnectorCapabilityDescriptor("a", "first", """{"type":"object"}"""),
        )
        val second = first.reversed()

        assertEquals(
            ConnectorToolDescriptorPinning.fingerprint(first),
            ConnectorToolDescriptorPinning.fingerprint(second),
        )
    }

    @Test
    fun `connector plugin loader returns no plugins when connectors are disabled`() {
        val result = ConnectorActionPluginLoader.load(AgentConfig())

        assertTrue(result.plugins.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `connector plugin loader skips locally enabled connector that is not allowlisted`() {
        val tempDir = Files.createTempDirectory("neopsyke-connector-loader")
        val catalogDir = tempDir.resolve("catalog")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(ConnectorRuntimePaths.curatedConnectorsDir(catalogDir))
        Files.createDirectories(ConnectorRuntimePaths.installedStateDir(stateDir))
        Files.writeString(
            ConnectorRuntimePaths.curatedConnectorsDir(catalogDir).resolve("gmail.yaml"),
            """
            connector_id: gmail
            display_name: Gmail
            vendor: google
            command: uvx gmail-connector
            action_manifests:
              - action_type: gmail_observe_search
                tool_name: gmail.search_messages
                planner_description: Search Gmail messages
                payload_guidance: JSON search payload
            """.trimIndent()
        )
        Files.writeString(
            ConnectorRuntimePaths.installedStateDir(stateDir).resolve("gmail.yaml"),
            """
            connector_id: gmail
            enabled: true
            """.trimIndent()
        )

        val result = ConnectorActionPluginLoader.load(
            AgentConfig(
                connectors = ConnectorRuntimeConfig(
                    enabled = true,
                    curatedCatalogPath = catalogDir.toString(),
                    installStateDir = stateDir.toString(),
                    allowedConnectorIds = setOf("telegram"),
                )
            )
        )

        assertTrue(result.plugins.isEmpty())
        assertTrue(result.warnings.any { it.contains("not allowlisted") })
    }

    @Test
    fun `bundle presets expand the connector allowlist`() {
        val tempDir = Files.createTempDirectory("neopsyke-connector-bundle-allowlist")
        val catalogDir = tempDir.resolve("catalog")
        val stateDir = tempDir.resolve("state")
        Files.createDirectories(ConnectorRuntimePaths.curatedConnectorsDir(catalogDir))
        Files.createDirectories(ConnectorRuntimePaths.curatedBundlesDir(catalogDir))
        Files.createDirectories(ConnectorRuntimePaths.installedStateDir(stateDir))
        Files.writeString(
            ConnectorRuntimePaths.curatedConnectorsDir(catalogDir).resolve("gmail.yaml"),
            """
            connector_id: gmail
            display_name: Gmail
            vendor: google
            action_manifests:
              - action_type: gmail_observe_search
                tool_name: gmail.search_messages
                planner_description: Search Gmail messages
                payload_guidance: JSON search payload
            """.trimIndent()
        )
        Files.writeString(
            ConnectorRuntimePaths.curatedBundlesDir(catalogDir).resolve("morning-briefing.yaml"),
            """
            bundle_id: morning-briefing
            display_name: Morning Briefing
            connector_ids:
              - gmail
            """.trimIndent()
        )
        Files.writeString(
            ConnectorRuntimePaths.installedStateDir(stateDir).resolve("gmail.yaml"),
            """
            connector_id: gmail
            enabled: true
            """.trimIndent()
        )

        val result = ConnectorActionPluginLoader.load(
            AgentConfig(
                connectors = ConnectorRuntimeConfig(
                    enabled = true,
                    curatedCatalogPath = catalogDir.toString(),
                    installStateDir = stateDir.toString(),
                    enabledBundleIds = setOf("morning-briefing"),
                )
            )
        )

        assertTrue(result.plugins.isEmpty())
        assertTrue(result.warnings.none { it.contains("not allowlisted") })
        assertTrue(result.warnings.any { it.contains("no executable command") })
    }

    @Test
    fun `default curated catalog ships starter connector and preset manifests`() {
        val result = CuratedConnectorCatalogLoader.load(Paths.get("connectors/catalog"))

        assertTrue(result.warnings.isEmpty(), result.warnings.joinToString(" | "))
        assertTrue(result.catalog.connectors.keys.containsAll(setOf("gmail", "news", "rss", "telegram")))
        assertTrue(
            result.catalog.bundles.keys.containsAll(
                setOf("morning-briefing", "inbox-management", "social-automation")
            )
        )
    }
}
