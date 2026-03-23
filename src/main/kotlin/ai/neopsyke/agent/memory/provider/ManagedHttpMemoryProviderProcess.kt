package ai.neopsyke.agent.memory.provider

import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private val providerProcessLogger = KotlinLogging.logger {}

class ManagedHttpMemoryProviderProcess(
    command: List<String>,
    private val baseUrl: String,
    private val startupTimeoutMs: Long,
    private val healthTimeoutMs: Long,
) : AutoCloseable {
    private val process: Process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    private val healthClient = OkHttpClient.Builder()
        .callTimeout(healthTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    init {
        waitForHealthy()
    }

    private fun waitForHealthy() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs)
        var lastError: String = "startup_not_confirmed"
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw IOException("Memory provider process exited before health check completed.")
            }
            try {
                val request = Request.Builder()
                    .url("$baseUrl/v1/health")
                    .get()
                    .build()
                healthClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return
                    }
                    lastError = "http_${response.code}"
                }
            } catch (ex: Exception) {
                lastError = ex.message ?: "health_check_failed"
            }
            Thread.sleep(200)
        }
        throw IOException("Timed out waiting for memory provider health at $baseUrl/v1/health ($lastError).")
    }

    override fun close() {
        if (!process.isAlive) return
        providerProcessLogger.debug { "Stopping managed HTTP memory provider process." }
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}
