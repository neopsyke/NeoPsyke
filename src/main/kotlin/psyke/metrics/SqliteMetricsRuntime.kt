package psyke.metrics

import mu.KotlinLogging
import psyke.config.RuntimeDefaultsConfigLoader
import psyke.llm.ChatCallObserver
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import psyke.llm.PersistentMetricsChatCallObserver
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
    private val provider: String,
    apiKey: String,
    egoModel: String,
    superegoModel: String,
) : MetricsRuntime {
    private val dbPath = resolveDbPath()
    private val metricsDir = dbPath.toAbsolutePath().parent ?: Paths.get(System.getProperty("user.dir"))
    private val connection: Connection
    private val runId = UUID.randomUUID().toString()
    private val keyFingerprint = fingerprintKey(apiKey, metricsDir.resolve("metrics.salt"))
    private val responseLatenciesMs = mutableListOf<Long>()

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
                  provider TEXT NOT NULL,
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
                  error_count INTEGER NOT NULL DEFAULT 0,
                  planner_noop_count INTEGER NOT NULL DEFAULT 0,
                  planner_output_repaired_count INTEGER NOT NULL DEFAULT 0,
                  queue_saturation_events INTEGER NOT NULL DEFAULT 0,
                  dropped_events INTEGER NOT NULL DEFAULT 0,
                  memory_recall_attempts INTEGER NOT NULL DEFAULT 0,
                  memory_recall_hits INTEGER NOT NULL DEFAULT 0,
                  memory_recall_failures INTEGER NOT NULL DEFAULT 0,
                  memory_recall_truncated INTEGER NOT NULL DEFAULT 0,
                  memory_recall_latency_ms_total INTEGER NOT NULL DEFAULT 0,
                  memory_recall_chars_total INTEGER NOT NULL DEFAULT 0,
                  long_term_memory_recall_skipped INTEGER NOT NULL DEFAULT 0,
                  memory_consolidation_assessments INTEGER NOT NULL DEFAULT 0,
                  memory_consolidation_save_recommended INTEGER NOT NULL DEFAULT 0,
                  long_term_memory_assessment_parse_failures INTEGER NOT NULL DEFAULT 0,
                  memory_imprint_attempts INTEGER NOT NULL DEFAULT 0,
                  memory_imprint_saved INTEGER NOT NULL DEFAULT 0,
                  memory_imprint_failures INTEGER NOT NULL DEFAULT 0,
                  memory_imprint_latency_ms_total INTEGER NOT NULL DEFAULT 0,
                  memory_imprint_chars_total INTEGER NOT NULL DEFAULT 0,
                  response_latency_count INTEGER NOT NULL DEFAULT 0,
                  response_latency_sum_ms INTEGER NOT NULL DEFAULT 0,
                  response_latency_p50_ms REAL
                );
                """.trimIndent()
            )
            addRunsColumnIfMissing(statement, "provider", "TEXT NOT NULL DEFAULT 'unknown'")
            addRunsColumnIfMissing(statement, "planner_noop_count", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "planner_output_repaired_count", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "queue_saturation_events", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "dropped_events", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_recall_attempts", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_recall_hits", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_recall_failures", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_recall_truncated", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_recall_latency_ms_total", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_recall_chars_total", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "long_term_memory_recall_skipped", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_consolidation_assessments", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_consolidation_save_recommended", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "long_term_memory_assessment_parse_failures", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_imprint_attempts", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_imprint_saved", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_imprint_failures", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_imprint_latency_ms_total", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "memory_imprint_chars_total", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "response_latency_count", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "response_latency_sum_ms", "INTEGER NOT NULL DEFAULT 0")
            addRunsColumnIfMissing(statement, "response_latency_p50_ms", "REAL")
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
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_scope ON runs(provider, key_fingerprint, started_at);")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_llm_calls_scope ON llm_calls(provider, key_fingerprint, ts);")
        }
        startRun(egoModel, superegoModel)
    }

    override fun chatCallObserver(provider: String): ChatCallObserver =
        SqlitePersistingChatCallObserver { record -> recordCall(provider, record) }

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

    override fun recordPlannerNoop() {
        synchronized(connection) {
            connection.prepareStatement(
                "UPDATE runs SET planner_noop_count = planner_noop_count + 1 WHERE run_id = ?"
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordPlannerOutputRepaired() {
        synchronized(connection) {
            connection.prepareStatement(
                "UPDATE runs SET planner_output_repaired_count = planner_output_repaired_count + 1 WHERE run_id = ?"
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordDroppedEvents(count: Long) {
        if (count <= 0) {
            return
        }
        synchronized(connection) {
            connection.prepareStatement(
                "UPDATE runs SET dropped_events = dropped_events + ? WHERE run_id = ?"
            ).use { statement ->
                statement.setLong(1, count)
                statement.setString(2, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordQueueSaturation(queueType: String) {
        synchronized(connection) {
            connection.prepareStatement(
                "UPDATE runs SET queue_saturation_events = queue_saturation_events + 1 WHERE run_id = ?"
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                UPDATE runs
                SET memory_recall_attempts = memory_recall_attempts + 1,
                    memory_recall_hits = memory_recall_hits + ?,
                    memory_recall_truncated = memory_recall_truncated + ?,
                    memory_recall_latency_ms_total = memory_recall_latency_ms_total + ?,
                    memory_recall_chars_total = memory_recall_chars_total + ?
                WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, hitCount.coerceAtLeast(0).toLong())
                statement.setLong(2, if (truncated) 1 else 0)
                statement.setLong(3, latencyMs.coerceAtLeast(0))
                statement.setLong(4, recallChars.coerceAtLeast(0).toLong())
                statement.setString(5, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordMemoryRecallFailure(latencyMs: Long) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                UPDATE runs
                SET memory_recall_attempts = memory_recall_attempts + 1,
                    memory_recall_failures = memory_recall_failures + 1,
                    memory_recall_latency_ms_total = memory_recall_latency_ms_total + ?
                WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, latencyMs.coerceAtLeast(0))
                statement.setString(2, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordLongTermMemoryRecallSkipped() {
        synchronized(connection) {
            connection.prepareStatement(
                "UPDATE runs SET long_term_memory_recall_skipped = long_term_memory_recall_skipped + 1 WHERE run_id = ?"
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordLongTermMemoryAssessment(saveRecommended: Boolean) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                UPDATE runs
                SET memory_consolidation_assessments = memory_consolidation_assessments + 1,
                    memory_consolidation_save_recommended = memory_consolidation_save_recommended + ?
                WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, if (saveRecommended) 1 else 0)
                statement.setString(2, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordLongTermMemoryAssessmentParseFailure() {
        synchronized(connection) {
            connection.prepareStatement(
                """
                UPDATE runs
                SET long_term_memory_assessment_parse_failures = long_term_memory_assessment_parse_failures + 1
                WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                UPDATE runs
                SET memory_imprint_attempts = memory_imprint_attempts + 1,
                    memory_imprint_saved = memory_imprint_saved + ?,
                    memory_imprint_failures = memory_imprint_failures + ?,
                    memory_imprint_latency_ms_total = memory_imprint_latency_ms_total + ?,
                    memory_imprint_chars_total = memory_imprint_chars_total + ?
                WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, if (saved) 1 else 0)
                statement.setLong(2, if (saved) 0 else 1)
                statement.setLong(3, latencyMs.coerceAtLeast(0))
                statement.setLong(4, summaryChars.coerceAtLeast(0).toLong())
                statement.setString(5, runId)
                statement.executeUpdate()
            }
        }
    }

    override fun recordEndToEndResponseLatency(latencyMs: Long) {
        val normalizedLatency = latencyMs.coerceAtLeast(0L)
        synchronized(connection) {
            responseLatenciesMs.add(normalizedLatency)
            val p50 = median(responseLatenciesMs)
            connection.prepareStatement(
                """
                UPDATE runs
                SET response_latency_count = response_latency_count + 1,
                    response_latency_sum_ms = response_latency_sum_ms + ?,
                    response_latency_p50_ms = ?
                WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, normalizedLatency)
                statement.setDouble(2, p50)
                statement.setString(3, runId)
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

    override fun snapshot(): MetricsSnapshot {
        synchronized(connection) {
            val runTotals = connection.prepareStatement(
                """
                SELECT total_calls,
                       prompt_tokens,
                       completion_tokens,
                       total_tokens,
                       denied_actions,
                       error_count,
                       planner_noop_count,
                       planner_output_repaired_count,
                       queue_saturation_events,
                       dropped_events,
                       memory_recall_attempts,
                       memory_recall_hits,
                       memory_recall_failures,
                       memory_recall_truncated,
                       memory_recall_latency_ms_total,
                       memory_recall_chars_total,
                       long_term_memory_recall_skipped,
                       memory_consolidation_assessments,
                       memory_consolidation_save_recommended,
                       long_term_memory_assessment_parse_failures,
                       memory_imprint_attempts,
                       memory_imprint_saved,
                       memory_imprint_failures,
                       memory_imprint_latency_ms_total,
                       memory_imprint_chars_total,
                       response_latency_count,
                       response_latency_sum_ms,
                       response_latency_p50_ms
                FROM runs WHERE run_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        MetricsTotals(0, 0, 0, 0, 0, 0)
                    } else {
                        MetricsTotals(
                            calls = rs.getLong("total_calls"),
                            promptTokens = rs.getLong("prompt_tokens"),
                            completionTokens = rs.getLong("completion_tokens"),
                            totalTokens = rs.getLong("total_tokens"),
                            deniedActions = rs.getLong("denied_actions"),
                            errorCount = rs.getLong("error_count"),
                            plannerNoopCount = rs.getLong("planner_noop_count"),
                            plannerOutputRepairedCount = rs.getLong("planner_output_repaired_count"),
                            queueSaturationEvents = rs.getLong("queue_saturation_events"),
                            droppedEvents = rs.getLong("dropped_events"),
                            memoryRecallAttempts = rs.getLong("memory_recall_attempts"),
                            memoryRecallHits = rs.getLong("memory_recall_hits"),
                            memoryRecallFailures = rs.getLong("memory_recall_failures"),
                            memoryRecallTruncated = rs.getLong("memory_recall_truncated"),
                            memoryRecallLatencyMsTotal = rs.getLong("memory_recall_latency_ms_total"),
                            memoryRecallCharsTotal = rs.getLong("memory_recall_chars_total"),
                            longTermMemoryRecallSkipped = rs.getLong("long_term_memory_recall_skipped"),
                            memoryConsolidationAssessments = rs.getLong("memory_consolidation_assessments"),
                            memoryConsolidationSaveRecommended = rs.getLong("memory_consolidation_save_recommended"),
                            memoryConsolidationParseFailures = rs.getLong("long_term_memory_assessment_parse_failures"),
                            memoryImprintAttempts = rs.getLong("memory_imprint_attempts"),
                            memoryImprintSaved = rs.getLong("memory_imprint_saved"),
                            memoryImprintFailures = rs.getLong("memory_imprint_failures"),
                            memoryImprintLatencyMsTotal = rs.getLong("memory_imprint_latency_ms_total"),
                            memoryImprintCharsTotal = rs.getLong("memory_imprint_chars_total"),
                            responseLatencyCount = rs.getLong("response_latency_count"),
                            responseLatencySumMs = rs.getLong("response_latency_sum_ms"),
                            medianEndToEndResponseLatencyMs = rs.getDouble("response_latency_p50_ms").let {
                                if (rs.wasNull()) null else it
                            }
                        )
                    }
                }
            }

            val persistent = connection.prepareStatement(
                """
                SELECT COUNT(*) AS run_count,
                       COALESCE(SUM(total_calls), 0) AS total_calls,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       COALESCE(SUM(denied_actions), 0) AS denied_actions,
                       COALESCE(SUM(error_count), 0) AS error_count,
                       COALESCE(SUM(planner_noop_count), 0) AS planner_noop_count,
                       COALESCE(SUM(planner_output_repaired_count), 0) AS planner_output_repaired_count,
                       COALESCE(SUM(queue_saturation_events), 0) AS queue_saturation_events,
                       COALESCE(SUM(dropped_events), 0) AS dropped_events,
                       COALESCE(SUM(memory_recall_attempts), 0) AS memory_recall_attempts,
                       COALESCE(SUM(memory_recall_hits), 0) AS memory_recall_hits,
                       COALESCE(SUM(memory_recall_failures), 0) AS memory_recall_failures,
                       COALESCE(SUM(memory_recall_truncated), 0) AS memory_recall_truncated,
                       COALESCE(SUM(memory_recall_latency_ms_total), 0) AS memory_recall_latency_ms_total,
                       COALESCE(SUM(memory_recall_chars_total), 0) AS memory_recall_chars_total,
                       COALESCE(SUM(long_term_memory_recall_skipped), 0) AS long_term_memory_recall_skipped,
                       COALESCE(SUM(memory_consolidation_assessments), 0) AS memory_consolidation_assessments,
                       COALESCE(SUM(memory_consolidation_save_recommended), 0) AS memory_consolidation_save_recommended,
                       COALESCE(SUM(long_term_memory_assessment_parse_failures), 0) AS long_term_memory_assessment_parse_failures,
                       COALESCE(SUM(memory_imprint_attempts), 0) AS memory_imprint_attempts,
                       COALESCE(SUM(memory_imprint_saved), 0) AS memory_imprint_saved,
                       COALESCE(SUM(memory_imprint_failures), 0) AS memory_imprint_failures,
                       COALESCE(SUM(memory_imprint_latency_ms_total), 0) AS memory_imprint_latency_ms_total,
                       COALESCE(SUM(memory_imprint_chars_total), 0) AS memory_imprint_chars_total,
                       COALESCE(SUM(response_latency_count), 0) AS response_latency_count,
                       COALESCE(SUM(response_latency_sum_ms), 0) AS response_latency_sum_ms
                FROM runs
                WHERE key_fingerprint = ? AND provider = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, keyFingerprint)
                statement.setString(2, provider)
                statement.executeQuery().use { rs ->
                    rs.next()
                    val totals = MetricsTotals(
                        calls = rs.getLong("total_calls"),
                        promptTokens = rs.getLong("prompt_tokens"),
                        completionTokens = rs.getLong("completion_tokens"),
                        totalTokens = rs.getLong("total_tokens"),
                        deniedActions = rs.getLong("denied_actions"),
                        errorCount = rs.getLong("error_count"),
                        plannerNoopCount = rs.getLong("planner_noop_count"),
                        plannerOutputRepairedCount = rs.getLong("planner_output_repaired_count"),
                        queueSaturationEvents = rs.getLong("queue_saturation_events"),
                        droppedEvents = rs.getLong("dropped_events"),
                        memoryRecallAttempts = rs.getLong("memory_recall_attempts"),
                        memoryRecallHits = rs.getLong("memory_recall_hits"),
                        memoryRecallFailures = rs.getLong("memory_recall_failures"),
                        memoryRecallTruncated = rs.getLong("memory_recall_truncated"),
                        memoryRecallLatencyMsTotal = rs.getLong("memory_recall_latency_ms_total"),
                        memoryRecallCharsTotal = rs.getLong("memory_recall_chars_total"),
                        longTermMemoryRecallSkipped = rs.getLong("long_term_memory_recall_skipped"),
                        memoryConsolidationAssessments = rs.getLong("memory_consolidation_assessments"),
                        memoryConsolidationSaveRecommended = rs.getLong("memory_consolidation_save_recommended"),
                        memoryConsolidationParseFailures = rs.getLong("long_term_memory_assessment_parse_failures"),
                        memoryImprintAttempts = rs.getLong("memory_imprint_attempts"),
                        memoryImprintSaved = rs.getLong("memory_imprint_saved"),
                        memoryImprintFailures = rs.getLong("memory_imprint_failures"),
                        memoryImprintLatencyMsTotal = rs.getLong("memory_imprint_latency_ms_total"),
                        memoryImprintCharsTotal = rs.getLong("memory_imprint_chars_total"),
                        responseLatencyCount = rs.getLong("response_latency_count"),
                        responseLatencySumMs = rs.getLong("response_latency_sum_ms"),
                        medianEndToEndResponseLatencyMs = null
                    )
                    Pair(rs.getLong("run_count"), totals)
                }
            }

            val runSuperegoTokens = connection.prepareStatement(
                """
                SELECT COALESCE(SUM(COALESCE(total_tokens, COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0))), 0) AS total
                FROM llm_calls
                WHERE run_id = ? AND LOWER(COALESCE(actor, '')) = 'superego'
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, runId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong("total")
                }
            }

            val persistentSuperegoTokens = connection.prepareStatement(
                """
                SELECT COALESCE(SUM(COALESCE(total_tokens, COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0))), 0) AS total
                FROM llm_calls
                WHERE key_fingerprint = ? AND LOWER(COALESCE(actor, '')) = 'superego' AND provider = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, keyFingerprint)
                statement.setString(2, provider)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong("total")
                }
            }

            return MetricsSnapshot(
                runId = runId,
                provider = provider,
                keyFingerprint = keyFingerprint,
                updatedAtIso = nowIso(),
                runTotals = runTotals,
                persistentTotals = persistent.second,
                runCountForScope = persistent.first,
                runSuperegoTokens = runSuperegoTokens,
                persistentSuperegoTokens = persistentSuperegoTokens
            )
        }
    }

    private fun startRun(egoModel: String, superegoModel: String) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO runs(run_id, provider, key_fingerprint, started_at, ego_model, superego_model)
                VALUES(?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, runId)
                statement.setString(2, provider)
                statement.setString(3, keyFingerprint)
                statement.setString(4, nowIso())
                statement.setString(5, egoModel)
                statement.setString(6, superegoModel)
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
        private class SqlitePersistingChatCallObserver(
            private val sink: (ChatCallRecord) -> Unit,
        ) : PersistentMetricsChatCallObserver {
            override fun onChatCall(record: ChatCallRecord) {
                sink(record)
            }
        }

        private fun addRunsColumnIfMissing(statement: java.sql.Statement, column: String, definition: String) {
            try {
                statement.execute("ALTER TABLE runs ADD COLUMN $column $definition;")
            } catch (_: Exception) {
                // compatible with pre-existing DBs where column already exists
            }
        }

        private fun median(values: List<Long>): Double {
            if (values.isEmpty()) {
                return 0.0
            }
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid].toDouble()
            } else {
                (sorted[mid - 1].toDouble() + sorted[mid].toDouble()) / 2.0
            }
        }

        private fun resolveDbPath(): Path {
            val raw = System.getProperty("psyke.metrics.db")
            if (!raw.isNullOrBlank()) {
                return expandUserPath(raw)
            }
            return RuntimeDefaultsConfigLoader.resolveMetricsDbPath()
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
