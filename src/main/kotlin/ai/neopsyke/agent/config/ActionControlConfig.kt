package ai.neopsyke.agent.config

data class ActionControlConfig(
    val enabled: Boolean = true,
    val dbPath: String = ".neopsyke/action-control.db",
    val policyPath: String = "action-security.yaml",
    val authorizationTtlMs: Long = 86_400_000L,
    val maxInspectResults: Int = 200,
)
