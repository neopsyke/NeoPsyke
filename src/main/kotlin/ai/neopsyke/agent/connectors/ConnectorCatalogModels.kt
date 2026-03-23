package ai.neopsyke.agent.connectors

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.InstructionTrust

enum class ConnectorTrustTier {
    FIRST_PARTY_CURATED,
    TRUSTED_PARTNER,
    THIRD_PARTY,
}

data class ConnectorActionManifestTemplate(
    @param:JsonProperty("action_type")
    val actionType: String,
    @param:JsonProperty("tool_name")
    val toolName: String,
    @param:JsonProperty("planner_description")
    val plannerDescription: String,
    @param:JsonProperty("payload_guidance")
    val payloadGuidance: String,
    @param:JsonProperty("payload_schema_example")
    val payloadSchemaExample: String? = null,
    @param:JsonProperty("follow_up_prefix")
    val followUpPrefix: String = "Action completed.",
    @param:JsonProperty("requires_follow_up_thought")
    val requiresFollowUpThought: Boolean = false,
    @param:JsonProperty("effect_class")
    val effectClass: ActionEffectClass = ActionEffectClass.OBSERVE,
    @param:JsonProperty("direct_commit_allowed")
    val directCommitAllowed: Boolean = false,
    @param:JsonProperty("supports_autonomous_commit")
    val supportsAutonomousCommit: Boolean = false,
    @param:JsonProperty("allowed_instruction_trust")
    val allowedInstructionTrust: Set<InstructionTrust> = setOf(
        InstructionTrust.TRUSTED_INSTRUCTION,
        InstructionTrust.UNTRUSTED_INSTRUCTION,
    ),
    @param:JsonProperty("allowed_argument_data_trust")
    val allowedArgumentDataTrust: Set<DataTrust> = setOf(
        DataTrust.TRUSTED_DATA,
        DataTrust.SANITIZED_EXTERNAL_DATA,
    ),
)

data class CuratedConnectorManifest(
    @param:JsonProperty("connector_id")
    val connectorId: String,
    @param:JsonProperty("display_name")
    val displayName: String = connectorId,
    val vendor: String = "unknown",
    val version: String? = null,
    val description: String = "",
    @param:JsonProperty("tool_description_digest")
    val toolDescriptionDigest: String? = null,
    @param:JsonProperty("trust_tier")
    val trustTier: ConnectorTrustTier = ConnectorTrustTier.FIRST_PARTY_CURATED,
    val mode: String = "stdio",
    val command: String = "",
    @param:JsonProperty("fallback_commands")
    val fallbackCommands: List<String> = emptyList(),
    @param:JsonProperty("secret_handles")
    val secretHandles: Set<String> = emptySet(),
    @param:JsonProperty("allowed_tools")
    val allowedTools: Set<String> = emptySet(),
    @param:JsonProperty("action_manifests")
    val actionManifests: List<ConnectorActionManifestTemplate> = emptyList(),
)

data class ConnectorBundleManifest(
    @param:JsonProperty("bundle_id")
    val bundleId: String,
    @param:JsonProperty("display_name")
    val displayName: String = bundleId,
    val description: String = "",
    @param:JsonProperty("connector_ids")
    val connectorIds: Set<String> = emptySet(),
)

data class CuratedConnectorCatalog(
    val connectors: Map<String, CuratedConnectorManifest>,
    val bundles: Map<String, ConnectorBundleManifest>,
) {
    fun connector(connectorId: String): CuratedConnectorManifest? = connectors[connectorId]

    fun bundle(bundleId: String): ConnectorBundleManifest? = bundles[bundleId]

    companion object {
        fun empty(): CuratedConnectorCatalog =
            CuratedConnectorCatalog(
                connectors = emptyMap(),
                bundles = emptyMap(),
            )
    }
}

data class CuratedConnectorCatalogLoadResult(
    val catalog: CuratedConnectorCatalog,
    val warnings: List<String> = emptyList(),
)

data class InstalledConnectorState(
    @param:JsonProperty("connector_id")
    val connectorId: String,
    val enabled: Boolean = false,
    @param:JsonProperty("command_override")
    val commandOverride: String? = null,
    val version: String? = null,
    @param:JsonProperty("binary_digest")
    val binaryDigest: String? = null,
    @param:JsonProperty("tool_description_digest")
    val toolDescriptionDigest: String? = null,
)

data class InstalledConnectorStateLoadResult(
    val connectors: Map<String, InstalledConnectorState>,
    val warnings: List<String> = emptyList(),
)
