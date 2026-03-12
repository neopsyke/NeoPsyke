package psyke.agent.support

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Shared deterministic-review helpers for action payload validation.
 *
 * These are pure functions used by action plugins to enforce hard-deny
 * security rules before the LLM superego stage.
 */
object ActionPayloadSecurity {

    // ── Secret / PII detection ───────────────────────────────────────

    fun containsSecretExfilIntent(text: String): Boolean =
        SECRET_EXFIL_INTENT_REGEX.containsMatchIn(text)

    fun containsSensitivePiiExfilIntent(text: String): Boolean =
        PII_EXFIL_INTENT_REGEX.containsMatchIn(text)

    fun containsInlineSecretMaterial(text: String): Boolean =
        INLINE_SECRET_MATERIAL_REGEX.containsMatchIn(text)

    // ── URL validation ───────────────────────────────────────────────

    fun isPublicHttpsUrl(rawUrl: String): Boolean {
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

    fun hasSensitiveEndpoint(rawUrl: String): Boolean {
        val path = try {
            URI(rawUrl).path.orEmpty()
        } catch (_: Exception) {
            return true
        }
        return SENSITIVE_PATH_REGEX.containsMatchIn(path.lowercase(Locale.ROOT))
    }

    fun hasSensitiveQueryParams(rawUrl: String): Boolean {
        val rawQuery = try {
            URI(rawUrl).rawQuery.orEmpty()
        } catch (_: Exception) {
            return true
        }
        if (rawQuery.isBlank()) return false
        return rawQuery.split('&').any { pair ->
            val name = pair.substringBefore('=')
            val decoded = URLDecoder.decode(name, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
            SENSITIVE_QUERY_PARAM_NAMES.any { token -> decoded.contains(token) }
        }
    }

    // ── MCP-time timezone validation ─────────────────────────────────

    val TIMEZONE_REGEX: Regex = Regex("""^[A-Za-z0-9_\-+/]{1,80}$""")

    // ── Constants ────────────────────────────────────────────────────

    const val WEBSITE_FETCH_MIN_MAX_CHARS: Int = 256

    // ── Private regex / lookup tables ────────────────────────────────

    private val SECRET_EXFIL_INTENT_REGEX = Regex(
        pattern = """(?is)\b(show|dump|extract|leak|exfiltrate|steal|reveal|get)\b.{0,60}\b(password|api[ _-]?keys?|tokens?|cookie|credentials?|private key|secret)\b"""
    )
    private val PII_EXFIL_INTENT_REGEX = Regex(
        pattern = """(?is)\b(find|search|lookup|scrape|collect|dump|exfiltrate|reveal|get)\b.{0,60}\b(ssn|social security|credit card|cvv|bank account|medical record|passport|driver'?s license|phone number|home address|email list)\b"""
    )
    internal val INLINE_SECRET_MATERIAL_REGEX = Regex(
        pattern = """(?is)(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|\b(api[ _-]?key|token|password|secret)\s*[:=]\s*[A-Za-z0-9_\-]{8,})"""
    )
    private val SENSITIVE_PATH_REGEX = Regex(
        pattern = """(^|/)(auth|login|signin|oauth|token|account|billing|payment|admin|internal|private)(/|$)"""
    )
    private val SENSITIVE_QUERY_PARAM_NAMES = setOf(
        "token",
        "key",
        "api_key",
        "apikey",
        "password",
        "passwd",
        "auth",
        "authorization",
        "session",
        "secret",
        "cookie",
        "jwt",
        "access_token",
        "refresh_token",
    )
}
