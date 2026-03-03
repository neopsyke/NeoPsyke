package psyke.mcp.memory.db

import com.pgvector.PGvector
import mu.KotlinLogging
import psyke.mcp.memory.MemoryServerConfig
import psyke.mcp.memory.metrics.MemoryServerMetrics
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

private val logger = KotlinLogging.logger {}

data class MemoryRow(
    val id: Long,
    val content: String,
    val source: String,
    val confidence: Double,
    val tags: List<String>,
    val similarity: Double,
    val recency: Double,
    val score: Double,
    val createdAt: Instant,
)

class MemoryRepository(
    private val config: MemoryServerConfig,
    private val metrics: MemoryServerMetrics? = null,
) : AutoCloseable {

    companion object {
        const val MAX_CONTENT_CHARS = 2000
        const val RECENCY_DECAY_BASE = 0.95
        const val SECONDS_PER_DAY = 86400.0
    }

    @Volatile
    private var connection: Connection? = null

    fun getConnection(): Connection {
        val conn = connection
        if (conn != null && !conn.isClosed) return conn
        val newConn = DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword)
        PGvector.addVectorType(newConn)
        connection = newConn
        return newConn
    }

    fun initSchema() {
        SchemaInitializer.initialize(getConnection())
    }

    fun searchByVector(
        queryEmbedding: FloatArray,
        limit: Int,
        minConfidence: Double = 0.0,
        tagFilter: List<String>? = null,
    ): List<MemoryRow> {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            val conn = getConnection()
            val tagClause = if (!tagFilter.isNullOrEmpty()) "AND tags && ?" else ""
            val sql = """
                SELECT id, content, source, confidence, tags, created_at,
                    (1 - (embedding <=> ?)) AS similarity,
                    POWER($RECENCY_DECAY_BASE, EXTRACT(EPOCH FROM (now() - created_at)) / $SECONDS_PER_DAY) AS recency,
                    (1 - (embedding <=> ?))
                        * confidence
                        * POWER($RECENCY_DECAY_BASE, EXTRACT(EPOCH FROM (now() - created_at)) / $SECONDS_PER_DAY)
                        AS score
                FROM memories
                WHERE embedding IS NOT NULL
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
                    return rows
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    /**
     * Inserts a memory, returning the row id. Uses UPSERT for fingerprint dedup.
     * Returns a pair of (id, wasConflict) via the [InsertResult] wrapper.
     */
    fun insertMemory(
        content: String,
        embedding: FloatArray,
        source: String,
        confidence: Double,
        tags: List<String>,
        fingerprint: String,
        metadata: String = "{}",
    ): Long {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            val conn = getConnection()
            val sql = """
                INSERT INTO memories (content, embedding, source, confidence, tags, fingerprint, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (fingerprint) DO UPDATE SET
                    confidence = GREATEST(memories.confidence, EXCLUDED.confidence),
                    tags = (SELECT array_agg(DISTINCT t) FROM unnest(memories.tags || EXCLUDED.tags) t),
                    updated_at = now()
                RETURNING id, (xmax <> 0) AS was_conflict
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, content.take(MAX_CONTENT_CHARS))
                stmt.setObject(2, PGvector(embedding))
                stmt.setString(3, source)
                stmt.setDouble(4, confidence)
                stmt.setArray(5, conn.createArrayOf("text", tags.toTypedArray()))
                stmt.setString(6, fingerprint)
                stmt.setString(7, metadata)

                stmt.executeQuery().use { rs ->
                    rs.next()
                    val id = rs.getLong("id")
                    val wasConflict = rs.getBoolean("was_conflict")
                    metrics?.dbInserts?.incrementAndGet()
                    metrics?.dbInsertLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    if (wasConflict) metrics?.dbUpsertConflicts?.incrementAndGet()
                    return id
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun readAllGroupedBySource(): Map<String, List<String>> {
        val startNs = metrics?.startTimer() ?: 0L
        try {
            val conn = getConnection()
            val sql = "SELECT source, content FROM memories ORDER BY source, created_at DESC"
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableMapOf<String, MutableList<String>>()
                    while (rs.next()) {
                        val source = rs.getString("source")
                        val content = rs.getString("content")
                        result.getOrPut(source) { mutableListOf() }.add(content)
                    }
                    metrics?.dbReads?.incrementAndGet()
                    metrics?.dbReadLatencyNs?.addAndGet(System.nanoTime() - startNs)
                    return result
                }
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun deleteByContentPatterns(patterns: List<String>): Int {
        if (patterns.isEmpty()) return 0
        val startNs = metrics?.startTimer() ?: 0L
        try {
            val conn = getConnection()
            // Build: content ILIKE ANY(ARRAY[...])
            val placeholders = patterns.joinToString(", ") { "?" }
            val sql = "DELETE FROM memories WHERE content ILIKE ANY(ARRAY[$placeholders])"
            conn.prepareStatement(sql).use { stmt ->
                patterns.forEachIndexed { i, pattern ->
                    stmt.setString(i + 1, "%$pattern%")
                }
                val deleted = stmt.executeUpdate()
                metrics?.dbDeletes?.addAndGet(deleted.toLong())
                metrics?.dbDeleteLatencyNs?.addAndGet(System.nanoTime() - startNs)
                return deleted
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    fun deleteByEntityAndContents(entityName: String, contents: List<String>): Int {
        if (contents.isEmpty()) return 0
        val startNs = metrics?.startTimer() ?: 0L
        try {
            val conn = getConnection()
            val placeholders = contents.joinToString(", ") { "?" }
            val sql = "DELETE FROM memories WHERE source = ? AND content IN ($placeholders)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entityName)
                contents.forEachIndexed { i, content ->
                    stmt.setString(i + 2, content)
                }
                val deleted = stmt.executeUpdate()
                metrics?.dbDeletes?.addAndGet(deleted.toLong())
                metrics?.dbDeleteLatencyNs?.addAndGet(System.nanoTime() - startNs)
                return deleted
            }
        } catch (ex: Exception) {
            metrics?.dbErrors?.incrementAndGet()
            throw ex
        }
    }

    private fun mapRow(rs: ResultSet): MemoryRow {
        val tagsArray = rs.getArray("tags")
        @Suppress("UNCHECKED_CAST")
        val tags = (tagsArray?.array as? Array<String>)?.toList() ?: emptyList()
        return MemoryRow(
            id = rs.getLong("id"),
            content = rs.getString("content"),
            source = rs.getString("source"),
            confidence = rs.getDouble("confidence"),
            tags = tags,
            similarity = rs.getDouble("similarity"),
            recency = rs.getDouble("recency"),
            score = rs.getDouble("score"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    override fun close() {
        try {
            connection?.close()
        } catch (ex: Exception) {
            logger.warn(ex) { "Error closing database connection." }
        }
    }
}
