package ai.neopsyke.mcp.memory.db

import com.pgvector.PGvector
import mu.KotlinLogging
import ai.neopsyke.mcp.memory.MemoryServerConfig
import ai.neopsyke.mcp.memory.metrics.MemoryServerMetrics
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

data class MemoryRow(
    val id: Long,
    val namespace: String,
    val content: String,
    val source: String,
    val confidence: Double,
    val tags: List<String>,
    val similarity: Double,
    val recency: Double,
    val score: Double,
    val createdAt: Instant,
    val isActive: Boolean,
    val supersedesMemoryId: Long?,
    val factSubject: String?,
    val factKey: String?,
    val factValue: String?,
    val versionedAt: Instant?,
)

enum class MemoryWriteMode {
    APPEND,
    DEDUPE_IF_SIMILAR,
    UPSERT_FACT;

    companion object {
        fun fromRaw(raw: String?): MemoryWriteMode? =
            when (raw?.trim()?.lowercase()) {
                "append" -> APPEND
                "dedupe_if_similar" -> DEDUPE_IF_SIMILAR
                "upsert_fact" -> UPSERT_FACT
                else -> null
            }
    }
}

data class FactWriteRequest(
    val subject: String,
    val key: String,
    val value: String,
    val versionedAt: Instant?,
)

data class MemoryWriteResult(
    val id: Long,
    val status: String,
    val supersedesMemoryId: Long? = null,
    val semanticSimilarity: Double? = null,
)

class MemoryRepository(
    private val config: MemoryServerConfig,
    private val metrics: MemoryServerMetrics? = null,
) : AutoCloseable {

    companion object {
        const val MAX_CONTENT_CHARS = 2000
        const val RECENCY_DECAY_BASE = 0.95
        const val SECONDS_PER_DAY = 86400.0
        const val ACTIVE_ROW_SCORE_BOOST = 1.05
        const val INACTIVE_ROW_SCORE_PENALTY = 0.60
    }

    fun initSchema() {
        withConnection { connection ->
            SchemaInitializer.initialize(connection, config.embeddingDimensions)
        }
    }

    fun searchByVector(
        namespace: String,
        queryEmbedding: FloatArray,
        limit: Int,
        minConfidence: Double = 0.0,
        tagFilter: List<String>? = null,
    ): List<MemoryRow> {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val tagClause = if (!tagFilter.isNullOrEmpty()) "AND tags && ?" else ""
                val sql = """
                    SELECT id, namespace, content, source, confidence, tags, created_at,
                        is_active, supersedes_memory_id, fact_subject, fact_key, fact_value, versioned_at,
                        (1 - (embedding <=> ?)) AS similarity,
                        POWER($RECENCY_DECAY_BASE, EXTRACT(EPOCH FROM (now() - created_at)) / $SECONDS_PER_DAY) AS recency,
                        (1 - (embedding <=> ?))
                            * confidence
                            * POWER($RECENCY_DECAY_BASE, EXTRACT(EPOCH FROM (now() - created_at)) / $SECONDS_PER_DAY)
                            * CASE WHEN is_active THEN $ACTIVE_ROW_SCORE_BOOST ELSE $INACTIVE_ROW_SCORE_PENALTY END
                            AS score
                    FROM memories
                    WHERE embedding IS NOT NULL
                      AND namespace = ?
                      AND confidence >= ?
                      $tagClause
                    ORDER BY score DESC
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    val vec = PGvector(queryEmbedding)
                    var idx = 1
                    stmt.setObject(idx++, vec)
                    stmt.setObject(idx++, vec)
                    stmt.setString(idx++, namespace)
                    stmt.setDouble(idx++, minConfidence)
                    if (!tagFilter.isNullOrEmpty()) {
                        val arr = conn.createArrayOf("text", tagFilter.toTypedArray())
                        stmt.setArray(idx++, arr)
                    }
                    stmt.setInt(idx, limit.coerceIn(1, 100))

                    stmt.executeQuery().use { rs ->
                        val rows = generateSequence { if (rs.next()) mapRow(rs) else null }.toList()
                        metrics?.dbSearches?.incrementAndGet()
                        metrics?.dbSearchLatencyNs?.addAndGet(System.nanoTime() - startNs)
                        rows
                    }
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun writeMemory(
        namespace: String,
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String = "{}",
        writeMode: MemoryWriteMode = MemoryWriteMode.APPEND,
        fact: FactWriteRequest? = null,
    ): MemoryWriteResult {
        return when (writeMode) {
            MemoryWriteMode.APPEND -> writeAppend(
                namespace = namespace,
                content = content,
                embedding = embedding,
                source = source,
                confidence = confidence,
                tags = tags,
                fingerprint = fingerprint,
                metadata = metadata
            )

            MemoryWriteMode.DEDUPE_IF_SIMILAR -> writeWithSemanticDedupe(
                namespace = namespace,
                content = content,
                embedding = embedding,
                source = source,
                confidence = confidence,
                tags = tags,
                fingerprint = fingerprint,
                metadata = metadata
            )

            MemoryWriteMode.UPSERT_FACT -> upsertFact(
                namespace = namespace,
                content = content,
                embedding = embedding,
                source = source,
                confidence = confidence,
                tags = tags,
                fingerprint = fingerprint,
                metadata = metadata,
                fact = requireNotNull(fact) { "FactWriteRequest is required for UPSERT_FACT mode." }
            )
        }
    }

    fun insertMemory(
        namespace: String,
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String = "{}",
    ): Long =
        writeMemory(
            namespace = namespace,
            content = content,
            embedding = embedding,
            source = source,
            confidence = confidence,
            tags = tags,
            fingerprint = fingerprint,
            metadata = metadata,
            writeMode = MemoryWriteMode.APPEND
        ).id

    fun readAllGroupedBySource(namespace: String): Map<String, List<String>> {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val sql = """
                    SELECT source, content
                    FROM memories
                    WHERE namespace = ?
                    ORDER BY source, is_active DESC, created_at DESC
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, namespace)
                    stmt.executeQuery().use { rs ->
                        val result = mutableMapOf<String, MutableList<String>>()
                        while (rs.next()) {
                            val source = rs.getString("source")
                            val content = rs.getString("content")
                            result.getOrPut(source) { mutableListOf() }.add(content)
                        }
                        metrics?.dbReads?.incrementAndGet()
                        metrics?.dbReadLatencyNs?.addAndGet(System.nanoTime() - startNs)
                        result
                    }
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun deleteByContentPatterns(namespace: String, patterns: List<String>): Int {
        if (patterns.isEmpty()) return 0
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val placeholders = patterns.joinToString(", ") { "?" }
                val sql = "DELETE FROM memories WHERE namespace = ? AND content ILIKE ANY(ARRAY[$placeholders])"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, namespace)
                    patterns.forEachIndexed { i, pattern ->
                        stmt.setString(i + 2, "%$pattern%")
                    }
                    val deleted = stmt.executeUpdate()
                    metrics?.dbDeletes?.addAndGet(deleted.toLong())
                    metrics?.dbDeleteLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    deleted
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun deleteByEntityAndContents(namespace: String, entityName: String, contents: List<String>): Int {
        if (contents.isEmpty()) return 0
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val placeholders = contents.joinToString(", ") { "?" }
                val sql = "DELETE FROM memories WHERE namespace = ? AND source = ? AND content IN ($placeholders)"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, namespace)
                    stmt.setString(2, entityName)
                    contents.forEachIndexed { i, content ->
                        stmt.setString(i + 3, content)
                    }
                    val deleted = stmt.executeUpdate()
                    metrics?.dbDeletes?.addAndGet(deleted.toLong())
                    metrics?.dbDeleteLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    deleted
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun deleteNamespace(namespace: String): Int {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                conn.prepareStatement(
                    """
                    DELETE FROM memories
                    WHERE namespace = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, namespace)
                    val deleted = stmt.executeUpdate()
                    metrics?.dbDeletes?.addAndGet(deleted.toLong())
                    metrics?.dbDeleteLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    deleted
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    private fun writeAppend(
        namespace: String,
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String,
    ): MemoryWriteResult {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val outcome = upsertByFingerprint(
                    conn = conn,
                    namespace = namespace,
                    content = content,
                    embedding = embedding,
                    source = source,
                    confidence = confidence,
                    tags = tags,
                    fingerprint = fingerprint,
                    metadata = metadata,
                    fact = null,
                    supersedesMemoryId = null,
                    isActive = true,
                    versionedAt = null
                )
                metrics?.dbInserts?.incrementAndGet()
                metrics?.dbInsertLatencyNs?.addAndGet(System.nanoTime() - startNs)
                if (outcome.status == "merged_fingerprint") {
                    metrics?.dbUpsertConflicts?.incrementAndGet()
                }
                outcome
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    private fun writeWithSemanticDedupe(
        namespace: String,
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String,
    ): MemoryWriteResult {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val candidate = findBestSemanticCandidate(
                    conn = conn,
                    namespace = namespace,
                    embedding = embedding,
                    minConfidence = config.semanticDedupeMinConfidence
                )
                if (candidate != null && candidate.similarity >= config.semanticDedupeSimilarityThreshold) {
                    mergeExistingMemory(
                        conn = conn,
                        id = candidate.id,
                        confidence = confidence,
                        tags = tags
                    )
                    metrics?.dbSemanticDedupeHits?.incrementAndGet()
                    metrics?.dbInsertLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    return@withConnection MemoryWriteResult(
                        id = candidate.id,
                        status = "deduped_semantic",
                        semanticSimilarity = candidate.similarity
                    )
                }

                val outcome = upsertByFingerprint(
                    conn = conn,
                    namespace = namespace,
                    content = content,
                    embedding = embedding,
                    source = source,
                    confidence = confidence,
                    tags = tags,
                    fingerprint = fingerprint,
                    metadata = metadata,
                    fact = null,
                    supersedesMemoryId = null,
                    isActive = true,
                    versionedAt = null
                )
                metrics?.dbInserts?.incrementAndGet()
                metrics?.dbInsertLatencyNs?.addAndGet(System.nanoTime() - startNs)
                if (outcome.status == "merged_fingerprint") {
                    metrics?.dbUpsertConflicts?.incrementAndGet()
                }
                outcome
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    private fun upsertFact(
        namespace: String,
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String,
        fact: FactWriteRequest,
    ): MemoryWriteResult {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            return withConnection { conn ->
                val originalAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val activeFact = findActiveFact(
                        conn = conn,
                        namespace = namespace,
                        subject = fact.subject,
                        key = fact.key
                    )
                    if (activeFact != null && normalizeForComparison(activeFact.value) == normalizeForComparison(fact.value)) {
                        mergeExistingMemory(
                            conn = conn,
                            id = activeFact.id,
                            confidence = confidence,
                            tags = tags
                        )
                        conn.commit()
                        metrics?.dbFactUpserts?.incrementAndGet()
                        metrics?.dbInsertLatencyNs?.addAndGet(System.nanoTime() - startNs)
                        return@withConnection MemoryWriteResult(
                            id = activeFact.id,
                            status = "fact_unchanged"
                        )
                    }

                    var supersededId: Long? = null
                    if (activeFact != null) {
                        supersededId = activeFact.id
                        deactivateMemory(conn, activeFact.id)
                        metrics?.dbFactSupersedes?.incrementAndGet()
                    }

                    val outcome = upsertByFingerprint(
                        conn = conn,
                        namespace = namespace,
                        content = content,
                        embedding = embedding,
                        source = source,
                        confidence = confidence,
                        tags = tags,
                        fingerprint = fingerprint,
                        metadata = metadata,
                        fact = fact,
                        supersedesMemoryId = supersededId,
                        isActive = true,
                        versionedAt = fact.versionedAt ?: Instant.now()
                    )
                    conn.commit()
                    metrics?.dbFactUpserts?.incrementAndGet()
                    metrics?.dbInserts?.incrementAndGet()
                    metrics?.dbInsertLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    if (outcome.status == "merged_fingerprint") {
                        metrics?.dbUpsertConflicts?.incrementAndGet()
                    }
                    outcome.copy(
                        status = if (supersededId != null) "fact_updated" else "fact_inserted",
                        supersedesMemoryId = supersededId
                    )
                } catch (ex: Exception) {
                    conn.rollback()
                    throw ex
                } finally {
                    conn.autoCommit = originalAutoCommit
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    private fun upsertByFingerprint(
        conn: Connection,
        namespace: String,
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String,
        fact: FactWriteRequest?,
        supersedesMemoryId: Long?,
        isActive: Boolean,
        versionedAt: Instant?,
    ): MemoryWriteResult {
        val sql = """
            INSERT INTO memories (
                namespace,
                content,
                embedding,
                source,
                confidence,
                tags,
                fingerprint,
                metadata,
                fact_subject,
                fact_key,
                fact_value,
                versioned_at,
                supersedes_memory_id,
                is_active
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (namespace, fingerprint) DO UPDATE SET
                content = EXCLUDED.content,
                confidence = GREATEST(memories.confidence, EXCLUDED.confidence),
                tags = (SELECT array_agg(DISTINCT t) FROM unnest(memories.tags || EXCLUDED.tags) t),
                metadata = CASE
                    WHEN memories.metadata = '{}'::jsonb THEN EXCLUDED.metadata
                    ELSE memories.metadata
                END,
                source = EXCLUDED.source,
                fact_subject = COALESCE(EXCLUDED.fact_subject, memories.fact_subject),
                fact_key = COALESCE(EXCLUDED.fact_key, memories.fact_key),
                fact_value = COALESCE(EXCLUDED.fact_value, memories.fact_value),
                versioned_at = COALESCE(EXCLUDED.versioned_at, memories.versioned_at),
                supersedes_memory_id = COALESCE(EXCLUDED.supersedes_memory_id, memories.supersedes_memory_id),
                is_active = EXCLUDED.is_active,
                updated_at = now()
            RETURNING id, (xmax <> 0) AS was_conflict
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, namespace)
            stmt.setString(2, content.take(MAX_CONTENT_CHARS))
            stmt.setObject(3, PGvector(embedding))
            stmt.setString(4, source)
            stmt.setDouble(5, confidence)
            stmt.setArray(6, conn.createArrayOf("text", tags.toTypedArray()))
            stmt.setString(7, fingerprint)
            stmt.setString(8, metadata)
            stmt.setString(9, fact?.subject)
            stmt.setString(10, fact?.key)
            stmt.setString(11, fact?.value?.take(MAX_CONTENT_CHARS))
            stmt.setObject(12, versionedAt)
            stmt.setObject(13, supersedesMemoryId)
            stmt.setBoolean(14, isActive)

            stmt.executeQuery().use { rs ->
                rs.next()
                val id = rs.getLong("id")
                val wasConflict = rs.getBoolean("was_conflict")
                return MemoryWriteResult(
                    id = id,
                    status = if (wasConflict) "merged_fingerprint" else "inserted",
                    supersedesMemoryId = supersedesMemoryId
                )
            }
        }
    }

    private fun findBestSemanticCandidate(
        conn: Connection,
        namespace: String,
        embedding: FloatArray,
        minConfidence: Double,
    ): SemanticCandidate? {
        val sql = """
            SELECT id, confidence, (1 - (embedding <=> ?)) AS similarity
            FROM memories
            WHERE namespace = ?
              AND embedding IS NOT NULL
              AND is_active = TRUE
              AND confidence >= ?
            ORDER BY embedding <=> ? ASC
            LIMIT 1
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            val vec = PGvector(embedding)
            stmt.setObject(1, vec)
            stmt.setString(2, namespace)
            stmt.setDouble(3, minConfidence)
            stmt.setObject(4, vec)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return SemanticCandidate(
                    id = rs.getLong("id"),
                    similarity = rs.getDouble("similarity")
                )
            }
        }
    }

    private fun mergeExistingMemory(
        conn: Connection,
        id: Long,
        confidence: Double,
        tags: List<String>,
    ) {
        val sql = """
            UPDATE memories
            SET confidence = GREATEST(confidence, ?),
                tags = (SELECT array_agg(DISTINCT t) FROM unnest(tags || ?::text[]) t),
                updated_at = now()
            WHERE id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setDouble(1, confidence)
            stmt.setArray(2, conn.createArrayOf("text", tags.toTypedArray()))
            stmt.setLong(3, id)
            stmt.executeUpdate()
        }
    }

    private fun findActiveFact(
        conn: Connection,
        namespace: String,
        subject: String,
        key: String,
    ): ActiveFactRow? {
        val sql = """
            SELECT id, fact_value, content
            FROM memories
            WHERE namespace = ?
              AND fact_subject = ?
              AND fact_key = ?
              AND is_active = TRUE
            ORDER BY COALESCE(versioned_at, created_at) DESC
            LIMIT 1
            FOR UPDATE
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, namespace)
            stmt.setString(2, subject)
            stmt.setString(3, key)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return ActiveFactRow(
                    id = rs.getLong("id"),
                    value = rs.getString("fact_value") ?: rs.getString("content")
                )
            }
        }
    }

    private fun deactivateMemory(conn: Connection, id: Long) {
        conn.prepareStatement(
            """
            UPDATE memories
            SET is_active = FALSE,
                updated_at = now()
            WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setLong(1, id)
            stmt.executeUpdate()
        }
    }

    private fun normalizeForComparison(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun <T> withConnection(block: (Connection) -> T): T {
        val connection = DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword)
        connection.use { conn ->
            PGvector.addVectorType(conn)
            return block(conn)
        }
    }

    private fun mapRow(rs: ResultSet): MemoryRow {
        val tagsArray = rs.getArray("tags")
        @Suppress("UNCHECKED_CAST")
        val tags = (tagsArray?.array as? Array<String>)?.toList() ?: emptyList()
        val versionedAt = rs.getTimestamp("versioned_at")?.toInstant()
        return MemoryRow(
            id = rs.getLong("id"),
            namespace = rs.getString("namespace"),
            content = rs.getString("content"),
            source = rs.getString("source"),
            confidence = rs.getDouble("confidence"),
            tags = tags,
            similarity = rs.getDouble("similarity"),
            recency = rs.getDouble("recency"),
            score = rs.getDouble("score"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            isActive = rs.getBoolean("is_active"),
            supersedesMemoryId = rs.getLong("supersedes_memory_id").let { if (rs.wasNull()) null else it },
            factSubject = rs.getString("fact_subject"),
            factKey = rs.getString("fact_key"),
            factValue = rs.getString("fact_value"),
            versionedAt = versionedAt
        )
    }

    override fun close() {
        logger.debug { "MemoryRepository closed." }
    }

    private data class SemanticCandidate(
        val id: Long,
        val similarity: Double,
    )

    private data class ActiveFactRow(
        val id: Long,
        val value: String,
    )
}
