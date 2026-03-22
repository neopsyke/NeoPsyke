package ai.neopsyke.memory.pgvector

import ai.neopsyke.memory.pgvector.db.FactWriteRequest
import ai.neopsyke.memory.pgvector.db.MemoryRepository
import ai.neopsyke.memory.pgvector.db.MemoryRow
import ai.neopsyke.memory.pgvector.db.MemoryWriteMode
import ai.neopsyke.memory.pgvector.embedding.Embedder
import ai.neopsyke.memory.pgvector.tools.extractInlineTags
import ai.neopsyke.memory.pgvector.tools.normalizeFingerprint
import ai.neopsyke.memory.pgvector.tools.removeInlineTags
import ai.neopsyke.memory.pgvector.tools.sanitizeFactKey
import ai.neopsyke.memory.pgvector.tools.sanitizeNamespace
import java.io.IOException
import java.sql.SQLException
import java.time.Instant

class ProviderMemoryService(
    private val config: PgvectorMemoryProviderConfig,
    private val repository: MemoryRepository,
    private val embedder: Embedder,
) {
    fun recall(request: ProviderRecallRequest): ProviderRecallResponse {
        val cue = request.cue.trim()
        if (cue.isBlank()) {
            return ProviderRecallResponse(
                provider = config.providerName,
                items = emptyList(),
                renderedText = "",
                hitCount = 0,
                truncated = false,
            )
        }
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        val rows = withDependencyGuard("recall_unavailable") {
            val embedding = embedder.embed(cue)
            repository.searchByVector(
                namespace = namespace,
                queryEmbedding = embedding,
                limit = request.maxItems.coerceIn(1, 50),
            )
        }
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
            provider = config.providerName,
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
            else -> throw ProviderBadRequestException(
                errorCode = "unsupported_type",
                message = "Unsupported imprint type '${request.type}'."
            )
        }
    }

    fun metrics(): Map<String, Any?> = buildMap {
        put("db_searches", config.providerName) // placeholder key to signal provider metrics availability
    }

    fun reset(request: ProviderResetRequest): ProviderResetResponse {
        if (!request.clearAll) {
            throw ProviderBadRequestException(
                errorCode = "clear_all_false",
                message = "Reset requires clearAll=true."
            )
        }
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        val deleted = withDependencyGuard("reset_unavailable") {
            repository.deleteNamespace(namespace)
        }
        return ProviderResetResponse(deletedCount = deleted)
    }

    fun forget(request: ProviderForgetRequest): ProviderForgetResponse {
        val namespace = sanitizeNamespace(request.namespace, config.defaultNamespace)
        val markers = request.tagMarkers.map { it.trim() }.filter { it.isNotBlank() }
        val ids = request.ids.map { it.trim() }.filter { it.isNotBlank() }
        if (markers.isEmpty() && ids.isEmpty()) {
            throw ProviderBadRequestException(
                errorCode = "missing_delete_criteria",
                message = "Forget requires at least one tag marker or id."
            )
        }
        if (ids.isNotEmpty()) {
            throw ProviderBadRequestException(
                errorCode = "forget_by_id_not_supported",
                message = "v1 forget supports tag markers only."
            )
        }
        val deleted = withDependencyGuard("forget_unavailable") {
            repository.deleteByTagMarkers(namespace = namespace, tagMarkers = markers)
        }
        return ProviderForgetResponse(deletedCount = deleted)
    }

    private fun storeNarrative(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val summary = request.summary?.trim().orEmpty()
        if (summary.isBlank()) {
            throw ProviderBadRequestException(
                errorCode = "blank_summary",
                message = "Narrative imprint requires a non-blank summary."
            )
        }
        val tags = request.tags + extractInlineTags(summary)
        val cleanedSummary = removeInlineTags(summary)
        val result = withDependencyGuard("narrative_imprint_unavailable") {
            write(
                namespace = namespace,
                content = cleanedSummary,
                source = request.source,
                confidence = request.confidence,
                tags = tags,
                writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR,
                fact = null,
            )
        }
        return ProviderImprintResponse(
            provider = config.providerName,
            accepted = true,
            storedCount = 1,
            detail = result.status,
        )
    }

    private fun storeFact(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val subject = sanitizeNamespace(request.subject, config.factDefaultSubject)
        val predicate = sanitizeFactKey(request.predicate)
            ?: throw ProviderBadRequestException(
                errorCode = "invalid_predicate",
                message = "Fact imprint requires a valid predicate."
            )
        val obj = request.obj?.trim().orEmpty()
        if (obj.isBlank()) {
            throw ProviderBadRequestException(
                errorCode = "blank_object",
                message = "Fact imprint requires a non-blank object value."
            )
        }
        val content = "$subject $predicate $obj"
        val result = withDependencyGuard("fact_imprint_unavailable") {
            write(
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
        }
        return ProviderImprintResponse(
            provider = config.providerName,
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
            throw ProviderBadRequestException(
                errorCode = "invalid_relation",
                message = "Relation imprint requires non-blank from, relation, and to fields."
            )
        }
        val content = "$from $relation $to"
        val result = withDependencyGuard("relation_imprint_unavailable") {
            write(
                namespace = namespace,
                content = content,
                source = request.source,
                confidence = request.confidence,
                tags = request.tags + listOf("kind:relation"),
                writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR,
                fact = null,
            )
        }
        return ProviderImprintResponse(
            provider = config.providerName,
            accepted = true,
            storedCount = 1,
            detail = result.status,
        )
    }

    private fun storeEpisode(namespace: String, request: ProviderImprintRequest): ProviderImprintResponse {
        val summary = request.summary?.trim().orEmpty()
        if (summary.isBlank()) {
            throw ProviderBadRequestException(
                errorCode = "blank_summary",
                message = "Episode imprint requires a non-blank summary."
            )
        }
        val eventTag = request.eventType?.takeIf { it.isNotBlank() }?.let { listOf("event:$it") }.orEmpty()
        val result = withDependencyGuard("episode_imprint_unavailable") {
            write(
                namespace = namespace,
                content = summary,
                source = request.source,
                confidence = request.confidence,
                tags = request.tags + listOf("kind:episode") + eventTag,
                writeMode = MemoryWriteMode.APPEND,
                fact = null,
            )
        }
        return ProviderImprintResponse(
            provider = config.providerName,
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

    private fun <T> withDependencyGuard(errorCode: String, block: () -> T): T =
        try {
            block()
        } catch (ex: ProviderBadRequestException) {
            throw ex
        } catch (ex: SQLException) {
            throw ProviderDependencyException(errorCode, "Storage dependency failed.", ex)
        } catch (ex: IOException) {
            throw ProviderDependencyException(errorCode, "Provider dependency failed.", ex)
        }
}
