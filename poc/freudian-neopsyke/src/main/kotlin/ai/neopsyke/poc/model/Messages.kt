package ai.neopsyke.poc.model

/**
 * External user input captured by the sensory cortex. Origin is always USER.
 */
data class UserRequest(
    val content: String,
)

/**
 * Internal Id impulse injected into Ego through the dedicated internal path.
 */
data class IdImpulse(
    val rootImpulseId: String,
    val needName: String,
    val message: String,
    val urgency: Double,
    val rawNeedValue: Double,
)

data class ThoughtTask(
    val thoughtId: String,
    val rootImpulseId: String?,
    val needName: String?,
    val origin: OriginSource,
    val content: String,
    val strategy: ThoughtStrategy,
)

enum class ThoughtStrategy {
    NOOP_BRANCH,
    EXECUTION_BRANCH,
    USER_REQUEST_BRANCH,
}

data class ActionProposal(
    val actionId: String,
    val rootImpulseId: String?,
    val needName: String?,
    val origin: OriginSource,
    val type: ActionType,
    val summary: String,
    val payload: String,
)

data class SuperegoDecision(
    val allow: Boolean,
    val reasonCode: String,
    val reason: String,
)

data class MotorOutcome(
    val success: Boolean,
    val status: String,
)

enum class ImpulseResult {
    ACCEPTED,
    DENIED,
}

data class ImpulseFeedback(
    val rootImpulseId: String,
    val needName: String,
    val result: ImpulseResult,
)
