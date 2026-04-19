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
    val provenance: Provenance = Provenances.fromStimulusTrustLevel(source = source, trustLevel = trustLevel),
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
    val provenance: Provenance = Provenances.defaultExternal(),
    val metadata: Map<String, String> = emptyMap(),
)

data class CognitiveThread(
    val id: String,
    val kind: CognitiveThreadKind,
    val status: CognitiveThreadStatus,
    val title: String,
    val conversationContext: ConversationContext = ConversationContext.default(),
    val securityContext: CognitiveThreadSecurityContext =
        CognitiveThreadSecurityContext.fromConversation(ConversationContext.default().security),
    val workItemId: String? = null,
    val assignmentRunId: String? = null,
    val rootStimulusId: String? = null,
    val lastUpdatedAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)

data class CognitiveThreadWaitState(
    val status: CognitiveThreadStatus,
    val reason: String? = null,
    val since: Instant,
    val resumeHint: String? = null,
)

data class CognitiveThreadTerminalState(
    val status: CognitiveThreadStatus,
    val summary: String,
    val reason: String? = null,
    val completedAt: Instant,
)

data class CognitiveThreadSnapshot(
    val thread: CognitiveThread,
    val latestPercept: Percept? = null,
    val latestOpportunity: Opportunity? = null,
    val latestIntention: Intention? = null,
    val waitState: CognitiveThreadWaitState? = null,
    val terminalState: CognitiveThreadTerminalState? = null,
    val lastBlockedReason: String? = null,
    val lastBlockedReasonCode: String? = null,
    val lastDeniedReason: String? = null,
    val lastDeniedReasonCode: String? = null,
)

enum class CognitiveThreadKind {
    CONVERSATION,
    DRIVE,
    ASSIGNMENT_DIRECTED,
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
    val securityContext: CognitiveThreadSecurityContext =
        CognitiveThreadSecurityContext.fromConversation(ConversationContext.default().security),
    val rootStimulusId: String? = null,
    val workItemId: String? = null,
    val assignmentRunId: String? = null,
    val allowedIntentions: Set<IntentionKind> = emptySet(),
    val allowedCommitModes: Set<CommitMode> = setOf(CommitMode.NOT_APPLICABLE),
    val availableActions: Set<ActionType> = emptySet(),
    val actionDefinitions: List<ActionPlanningDefinition> = emptyList(),
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
    val commitMode: CommitMode = CommitMode.NOT_APPLICABLE,
    val rootStimulusId: String? = null,
    val workItemId: String? = null,
    val assignmentRunId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class IntentionKind {
    OBSERVE,
    PREPARE,
    STAGE,
    REQUEST_AUTHORIZATION,
    COMMIT,
}
