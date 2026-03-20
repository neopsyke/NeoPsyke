package ai.neopsyke.agent.memory.episodic

import mu.KotlinLogging
import ai.neopsyke.agent.config.LogbookConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * SQLite + FTS5 implementation of [Logbook].
 * Follows the same patterns as [ai.neopsyke.metrics.SqliteMetricsRuntime]:
 * WAL mode, synchronized access, schema init on construction.
 */
class SqliteLogbook(
    private val config: LogbookConfig,
) : Logbook {
    private val dbPath: Path = resolveDbPath(config.dbPath)
    private val connection: Connection

    init {
        Files.createDirectories(dbPath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA synchronous=NORMAL;")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS entries (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  ts          TEXT    NOT NULL,
                  ts_epoch_ms INTEGER NOT NULL,
                  event_type  TEXT    NOT NULL,
                  summary     TEXT    NOT NULL,
                  keywords    TEXT    NOT NULL DEFAULT '',
                  action_type TEXT,
                  run_id      TEXT,
                  metadata    TEXT
                )
                """.trimIndent()
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entries_ts_epoch ON entries(ts_epoch_ms);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entries_event_type ON entries(event_type);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entries_run_id ON entries(run_id);")

            // --- Schema migration: add session_id and interlocutor_id columns ---
            val existingColumns = mutableSetOf<String>()
            stmt.executeQuery("PRAGMA table_info(entries)").use { rs ->
                while (rs.next()) {
                    existingColumns.add(rs.getString("name"))
                }
            }
            if ("session_id" !in existingColumns) {
                stmt.execute("ALTER TABLE entries ADD COLUMN session_id TEXT;")
            }
            if ("interlocutor_id" !in existingColumns) {
                stmt.execute("ALTER TABLE entries ADD COLUMN interlocutor_id TEXT;")
            }
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entries_session_id ON entries(session_id);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_entries_interlocutor_id ON entries(interlocutor_id);")

            stmt.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS entries_fts USING fts5(
                  summary, keywords, content=entries, content_rowid=id
                )
                """.trimIndent()
            )

            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS entries_ai AFTER INSERT ON entries BEGIN
                  INSERT INTO entries_fts(rowid, summary, keywords) VALUES (new.id, new.summary, new.keywords);
                END
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS entries_ad AFTER DELETE ON entries BEGIN
                  INSERT INTO entries_fts(entries_fts, rowid, summary, keywords) VALUES('delete', old.id, old.summary, old.keywords);
                END
                """.trimIndent()
            )
        }
    }

    override fun record(entry: LogbookEntry): Long {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO entries(ts, ts_epoch_ms, event_type, summary, keywords, action_type, run_id, metadata, session_id, interlocutor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, entry.ts.toString())
                stmt.setLong(2, entry.ts.toEpochMilli())
                stmt.setString(3, entry.eventType.dbValue())
                stmt.setString(4, entry.summary)
                stmt.setString(5, entry.keywords.joinToString(" "))
                stmt.setString(6, entry.actionType)
                stmt.setString(7, entry.runId)
                stmt.setString(8, entry.metadata?.toString())
                stmt.setString(9, entry.sessionId)
                stmt.setString(10, entry.interlocutorId)
                stmt.executeUpdate()
            }
            connection.prepareStatement("SELECT last_insert_rowid()").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun query(query: LogbookQuery): LogbookRecall {
        synchronized(connection) {
            val conditions = mutableListOf<String>()
            val params = mutableListOf<Any>()

            if (query.startTime != null) {
                conditions.add("e.ts_epoch_ms >= ?")
                params.add(query.startTime.toEpochMilli())
            }
            if (query.endTime != null) {
                conditions.add("e.ts_epoch_ms <= ?")
                params.add(query.endTime.toEpochMilli())
            }
            if (!query.eventTypes.isNullOrEmpty()) {
                val placeholders = query.eventTypes.joinToString(",") { "?" }
                conditions.add("e.event_type IN ($placeholders)")
                params.addAll(query.eventTypes.map { it.dbValue() })
            }
            if (!query.actionTypes.isNullOrEmpty()) {
                val placeholders = query.actionTypes.joinToString(",") { "?" }
                conditions.add("e.action_type IN ($placeholders)")
                params.addAll(query.actionTypes)
            }
            if (query.sessionId != null) {
                conditions.add("e.session_id = ?")
                params.add(query.sessionId)
            }
            if (query.interlocutorId != null) {
                conditions.add("e.interlocutor_id = ?")
                params.add(query.interlocutorId)
            }

            val useFts = !query.keywordSearch.isNullOrBlank()
            val fromClause = if (useFts) {
                conditions.add("entries_fts MATCH ?")
                params.add(query.keywordSearch!!)
                "entries e JOIN entries_fts f ON e.id = f.rowid"
            } else {
                "entries e"
            }

            val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
            val limit = query.maxResults.coerceIn(1, MAX_QUERY_RESULTS)

            val countSql = "SELECT COUNT(*) FROM $fromClause $whereClause"
            val totalMatched = connection.prepareStatement(countSql).use { stmt ->
                params.forEachIndexed { i, p -> bindParam(stmt, i + 1, p) }
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

            val selectSql = """
                SELECT e.id, e.ts, e.ts_epoch_ms, e.event_type, e.summary, e.keywords,
                       e.action_type, e.run_id, e.metadata, e.session_id, e.interlocutor_id
                FROM $fromClause
                $whereClause
                ORDER BY e.ts_epoch_ms DESC
                LIMIT ?
            """.trimIndent()

            val entries = connection.prepareStatement(selectSql).use { stmt ->
                params.forEachIndexed { i, p -> bindParam(stmt, i + 1, p) }
                stmt.setInt(params.size + 1, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LogbookEntry(
                                    id = rs.getLong("id"),
                                    ts = Instant.parse(rs.getString("ts")),
                                    eventType = EpisodicEventType.fromDb(rs.getString("event_type"))
                                        ?: EpisodicEventType.INPUT_RECEIVED,
                                    summary = rs.getString("summary"),
                                    keywords = rs.getString("keywords")
                                        .split(" ")
                                        .filter { it.isNotBlank() },
                                    actionType = rs.getString("action_type"),
                                    runId = rs.getString("run_id"),
                                    metadata = null,
                                    sessionId = rs.getString("session_id"),
                                    interlocutorId = rs.getString("interlocutor_id"),
                                )
                            )
                        }
                    }
                }
            }

            return LogbookRecall(
                entries = entries,
                totalMatched = totalMatched,
                truncated = totalMatched > limit,
            )
        }
    }

    override fun pruneOlderThan(retentionDays: Int): Int {
        val cutoffMs = Instant.now().toEpochMilli() - (retentionDays.toLong() * MILLIS_PER_DAY)
        synchronized(connection) {
            return connection.prepareStatement(
                "DELETE FROM entries WHERE ts_epoch_ms < ?"
            ).use { stmt ->
                stmt.setLong(1, cutoffMs)
                stmt.executeUpdate()
            }
        }
    }

    override fun clearAll(): Int {
        synchronized(connection) {
            return connection.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM entries")
            }
        }
    }

    override fun close() {
        try {
            synchronized(connection) {
                connection.close()
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to close logbook database." }
        }
    }

    private fun bindParam(stmt: java.sql.PreparedStatement, index: Int, value: Any) {
        when (value) {
            is Long -> stmt.setLong(index, value)
            is Int -> stmt.setInt(index, value)
            is String -> stmt.setString(index, value)
            else -> stmt.setString(index, value.toString())
        }
    }

    companion object {
        const val MAX_QUERY_RESULTS: Int = 100
        private const val MILLIS_PER_DAY: Long = 86_400_000L
        private const val DEFAULT_DB_RELATIVE_PATH: String = ".neopsyke/logbook.db"

        fun resolveDbPath(configured: String): Path {
            val trimmed = configured.trim()
            if (trimmed.isNotBlank()) {
                return expandUserPath(trimmed)
            }
            val fromProperty = System.getProperty("neopsyke.logbook.db")
            if (!fromProperty.isNullOrBlank()) {
                return expandUserPath(fromProperty)
            }
            val fromEnv = System.getenv("NEOPSYKE_LOGBOOK_DB_PATH")
            if (!fromEnv.isNullOrBlank()) {
                return expandUserPath(fromEnv)
            }
            val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            return cwd.resolve(DEFAULT_DB_RELATIVE_PATH).normalize().toAbsolutePath()
        }

        private fun expandUserPath(raw: String): Path {
            val trimmed = raw.trim()
            if (trimmed.startsWith("~/")) {
                return Paths.get(System.getProperty("user.home"), trimmed.removePrefix("~/"))
                    .normalize().toAbsolutePath()
            }
            if (trimmed == "~") {
                return Paths.get(System.getProperty("user.home")).normalize().toAbsolutePath()
            }
            val candidate = Paths.get(trimmed)
            return if (candidate.isAbsolute) {
                candidate.normalize()
            } else {
                Paths.get(System.getProperty("user.dir")).resolve(candidate).normalize().toAbsolutePath()
            }
        }
    }
}
