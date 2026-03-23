package ai.neopsyke.integrations.google

import ai.neopsyke.agent.config.GoogleWorkspaceConfig
import ai.neopsyke.integrations.auth.OAuthPendingAuthorization
import ai.neopsyke.integrations.auth.OAuthPendingAuthorizationStore
import ai.neopsyke.integrations.auth.OAuthStateCodec
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class GoogleOAuthStartResult(
    val statusCode: Int,
    val ok: Boolean,
    val detail: String,
    val authorizationUrl: String? = null,
)

data class GoogleOAuthCallbackResult(
    val statusCode: Int,
    val ok: Boolean,
    val detail: String,
)

class GoogleWorkspaceOAuthBridge(
    private val config: GoogleWorkspaceConfig,
    private val clientId: String,
    private val clientSecret: String,
    private val stateCodec: OAuthStateCodec,
    private val pendingAuthorizationStore: OAuthPendingAuthorizationStore,
    private val credentialStore: GoogleWorkspaceCredentialStore,
    private val apiClient: GoogleWorkspaceApiClient,
) {
    fun startAuthorization(): GoogleOAuthStartResult {
        if (!config.enabled) {
            return GoogleOAuthStartResult(503, ok = false, detail = "Google Workspace integration is disabled.")
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return GoogleOAuthStartResult(503, ok = false, detail = "Google OAuth client credentials are not configured.")
        }
        if (config.allowedOwnerEmail.isBlank()) {
            return GoogleOAuthStartResult(503, ok = false, detail = "Google allowed owner email is not configured.")
        }
        val publicBaseUrl = config.publicBaseUrl.trim().removeSuffix("/")
        if (publicBaseUrl.isBlank()) {
            return GoogleOAuthStartResult(503, ok = false, detail = "Google public base URL is not configured.")
        }

        val nonce = randomToken(NONCE_BYTES)
        val codeVerifier = if (config.requirePkce) randomToken(CODE_VERIFIER_BYTES) else ""
        val record = OAuthPendingAuthorization(
            nonce = nonce,
            provider = PROVIDER_ID,
            ownerEmail = config.allowedOwnerEmail,
            codeVerifier = codeVerifier,
            scopes = config.scopes,
            issuedAtEpochSec = System.currentTimeMillis() / 1000L,
            expiresAtEpochSec = System.currentTimeMillis() / 1000L + config.oauthStateTtlSeconds,
        )
        pendingAuthorizationStore.save(record)
        val state = stateCodec.issue(
            provider = PROVIDER_ID,
            nonce = nonce,
            redirectPath = config.callbackPath,
            ownerEmail = config.allowedOwnerEmail,
            ttlSeconds = config.oauthStateTtlSeconds,
        )
        val redirectUri = redirectUri()
        val url = buildAuthorizationUrl(
            authorizationBaseUrl = config.authorizationBaseUrl,
            clientId = clientId,
            redirectUri = redirectUri,
            state = state,
            scopes = config.scopes,
            codeVerifier = codeVerifier.takeIf { it.isNotBlank() },
            requireRefreshToken = config.requireRefreshToken,
        )
        return GoogleOAuthStartResult(
            statusCode = 200,
            ok = true,
            detail = "Google OAuth authorization URL generated.",
            authorizationUrl = url,
        )
    }

    fun completeAuthorization(code: String?, stateToken: String?): GoogleOAuthCallbackResult {
        if (!config.enabled) {
            return GoogleOAuthCallbackResult(503, ok = false, detail = "Google Workspace integration is disabled.")
        }
        val state = stateToken?.trim().orEmpty()
        val authCode = code?.trim().orEmpty()
        if (state.isBlank() || authCode.isBlank()) {
            return GoogleOAuthCallbackResult(400, ok = false, detail = "Google OAuth callback is missing code or state.")
        }
        val verified = stateCodec.verify(
            token = state,
            expectedProvider = PROVIDER_ID,
            expectedRedirectPath = config.callbackPath,
            expectedOwnerEmail = config.allowedOwnerEmail,
        ) ?: return GoogleOAuthCallbackResult(403, ok = false, detail = "Google OAuth state verification failed.")

        val pending = pendingAuthorizationStore.consume(verified.nonce)
            ?: return GoogleOAuthCallbackResult(403, ok = false, detail = "Google OAuth authorization is missing or expired.")
        if (!pending.ownerEmail.equals(config.allowedOwnerEmail, ignoreCase = true)) {
            return GoogleOAuthCallbackResult(403, ok = false, detail = "Google OAuth owner email mismatch.")
        }

        val exchanged = try {
            apiClient.exchangeAuthorizationCode(
                code = authCode,
                redirectUri = redirectUri(),
                codeVerifier = pending.codeVerifier.takeIf { it.isNotBlank() },
            )
        } catch (ex: Exception) {
            return GoogleOAuthCallbackResult(502, ok = false, detail = ex.message ?: "Google token exchange failed.")
        }
        if (!exchanged.ownerEmail.equals(config.allowedOwnerEmail, ignoreCase = true)) {
            return GoogleOAuthCallbackResult(
                403,
                ok = false,
                detail = "Google account '${exchanged.ownerEmail}' is not the configured owner.",
            )
        }
        credentialStore.save(
            GoogleWorkspaceCredentialRecord(
                ownerEmail = exchanged.ownerEmail,
                accessToken = exchanged.accessToken,
                refreshToken = exchanged.refreshToken,
                tokenType = exchanged.tokenType,
                scopes = exchanged.scopes.ifEmpty { config.scopes },
                expiresAtEpochSec = exchanged.expiresAtEpochSec,
                issuedAtEpochSec = System.currentTimeMillis() / 1000L,
            )
        )
        return GoogleOAuthCallbackResult(200, ok = true, detail = "Google Workspace authorization stored for ${exchanged.ownerEmail}.")
    }

    fun startPath(): String = config.oauthStartPath
    fun callbackPath(): String = config.callbackPath

    private fun redirectUri(): String =
        config.publicBaseUrl.trim().removeSuffix("/") + config.callbackPath

    private fun buildAuthorizationUrl(
        authorizationBaseUrl: String,
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: Set<String>,
        codeVerifier: String?,
        requireRefreshToken: Boolean,
    ): String {
        val query = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scopes.sorted().joinToString(" "),
            "state" to state,
            "include_granted_scopes" to "true",
        )
        if (requireRefreshToken) {
            query["access_type"] = "offline"
            query["prompt"] = "consent"
        }
        if (!codeVerifier.isNullOrBlank()) {
            query["code_challenge_method"] = "S256"
            query["code_challenge"] = pkceChallenge(codeVerifier)
        }
        return authorizationBaseUrl.trim().removeSuffix("/") + "?" +
            query.entries.joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }
    }

    private fun pkceChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private const val PROVIDER_ID: String = "google-workspace"
        private const val NONCE_BYTES: Int = 24
        private const val CODE_VERIFIER_BYTES: Int = 48
        private val secureRandom = SecureRandom()
    }
}
