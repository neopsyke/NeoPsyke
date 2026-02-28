package psyke.llm

import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI
import java.time.Duration

private val logger = KotlinLogging.logger {}

enum class ProviderHealthState {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE
}

data class ProviderStatus(
    val provider: String,
    val state: ProviderHealthState,
    val detail: String,
)

interface LlmProviderStatusChecker {
    fun check(): ProviderStatus
}

class MistralProviderStatusChecker(
    private val apiKey: String,
    private val baseUrl: String = "https://api.mistral.ai/v1",
    private val timeoutMs: Long = 4_000,
) : LlmProviderStatusChecker {
    override fun check(): ProviderStatus {
        val host = try {
            URI(baseUrl).host?.ifBlank { null } ?: return unavailable("Invalid Mistral base URL: $baseUrl")
        } catch (_: Exception) {
            return unavailable("Invalid Mistral base URL: $baseUrl")
        }

        val dnsReachable = try {
            InetAddress.getByName(host)
            true
        } catch (_: Exception) {
            false
        }
        if (!dnsReachable) {
            return unavailable("DNS lookup failed for $host.")
        }

        if (apiKey.isBlank()) {
            return unavailable("MISTRAL_API_KEY is missing.")
        }

        val httpClient = OkHttpClient.Builder()
            .callTimeout(Duration.ofMillis(timeoutMs))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> available("Mistral API reachable (HTTP ${response.code}).")
                    response.code == 401 || response.code == 403 -> unavailable(
                        "Mistral API reachable but authentication failed (HTTP ${response.code})."
                    )
                    response.code == 429 -> degraded("Mistral API rate limited (HTTP 429).")
                    response.code >= 500 -> unavailable("Mistral API returned server error (HTTP ${response.code}).")
                    else -> degraded("Mistral API reachable but returned HTTP ${response.code}.")
                }
            }
        } catch (ex: Exception) {
            unavailable("Mistral API check failed: ${ex.message ?: ex::class.simpleName ?: "unknown error"}")
        }
    }

    private fun available(detail: String): ProviderStatus =
        ProviderStatus(
            provider = "mistral",
            state = ProviderHealthState.AVAILABLE,
            detail = detail
        )

    private fun degraded(detail: String): ProviderStatus =
        ProviderStatus(
            provider = "mistral",
            state = ProviderHealthState.DEGRADED,
            detail = detail
        )

    private fun unavailable(detail: String): ProviderStatus =
        ProviderStatus(
            provider = "mistral",
            state = ProviderHealthState.UNAVAILABLE,
            detail = detail
        )
}

fun reportProviderStatusAndDecide(
    modeLabel: String,
    status: ProviderStatus,
): Boolean {
    val message = "[provider.status] mode=$modeLabel provider=${status.provider} state=${status.state.name.lowercase()} detail=${status.detail}"
    return when (status.state) {
        ProviderHealthState.AVAILABLE -> {
            logger.info { message }
            true
        }

        ProviderHealthState.DEGRADED -> {
            logger.warn { message }
            println("Warning: $message")
            true
        }

        ProviderHealthState.UNAVAILABLE -> {
            logger.error { message }
            System.err.println("Error: $message")
            false
        }
    }
}
