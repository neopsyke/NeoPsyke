package ai.neopsyke.agent.cortex.motor.actions.fetch

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.support.TextSecurity
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private val logger = KotlinLogging.logger {}

class NativeFetchTool(
    private val callTimeoutMs: Long,
    private val maxChars: Int,
) : FetchTool {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(5_000, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun fetch(payload: String): String = fetchWithOutcome(payload).message

    override suspend fun fetchWithOutcome(payload: String): FetchOutcome = withContext(Dispatchers.IO) {
        val parsed = try {
            mapper.readValue<FetchPayload>(payload)
        } catch (_: Exception) {
            return@withContext FetchOutcome(
                message = "Fetch payload is invalid. Expected JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}.",
                errorCategory = FetchErrorCategory.MALFORMED_REQUEST
            )
        }

        val url = parsed.url?.trim().orEmpty()
        if (url.isEmpty()) {
            return@withContext FetchOutcome(
                message = "Fetch payload is missing url.",
                errorCategory = FetchErrorCategory.MALFORMED_REQUEST
            )
        }
        if (!isFetchUrlAllowed(url)) {
            return@withContext FetchOutcome(
                message = "Fetch blocked URL by safety policy. Only public HTTPS URLs are allowed.",
                errorCategory = FetchErrorCategory.MALFORMED_REQUEST
            )
        }

        val requestedMaxChars = parsed.maxChars ?: maxChars
        val safeMaxChars = requestedMaxChars.coerceIn(256, maxChars)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (ex: SocketTimeoutException) {
            return@withContext FetchOutcome(
                message = "Fetch timed out for $url: ${ex.message}",
                errorCategory = FetchErrorCategory.RETRYABLE
            )
        } catch (ex: UnknownHostException) {
            return@withContext FetchOutcome(
                message = "DNS resolution failed for $url: ${ex.message}",
                errorCategory = FetchErrorCategory.NON_RETRYABLE
            )
        } catch (ex: SSLException) {
            return@withContext FetchOutcome(
                message = "SSL error fetching $url: ${ex.message}",
                errorCategory = FetchErrorCategory.NON_RETRYABLE
            )
        } catch (ex: IOException) {
            return@withContext FetchOutcome(
                message = "Network error fetching $url: ${ex.message}",
                errorCategory = FetchErrorCategory.RETRYABLE
            )
        }

        response.use { resp ->
            val code = resp.code
            if (code in NON_RETRYABLE_HTTP_CODES) {
                return@withContext FetchOutcome(
                    message = "HTTP $code ${resp.message} for $url",
                    errorCategory = FetchErrorCategory.NON_RETRYABLE
                )
            }
            if (!resp.isSuccessful) {
                val retryable = code in 500..599 || code == 429
                return@withContext FetchOutcome(
                    message = "HTTP $code ${resp.message} for $url",
                    errorCategory = if (retryable) FetchErrorCategory.RETRYABLE else FetchErrorCategory.NON_RETRYABLE
                )
            }

            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext FetchOutcome(
                    message = "Fetch completed for $url but returned empty body.",
                    errorCategory = FetchErrorCategory.NONE
                )
            }

            val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val text = if ("html" in contentType || "xhtml" in contentType) {
                htmlToReadableText(body, url)
            } else {
                body
            }

            val clamped = PromptInjectionDefense.sanitizeExternalText(text, safeMaxChars)
            val injectionScan = PromptInjectionDefense.scan(text)
            val preview = TextSecurity.preview(clamped, 240)
            val promptInjectionSignals =
                if (injectionScan.suspicious) injectionScan.signalIds.sorted().joinToString(",") else "none"
            FetchOutcome(
                message = "Fetch completed for $url. Extracted ${clamped.length} chars. Preview: $preview. prompt_injection_signals=$promptInjectionSignals",
                errorCategory = FetchErrorCategory.NONE
            )
        }
    }

    override suspend fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "Native JVM fetch tool (OkHttp + Jsoup). No external process required."
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (compatible; neopsyke-agent/1.0; +https://neopsyke.ai)"

        private val NON_RETRYABLE_HTTP_CODES = setOf(400, 401, 403, 404, 410, 451)

        internal fun htmlToReadableText(html: String, baseUri: String): String {
            val doc = Jsoup.parse(html, baseUri)
            doc.select("script, style, noscript, svg, [hidden]").remove()
            return buildString { renderNode(doc.body(), this) }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "main", "aside",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "tr", "blockquote", "pre", "figure", "figcaption",
            "details", "summary", "dl", "dt", "dd",
        )

        private fun renderNode(node: Node, out: StringBuilder) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText.replace(Regex("[ \\t]+"), " ")
                    if (text.isNotBlank()) out.append(text)
                }

                is Element -> {
                    val tag = node.tagName()
                    val isBlock = tag in BLOCK_TAGS

                    if (isBlock && out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                    when {
                        tag.matches(Regex("h[1-6]")) -> out.append("#".repeat(tag[1].digitToInt())).append(' ')
                        tag == "li" -> out.append("- ")
                        tag == "br" -> { out.append('\n'); return }
                        tag == "pre" -> {
                            out.append("```\n")
                            out.append(node.wholeText())
                            out.append("\n```\n")
                            return
                        }
                    }
                    for (child in node.childNodes()) renderNode(child, out)
                    if (isBlock) out.append('\n')
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared URL safety gate.
// ---------------------------------------------------------------------------

internal fun isFetchUrlAllowed(rawUrl: String): Boolean {
    val uri = try {
        URI(rawUrl)
    } catch (_: Exception) {
        return false
    }

    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "https") return false

    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (host.isBlank()) return false
    if (host == "localhost" || host.endsWith(".local")) return false
    if (host == "::1" || host.startsWith("127.")) return false
    if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false

    if (host.startsWith("172.")) {
        val secondOctet = host.split('.').getOrNull(1)?.toIntOrNull()
        if (secondOctet != null && secondOctet in 16..31) return false
    }

    if (host.contains(":") && (host.startsWith("fc") || host.startsWith("fd"))) return false

    return true
}

private data class FetchPayload(
    val url: String? = null,
    @param:JsonProperty("max_chars")
    val maxChars: Int? = null,
)

private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
