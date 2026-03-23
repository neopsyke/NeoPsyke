package ai.neopsyke.agent.actioncontrol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.model.ActionReceipt
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.StagedAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

class SqliteActionControlStore(
    dbPath: String,
) : ActionControlStore {
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
                  action_type TEXT NOT NULL,
                  root_input_id TEXT,
                  created_at_ms INTEGER NOT NULL,
                  updated_at_ms INTEGER NOT NULL,
                  payload_json TEXT NOT NULL
                );
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_staged_actions_status_updated ON staged_actions(status, updated_at_ms DESC);")
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

    override fun saveStagedAction(action: StagedAction): StagedAction {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO staged_actions(id, status, action_type, root_input_id, created_at_ms, updated_at_ms, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  status = excluded.status,
                  action_type = excluded.action_type,
                  root_input_id = excluded.root_input_id,
                  updated_at_ms = excluded.updated_at_ms,
                  payload_json = excluded.payload_json
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, action.id)
                statement.setString(2, action.status.name)
                statement.setString(3, action.actionType.id)
                statement.setString(4, action.rootInputId)
                statement.setLong(5, action.createdAtMs)
                statement.setLong(6, action.updatedAtMs)
                statement.setString(7, mapper.writeValueAsString(action))
                statement.executeUpdate()
            }
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

    override fun saveAuthorization(authorization: CommitAuthorization): CommitAuthorization {
        synchronized(connection) {
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
}
