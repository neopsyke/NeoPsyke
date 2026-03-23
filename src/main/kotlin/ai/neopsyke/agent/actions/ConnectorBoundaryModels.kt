package ai.neopsyke.agent.actions

data class SecretHandle(
    val name: String,
)

data class ConnectorActionBinding(
    val manifestId: String,
    val toolName: String,
)

interface ActionSecretProvider {
    fun read(handle: SecretHandle): String?

    fun materialize(handles: Set<SecretHandle>): Map<String, String> =
        handles
            .associate { handle -> handle.name to read(handle) }
            .filterValues { value -> !value.isNullOrBlank() }
            .mapValues { (_, value) -> value.orEmpty() }
}

class EnvActionSecretProvider(
    private val env: Map<String, String>,
) : ActionSecretProvider {
    override fun read(handle: SecretHandle): String? = env[handle.name]
}

enum class ConnectorIsolationLevel {
    FIRST_PARTY_IN_PROCESS,
    THIRD_PARTY_OUT_OF_PROCESS,
}

data class ConnectorRuntimeBoundary(
    val connectorId: String,
    val vendor: String,
    val isolationLevel: ConnectorIsolationLevel,
    val trustedCode: Boolean,
    val supportsThirdPartyHost: Boolean,
) {
    companion object {
        fun firstPartyBuiltin(): ConnectorRuntimeBoundary =
            ConnectorRuntimeBoundary(
                connectorId = "builtin",
                vendor = "neopsyke",
                isolationLevel = ConnectorIsolationLevel.FIRST_PARTY_IN_PROCESS,
                trustedCode = true,
                supportsThirdPartyHost = false,
            )

        fun thirdPartyHosted(
            connectorId: String,
            vendor: String,
        ): ConnectorRuntimeBoundary =
            ConnectorRuntimeBoundary(
                connectorId = connectorId,
                vendor = vendor,
                isolationLevel = ConnectorIsolationLevel.THIRD_PARTY_OUT_OF_PROCESS,
                trustedCode = false,
                supportsThirdPartyHost = true,
            )
    }
}
