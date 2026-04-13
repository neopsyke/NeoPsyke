package ai.neopsyke.agent.config

data class ActionControlConfig(
    val enabled: Boolean = true,
    val dbPath: String = ".neopsyke/action-control.db",
    val policyPath: String = "action-security.yaml",
    val authorizationTtlMs: Long = 86_400_000L,
    val maxInspectResults: Int = 200,
    val autonomousWorkerEnabled: Boolean = true,
    val autonomousWorkerPollMs: Long = 500L,
    val autonomousWorkerBatchSize: Int = 16,
    val observePerTypePerRootInput: Int = 10,
    val contactUserPerRootInput: Int = 5,
    val reflectionFamilyPerRootInput: Int = 2,
    val reflectEvidencePerRootInput: Int = 1,
    val durableWorkOperationPerRootInput: Int = 3,
    val commitPrivatePerTypePerRootInput: Int = 3,
    val commitStatefulPerTypePerRootInput: Int = 2,
    val commitPublicPerTypePerRootInput: Int = 1,
    val controlPlanePerTypePerRootInput: Int = 2,
)
