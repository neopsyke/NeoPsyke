package ai.neopsyke.memory.pgvector.db

import ai.neopsyke.memory.pgvector.PgvectorMemoryProviderConfig
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual-only DB-backed evals.
 *
 * Not part of the default build. Run explicitly via:
 *   ./gradlew :neopsyke-pgvector-memory:memoryDbEval
 */
class MemoryRepositoryDbEvalTest {

    @Test
    fun `semantic dedupe merges high-similarity writes in same namespace`() {
        withRepository { repository, config ->
            val namespace = evalNamespace("semantic")
            try {
                val vector = axisVector(config.embeddingDimensions, axis = 0)
                val first = repository.writeMemory(
                    namespace = namespace,
                    content = "User likes concise bullet summaries.",
                    embedding = vector,
                    source = "eval",
                    confidence = 0.90,
                    tags = listOf("preference"),
                    fingerprint = normalize("semantic-first"),
                    writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR
                )
                val second = repository.writeMemory(
                    namespace = namespace,
                    content = "User prefers concise bullet summaries.",
                    embedding = vector,
                    source = "eval",
                    confidence = 0.92,
                    tags = listOf("style"),
                    fingerprint = normalize("semantic-second"),
                    writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR
                )

                assertEquals("inserted", first.status)
                assertEquals("deduped_semantic", second.status)
                assertEquals(first.id, second.id)
                val semanticSimilarity = assertNotNull(second.semanticSimilarity)
                assertTrue(semanticSimilarity >= config.semanticDedupeSimilarityThreshold)

                val rows = repository.searchByVector(
                    namespace = namespace,
                    queryEmbedding = vector,
                    limit = 10
                )
                assertEquals(1, rows.size)
            } finally {
                repository.deleteNamespace(namespace)
            }
        }
    }

    @Test
    fun `fact upsert supersedes prior active value`() {
        withRepository { repository, config ->
            val namespace = evalNamespace("fact")
            try {
                val vector = axisVector(config.embeddingDimensions, axis = 1)
                val first = repository.writeMemory(
                    namespace = namespace,
                    content = "My name is Victor.",
                    embedding = vector,
                    source = "eval",
                    confidence = 0.95,
                    tags = listOf("identity"),
                    fingerprint = normalize("user|profile.name|victor"),
                    writeMode = MemoryWriteMode.UPSERT_FACT,
                    fact = FactWriteRequest(
                        subject = "user",
                        key = "profile.name",
                        value = "Victor",
                        versionedAt = Instant.now()
                    )
                )
                val second = repository.writeMemory(
                    namespace = namespace,
                    content = "My name is Victor Toral.",
                    embedding = vector,
                    source = "eval",
                    confidence = 0.95,
                    tags = listOf("identity"),
                    fingerprint = normalize("user|profile.name|victor toral"),
                    writeMode = MemoryWriteMode.UPSERT_FACT,
                    fact = FactWriteRequest(
                        subject = "user",
                        key = "profile.name",
                        value = "Victor Toral",
                        versionedAt = Instant.now()
                    )
                )

                assertEquals("fact_inserted", first.status)
                assertEquals("fact_updated", second.status)
                assertEquals(first.id, second.supersedesMemoryId)

                val rows = repository.searchByVector(
                    namespace = namespace,
                    queryEmbedding = vector,
                    limit = 20
                ).filter { it.factSubject == "user" && it.factKey == "profile.name" }

                assertTrue(rows.any { it.id == first.id && !it.isActive })
                assertTrue(
                    rows.any {
                        it.id == second.id &&
                            it.isActive &&
                            it.supersedesMemoryId == first.id &&
                            it.factValue == "Victor Toral"
                    }
                )
            } finally {
                repository.deleteNamespace(namespace)
            }
        }
    }

    @Test
    fun `namespace isolation keeps memories separated`() {
        withRepository { repository, config ->
            val namespaceA = evalNamespace("isolation-a")
            val namespaceB = evalNamespace("isolation-b")
            try {
                val vector = axisVector(config.embeddingDimensions, axis = 2)
                repository.writeMemory(
                    namespace = namespaceA,
                    content = "Team A secret memory",
                    embedding = vector,
                    source = "eval",
                    confidence = 0.9,
                    tags = listOf("secret"),
                    fingerprint = normalize("team-a"),
                    writeMode = MemoryWriteMode.APPEND
                )
                repository.writeMemory(
                    namespace = namespaceB,
                    content = "Team B secret memory",
                    embedding = vector,
                    source = "eval",
                    confidence = 0.9,
                    tags = listOf("secret"),
                    fingerprint = normalize("team-b"),
                    writeMode = MemoryWriteMode.APPEND
                )

                val rowsA = repository.searchByVector(namespaceA, vector, limit = 10)
                val rowsB = repository.searchByVector(namespaceB, vector, limit = 10)

                assertTrue(rowsA.isNotEmpty())
                assertTrue(rowsB.isNotEmpty())
                assertTrue(rowsA.all { it.namespace == namespaceA })
                assertTrue(rowsB.all { it.namespace == namespaceB })
                assertTrue(rowsA.none { it.content.contains("Team B", ignoreCase = true) })
                assertTrue(rowsB.none { it.content.contains("Team A", ignoreCase = true) })
            } finally {
                repository.deleteNamespace(namespaceA)
                repository.deleteNamespace(namespaceB)
            }
        }
    }

    private fun withRepository(block: (MemoryRepository, PgvectorMemoryProviderConfig) -> Unit) {
        val config = resolveConfig()
        MemoryRepository(config).use { repository ->
            repository.initSchema()
            block(repository, config)
        }
    }

    private fun resolveConfig(): PgvectorMemoryProviderConfig {
        val base = PgvectorMemoryProviderConfig.fromEnv()
        val detectedDimensions = detectExistingEmbeddingDimensions(base)
        if (detectedDimensions != null && detectedDimensions > 0 && detectedDimensions != base.embeddingDimensions) {
            return base.copy(embeddingDimensions = detectedDimensions)
        }
        return base
    }

    private fun detectExistingEmbeddingDimensions(config: PgvectorMemoryProviderConfig): Int? {
        return try {
            DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword).use { conn ->
                conn.prepareStatement(
                    """
                    SELECT a.atttypmod
                    FROM pg_attribute a
                    JOIN pg_class c ON a.attrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE c.relname = 'memories'
                      AND a.attname = 'embedding'
                      AND NOT a.attisdropped
                      AND n.nspname = current_schema()
                    LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            return null
                        }
                        rs.getInt("atttypmod").coerceAtLeast(0)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun evalNamespace(prefix: String): String =
        "eval_${prefix}_${Instant.now().epochSecond}_${UUID.randomUUID().toString().substring(0, 8)}"

    private fun axisVector(dimensions: Int, axis: Int): FloatArray {
        require(dimensions > 0) { "embedding dimensions must be > 0" }
        val vector = FloatArray(dimensions) { 0f }
        vector[axis.coerceIn(0, dimensions - 1)] = 1f
        return vector
    }

    private fun normalize(raw: String): String =
        raw.lowercase().replace(Regex("\\s+"), " ").trim()
}
