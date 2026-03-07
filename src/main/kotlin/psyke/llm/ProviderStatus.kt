package psyke.llm

import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import psyke.ConsoleReporter
import psyke.StdConsoleReporter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.time.Duration

private val logger = KotlinLogging.logger {}
private val output: ConsoleReporter = StdConsoleReporter

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

private class HttpModelsProviderStatusChecker(
    private val providerLabel: String,
    private val missingApiKeyEnvVar: String,
    private val apiKey: String,
    private val baseUrl: String,
    private val timeoutMs: Long,
    private val modelsPath: String = "models",
    private val apiKeyHeaderName: String = "Authorization",
    private val apiKeyHeaderValuePrefix: String = "Bearer ",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmProviderStatusChecker {
    override fun check(): ProviderStatus {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val host = try {
            URI(normalizedBaseUrl).host?.ifBlank { null } ?: return unavailable("Invalid $providerLabel base URL: $baseUrl")
        } catch (_: Exception) {
            return unavailable("Invalid $providerLabel base URL: $baseUrl")
        }

        if (apiKey.isBlank()) {
            return unavailable("$missingApiKeyEnvVar is missing.")
        }

        val httpClient = OkHttpClient.Builder()
            .callTimeout(Duration.ofMillis(timeoutMs))
            .build()
        val request = Request.Builder()
            .url(buildHealthModelsUrl(baseUrl = normalizedBaseUrl, modelsPath = modelsPath))
            .header(apiKeyHeaderName, "$apiKeyHeaderValuePrefix$apiKey")
            .header("Accept", "application/json")
            .apply {
                extraHeaders.forEach { (name, value) -> header(name, value) }
            }
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> available("$providerLabel API reachable (HTTP ${response.code}).")
                    response.code == 401 || response.code == 403 -> unavailable(
                        "$providerLabel API reachable but authentication failed (HTTP ${response.code})."
                    )
                    response.code == 429 -> degraded("$providerLabel API rate limited (HTTP 429).")
                    response.code >= 500 -> unavailable("$providerLabel API returned server error (HTTP ${response.code}).")
                    else -> degraded("$providerLabel API reachable but returned HTTP ${response.code}.")
                }
            }
        } catch (ex: UnknownHostException) {
            unavailable("DNS lookup failed for $host. Check network/proxy configuration or set LLM_BASE_URL to a reachable endpoint.")
        } catch (ex: Exception) {
            unavailable("$providerLabel API check failed: ${ex.message ?: ex::class.simpleName ?: "unknown error"}")
        }
    }

    private fun available(detail: String): ProviderStatus =
        ProviderStatus(
            provider = providerLabel.lowercase(),
            state = ProviderHealthState.AVAILABLE,
            detail = detail
        )

    private fun degraded(detail: String): ProviderStatus =
        ProviderStatus(
            provider = providerLabel.lowercase(),
            state = ProviderHealthState.DEGRADED,
            detail = detail
        )

    private fun unavailable(detail: String): ProviderStatus =
        ProviderStatus(
            provider = providerLabel.lowercase(),
            state = ProviderHealthState.UNAVAILABLE,
            detail = detail
        )
}

class MistralProviderStatusChecker(
    private val apiKey: String,
    private val baseUrl: String = "https://api.mistral.ai/v1",
    private val timeoutMs: Long = 4_000,
) : LlmProviderStatusChecker {
    override fun check(): ProviderStatus =
        HttpModelsProviderStatusChecker(
            providerLabel = "mistral",
            missingApiKeyEnvVar = "MISTRAL_API_KEY",
            apiKey = apiKey,
            baseUrl = baseUrl,
            timeoutMs = timeoutMs
        ).check()
}

class GeminiProviderStatusChecker(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai/",
    private val timeoutMs: Long = 4_000,
) : LlmProviderStatusChecker {
    override fun check(): ProviderStatus {
        val primary = HttpModelsProviderStatusChecker(
            providerLabel = "google",
            missingApiKeyEnvVar = "GOOGLE_API_KEY",
            apiKey = apiKey,
            baseUrl = baseUrl,
            timeoutMs = timeoutMs
        ).check()

        if (!primary.isDegradedHttp404()) {
            return primary
        }
        val nativeBaseUrl = deriveGoogleNativeModelsBaseUrl(baseUrl) ?: return primary

        // Google OpenAI-compat can return 404 for /openai/models while native /models is healthy.
        return HttpModelsProviderStatusChecker(
            providerLabel = "google",
            missingApiKeyEnvVar = "GOOGLE_API_KEY",
            apiKey = apiKey,
            baseUrl = nativeBaseUrl,
            timeoutMs = timeoutMs,
            modelsPath = "models",
            apiKeyHeaderName = "x-goog-api-key",
            apiKeyHeaderValuePrefix = "",
            extraHeaders = mapOf("Authorization" to "Bearer $apiKey")
        ).check()
    }
}

class GroqProviderStatusChecker(
    private val apiKey: String,
    private val baseUrl: String = "https://api.groq.com/openai/v1",
    private val timeoutMs: Long = 4_000,
) : LlmProviderStatusChecker {
    override fun check(): ProviderStatus =
        HttpModelsProviderStatusChecker(
            providerLabel = "groq",
            missingApiKeyEnvVar = "GROQ_API_KEY",
            apiKey = apiKey,
            baseUrl = baseUrl,
            timeoutMs = timeoutMs
        ).check()
}

fun reportProviderStatusAndDecide(
    modeLabel: String,
    status: ProviderStatus,
): Boolean {
    val message = "[provider.status] mode=$modeLabel provider=${status.provider} state=${status.state.name.lowercase()} detail=${status.detail}"
    persistProviderStatusLine(message)
    return when (status.state) {
        ProviderHealthState.AVAILABLE -> {
            logger.info { message }
            true
        }

        ProviderHealthState.DEGRADED -> {
            logger.warn { message }
            output.warn("Warning: $message")
            true
        }

        ProviderHealthState.UNAVAILABLE -> {
            logger.error { message }
            output.error("Error: $message")
            false
        }
    }
}

private fun persistProviderStatusLine(message: String) {
    val path = resolveProviderStatusLogPath()
    try {
        Files.createDirectories(path.parent)
        val line = "${Instant.now()} $message\n"
        Files.writeString(
            path,
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
    } catch (ex: Exception) {
        logger.debug(ex) { "Failed to persist provider status line to $path." }
    }
}

private fun resolveProviderStatusLogPath(): Path {
    val env = System.getenv()
    env["PSYKE_PROVIDER_STATUS_LOG_FILE"]?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
        return Path.of(explicit)
    }
    env["PSYKE_LOG_DIR"]?.trim()?.takeIf { it.isNotEmpty() }?.let { logDir ->
        return Paths.get(logDir).resolve("provider-status.log")
    }
    env["PSYKE_LOG_FILE"]?.trim()?.takeIf { it.isNotEmpty() }?.let { runLog ->
        val runLogPath = Path.of(runLog)
        return runLogPath.resolveSibling("provider-status.log")
    }
    return Paths.get(System.getProperty("user.dir")).resolve(".psyke").resolve("logs").resolve("provider-status.log")
}

internal fun buildHealthModelsUrl(baseUrl: String, modelsPath: String = "models"): String {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedModelsPath = modelsPath.trim().trimStart('/')
    return "$normalizedBaseUrl/$normalizedModelsPath"
}

internal fun deriveGoogleNativeModelsBaseUrl(baseUrl: String): String? {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    if (normalizedBaseUrl.endsWith("/openai")) {
        return normalizedBaseUrl.removeSuffix("/openai")
    }
    return null
}

private fun ProviderStatus.isDegradedHttp404(): Boolean =
    state == ProviderHealthState.DEGRADED && detail.contains("HTTP 404")
