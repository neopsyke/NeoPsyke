package psyke.metrics

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import psyke.llm.ChatCallObserver
import psyke.llm.ChatCallRecord
import psyke.llm.PersistentMetricsChatCallObserver
import psyke.config.RuntimeDefaultsConfigLoader
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

private val logger = KotlinLogging.logger {}

interface MetricsRuntime : Closeable {
    fun chatCallObserver(provider: String): ChatCallObserver?
    fun recordActionCall(actionType: String)
    fun recordDeniedAction()
    fun recordPlannerNoop()
    fun recordPlannerOutputRepaired()
    fun recordDroppedEvents(count: Long = 1)
    fun recordQueueSaturation(queueType: String)
    fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean)
    fun recordMemoryRecallFailure(latencyMs: Long)
    fun recordLongTermMemoryRecallSkipped()
    fun recordLongTermMemoryAssessment(saveRecommended: Boolean)
    fun recordLongTermMemoryAssessmentParseFailure()
    fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long)
    fun recordEpisodicRecall(hitCount: Int, recallChars: Int)
    fun recordLessonRecall(hitCount: Int, recallChars: Int)
    fun recordEndToEndResponseLatency(latencyMs: Long)
    fun snapshot(): MetricsSnapshot?

    override fun close() {}
}

class NoopMetricsRuntime : MetricsRuntime {
    override fun chatCallObserver(provider: String): ChatCallObserver? = null
    override fun recordActionCall(actionType: String) {}
    override fun recordDeniedAction() {}
    override fun recordPlannerNoop() {}
    override fun recordPlannerOutputRepaired() {}
    override fun recordDroppedEvents(count: Long) {}
    override fun recordQueueSaturation(queueType: String) {}
    override fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean) {}
    override fun recordMemoryRecallFailure(latencyMs: Long) {}
    override fun recordLongTermMemoryRecallSkipped() {}
    override fun recordLongTermMemoryAssessment(saveRecommended: Boolean) {}
    override fun recordLongTermMemoryAssessmentParseFailure() {}
    override fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long) {}
    override fun recordEpisodicRecall(hitCount: Int, recallChars: Int) {}
    override fun recordLessonRecall(hitCount: Int, recallChars: Int) {}
    override fun recordEndToEndResponseLatency(latencyMs: Long) {}
    override fun snapshot(): MetricsSnapshot? = null
}

object MetricsRuntimeFactory {
    fun create(
        provider: String,
        apiKey: String,
        egoModel: String,
        superegoModel: String,
    ): MetricsRuntime {
        ensureMetricsDbSystemProperty()
        return try {
            SqliteMetricsRuntime(
                provider = provider,
                apiKey = apiKey,
                egoModel = egoModel,
                superegoModel = superegoModel
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Primary SQLite metrics initialization failed; falling back to JSONL metrics sink." }
            createFallbackRuntime(provider = provider)
        }
    }

    private fun ensureMetricsDbSystemProperty() {
        if (!System.getProperty("psyke.metrics.db").isNullOrBlank()) {
            return
        }
        val resolved = RuntimeDefaultsConfigLoader.resolveMetricsDbPath()
        System.setProperty("psyke.metrics.db", resolved.toString())
    }

    private fun createFallbackRuntime(provider: String): MetricsRuntime {
        val targetPath = try {
            RuntimeDefaultsConfigLoader.resolveMetricsDbPath().resolveSibling("metrics-fallback.jsonl")
        } catch (_: Exception) {
            Path.of(System.getProperty("user.dir"), ".psyke", "metrics-fallback.jsonl")
        }
        return try {
            JsonlFallbackMetricsRuntime(
                provider = provider,
                filePath = targetPath
            )
        } catch (fallbackEx: Exception) {
            logger.warn(fallbackEx) { "Fallback JSONL metrics initialization failed. Metrics are disabled." }
            NoopMetricsRuntime()
        }
    }
}

private class JsonlFallbackMetricsRuntime(
    private val provider: String,
    private val filePath: Path,
) : MetricsRuntime {
    private val mapper = jacksonObjectMapper()

    override fun chatCallObserver(provider: String): ChatCallObserver =
        object : PersistentMetricsChatCallObserver {
            override fun onChatCall(record: ChatCallRecord) {
                val payload = mapOf(
                    "ts" to Instant.now().toString(),
                    "provider" to provider,
                    "model" to record.model,
                    "actor" to record.metadata.actor,
                    "call_site" to record.metadata.callSite,
                    "action_type" to record.metadata.actionType,
                    "prompt_tokens" to record.promptTokens,
                    "completion_tokens" to record.completionTokens,
                    "total_tokens" to record.totalTokens,
                    "latency_ms" to record.latencyMs,
                    "status" to record.status.name.lowercase(),
                    "error_code" to record.errorCode,
                    "error_message" to record.errorMessage
                )
                appendLine(payload)
            }
        }

    override fun recordDeniedAction() {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "denied_action"
            )
        )
    }

    override fun recordActionCall(actionType: String) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "action_call",
                "action_type" to actionType
            )
        )
    }

    override fun recordPlannerNoop() {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "planner_noop"
            )
        )
    }

    override fun recordPlannerOutputRepaired() {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "planner_output_repaired"
            )
        )
    }

    override fun recordDroppedEvents(count: Long) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "dropped_events",
                "count" to count
            )
        )
    }

    override fun recordQueueSaturation(queueType: String) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "queue_saturation",
                "queue_type" to queueType
            )
        )
    }

    override fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "memory_recall",
                "hit_count" to hitCount,
                "latency_ms" to latencyMs,
                "recall_chars" to recallChars,
                "truncated" to truncated
            )
        )
    }

    override fun recordMemoryRecallFailure(latencyMs: Long) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "memory_recall_failure",
                "latency_ms" to latencyMs
            )
        )
    }

    override fun recordLongTermMemoryRecallSkipped() {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "long_term_memory_recall_skipped"
            )
        )
    }

    override fun recordLongTermMemoryAssessment(saveRecommended: Boolean) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "long_term_memory_assessment",
                "save_recommended" to saveRecommended
            )
        )
    }

    override fun recordLongTermMemoryAssessmentParseFailure() {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "long_term_memory_assessment_parse_failure"
            )
        )
    }

    override fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "memory_imprint",
                "saved" to saved,
                "summary_chars" to summaryChars,
                "latency_ms" to latencyMs
            )
        )
    }

    override fun recordEpisodicRecall(hitCount: Int, recallChars: Int) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "episodic_recall",
                "hit_count" to hitCount,
                "recall_chars" to recallChars
            )
        )
    }

    override fun recordLessonRecall(hitCount: Int, recallChars: Int) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "lesson_recall",
                "hit_count" to hitCount,
                "recall_chars" to recallChars
            )
        )
    }

    override fun recordEndToEndResponseLatency(latencyMs: Long) {
        appendLine(
            mapOf(
                "ts" to Instant.now().toString(),
                "provider" to provider,
                "event" to "response_latency",
                "latency_ms" to latencyMs
            )
        )
    }

    override fun snapshot(): MetricsSnapshot? = null

    private fun appendLine(payload: Map<String, Any?>) {
        try {
            Files.createDirectories(filePath.parent)
            val line = mapper.writeValueAsString(payload) + "\n"
            Files.writeString(
                filePath,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )
        } catch (ex: Exception) {
            logger.debug(ex) { "Fallback metrics write failed for path=$filePath." }
        }
    }
}

data class MetricsTotals(
    val calls: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val deniedActions: Long,
    val errorCount: Long,
    val plannerNoopCount: Long = 0,
    val plannerOutputRepairedCount: Long = 0,
    val queueSaturationEvents: Long = 0,
    val droppedEvents: Long = 0,
    val memoryRecallAttempts: Long = 0,
    val memoryRecallHits: Long = 0,
    val memoryRecallFailures: Long = 0,
    val memoryRecallTruncated: Long = 0,
    val memoryRecallLatencyMsTotal: Long = 0,
    val memoryRecallCharsTotal: Long = 0,
    val memoryConsolidationAssessments: Long = 0,
    val memoryConsolidationSaveRecommended: Long = 0,
    val longTermMemoryRecallSkipped: Long = 0,
    val memoryConsolidationParseFailures: Long = 0,
    val memoryImprintAttempts: Long = 0,
    val memoryImprintSaved: Long = 0,
    val memoryImprintFailures: Long = 0,
    val memoryImprintLatencyMsTotal: Long = 0,
    val memoryImprintCharsTotal: Long = 0,
    val episodicRecallAttempts: Long = 0,
    val episodicRecallHits: Long = 0,
    val episodicRecallCharsTotal: Long = 0,
    val lessonRecallAttempts: Long = 0,
    val lessonRecallHits: Long = 0,
    val lessonRecallCharsTotal: Long = 0,
    val responseLatencyCount: Long = 0,
    val responseLatencySumMs: Long = 0,
    val medianEndToEndResponseLatencyMs: Double? = null,
)

data class MetricsSnapshot(
    val runId: String,
    val provider: String,
    val keyFingerprint: String,
    val updatedAtIso: String,
    val runTotals: MetricsTotals,
    val persistentTotals: MetricsTotals,
    val runCountForScope: Long,
    val runSuperegoTokens: Long = 0,
    val persistentSuperegoTokens: Long = 0,
    val runActionCallsByType: Map<String, Long> = emptyMap(),
    val persistentActionCallsByType: Map<String, Long> = emptyMap(),
    val runTokensByProvider: Map<String, Long> = emptyMap(),
    val persistentTokensByProvider: Map<String, Long> = emptyMap(),
    val runTokensByRole: Map<String, Long> = emptyMap(),
    val persistentTokensByRole: Map<String, Long> = emptyMap(),
    val runTokensByModel: Map<String, Long> = emptyMap(),
    val persistentTokensByModel: Map<String, Long> = emptyMap(),
    val runModelsByRole: Map<String, Set<String>> = emptyMap(),
    val persistentModelsByRole: Map<String, Set<String>> = emptyMap(),
    val memoryServerMetrics: Map<String, Any>? = null,
)
