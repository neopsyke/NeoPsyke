package ai.neopsyke.agent.config

data class ConnectorRuntimeConfig(
    val enabled: Boolean = false,
    val curatedCatalogPath: String = "connectors/catalog",
    val installStateDir: String = ".neopsyke/connectors",
    val failClosed: Boolean = true,
    val pinningEnabled: Boolean = true,
    val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    val healthTimeoutMs: Long = DEFAULT_HEALTH_TIMEOUT_MS,
    val allowedConnectorIds: Set<String> = emptySet(),
    val enabledBundleIds: Set<String> = emptySet(),
    val allowThirdPartyConnectors: Boolean = false,
) {
    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MS: Long = 5_000L
        const val DEFAULT_HEALTH_TIMEOUT_MS: Long = 5_000L
    }
}
