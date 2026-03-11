package psyke.agent.superego

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import psyke.agent.actions.ActionRegistry
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class SuperegoDeterministicDecision(
    val allow: Boolean,
    val reason: String = "",
    val ruleId: String? = null,
    val reasonCode: String? = null,
)

/**
 * Deterministic gate for hard-deny policy checks and schema validation.
 * This gate runs before the LLM superego judgment and is authoritative.
 */
internal class SuperegoDeterministicConscience(
    private val config: AgentConfig,
    private val actionRegistry: ActionRegistry,
) {
    fun review(action: PendingAction, context: SuperegoContext): SuperegoDeterministicDecision {
        return try {
            validateActionShape(action)?.let { return it }
            actionRegistry.deterministicReview(action = action, context = context, config = config)
                ?.let { pluginDecision ->
                    return mapPluginDecision(pluginDecision)
                }
            when (action.type) {
                ActionType.ANSWER -> validateAnswer(action)
                ActionType.ANSWER_DRAFT -> validateAnswer(action)
                ActionType.WEB_SEARCH -> validateWebSearch(action, context)
                ActionType.MCP_TIME -> validateMcpTime(action)
                ActionType.WEBSITE_FETCH -> validateWebsiteFetch(action, context)
                ActionType.MEMORY -> allow() // Memory ops are internal subsystem calls, always allowed.
                else -> allow()
            }
        } catch (_: Exception) {
            deny(
                ruleId = "deterministic_review_failed",
                reason = "Deterministic superego checks failed unexpectedly; denying by default."
            )
        }
    }

    private fun mapPluginDecision(
        decision: psyke.agent.actions.ActionDeterministicReview,
    ): SuperegoDeterministicDecision {
        if (decision.allow) {
            return allow()
        }
        val ruleId = decision.ruleId ?: "action_policy_denied"
        val reasonCode = decision.reasonCode ?: "POLICY_${normalizeRuleId(ruleId)}"
        val reason = decision.reason.ifBlank { "Deterministic policy denied action." }
        return SuperegoDeterministicDecision(
            allow = false,
            reason = "$reason [rule:$ruleId]",
            ruleId = ruleId,
            reasonCode = reasonCode
        )
    }

    private fun validateActionShape(action: PendingAction): SuperegoDeterministicDecision? {
        if (action.summary.isBlank()) {
            return deny("summary_blank", "Action summary is required for deterministic review.")
        }
        if (action.summary.length > config.planner.maxActionSummaryChars) {
            return deny(
                "summary_too_long",
                "Action summary exceeds ${config.planner.maxActionSummaryChars} chars."
            )
        }
        if (action.payload.length > config.planner.maxActionPayloadChars) {
            return deny(
                "payload_too_long",
                "Action payload exceeds ${config.planner.maxActionPayloadChars} chars."
            )
        }
        return null
    }

    private fun validateAnswer(action: PendingAction): SuperegoDeterministicDecision {
        if (action.payload.trim().isBlank()) {
            return deny("answer_payload_blank", "ANSWER payload must not be blank.")
        }
        return allow()
    }

    private fun validateWebSearch(
        action: PendingAction,
        @Suppress("UNUSED_PARAMETER") context: SuperegoContext,
    ): SuperegoDeterministicDecision {
        val payload = action.payload.trim()
        if (payload.isBlank()) {
            return deny("web_search_payload_blank", "WEB_SEARCH payload must not be blank.")
        }
        if (containsSecretExfilIntent(payload)) {
            return deny(
                "web_search_secret_exfil",
                "WEB_SEARCH payload appears to request credential or secret exfiltration."
            )
        }
        if (containsSensitivePiiExfilIntent(payload)) {
            return deny(
                "web_search_sensitive_pii_exfil",
                "WEB_SEARCH payload appears to request sensitive personal data exfiltration."
            )
        }
        if (containsInlineSecretMaterial(payload)) {
            return deny(
                "web_search_inline_secret_material",
                "WEB_SEARCH payload contains inline secret-like material."
            )
        }
        return allow()
    }

    private fun validateMcpTime(action: PendingAction): SuperegoDeterministicDecision {
        val payload = action.payload.trim()
        if (payload.isBlank()) {
            return deny(
                "mcp_time_timezone_missing",
                "MCP_TIME payload must include a timezone, for example {\"timezone\":\"Europe/Berlin\"}."
            )
        }
        val parsed = try {
            mapper.readValue<McpTimePayload>(payload)
        } catch (_: Exception) {
            return deny(
                "mcp_time_payload_invalid_json",
                "MCP_TIME payload must be JSON like {\"timezone\":\"Europe/Berlin\"}."
            )
        }
        val timezone = parsed.timezone?.trim().orEmpty()
        if (timezone.isBlank()) {
            return deny(
                "mcp_time_timezone_missing",
                "MCP_TIME payload must include a non-empty timezone."
            )
        }
        if (!timezoneRegex.matches(timezone)) {
            return deny(
                "mcp_time_timezone_invalid",
                "MCP_TIME timezone contains invalid characters."
            )
        }
        return allow()
    }

    private fun validateWebsiteFetch(
        action: PendingAction,
        @Suppress("UNUSED_PARAMETER") context: SuperegoContext,
    ): SuperegoDeterministicDecision {
        val parsed = try {
            mapper.readValue<FetchValidationPayload>(action.payload)
        } catch (_: Exception) {
            return deny(
                "website_fetch_payload_invalid_json",
                "WEBSITE_FETCH payload must be JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}."
            )
        }
        val url = parsed.url?.trim().orEmpty()
        if (url.isBlank()) {
            return deny("website_fetch_url_missing", "WEBSITE_FETCH payload is missing required url.")
        }
        if (!isPublicHttpsUrl(url)) {
            return deny(
                "website_fetch_url_blocked",
                "WEBSITE_FETCH URL must be a public HTTPS URL and must not target private/local hosts."
            )
        }
        if (hasSensitiveEndpoint(url)) {
            return deny(
                "website_fetch_sensitive_endpoint",
                "WEBSITE_FETCH URL targets a sensitive endpoint (auth/account/payment/admin/metadata)."
            )
        }
        if (hasSensitiveQueryParams(url)) {
            return deny(
                "website_fetch_sensitive_query_params",
                "WEBSITE_FETCH URL contains sensitive query parameters."
            )
        }
        val requestedMaxChars = parsed.maxChars
        if (requestedMaxChars != null && requestedMaxChars !in WEBSITE_FETCH_MIN_MAX_CHARS..config.fetchMaxChars) {
            return deny(
                "website_fetch_max_chars_out_of_bounds",
                "WEBSITE_FETCH max_chars must be between $WEBSITE_FETCH_MIN_MAX_CHARS and ${config.fetchMaxChars}."
            )
        }
        if (containsSecretExfilIntent(action.payload) || containsInlineSecretMaterial(action.payload)) {
            return deny(
                "website_fetch_secret_exfil",
                "WEBSITE_FETCH payload appears to request credential or secret exfiltration."
            )
        }
        return allow()
    }

    private fun isPublicHttpsUrl(rawUrl: String): Boolean {
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

    private fun hasSensitiveEndpoint(rawUrl: String): Boolean {
        val path = try {
            URI(rawUrl).path.orEmpty()
        } catch (_: Exception) {
            return true
        }
        return sensitivePathRegex.containsMatchIn(path.lowercase(Locale.ROOT))
    }

    private fun hasSensitiveQueryParams(rawUrl: String): Boolean {
        val rawQuery = try {
            URI(rawUrl).rawQuery.orEmpty()
        } catch (_: Exception) {
            return true
        }
        if (rawQuery.isBlank()) return false
        return rawQuery.split('&').any { pair ->
            val name = pair.substringBefore('=')
            val decoded = URLDecoder.decode(name, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
            sensitiveQueryParamNames.any { token -> decoded.contains(token) }
        }
    }

    private fun containsSecretExfilIntent(text: String): Boolean =
        secretExfilIntentRegex.containsMatchIn(text)

    private fun containsSensitivePiiExfilIntent(text: String): Boolean =
        piiExfilIntentRegex.containsMatchIn(text)

    private fun containsInlineSecretMaterial(text: String): Boolean =
        inlineSecretMaterialRegex.containsMatchIn(text)

    private fun allow(): SuperegoDeterministicDecision = SuperegoDeterministicDecision(allow = true)

    private fun deny(ruleId: String, reason: String): SuperegoDeterministicDecision =
        SuperegoDeterministicDecision(
            allow = false,
            reason = "$reason [rule:$ruleId]",
            ruleId = ruleId,
            reasonCode = "POLICY_${normalizeRuleId(ruleId)}"
        )

    private fun normalizeRuleId(ruleId: String): String =
        ruleId.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifBlank { "DETERMINISTIC_DENY" }

    private data class McpTimePayload(
        val timezone: String? = null,
    )

    private data class FetchValidationPayload(
        val url: String? = null,
        @field:JsonProperty("max_chars")
        val maxChars: Int? = null,
    )

    private companion object {
        private const val WEBSITE_FETCH_MIN_MAX_CHARS: Int = 256
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        private val timezoneRegex = Regex("""^[A-Za-z0-9_\-+/]{1,80}$""")
        private val sensitivePathRegex = Regex(
            pattern = """(^|/)(auth|login|signin|oauth|token|account|billing|payment|admin|metadata|internal|private)(/|$)"""
        )
        private val sensitiveQueryParamNames = setOf(
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
        private val secretExfilIntentRegex = Regex(
            pattern = """(?is)\b(show|dump|extract|leak|exfiltrate|steal|reveal|get)\b.{0,60}\b(password|api[ _-]?keys?|tokens?|cookie|credentials?|private key|secret)\b"""
        )
        private val piiExfilIntentRegex = Regex(
            pattern = """(?is)\b(find|search|lookup|scrape|collect|dump|exfiltrate|reveal|get)\b.{0,60}\b(ssn|social security|credit card|cvv|bank account|medical record|passport|driver'?s license|phone number|home address|email list)\b"""
        )
        private val inlineSecretMaterialRegex = Regex(
            pattern = """(?is)(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|\b(api[ _-]?key|token|password|secret)\s*[:=]\s*[A-Za-z0-9_\-]{8,})"""
        )
    }
}
