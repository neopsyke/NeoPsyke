package ai.neopsyke.admin.approvals

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

interface ApprovalStore : AutoCloseable {
    fun saveRequest(request: ApprovalRequest): ApprovalRequest
    fun updateRequest(request: ApprovalRequest): ApprovalRequest
    fun transitionRequest(
        request: ApprovalRequest,
        expectedStatuses: Set<ApprovalRequestStatus>,
    ): Boolean
    fun request(id: String): ApprovalRequest?
    fun requestByStagedActionId(stagedActionId: String): ApprovalRequest?
    fun activeRequestForSession(sessionId: String): ApprovalRequest?
    fun latestRequestForSession(sessionId: String): ApprovalRequest?
    fun queuedRequestsForSession(sessionId: String): List<ApprovalRequest>
    fun pendingRequests(nowMs: Long): List<ApprovalRequest>
    fun saveAudit(entry: ApprovalAuditEntry): ApprovalAuditEntry
    fun listAudit(requestId: String): List<ApprovalAuditEntry>
    override fun close() {}
}

class SqliteApprovalStore(
    dbPath: String,
) : ApprovalStore {
    private val path: Path = resolveDbPath(dbPath)
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val connection: Connection

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL;")
            statement.execute("PRAGMA synchronous=NORMAL;")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS approval_requests (
                  id TEXT PRIMARY KEY,
                  staged_action_id TEXT NOT NULL UNIQUE,
                  target_session_id TEXT NOT NULL,
                  status TEXT NOT NULL,
                  created_at_ms INTEGER NOT NULL,
                  updated_at_ms INTEGER NOT NULL,
                  payload_json TEXT NOT NULL
                );
                """.trimIndent()
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_approval_requests_target_session ON approval_requests(target_session_id, status, updated_at_ms DESC);"
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS approval_audit_entries (
                  id TEXT PRIMARY KEY,
                  request_id TEXT NOT NULL,
                  created_at_ms INTEGER NOT NULL,
                  payload_json TEXT NOT NULL
                );
                """.trimIndent()
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_approval_audit_request_id ON approval_audit_entries(request_id, created_at_ms ASC);"
            )
        }
    }

    override fun saveRequest(request: ApprovalRequest): ApprovalRequest {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO approval_requests(id, staged_action_id, target_session_id, status, created_at_ms, updated_at_ms, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  staged_action_id = excluded.staged_action_id,
                  target_session_id = excluded.target_session_id,
                  status = excluded.status,
                  updated_at_ms = excluded.updated_at_ms,
                  payload_json = excluded.payload_json
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.id)
                statement.setString(2, request.stagedActionId)
                statement.setString(3, request.target.sessionId)
                statement.setString(4, request.status.name)
                statement.setLong(5, request.createdAtMs)
                statement.setLong(6, request.updatedAtMs)
                statement.setString(7, mapper.writeValueAsString(request))
                statement.executeUpdate()
            }
        }
        return request
    }

    override fun updateRequest(request: ApprovalRequest): ApprovalRequest = saveRequest(request)

    override fun transitionRequest(
        request: ApprovalRequest,
        expectedStatuses: Set<ApprovalRequestStatus>,
    ): Boolean {
        if (expectedStatuses.isEmpty()) return false
        val placeholders = expectedStatuses.joinToString(separator = ",") { "?" }
        synchronized(connection) {
            connection.prepareStatement(
                """
                UPDATE approval_requests
                SET target_session_id = ?, status = ?, updated_at_ms = ?, payload_json = ?
                WHERE id = ?
                  AND status IN ($placeholders)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.target.sessionId)
                statement.setString(2, request.status.name)
                statement.setLong(3, request.updatedAtMs)
                statement.setString(4, mapper.writeValueAsString(request))
                statement.setString(5, request.id)
                expectedStatuses.forEachIndexed { index, status ->
                    statement.setString(6 + index, status.name)
                }
                return statement.executeUpdate() == 1
            }
        }
    }

    override fun request(id: String): ApprovalRequest? =
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT payload_json FROM approval_requests WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    mapper.readValue(rs.getString("payload_json"))
                }
            }
        }

    override fun requestByStagedActionId(stagedActionId: String): ApprovalRequest? =
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT payload_json FROM approval_requests WHERE staged_action_id = ?"
            ).use { statement ->
                statement.setString(1, stagedActionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    mapper.readValue(rs.getString("payload_json"))
                }
            }
        }

    override fun activeRequestForSession(sessionId: String): ApprovalRequest? =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM approval_requests
                WHERE target_session_id = ?
                  AND status = 'PENDING'
                ORDER BY updated_at_ms DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    mapper.readValue(rs.getString("payload_json"))
                }
            }
        }

    override fun latestRequestForSession(sessionId: String): ApprovalRequest? =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM approval_requests
                WHERE target_session_id = ?
                ORDER BY updated_at_ms DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    mapper.readValue(rs.getString("payload_json"))
                }
            }
        }

    override fun queuedRequestsForSession(sessionId: String): List<ApprovalRequest> =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM approval_requests
                WHERE target_session_id = ?
                  AND status = 'QUEUED'
                ORDER BY created_at_ms ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapper.readValue(rs.getString("payload_json")))
                        }
                    }
                }
            }
        }

    override fun pendingRequests(nowMs: Long): List<ApprovalRequest> =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM approval_requests
                WHERE status = 'PENDING'
                ORDER BY updated_at_ms ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val request: ApprovalRequest = mapper.readValue(rs.getString("payload_json"))
                            if (request.expiresAtMs <= nowMs) {
                                add(request)
                            }
                        }
                    }
                }
            }
        }

    override fun saveAudit(entry: ApprovalAuditEntry): ApprovalAuditEntry {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO approval_audit_entries(id, request_id, created_at_ms, payload_json)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, entry.id)
                statement.setString(2, entry.requestId)
                statement.setLong(3, entry.createdAtMs)
                statement.setString(4, mapper.writeValueAsString(entry))
                statement.executeUpdate()
            }
        }
        return entry
    }

    override fun listAudit(requestId: String): List<ApprovalAuditEntry> =
        synchronized(connection) {
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM approval_audit_entries
                WHERE request_id = ?
                ORDER BY created_at_ms ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, requestId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapper.readValue(rs.getString("payload_json")))
                        }
                    }
                }
            }
        }

    override fun close() {
        connection.close()
    }

    companion object {
        private fun resolveDbPath(raw: String): Path {
            val candidate = Paths.get(raw)
            return if (candidate.isAbsolute) candidate else Paths.get("").resolve(candidate).normalize()
        }
    }
}
