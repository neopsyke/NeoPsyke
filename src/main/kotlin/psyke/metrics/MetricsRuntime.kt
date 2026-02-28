package psyke.metrics

import mu.KotlinLogging
import psyke.llm.ChatCallObserver
import java.io.Closeable

private val logger = KotlinLogging.logger {}

interface MetricsRuntime : Closeable {
    fun chatCallObserver(provider: String): ChatCallObserver?
    fun recordDeniedAction()
    fun recordDroppedEvents(count: Long = 1)
    fun recordQueueSaturation(queueType: String)
    fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean)
    fun recordMemoryRecallFailure(latencyMs: Long)
    fun recordMemoryConsolidationAssessment(saveRecommended: Boolean)
    fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long)
    fun snapshot(): MetricsSnapshot?

    override fun close() {}
}

class NoopMetricsRuntime : MetricsRuntime {
    override fun chatCallObserver(provider: String): ChatCallObserver? = null
    override fun recordDeniedAction() {}
    override fun recordDroppedEvents(count: Long) {}
    override fun recordQueueSaturation(queueType: String) {}
    override fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean) {}
    override fun recordMemoryRecallFailure(latencyMs: Long) {}
    override fun recordMemoryConsolidationAssessment(saveRecommended: Boolean) {}
    override fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long) {}
    override fun snapshot(): MetricsSnapshot? = null
}

object MetricsRuntimeFactory {
    fun create(
        apiKey: String,
        egoModel: String,
        superegoModel: String,
    ): MetricsRuntime {
        return try {
            SqliteMetricsRuntime(
                apiKey = apiKey,
                egoModel = egoModel,
                superegoModel = superegoModel
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Metrics disabled because initialization failed." }
            NoopMetricsRuntime()
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
    val memoryImprintAttempts: Long = 0,
    val memoryImprintSaved: Long = 0,
    val memoryImprintFailures: Long = 0,
    val memoryImprintLatencyMsTotal: Long = 0,
    val memoryImprintCharsTotal: Long = 0,
)

data class MetricsSnapshot(
    val runId: String,
    val keyFingerprint: String,
    val updatedAtIso: String,
    val runTotals: MetricsTotals,
    val persistentTotals: MetricsTotals,
    val runCountForKey: Long,
    val runSuperegoTokens: Long = 0,
    val persistentSuperegoTokens: Long = 0,
)
