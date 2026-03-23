package ai.neopsyke.agent.connectors

import ai.neopsyke.agent.tools.mcp.LazyMcpClientHolder
import ai.neopsyke.agent.tools.mcp.McpToolCallResult
import ai.neopsyke.agent.tools.mcp.McpToolDescriptor
import kotlinx.coroutines.CoroutineScope
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ConnectorCapabilityDescriptor(
    val name: String,
    val description: String,
    val inputSchema: String,
)

data class ConnectorHealthStatus(
    val available: Boolean,
    val detail: String,
)

class McpConnectorHostClient(
    private val connectorId: String,
    command: List<String>,
    scope: CoroutineScope? = null,
) : AutoCloseable {
    private val clientHolder = LazyMcpClientHolder(
        command = command,
        serverLabel = "connector-$connectorId",
        scope = scope,
    )

    suspend fun listCapabilities(timeoutMs: Long): List<ConnectorCapabilityDescriptor> =
        clientHolder.listToolDescriptors(timeoutMs)
            .map { it.toCapabilityDescriptor() }
            .sortedBy { it.name }

    suspend fun healthCheck(timeoutMs: Long): ConnectorHealthStatus =
        try {
            val capabilities = listCapabilities(timeoutMs)
            ConnectorHealthStatus(
                available = true,
                detail = "Connector $connectorId reachable with ${capabilities.size} capabilities.",
            )
        } catch (ex: Exception) {
            ConnectorHealthStatus(
                available = false,
                detail = "Connector $connectorId health check failed: ${ex.message ?: "unknown error"}",
            )
        }

    suspend fun callCapability(
        capabilityName: String,
        arguments: Map<String, Any>,
        timeoutMs: Long,
    ): McpToolCallResult =
        clientHolder.callTool(
            toolName = capabilityName,
            arguments = arguments,
            timeoutMs = timeoutMs,
        )

    override fun close() {
        clientHolder.close()
    }
}

object ConnectorToolDescriptorPinning {
    fun fingerprint(capabilities: List<ConnectorCapabilityDescriptor>): String {
        val canonical = capabilities
            .sortedBy { it.name }
            .joinToString(separator = "\n") { capability ->
                listOf(
                    capability.name,
                    capability.description.trim(),
                    capability.inputSchema.trim(),
                ).joinToString(separator = "\u001f")
            }
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private const val DIGEST_ALGORITHM: String = "SHA-256"
}

private fun McpToolDescriptor.toCapabilityDescriptor(): ConnectorCapabilityDescriptor =
    ConnectorCapabilityDescriptor(
        name = name,
        description = description,
        inputSchema = inputSchema,
    )
