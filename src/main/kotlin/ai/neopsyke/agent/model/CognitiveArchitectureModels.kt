package ai.neopsyke.agent.model

import java.time.Instant

enum class StimulusFamily {
    LINGUISTIC,
    OBSERVATION,
    FEEDBACK,
    CUE,
}

enum class PerceptFamily {
    REQUEST,
    OBSERVATION,
    FEEDBACK,
    STATE_CHANGE,
    DRIVE_ACTIVATION,
}

data class StimulusEnvelope(
    val id: String,
    val family: StimulusFamily,
    val source: String,
    val content: String,
    val receivedAt: Instant,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val correlationId: String? = null,
    val causationId: String? = null,
    val trustLevel: StimulusTrustLevel = StimulusTrustLevel.DEFAULT,
    val metadata: Map<String, String> = emptyMap(),
)

enum class StimulusTrustLevel {
    TRUSTED_INTERNAL,
    DEFAULT,
    UNTRUSTED_EXTERNAL,
}

data class Percept(
    val id: String,
    val family: PerceptFamily,
    val summary: String,
    val source: String,
    val occurredAt: Instant,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val rootStimulusId: String? = null,
    val cognitiveThreadId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class CognitiveThread(
    val id: String,
    val kind: CognitiveThreadKind,
    val status: CognitiveThreadStatus,
    val title: String,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val goalId: String? = null,
    val goalRunId: String? = null,
    val rootStimulusId: String? = null,
    val lastUpdatedAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)

enum class CognitiveThreadKind {
    CONVERSATION,
    DRIVE,
    GOAL_DIRECTED,
    ACTION_SUSPENSION,
}

enum class CognitiveThreadStatus {
    ACTIVE,
    WAITING,
    BLOCKED,
    RESOLVED,
    FAILED,
}

data class Opportunity(
    val id: String,
    val cognitiveThreadId: String,
    val kind: OpportunityKind,
    val summary: String,
    val salience: Double,
    val createdAt: Instant,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val rootStimulusId: String? = null,
    val goalId: String? = null,
    val goalRunId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class OpportunityKind {
    RESPOND,
    RESUME,
    INTEGRATE_FEEDBACK,
    CLARIFY,
    EXECUTE,
    FINALIZE,
}

data class Intention(
    val id: String,
    val cognitiveThreadId: String,
    val kind: IntentionKind,
    val summary: String,
    val createdAt: Instant,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val rootStimulusId: String? = null,
    val goalId: String? = null,
    val goalRunId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class IntentionKind {
    RESPOND,
    CLARIFY,
    SEARCH,
    CONTINUE_GOAL_WORK,
    WAIT,
    EXECUTE_ACTION,
}
