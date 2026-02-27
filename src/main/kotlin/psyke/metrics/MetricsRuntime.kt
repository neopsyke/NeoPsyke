package psyke.metrics

import mu.KotlinLogging
import psyke.llm.ChatCallObserver
import java.io.Closeable

private val logger = KotlinLogging.logger {}

interface MetricsRuntime : Closeable {
    fun chatCallObserver(provider: String): ChatCallObserver?
    fun recordDeniedAction()
    fun snapshot(): MetricsSnapshot?

    override fun close() {}
}

class NoopMetricsRuntime : MetricsRuntime {
    override fun chatCallObserver(provider: String): ChatCallObserver? = null
    override fun recordDeniedAction() {}
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
)

data class MetricsSnapshot(
    val runId: String,
    val keyFingerprint: String,
    val updatedAtIso: String,
    val runTotals: MetricsTotals,
    val persistentTotals: MetricsTotals,
    val runCountForKey: Long,
)
