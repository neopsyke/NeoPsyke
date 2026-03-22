package ai.neopsyke.agent.memory.longterm

import ai.neopsyke.agent.model.DialogueTurn
import java.time.Instant

enum class MemoryCapability {
    SEMANTIC_RECALL,
    EPISODIC_RECALL,
    HYBRID_RECALL,
    NARRATIVE_IMPRINT,
    FACT_IMPRINT,
    RELATION_IMPRINT,
    EPISODIC_IMPRINT,
    VERSIONED_FACTS,
    CONSOLIDATION,
}

enum class MemoryAdminCapability {
    STATS,
    FORGET,
    RESET,
}

data class MemoryHealth(
    val provider: String,
    val available: Boolean,
    val detail: String = "",
    val degraded: Boolean = false,
)

enum class RecallIntent {
    GENERAL,
    EPISODIC,
    LESSON,
    USER_PREFERENCE,
    FACT,
    GOAL,
    SELF_REFLECTION,
}

data class TimeRange(
    val start: Instant? = null,
    val end: Instant? = null,
)

data class MemoryContext(
    val sessionId: String? = null,
    val interlocutorId: String? = null,
    val activeGoalIds: List<String> = emptyList(),
    val timeRange: TimeRange? = null,
    val eventTypes: Set<MemoryEventType> = emptySet(),
)

data class RecallLimits(
    val maxItems: Int = 4,
    val maxChars: Int = 1200,
)

data class RecallRequest(
    val cue: String,
    val intent: RecallIntent = RecallIntent.GENERAL,
    val recentDialogue: List<DialogueTurn> = emptyList(),
    val shortTermContextSummary: String = "",
    val context: MemoryContext = MemoryContext(),
    val limits: RecallLimits = RecallLimits(),
) {
    constructor(
        cue: String,
        recentDialogue: List<DialogueTurn> = emptyList(),
        shortTermContextSummary: String = "",
        maxItems: Int = 4,
        maxChars: Int = 1200,
    ) : this(
        cue = cue,
        intent = RecallIntent.GENERAL,
        recentDialogue = recentDialogue,
        shortTermContextSummary = shortTermContextSummary,
        context = MemoryContext(),
        limits = RecallLimits(maxItems = maxItems, maxChars = maxChars)
    )

    val maxItems: Int
        get() = limits.maxItems

    val maxChars: Int
        get() = limits.maxChars
}

enum class MemoryKind {
    NARRATIVE,
    FACT,
    RELATION,
    EPISODE,
    LESSON,
    PREFERENCE,
    GOAL,
    CONSTRAINT,
}

data class MemoryItem(
    val id: String = "",
    val kind: MemoryKind,
    val summary: String,
    val content: String? = null,
    val score: Double? = null,
    val confidence: Double? = null,
    val timestamp: Instant? = null,
    val tags: List<String> = emptyList(),
    val eventType: MemoryEventType? = null,
    val actionType: String? = null,
    val metadata: Map<String, Any?>? = null,
)

data class RecallResult(
    val provider: String,
    val items: List<MemoryItem>,
    val renderedText: String,
    val hitCount: Int = 0,
    val truncated: Boolean = false,
) {
    constructor(
        provider: String,
        text: String,
        hitCount: Int = 0,
        truncated: Boolean = false,
    ) : this(
        provider = provider,
        items = text
            .takeIf { it.isNotBlank() }
            ?.let {
                listOf(
                    MemoryItem(
                        kind = MemoryKind.NARRATIVE,
                        summary = text,
                        content = text,
                    )
                )
            }
            .orEmpty(),
        renderedText = text,
        hitCount = hitCount,
        truncated = truncated
    )

    val text: String
        get() = renderedText
}

sealed interface ImprintRequest {
    val context: MemoryContext
    val confidence: Double
    val tags: List<String>
    val source: String
}

data class NarrativeImprint(
    val summary: String,
    val kind: MemoryKind = MemoryKind.NARRATIVE,
    override val context: MemoryContext = MemoryContext(),
    override val confidence: Double = 0.5,
    override val tags: List<String> = emptyList(),
    override val source: String = "ego_long_term_memory_assessment",
) : ImprintRequest

data class FactImprint(
    val subject: String,
    val predicate: String,
    val obj: String,
    override val context: MemoryContext = MemoryContext(),
    override val confidence: Double = 0.5,
    override val tags: List<String> = emptyList(),
    override val source: String = "ego_fact_imprint",
) : ImprintRequest

data class RelationImprint(
    val from: String,
    val relation: String,
    val to: String,
    override val context: MemoryContext = MemoryContext(),
    override val confidence: Double = 0.5,
    override val tags: List<String> = emptyList(),
    override val source: String = "ego_relation_imprint",
) : ImprintRequest

data class EpisodeImprint(
    val summary: String,
    val eventType: MemoryEventType,
    val occurredAt: Instant,
    val actionType: String? = null,
    val runId: String? = null,
    val details: String? = null,
    val metadata: Map<String, Any?>? = null,
    override val context: MemoryContext = MemoryContext(),
    override val confidence: Double = 1.0,
    override val tags: List<String> = emptyList(),
    override val source: String = "ego_episode_imprint",
) : ImprintRequest

data class ImprintResult(
    val provider: String,
    val accepted: Boolean,
    val storedCount: Int = 0,
    val detail: String = "",
)

data class ConsolidationRequest(
    val reason: ConsolidationReason,
    val maxWorkItems: Int = 20,
)

enum class ConsolidationReason {
    IDLE,
    POST_TURN,
    EXPLICIT_REFLECTION,
    INTERNAL_DRIVE,
}

data class ConsolidationResult(
    val provider: String,
    val supported: Boolean,
    val detail: String = "",
) {
    companion object {
        fun unsupported(provider: String): ConsolidationResult =
            ConsolidationResult(provider = provider, supported = false, detail = "unsupported")
    }
}

data class MemoryStatsResult(
    val stats: Map<String, Any?> = emptyMap(),
)

data class ForgetRequest(
    val tagMarkers: Set<String> = emptySet(),
    val ids: Set<String> = emptySet(),
)

data class ForgetResult(
    val deletedCount: Int,
    val detail: String = "",
)

data class ResetRequest(
    val clearAll: Boolean = false,
)

data class ResetResult(
    val deletedCount: Int,
    val detail: String = "",
)
