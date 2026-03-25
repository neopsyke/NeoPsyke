package ai.neopsyke.agent.config

data class BuiltinToolsConfig(
    val websiteFetch: WebsiteFetchConfig = WebsiteFetchConfig(),
)

data class WebsiteFetchConfig(
    val enabled: Boolean = true,
    val callTimeoutMs: Long = 8_000,
    val maxChars: Int = 4_000,
)
