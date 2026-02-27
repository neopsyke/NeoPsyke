package psyke.metrics

import mu.KotlinLogging
import psyke.llm.ChatCallObserver
import java.io.Closeable

private val logger = KotlinLogging.logger {}

interface MetricsRuntime : Closeable {
    fun chatCallObserver(provider: String): ChatCallObserver?
    fun recordDeniedAction()

    override fun close() {}
}

class NoopMetricsRuntime : MetricsRuntime {
    override fun chatCallObserver(provider: String): ChatCallObserver? = null
    override fun recordDeniedAction() {}
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
