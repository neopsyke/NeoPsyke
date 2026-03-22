package ai.neopsyke.pgvector.memory

import ai.neopsyke.pgvector.memory.db.FactWriteRequest
import ai.neopsyke.pgvector.memory.db.MemoryRepository
import ai.neopsyke.pgvector.memory.db.MemoryRow
import ai.neopsyke.pgvector.memory.db.MemoryWriteMode
import ai.neopsyke.pgvector.memory.embedding.Embedder
import ai.neopsyke.pgvector.memory.tools.extractInlineTags
import ai.neopsyke.pgvector.memory.tools.normalizeFingerprint
import ai.neopsyke.pgvector.memory.tools.removeInlineTags
import ai.neopsyke.pgvector.memory.tools.sanitizeFactKey
import ai.neopsyke.pgvector.memory.tools.sanitizeNamespace
import java.time.Instant

class ProviderMemoryService(
    private val config: MemoryServerConfig,
    private val repository: MemoryRepository,
    private val embedder: Embedder,
) {
    fun recall(request: ProviderRecallRequest): ProviderRecallResponse {
        val cue = request.cue.trim()
        if (cue.isBlank()) {
            return ProviderRecallResponse(
                provider = config.serverName,
                items = emptyList(),
                renderedText = "",
                hitCount = 0,
                truncated = false,
            )
        }
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        val embedding = embedder.embed(cue)
        val rows = repository.searchByVector(
            namespace = namespace,
            queryEmbedding = embedding,
            limit = request.maxItems.coerceIn(1, 50),
        )
        val items = rows.map { row ->
            ProviderMemoryItem(
                id = row.id.toString(),
                kind = determineKind(row),
                summary = row.content,
                content = row.content,
                score = row.score,
                confidence = row.confidence,
                timestamp = row.createdAt,
                tags = row.tags,
                metadata = buildMap {
                    put("namespace", row.namespace)
                    put("source", row.source)
                    if (row.factSubject != null) put("factSubject", row.factSubject)
                    if (row.factKey != null) put("factKey", row.factKey)
                    if (row.factValue != null) put("factValue", row.factValue)
                    if (row.supersedesMemoryId != null) put("supersedesMemoryId", row.supersedesMemoryId)
                }
            )
        }
        val rendered = renderRecallText(items, request.maxChars)
        return ProviderRecallResponse(
            provider = config.serverName,
            items = items,
            renderedText = rendered,
            hitCount = items.size,
            truncated = rendered.length >= request.maxChars && items.isNotEmpty(),
        )
    }

    fun imprint(request: ProviderImprintRequest): ProviderImprintResponse {
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        return when (request.type.trim().lowercase()) {
            "narrative" -> storeNarrative(namespace, request)
            "fact" -> storeFact(namespace, request)
            "relation" -> storeRelation(namespace, request)
            "episode" -> storeEpisode(namespace, request)
            else -> ProviderImprintResponse(
                provider = config.serverName,
                accepted = false,
                detail = "unsupported_type",
            )
        }
    }

    fun metrics(): Map<String, Any?> = buildMap {
        put("db_searches", config.serverName) // placeholder key to signal provider metrics availability
    }

    fun reset(request: ProviderResetRequest): ProviderResetResponse {
        if (!request.clearAll) {
            return ProviderResetResponse(deletedCount = 0, detail = "clear_all_false")
        }
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        val deleted = repository.deleteNamespace(namespace)
        return ProviderResetResponse(deletedCount = deleted)
    }

    fun forget(request: ProviderForgetRequest): ProviderForgetResponse {
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        val markers = request.tagMarkers.map { it.trim() }.filter { it.isNotBlank() }
        if (markers.isEmpty()) {
            return ProviderForgetResponse(deletedCount = 0)
        }
        val deleted = repository.deleteByTagMarkers(namespace = namespace, tagMarkers = markers)
        return ProviderForgetResponse(deletedCount = deleted)
    }

    private fun storeNarrative(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val summary = request.summary?.trim().orEmpty()
        if (summary.isBlank()) {
            return ProviderImprintResponse(provider = config.serverName, accepted = false, detail = "blank_summary")
        }
        val tags = request.tags + extractInlineTags(summary)
        val cleanedSummary = removeInlineTags(summary)
        val result = write(
            namespace = namespace,
            content = cleanedSummary,
            source = request.source,
            confidence = request.confidence,
            tags = tags,
            writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR,
            fact = null,
        )
        return ProviderImprintResponse(
            provider = config.serverName,
            accepted = true,
            storedCount = 1,
            detail = result.status,
        )
    }

    private fun storeFact(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val subject = sanitizeNamespace(request.subject, config.factDefaultSubject)
        val predicate = sanitizeFactKey(request.predicate)
            ?: return ProviderImprintResponse(provider = config.serverName, accepted = false, detail = "invalid_predicate")
        val obj = request.obj?.trim().orEmpty()
        if (obj.isBlank()) {
            return ProviderImprintResponse(provider = config.serverName, accepted = false, detail = "blank_object")
        }
        val content = "$subject $predicate $obj"
        val result = write(
            namespace = namespace,
            content = content,
            source = request.source,
            confidence = request.confidence,
            tags = request.tags + listOf("kind:fact"),
            writeMode = MemoryWriteMode.UPSERT_FACT,
            fact = FactWriteRequest(
                subject = subject,
                key = predicate,
                value = obj,
                versionedAt = Instant.now(),
            ),
        )
        return ProviderImprintResponse(
            provider = config.serverName,
            accepted = true,
            storedCount = 1,
            detail = result.status,
        )
    }

    private fun storeRelation(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val from = request.from?.trim().orEmpty()
        val relation = request.relation?.trim().orEmpty()
        val to = request.to?.trim().orEmpty()
        if (from.isBlank() || relation.isBlank() || to.isBlank()) {
            return ProviderImprintResponse(provider = config.serverName, accepted = false, detail = "invalid_relation")
        }
        val content = "$from $relation $to"
        val result = write(
            namespace = namespace,
            content = content,
            source = request.source,
            confidence = request.confidence,
            tags = request.tags + listOf("kind:relation"),
            writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR,
            fact = null,
        )
        return ProviderImprintResponse(
            provider = config.serverName,
            accepted = true,
            storedCount = 1,
            detail = result.status,
        )
    }

    private fun storeEpisode(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val summary = request.summary?.trim().orEmpty()
        if (summary.isBlank()) {
            return ProviderImprintResponse(provider = config.serverName, accepted = false, detail = "blank_summary")
        }
        val eventTag = request.eventType?.takeIf { it.isNotBlank() }?.let { listOf("event:$it") }.orEmpty()
        val result = write(
            namespace = namespace,
            content = summary,
            source = request.source,
            confidence = request.confidence,
            tags = request.tags + listOf("kind:episode") + eventTag,
            writeMode = MemoryWriteMode.APPEND,
            fact = null,
        )
        return ProviderImprintResponse(
            provider = config.serverName,
            accepted = true,
            storedCount = 1,
            detail = result.status,
        )
    }

    private fun write(
        namespace: String,
        content: String,
        source: String,
        confidence: Double,
        tags: List<String>,
        writeMode: MemoryWriteMode,
        fact: FactWriteRequest?,
    ) = repository.writeMemory(
        namespace = namespace,
        content = content,
        embedding = embedder.embed(content),
        source = source,
        confidence = confidence.coerceIn(0.0, 1.0),
        tags = tags.distinct(),
        fingerprint = buildFingerprint(content = content, writeMode = writeMode, fact = fact),
        writeMode = writeMode,
        fact = fact,
    )

    private fun determineKind(row: MemoryRow): String =
        when {
            row.factKey != null -> "FACT"
            row.tags.any { it.equals("kind:relation", ignoreCase = true) } -> "RELATION"
            row.tags.any { it.equals("kind:episode", ignoreCase = true) } -> "EPISODE"
            row.tags.any { it.equals("kind:lesson", ignoreCase = true) } -> "LESSON"
            else -> "NARRATIVE"
        }

    private fun renderRecallText(items: List<ProviderMemoryItem>, maxChars: Int): String {
        if (items.isEmpty()) return ""
        val rendered = items.joinToString(separator = "\n") { "- ${it.summary}" }
        return if (rendered.length <= maxChars) rendered else rendered.take(maxChars)
    }

    private fun buildFingerprint(content: String, writeMode: MemoryWriteMode, fact: FactWriteRequest?): String {
        if (writeMode == MemoryWriteMode.UPSERT_FACT && fact != null) {
            return normalizeFingerprint("${fact.subject}|${fact.key}|${fact.value}")
        }
        return normalizeFingerprint(content)
    }
}
