package ai.neopsyke.memory.pgvector.db

import mu.KotlinLogging
import java.sql.Connection

private val logger = KotlinLogging.logger {}

object SchemaInitializer {

    private const val SCHEMA_RESOURCE = "/schema/V001__init.sql"
    private const val EMBEDDING_DIMENSIONS_TOKEN = "{{EMBEDDING_DIMENSIONS}}"

    fun initialize(connection: Connection, expectedEmbeddingDimensions: Int) {
        val schemaTemplate = SchemaInitializer::class.java.getResourceAsStream(SCHEMA_RESOURCE)
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Schema resource not found: $SCHEMA_RESOURCE")
        val sql = schemaTemplate.replace(EMBEDDING_DIMENSIONS_TOKEN, expectedEmbeddingDimensions.toString())

        connection.createStatement().use { stmt ->
            stmt.execute(sql)
        }
        validateEmbeddingDimensions(connection, expectedEmbeddingDimensions)
        logger.info { "Database schema initialized." }
    }

    private fun validateEmbeddingDimensions(connection: Connection, expectedEmbeddingDimensions: Int) {
        val sql = """
            SELECT atttypmod
            FROM pg_attribute
            WHERE attrelid = 'memories'::regclass
              AND attname = 'embedding'
              AND NOT attisdropped
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw IllegalStateException("memories.embedding column metadata not found")
                }
                val atttypmod = rs.getInt("atttypmod")
                val actualEmbeddingDimensions = atttypmod.coerceAtLeast(0)
                if (actualEmbeddingDimensions != expectedEmbeddingDimensions) {
                    throw IllegalStateException(
                        "Embedding dimension mismatch: db=$actualEmbeddingDimensions configured=$expectedEmbeddingDimensions. " +
                            "Use a matching EMBEDDING_DIMENSIONS or migrate the table."
                    )
                }
            }
        }
    }
}
