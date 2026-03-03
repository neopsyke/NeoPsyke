package psyke.mcp.memory.db

import mu.KotlinLogging
import java.sql.Connection

private val logger = KotlinLogging.logger {}

object SchemaInitializer {

    private const val SCHEMA_RESOURCE = "/schema/V001__init.sql"

    fun initialize(connection: Connection) {
        val sql = SchemaInitializer::class.java.getResourceAsStream(SCHEMA_RESOURCE)
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Schema resource not found: $SCHEMA_RESOURCE")

        connection.createStatement().use { stmt ->
            stmt.execute(sql)
        }
        logger.info { "Database schema initialized." }
    }
}
