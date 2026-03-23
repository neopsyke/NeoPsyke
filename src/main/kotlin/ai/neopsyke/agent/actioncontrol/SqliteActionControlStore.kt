package ai.neopsyke.agent.actioncontrol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

private val logger = KotlinLogging.logger {}

class SqliteActionControlStore(
    dbPath: String,
) : ActionControlStore {
    private data class StoredStagedRow(
        val id: String,
        val status: String,
        val commitMode: String,
        val rootInputId: String?,
        val threadSequence: Long?,
        val executionKey: String?,
        val createdAtMs: Long,
        val payloadJson: String,
    )

    private val path: Path = resolveDbPath(dbPath)
    private val mapper = jacksonObjectMapper()
    private val connection: Connection

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL;")
            statement.execute("PRAGMA synchronous=NORMAL;")
            statement.execute("PRAGMA busy_timeout=1500;")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS staged_actions (
                  id TEXT PRIMARY KEY,
                  status TEXT NOT NULL,
                  commit_mode TEXT NOT NULL DEFAULT 'NOT_APPLICABLE',
                  action_type TEXT NOT NULL,
                  root_input_id TEXT,
                  thread_sequence INTEGER,
                  execution_key TEXT,
                  created_at_ms INTEGER NOT NULL,
                  updated_at_ms INTEGER NOT NULL,
                  payload_json TEXT NOT NULL
                );
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_staged_actions_status_updated ON staged_actions(status, updated_at_ms DESC);")
            ensureColumn("staged_actions", "commit_mode", "ALTER TABLE staged_actions ADD COLUMN commit_mode TEXT NOT NULL DEFAULT 'NOT_APPLICABLE'")
            ensureColumn("staged_actions", "thread_sequence", "ALTER TABLE staged_actions ADD COLUMN thread_sequence INTEGER")
            ensureColumn("staged_actions", "execution_key", "ALTER TABLE staged_actions ADD COLUMN execution_key TEXT")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_staged_actions_ready_commit ON staged_actions(status, commit_mode, created_at_ms ASC);")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_staged_actions_thread_sequence ON staged_actions(root_input_id, thread_sequence, status);")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_staged_actions_execution_key ON staged_actions(execution_key, status, created_at_ms ASC);")
            statement.execute(
                """
                UPDATE staged_actions
                SET commit_mode = 'NOT_APPLICABLE'
                WHERE commit_mode IS NULL OR commit_mode = '';
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS commit_authorizations (
                  id TEXT PRIMARY KEY,
                  staged_action_id TEXT NOT NULL,
                  granted_at_ms INTEGER NOT NULL,
                  payload_json TEXT NOT NULL
                );
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_commit_authorizations_staged_action_id ON commit_authorizations(staged_action_id);")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS action_receipts (
                  id TEXT PRIMARY KEY,
                  staged_action_id TEXT NOT NULL,
                  created_at_ms INTEGER NOT NULL,
                  payload_json TEXT NOT NULL
                );
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_action_receipts_staged_action_id ON action_receipts(staged_action_id);")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_action_receipts_created_at_ms ON action_receipts(created_at_ms DESC);")
        }
    }

    override fun nextThreadSequence(rootInputId: String): Long =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT COALESCE(MAX(thread_sequence), 0) + 1 AS next_thread_sequence
                FROM staged_actions
                WHERE root_input_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, rootInputId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return 1L
                    rs.getLong("next_thread_sequence").coerceAtLeast(1L)
                }
            }
        }

    override fun saveStagedAction(action: StagedAction): StagedAction {
        synchronized(connection) {
            saveStagedActionInternal(action)
        }
        return action
    }

    override fun updateStagedAction(action: StagedAction): StagedAction = saveStagedAction(action)

    override fun stagedAction(id: String): StagedAction? =
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT payload_json FROM staged_actions WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    mapper.readValue<StagedAction>(rs.getString("payload_json"))
                }
            }
        }

    override fun listStagedActions(limit: Int): List<StagedAction> =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM staged_actions
                ORDER BY updated_at_ms DESC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapper.readValue<StagedAction>(rs.getString("payload_json")))
                        }
                    }
                }
            }
        }

    override fun listRunnableReadyAutonomousActions(limit: Int): List<StagedAction> =
        synchronized(connection) {
            connection.prepareStatement(
                """
                WITH runnable_base AS (
                  SELECT sa.payload_json,
                         sa.execution_key,
                         sa.created_at_ms,
                         sa.thread_sequence,
                         sa.id
                  FROM staged_actions sa
                  WHERE sa.status = 'READY'
                    AND sa.commit_mode = 'POLICY_AUTONOMOUS'
                    AND NOT EXISTS (
                      SELECT 1
                      FROM staged_actions blocker
                      WHERE sa.root_input_id IS NOT NULL
                        AND sa.thread_sequence IS NOT NULL
                        AND blocker.root_input_id = sa.root_input_id
                        AND blocker.thread_sequence IS NOT NULL
                        AND blocker.thread_sequence < sa.thread_sequence
                        AND blocker.status IN ('READY', 'WAITING_AUTHORIZATION', 'AUTHORIZED', 'EXECUTING', 'WAITING_EXTERNAL')
                    )
                    AND NOT EXISTS (
                      SELECT 1
                      FROM staged_actions active_key
                      WHERE sa.execution_key IS NOT NULL
                        AND active_key.execution_key = sa.execution_key
                        AND active_key.id != sa.id
                        AND active_key.status IN ('EXECUTING', 'WAITING_EXTERNAL')
                    )
                ),
                ranked AS (
                  SELECT payload_json,
                         created_at_ms,
                         thread_sequence,
                         id,
                         ROW_NUMBER() OVER (
                           PARTITION BY COALESCE(execution_key, id)
                           ORDER BY created_at_ms ASC, COALESCE(thread_sequence, ${NULL_SEQUENCE_SORT_VALUE}) ASC, id ASC
                         ) AS execution_rank
                  FROM runnable_base
                )
                SELECT payload_json
                FROM ranked
                WHERE execution_rank = 1
                ORDER BY created_at_ms ASC, COALESCE(thread_sequence, ${NULL_SEQUENCE_SORT_VALUE}) ASC, id ASC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapper.readValue<StagedAction>(rs.getString("payload_json")))
                        }
                    }
                }
            }
        }

    override fun tryClaimAutonomousReadyAction(
        stagedActionId: String,
        authorization: CommitAuthorization,
        updatedAtMs: Long,
    ): StagedAction? =
        synchronized(connection) {
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val row = stagedRowLocked(stagedActionId)
                if (row == null || row.status != StagedActionStatus.READY.name || row.commitMode != CommitMode.POLICY_AUTONOMOUS.name) {
                    connection.rollback()
                    return null
                }
                if (!isRunnableAutonomousRowLocked(row)) {
                    connection.rollback()
                    return null
                }
                saveAuthorizationInternal(authorization)
                val staged = mapper.readValue<StagedAction>(row.payloadJson).copy(
                    status = StagedActionStatus.EXECUTING,
                    authorizationId = authorization.id,
                    updatedAtMs = updatedAtMs,
                )
                saveStagedActionInternal(staged)
                connection.commit()
                staged
            } catch (ex: Exception) {
                runCatching { connection.rollback() }
                throw ex
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }

    override fun saveAuthorization(authorization: CommitAuthorization): CommitAuthorization {
        synchronized(connection) {
            saveAuthorizationInternal(authorization)
        }
        return authorization
    }

    override fun authorization(id: String): CommitAuthorization? =
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT payload_json FROM commit_authorizations WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    mapper.readValue<CommitAuthorization>(rs.getString("payload_json"))
                }
            }
        }

    override fun saveReceipt(receipt: ActionReceipt): ActionReceipt {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO action_receipts(id, staged_action_id, created_at_ms, payload_json)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  staged_action_id = excluded.staged_action_id,
                  created_at_ms = excluded.created_at_ms,
                  payload_json = excluded.payload_json
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, receipt.id)
                statement.setString(2, receipt.stagedActionId)
                statement.setLong(3, receipt.createdAtMs)
                statement.setString(4, mapper.writeValueAsString(receipt))
                statement.executeUpdate()
            }
        }
        return receipt
    }

    override fun receipt(id: String): ActionReceipt? =
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT payload_json FROM action_receipts WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    mapper.readValue<ActionReceipt>(rs.getString("payload_json"))
                }
            }
        }

    override fun listReceipts(limit: Int): List<ActionReceipt> =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM action_receipts
                ORDER BY created_at_ms DESC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapper.readValue<ActionReceipt>(rs.getString("payload_json")))
                        }
                    }
                }
            }
        }

    override fun close() {
        try {
            synchronized(connection) {
                connection.close()
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to close action-control database." }
        }
    }

    companion object {
        private const val DEFAULT_DB_RELATIVE_PATH: String = ".neopsyke/action-control.db"
        private const val NULL_SEQUENCE_SORT_VALUE: Long = 9_223_372_036_854_775_000L

        fun resolveDbPath(configured: String): Path {
            val trimmed = configured.trim()
            if (trimmed.isNotBlank()) {
                return expandUserPath(trimmed)
            }
            val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            return cwd.resolve(DEFAULT_DB_RELATIVE_PATH).normalize().toAbsolutePath()
        }

        private fun expandUserPath(raw: String): Path {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("~/")) {
                Paths.get(System.getProperty("user.home")).resolve(trimmed.removePrefix("~/")).normalize()
            } else {
                Paths.get(trimmed).toAbsolutePath().normalize()
            }
        }
    }

    private fun saveStagedActionInternal(action: StagedAction) {
        connection.prepareStatement(
            """
            INSERT INTO staged_actions(id, status, commit_mode, action_type, root_input_id, thread_sequence, execution_key, created_at_ms, updated_at_ms, payload_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              status = excluded.status,
              commit_mode = excluded.commit_mode,
              action_type = excluded.action_type,
              root_input_id = excluded.root_input_id,
              thread_sequence = excluded.thread_sequence,
              execution_key = excluded.execution_key,
              updated_at_ms = excluded.updated_at_ms,
              payload_json = excluded.payload_json
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, action.id)
            statement.setString(2, action.status.name)
            statement.setString(3, action.commitMode.name)
            statement.setString(4, action.actionType.id)
            statement.setString(5, action.rootInputId)
            if (action.threadSequence != null) {
                statement.setLong(6, action.threadSequence)
            } else {
                statement.setNull(6, java.sql.Types.BIGINT)
            }
            statement.setString(7, action.executionKey)
            statement.setLong(8, action.createdAtMs)
            statement.setLong(9, action.updatedAtMs)
            statement.setString(10, mapper.writeValueAsString(action))
            statement.executeUpdate()
        }
    }

    private fun saveAuthorizationInternal(authorization: CommitAuthorization) {
        connection.prepareStatement(
            """
            INSERT INTO commit_authorizations(id, staged_action_id, granted_at_ms, payload_json)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              staged_action_id = excluded.staged_action_id,
              granted_at_ms = excluded.granted_at_ms,
              payload_json = excluded.payload_json
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, authorization.id)
            statement.setString(2, authorization.stagedActionId)
            statement.setLong(3, authorization.grantedAtMs)
            statement.setString(4, mapper.writeValueAsString(authorization))
            statement.executeUpdate()
        }
    }

    private fun stagedRowLocked(id: String): StoredStagedRow? =
        connection.prepareStatement(
            """
            SELECT id, status, commit_mode, root_input_id, thread_sequence, execution_key, created_at_ms, payload_json
            FROM staged_actions
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                rs.toStoredStagedRow()
            }
        }

    private fun isRunnableAutonomousRowLocked(row: StoredStagedRow): Boolean {
        if (row.rootInputId != null && row.threadSequence != null) {
            connection.prepareStatement(
                """
                SELECT 1
                FROM staged_actions blocker
                WHERE blocker.root_input_id = ?
                  AND blocker.thread_sequence IS NOT NULL
                  AND blocker.thread_sequence < ?
                  AND blocker.status IN ('READY', 'WAITING_AUTHORIZATION', 'AUTHORIZED', 'EXECUTING', 'WAITING_EXTERNAL')
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, row.rootInputId)
                statement.setLong(2, row.threadSequence)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return false
                    }
                }
            }
        }
        if (!row.executionKey.isNullOrBlank()) {
            connection.prepareStatement(
                """
                SELECT 1
                FROM staged_actions active_key
                WHERE active_key.execution_key = ?
                  AND active_key.id != ?
                  AND active_key.status IN ('EXECUTING', 'WAITING_EXTERNAL')
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, row.executionKey)
                statement.setString(2, row.id)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return false
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT 1
                FROM staged_actions earlier_ready
                WHERE earlier_ready.execution_key = ?
                  AND earlier_ready.id != ?
                  AND earlier_ready.status = 'READY'
                  AND earlier_ready.commit_mode = 'POLICY_AUTONOMOUS'
                  AND (
                    earlier_ready.created_at_ms < ?
                    OR (
                      earlier_ready.created_at_ms = ?
                      AND COALESCE(earlier_ready.thread_sequence, ${NULL_SEQUENCE_SORT_VALUE}) < COALESCE(?, ${NULL_SEQUENCE_SORT_VALUE})
                    )
                    OR (
                      earlier_ready.created_at_ms = ?
                      AND COALESCE(earlier_ready.thread_sequence, ${NULL_SEQUENCE_SORT_VALUE}) = COALESCE(?, ${NULL_SEQUENCE_SORT_VALUE})
                      AND earlier_ready.id < ?
                    )
                  )
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, row.executionKey)
                statement.setString(2, row.id)
                statement.setLong(3, row.createdAtMs)
                statement.setLong(4, row.createdAtMs)
                if (row.threadSequence != null) {
                    statement.setLong(5, row.threadSequence)
                } else {
                    statement.setNull(5, java.sql.Types.BIGINT)
                }
                statement.setLong(6, row.createdAtMs)
                if (row.threadSequence != null) {
                    statement.setLong(7, row.threadSequence)
                } else {
                    statement.setNull(7, java.sql.Types.BIGINT)
                }
                statement.setString(8, row.id)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun ensureColumn(table: String, column: String, ddl: String) {
        val exists = connection.prepareStatement("PRAGMA table_info($table)").use { statement ->
            statement.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) rs.getString("name") else null
                }.any { it == column }
            }
        }
        if (!exists) {
            connection.createStatement().use { statement ->
                statement.execute(ddl)
            }
        }
    }

    private fun ResultSet.toStoredStagedRow(): StoredStagedRow =
        StoredStagedRow(
            id = getString("id"),
            status = getString("status"),
            commitMode = getString("commit_mode"),
            rootInputId = getString("root_input_id"),
            threadSequence = getLong("thread_sequence").takeUnless { wasNull() },
            executionKey = getString("execution_key"),
            createdAtMs = getLong("created_at_ms"),
            payloadJson = getString("payload_json"),
        )
}
