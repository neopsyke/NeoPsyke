package ai.neopsyke.poc.config

data class PocConfig(
    val runtime: RuntimeConfig = RuntimeConfig(),
    val sensory: SensoryConfig = SensoryConfig(),
    val id: IdConfig = IdConfig(),
    val ego: EgoConfig = EgoConfig(),
    val superego: SuperegoConfig = SuperegoConfig(),
    val motor: MotorConfig = MotorConfig(),
    val logging: LoggingConfig = LoggingConfig(),
)

data class RuntimeConfig(
    val deterministicMode: Boolean = true,
    val deterministicSeed: Long = 42L,
    val totalTicks: Int = 60,
)

data class SensoryConfig(
    val scheduledUserRequests: List<ScheduledUserRequest> = emptyList(),
)

data class ScheduledUserRequest(
    val tick: Int,
    val content: String,
)

data class IdConfig(
    val enabled: Boolean = true,
    val triggerThreshold: Double = 0.7,
    val triggerProbability: Double = 1.0,
    val maxConsecutiveDenials: Int = 3,
    val backoffTicks: Int = 5,
    val needs: Map<String, NeedConfig> = mapOf(
        "be_useful" to NeedConfig(
            growthRate = 0.03,
            impulseMessage = "I feel a drive to be useful."
        ),
        "interact_with_user" to NeedConfig(
            growthRate = 0.025,
            impulseMessage = "I feel a drive to interact with the user."
        ),
        "learn_something" to NeedConfig(
            growthRate = 0.035,
            impulseMessage = "I feel a drive to learn something."
        ),
    ),
)

data class NeedConfig(
    val growthRate: Double,
    val resetValue: Double = 0.0,
    val impulseMessage: String,
)

data class EgoConfig(
    val parallelThoughtsPerImpulse: Int = 2,
    val includeNoopThoughtBranch: Boolean = true,
)

data class SuperegoConfig(
    val allowIdContactUser: Boolean = false,
    val allowedIdActionTypes: Set<String> = setOf(
        "REFLECT_INTERNAL",
        "WEB_SEARCH",
    ),
)

data class MotorConfig(
    val executableActionTypes: Set<String> = setOf(
        "REFLECT_INTERNAL",
        "WEB_SEARCH",
        "CONTACT_USER",
    ),
)

data class LoggingConfig(
    val eventsPath: String = "build/poc-events.jsonl",
    val summaryPath: String = "build/poc-summary.json",
)
