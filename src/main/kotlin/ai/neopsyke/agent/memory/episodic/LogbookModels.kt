package ai.neopsyke.agent.memory.episodic

import java.time.Instant

/**
 * Types of events recorded in the episodic logbook.
 */
enum class EpisodicEventType {
    INPUT_RECEIVED,
    PLANNER_DECISION,
    ACTION_EXECUTED,
    ACTION_DENIED,
    CONTACT_DELIVERED,
    MEMORY_IMPRINT,
    SELF_INITIATED;

    fun dbValue(): String = name.lowercase()

    companion object {
        fun fromDb(value: String): EpisodicEventType? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * A single timestamped entry in the episodic logbook.
 */
data class LogbookEntry(
    val id: Long = 0,
    val ts: Instant,
    val eventType: EpisodicEventType,
    val summary: String,
    val keywords: List<String> = emptyList(),
    val actionType: String? = null,
    val runId: String? = null,
    val metadata: Map<String, Any?>? = null,
    val sessionId: String? = null,
    val interlocutorId: String? = null,
)

/**
 * Query parameters for episodic memory retrieval.
 */
data class LogbookQuery(
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val keywordSearch: String? = null,
    val eventTypes: Set<EpisodicEventType>? = null,
    val actionTypes: Set<String>? = null,
    val maxResults: Int = 20,
    val sessionId: String? = null,
    val interlocutorId: String? = null,
)

/**
 * Result of an episodic memory query.
 */
data class LogbookRecall(
    val entries: List<LogbookEntry>,
    val totalMatched: Int,
    val truncated: Boolean,
)
