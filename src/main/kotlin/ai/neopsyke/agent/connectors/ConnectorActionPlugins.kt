package ai.neopsyke.agent.connectors

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginHealth
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.ConnectorActionBinding
import ai.neopsyke.agent.actions.ConnectorRuntimeBoundary
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SourceDescriptor
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.ExternalContentPipeline
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.tools.mcp.McpToolCallResult
import java.util.concurrent.atomic.AtomicBoolean

internal class ConnectorActionPlugin(
    private val connectorManifest: CuratedConnectorManifest,
    private val actionManifest: ConnectorActionManifestTemplate,
    private val hostClient: SharedConnectorHost,
    private val config: AgentConfig,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType(actionManifest.actionType),
        dispatchable = true,
        plannerDescription = actionManifest.plannerDescription,
        payloadGuidance = actionManifest.payloadGuidance,
        payloadSchemaExample = actionManifest.payloadSchemaExample,
        requiresFollowUpThought = actionManifest.requiresFollowUpThought,
        followUpPrefix = actionManifest.followUpPrefix,
        effectClass = actionManifest.effectClass,
        directCommitAllowed = actionManifest.directCommitAllowed,
        supportsAutonomousCommit = actionManifest.supportsAutonomousCommit,
        allowedInstructionTrust = actionManifest.allowedInstructionTrust,
        allowedArgumentDataTrust = actionManifest.allowedArgumentDataTrust,
        connectorRuntime = ConnectorRuntimeBoundary.thirdPartyHosted(
            connectorId = connectorManifest.connectorId,
            vendor = connectorManifest.vendor,
        ),
        connectorBinding = ConnectorActionBinding(
            manifestId = connectorManifest.connectorId,
            toolName = actionManifest.toolName,
        ),
    )

    override suspend fun healthCheck(): ActionPluginHealth {
        val health = hostClient.healthCheck(config.connectors.healthTimeoutMs)
        return ActionPluginHealth(
            available = health.available,
            detail = health.detail,
        )
    }

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val payload = action.payload.trim()
        if (payload.isEmpty()) {
            return ActionDeterministicReview(allow = true)
        }
        return try {
            mapper.readTree(payload)
            ActionDeterministicReview(allow = true)
        } catch (_: Exception) {
            ActionDeterministicReview(
                allow = false,
                ruleId = "connector_action_payload_invalid_json",
                reason = "Connector action payload must be blank or valid JSON.",
            )
        }
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val arguments = try {
            parseArguments(action.payload)
        } catch (_: Exception) {
            return ActionOutcome(
                statusSummary = "Connector action failed: payload must be blank or valid JSON.",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "connector_payload_invalid",
            )
        }

        val result = try {
            hostClient.callCapability(
                capabilityName = actionManifest.toolName,
                arguments = arguments,
                timeoutMs = config.mcpCallTimeoutMs,
            )
        } catch (ex: Exception) {
            return ActionOutcome(
                statusSummary = "Connector action failed: ${ex.message ?: "tool call failed"}",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "connector_tool_failed",
            )
        }

        val sanitized = PromptInjectionDefense.sanitizeExternalText(
            result.content.ifBlank { "No content returned." },
            MAX_RESULT_CHARS,
        )
        if (result.isError) {
            return ActionOutcome(
                statusSummary = "Connector tool returned an error: $sanitized",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "connector_tool_error",
            )
        }
        val artifact = ExternalContentPipeline.ingest(
            text = result.content.ifBlank { sanitized },
            maxChars = MAX_RESULT_CHARS,
            source = SourceDescriptor(
                provider = connectorManifest.connectorId,
                contentKind = ContentKind.RESPONSE,
                objectType = actionManifest.toolName,
                sourceRef = action.rootInputId,
            ),
        )
        return ActionOutcome(
            statusSummary = sanitized,
            executionStatus = ActionExecutionStatus.SUCCESS,
            observedEvidence = descriptor.effectClass == ai.neopsyke.agent.model.ActionEffectClass.OBSERVE,
            effects = successEffects(descriptor.effectClass),
            resultArtifacts = listOf(artifact),
        )
    }

    override fun close() {
        hostClient.close()
    }

    private fun parseArguments(payload: String): Map<String, Any> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) {
            return emptyMap()
        }
        val node = mapper.readTree(trimmed)
        return when {
            node.isObject -> mapper.convertValue(node, MAP_TYPE_REFERENCE)
            node.isTextual -> mapOf("input" to node.asText())
            else -> mapOf("input" to trimmed)
        }
    }

    private fun successEffects(effectClass: ai.neopsyke.agent.model.ActionEffectClass): Set<ActionEffect> =
        when (effectClass) {
            ai.neopsyke.agent.model.ActionEffectClass.OBSERVE ->
                setOf(ActionEffect.EVIDENCE_GATHERED)

            ai.neopsyke.agent.model.ActionEffectClass.COMMIT_PRIVATE ->
                setOf(ActionEffect.USER_MESSAGE_DELIVERED)

            else -> setOf(ActionEffect.TASK_PROGRESS)
        }

    companion object {
        private val mapper = jacksonObjectMapper()
        private val MAP_TYPE_REFERENCE = object : TypeReference<Map<String, Any>>() {}
        private const val MAX_RESULT_CHARS: Int = 1_500
    }
}

data class ConnectorPluginLoadResult(
    val plugins: List<AgentActionPlugin>,
    val warnings: List<String>,
)

object ConnectorActionPluginLoader {
    fun load(config: AgentConfig): ConnectorPluginLoadResult {
        if (!config.connectors.enabled) {
            return ConnectorPluginLoadResult(
                plugins = emptyList(),
                warnings = emptyList(),
            )
        }

        val catalogResult = CuratedConnectorCatalogLoader.load(java.nio.file.Paths.get(config.connectors.curatedCatalogPath))
        val stateResult = InstalledConnectorStateLoader.load(java.nio.file.Paths.get(config.connectors.installStateDir))
        val warnings = mutableListOf<String>()
        warnings += catalogResult.warnings
        warnings += stateResult.warnings
        val allowedConnectorIds = resolveAllowedConnectorIds(
            catalog = catalogResult.catalog,
            config = config,
            warnings = warnings,
        )

        val hosts = linkedMapOf<String, SharedConnectorHost>()
        val plugins = mutableListOf<AgentActionPlugin>()
        stateResult.connectors.values
            .sortedBy { it.connectorId }
            .forEach { state ->
                if (!state.enabled) {
                    return@forEach
                }
                if (allowedConnectorIds.isNotEmpty() &&
                    state.connectorId !in allowedConnectorIds
                ) {
                    warnings += "Connector ${state.connectorId} is enabled locally but not allowlisted; skipping."
                    return@forEach
                }

                val manifest = catalogResult.catalog.connector(state.connectorId)
                if (manifest == null) {
                    warnings += "Connector ${state.connectorId} is enabled locally but missing from curated catalog; skipping."
                    return@forEach
                }
                if (manifest.trustTier == ConnectorTrustTier.THIRD_PARTY &&
                    !config.connectors.allowThirdPartyConnectors
                ) {
                    warnings += "Connector ${state.connectorId} requires third-party enablement; skipping."
                    return@forEach
                }
                if (!manifest.mode.equals("stdio", ignoreCase = true)) {
                    warnings += "Connector ${state.connectorId} mode ${manifest.mode} is unsupported in this runtime; skipping."
                    return@forEach
                }

                val command = resolveCommand(manifest = manifest, state = state)
                if (command.isEmpty()) {
                    warnings += "Connector ${state.connectorId} has no executable command configured; skipping."
                    return@forEach
                }

                val host = hosts.getOrPut(state.connectorId) {
                    SharedConnectorHost(
                        delegate = McpConnectorHostClient(
                            connectorId = state.connectorId,
                            command = command,
                        )
                    )
                }

                val capabilities = try {
                    kotlinx.coroutines.runBlocking {
                        host.listCapabilities(config.connectors.startupTimeoutMs)
                    }
                } catch (ex: Exception) {
                    warnings += "Connector ${state.connectorId} startup failed: ${ex.message}; skipping."
                    hosts.remove(state.connectorId)?.close()
                    return@forEach
                }

                val requiredTools = when {
                    manifest.allowedTools.isNotEmpty() -> manifest.allowedTools
                    else -> manifest.actionManifests.map { it.toolName }.toSet()
                }
                val capabilityNames = capabilities.map { it.name }.toSet()
                val missingTools = requiredTools - capabilityNames
                if (missingTools.isNotEmpty()) {
                    warnings += "Connector ${state.connectorId} missing required tools: ${missingTools.sorted().joinToString(", ")}; skipping."
                    hosts.remove(state.connectorId)?.close()
                    return@forEach
                }

                if (config.connectors.pinningEnabled) {
                    val expectedDigest = state.toolDescriptionDigest?.trim().orEmpty()
                        .ifBlank { manifest.toolDescriptionDigest?.trim().orEmpty() }
                    if (expectedDigest.isBlank()) {
                        warnings += "Connector ${state.connectorId} pinning is enabled but no expected tool-description digest is configured; skipping."
                        hosts.remove(state.connectorId)?.close()
                        return@forEach
                    }
                    val actualDigest = ConnectorToolDescriptorPinning.fingerprint(
                        capabilities.filter { it.name in requiredTools }
                    )
                    if (actualDigest != expectedDigest) {
                        warnings += "Connector ${state.connectorId} tool-description digest mismatch; skipping."
                        hosts.remove(state.connectorId)?.close()
                        return@forEach
                    }
                }

                manifest.actionManifests.forEach { actionManifest ->
                    if (actionManifest.toolName !in capabilityNames) {
                        return@forEach
                    }
                    plugins += ConnectorActionPlugin(
                        connectorManifest = manifest,
                        actionManifest = actionManifest,
                        hostClient = host,
                        config = config,
                    )
                }
            }

        return ConnectorPluginLoadResult(
            plugins = plugins.toList(),
            warnings = warnings.toList(),
        )
    }

    private fun resolveAllowedConnectorIds(
        catalog: CuratedConnectorCatalog,
        config: AgentConfig,
        warnings: MutableList<String>,
    ): Set<String> {
        val explicitConnectorIds = config.connectors.allowedConnectorIds
        val presetConnectorIds = linkedSetOf<String>()
        config.connectors.enabledBundleIds.forEach { bundleId ->
            val bundle = catalog.bundle(bundleId)
            if (bundle == null) {
                warnings += "Connector preset $bundleId is configured but missing from the curated catalog."
                return@forEach
            }
            presetConnectorIds += bundle.connectorIds
        }
        return explicitConnectorIds + presetConnectorIds
    }

    private fun resolveCommand(
        manifest: CuratedConnectorManifest,
        state: InstalledConnectorState,
    ): List<String> {
        val raw = state.commandOverride?.trim().orEmpty().ifBlank { manifest.command.trim() }
        return ai.neopsyke.agent.tools.mcp.McpStdioClient.parseCommand(raw)
    }
}

internal class SharedConnectorHost(
    private val delegate: McpConnectorHostClient,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    suspend fun listCapabilities(timeoutMs: Long): List<ConnectorCapabilityDescriptor> =
        delegate.listCapabilities(timeoutMs)

    suspend fun healthCheck(timeoutMs: Long): ConnectorHealthStatus =
        delegate.healthCheck(timeoutMs)

    suspend fun callCapability(
        capabilityName: String,
        arguments: Map<String, Any>,
        timeoutMs: Long,
    ): McpToolCallResult =
        delegate.callCapability(
            capabilityName = capabilityName,
            arguments = arguments,
            timeoutMs = timeoutMs,
        )

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
        }
    }
}
