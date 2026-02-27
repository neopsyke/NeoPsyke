package psyke.metrics

import mu.KotlinLogging
import psyke.llm.ChatCallObserver
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

class SqliteMetricsRuntime(
    apiKey: String,
    egoModel: String,
    superegoModel: String,
) : MetricsRuntime {
    private val dbPath = resolveDbPath()
    private val metricsDir = dbPath.toAbsolutePath().parent ?: Paths.get(System.getProperty("user.dir"))
    private val connection: Connection
    private val runId = UUID.randomUUID().toString()
    private val keyFingerprint = fingerprintKey(apiKey, metricsDir.resolve("metrics.salt"))

    init {
        Files.createDirectories(metricsDir)
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL;")
            statement.execute("PRAGMA synchronous=NORMAL;")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS runs (
                  run_id TEXT PRIMARY KEY,
                  key_fingerprint TEXT NOT NULL,
                  started_at TEXT NOT NULL,
                  ended_at TEXT,
                  ego_model TEXT NOT NULL,
                  superego_model TEXT NOT NULL,
                  total_calls INTEGER NOT NULL DEFAULT 0,
                  prompt_tokens INTEGER NOT NULL DEFAULT 0,
                  completion_tokens INTEGER NOT NULL DEFAULT 0,
                  total_tokens INTEGER NOT NULL DEFAULT 0,
                  denied_actions INTEGER NOT NULL DEFAULT 0,
                  error_count INTEGER NOT NULL DEFAULT 0
                );
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS llm_calls (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id TEXT NOT NULL,
                  key_fingerprint TEXT NOT NULL,
                  ts TEXT NOT NULL,
                  provider TEXT NOT NULL,
                  model TEXT NOT NULL,
                  actor TEXT,
                  call_site TEXT,
                  action_type TEXT,
                  prompt_tokens INTEGER,
                  completion_tokens INTEGER,
                  total_tokens INTEGER,
                  latency_ms INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  error_code TEXT,
                  error_message TEXT
                );
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_llm_calls_run_id ON llm_calls(run_id);")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_llm_calls_key_ts ON llm_calls(key_fingerprint, ts);")
        }
        startRun(egoModel, superegoModel)
    }

    override fun chatCallObserver(provider: String): ChatCallObserver =
        ChatCallObserver { record -> recordCall(provider, record) }

    override fun recordDeniedAction() {
        synchronized(connection) {
            connection.prepareStatement(
                "UPDATE runs SET denied_actions = denied_actions + 1 WHERE run_id = ?"
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun close() {
        try {
            synchronized(connection) {
                connection.prepareStatement(
                    "UPDATE runs SET ended_at = ? WHERE run_id = ?"
                ).use { statement ->
                    statement.setString(1, nowIso())
                    statement.setString(2, runId)
                    statement.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to finalize metrics run." }
        } finally {
            connection.close()
        }
    }

    private fun startRun(egoModel: String, superegoModel: String) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO runs(run_id, key_fingerprint, started_at, ego_model, superego_model)
                VALUES(?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, runId)
                statement.setString(2, keyFingerprint)
                statement.setString(3, nowIso())
                statement.setString(4, egoModel)
                statement.setString(5, superegoModel)
                statement.executeUpdate()
            }
        }
    }

    private fun recordCall(provider: String, record: ChatCallRecord) {
        synchronized(connection) {
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO llm_calls(
                      run_id, key_fingerprint, ts, provider, model, actor, call_site, action_type,
                      prompt_tokens, completion_tokens, total_tokens, latency_ms, status, error_code, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, runId)
                    statement.setString(2, keyFingerprint)
                    statement.setString(3, nowIso())
                    statement.setString(4, provider)
                    statement.setString(5, record.model)
                    statement.setString(6, record.metadata.actor)
                    statement.setString(7, record.metadata.callSite)
                    statement.setString(8, record.metadata.actionType)
                    statement.setObject(9, record.promptTokens)
                    statement.setObject(10, record.completionTokens)
                    statement.setObject(11, record.totalTokens)
                    statement.setLong(12, record.latencyMs)
                    statement.setString(13, record.status.name.lowercase())
                    statement.setString(14, record.errorCode)
                    statement.setString(15, record.errorMessage)
                    statement.executeUpdate()
                }

                val promptTokens = record.promptTokens ?: 0
                val completionTokens = record.completionTokens ?: 0
                val totalTokens = record.totalTokens ?: promptTokens + completionTokens
                val errorDelta = if (record.status == ChatCallStatus.ERROR) 1 else 0

                connection.prepareStatement(
                    """
                    UPDATE runs
                    SET total_calls = total_calls + 1,
                        prompt_tokens = prompt_tokens + ?,
                        completion_tokens = completion_tokens + ?,
                        total_tokens = total_tokens + ?,
                        error_count = error_count + ?
                    WHERE run_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, promptTokens)
                    statement.setInt(2, completionTokens)
                    statement.setInt(3, totalTokens)
                    statement.setInt(4, errorDelta)
                    statement.setString(5, runId)
                    statement.executeUpdate()
                }

                connection.commit()
            } catch (ex: Exception) {
                connection.rollback()
                logger.warn(ex) { "Failed to persist llm call metrics." }
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private companion object {
        private fun resolveDbPath(): Path {
            val raw = System.getenv("PSYKE_METRICS_DB") ?: "~/.psyke/metrics.db"
            return expandUserPath(raw)
        }

        private fun expandUserPath(raw: String): Path {
            if (raw.startsWith("~/")) {
                return Paths.get(System.getProperty("user.home"), raw.removePrefix("~/"))
            }
            if (raw == "~") {
                return Paths.get(System.getProperty("user.home"))
            }
            return Paths.get(raw)
        }

        private fun nowIso(): String = Instant.now().toString()

        private fun fingerprintKey(apiKey: String, saltFile: Path): String {
            val salt = loadOrCreateSalt(saltFile)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(apiKey.toByteArray(StandardCharsets.UTF_8))
            return digest.digest().toHex().take(16)
        }

        private fun loadOrCreateSalt(saltFile: Path): ByteArray {
            Files.createDirectories(saltFile.parent)
            if (Files.exists(saltFile)) {
                return Files.readString(saltFile).trim().hexToBytes()
            }

            val salt = ByteArray(32)
            SecureRandom().nextBytes(salt)
            Files.writeString(saltFile, salt.toHex())
            return salt
        }

        private fun ByteArray.toHex(): String =
            joinToString(separator = "") { b -> "%02x".format(b) }

        private fun String.hexToBytes(): ByteArray {
            val cleaned = trim()
            require(cleaned.length % 2 == 0) { "Invalid hex length for metrics salt." }
            return ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
