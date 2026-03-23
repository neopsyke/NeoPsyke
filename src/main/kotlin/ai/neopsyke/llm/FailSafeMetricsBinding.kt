package ai.neopsyke.llm

import ai.neopsyke.config.RuntimeDefaultsConfigLoader
import ai.neopsyke.metrics.MetricsRuntime
import ai.neopsyke.metrics.MetricsRuntimeFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class FailSafeMetricsBinding(
    val observer: ChatCallObserver?,
)

fun bindFailSafeMetricsObserver(
    provider: String,
    apiKey: String,
    modelName: String,
    primaryObserver: ChatCallObserver?,
): FailSafeMetricsBinding {
    if (hasPersistentMetricsObserver(primaryObserver)) {
        return FailSafeMetricsBinding(
            observer = primaryObserver
        )
    }

    val runtime = FailSafeMetricsRegistry.runtimeFor(
        provider = provider,
        apiKey = apiKey,
        modelName = modelName
    )
    val observer = combineChatCallObservers(
        runtime.chatCallObserver(provider),
        primaryObserver
    )
    return FailSafeMetricsBinding(
        observer = observer
    )
}

private object FailSafeMetricsRegistry {
    private val runtimes = ConcurrentHashMap<String, MetricsRuntime>()
    private val shutdownHookRegistered = AtomicBoolean(false)

    fun runtimeFor(provider: String, apiKey: String, modelName: String): MetricsRuntime {
        ensureShutdownHook()
        val safeApiKey = apiKey.ifBlank { provider }
        if (System.getProperty("neopsyke.metrics.db").isNullOrBlank()) {
            val resolvedDbPath = RuntimeDefaultsConfigLoader.resolveMetricsDbPath()
            System.setProperty("neopsyke.metrics.db", resolvedDbPath.toString())
        }
        val dbKey = System.getProperty("neopsyke.metrics.db").orEmpty()
        val key = "$provider:${safeApiKey.hashCode()}:${modelName}:${dbKey.hashCode()}"
        return runtimes.computeIfAbsent(key) {
            MetricsRuntimeFactory.create(
                provider = provider,
                apiKey = safeApiKey,
                egoModel = modelName,
                superegoModel = modelName
            )
        }
    }

    private fun ensureShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runtimes.values.forEach { runtime ->
                    try {
                        runtime.close()
                    } catch (_: Exception) {
                        // best-effort flush
                    }
                }
                runtimes.clear()
            }
        )
    }
}
