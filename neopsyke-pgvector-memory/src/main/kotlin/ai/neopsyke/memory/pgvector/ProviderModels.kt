package ai.neopsyke.memory.pgvector

import java.time.Instant

data class ProviderRecallRequest(
    val namespace: String? = null,
    val cue: String = "",
    val intent: String = "GENERAL",
    val maxItems: Int = 4,
    val maxChars: Int = 1200,
    val sessionId: String? = null,
    val interlocutorId: String? = null,
    val activeGoalIds: List<String> = emptyList(),
)

data class ProviderMemoryItem(
    val id: String,
    val kind: String,
    val summary: String,
    val content: String? = null,
    val score: Double? = null,
    val confidence: Double? = null,
    val timestamp: Instant? = null,
    val tags: List<String> = emptyList(),
    val eventType: String? = null,
    val metadata: Map<String, Any?>? = null,
)

data class ProviderRecallResponse(
    val provider: String,
    val items: List<ProviderMemoryItem>,
    val renderedText: String,
    val hitCount: Int,
    val truncated: Boolean,
)

data class ProviderImprintRequest(
    val type: String,
    val namespace: String? = null,
    val summary: String? = null,
    val kind: String? = null,
    val subject: String? = null,
    val predicate: String? = null,
    val obj: String? = null,
    val from: String? = null,
    val relation: String? = null,
    val to: String? = null,
    val eventType: String? = null,
    val occurredAt: Instant? = null,
    val actionType: String? = null,
    val runId: String? = null,
    val details: String? = null,
    val metadata: Map<String, Any?>? = null,
    val confidence: Double = 0.5,
    val tags: List<String> = emptyList(),
    val source: String = "provider_http",
)

data class ProviderImprintResponse(
    val provider: String,
    val accepted: Boolean,
    val storedCount: Int = 0,
    val detail: String = "",
)

data class ProviderForgetRequest(
    val namespace: String? = null,
    val tagMarkers: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
)

data class ProviderForgetResponse(
    val deletedCount: Int,
    val detail: String = "",
)

data class ProviderResetRequest(
    val namespace: String? = null,
    val clearAll: Boolean = false,
)

data class ProviderResetResponse(
    val deletedCount: Int,
    val detail: String = "",
)
