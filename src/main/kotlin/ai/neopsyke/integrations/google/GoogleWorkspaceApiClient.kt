package ai.neopsyke.integrations.google

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ai.neopsyke.agent.support.TextSecurity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

data class GoogleOAuthExchangeResult(
    val ownerEmail: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val scopes: Set<String>,
    val expiresAtEpochSec: Long?,
)

data class GmailSearchRequest(
    val query: String = "",
    val labelIds: List<String> = emptyList(),
    val maxResults: Int = 10,
)

data class CalendarEventsRequest(
    val calendarId: String = "primary",
    val timeMinIso: String? = null,
    val timeMaxIso: String? = null,
    val maxResults: Int = 10,
    val query: String? = null,
)

class GoogleWorkspaceApiClient(
    private val clientId: String,
    private val clientSecret: String,
    private val tokenBaseUrl: String,
    private val credentialStore: GoogleWorkspaceCredentialStore,
    private val gmailBaseUrl: String = DEFAULT_GMAIL_BASE_URL,
    private val calendarBaseUrl: String = DEFAULT_CALENDAR_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): GoogleOAuthExchangeResult {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .apply {
                if (!codeVerifier.isNullOrBlank()) {
                    add("code_verifier", codeVerifier)
                }
            }
            .build()
        val request = Request.Builder()
            .url(tokenBaseUrl)
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Google token exchange failed (${response.code}): ${TextSecurity.preview(body, 160)}")
            }
            val token = mapper.readValue<TokenResponse>(body)
            val accessToken = token.accessToken?.trim().orEmpty()
                .ifBlank { throw IllegalStateException("Google token response missing access_token.") }
            val refreshToken = token.refreshToken?.trim().orEmpty()
                .ifBlank { throw IllegalStateException("Google token response missing refresh_token.") }
            val ownerEmail = fetchAuthorizedEmail(accessToken)
            return GoogleOAuthExchangeResult(
                ownerEmail = ownerEmail,
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenType = token.tokenType?.trim().orEmpty().ifBlank { "Bearer" },
                scopes = token.scope
                    ?.split(' ')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet(),
                expiresAtEpochSec = token.expiresIn?.let { clock.instant().epochSecond + it },
            )
        }
    }

    fun currentAuthorizedEmail(): String =
        fetchAuthorizedEmail(validAccessToken())

    fun searchMessages(request: GmailSearchRequest): String {
        val accessToken = validAccessToken()
        val urlBuilder = "${gmailBaseUrl.trim().removeSuffix("/")}/gmail/v1/users/me/messages".toHttpUrl().newBuilder()
        if (request.query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", request.query)
        }
        request.labelIds.forEach { urlBuilder.addQueryParameter("labelIds", it) }
        urlBuilder.addQueryParameter("maxResults", request.maxResults.coerceIn(1, 25).toString())
        val response = executeGet(urlBuilder.build().toString(), accessToken)
        val parsed = mapper.readValue<GmailListResponse>(response)
        val items = parsed.messages.orEmpty().take(request.maxResults.coerceIn(1, 25))
        if (items.isEmpty()) {
            return "Gmail search found no matching messages."
        }
        return buildString {
            append("Gmail search results (${items.size})")
            parsed.resultSizeEstimate?.let { append(", estimated_total=$it") }
            append(":\n")
            items.forEachIndexed { index, item ->
                append("${index + 1}. id=${item.id.orEmpty()}")
                item.threadId?.takeIf { it.isNotBlank() }?.let { append(" thread=$it") }
                append('\n')
            }
        }.trim()
    }

    fun getMessage(messageId: String): String {
        val accessToken = validAccessToken()
        val encodedId = URLEncoder.encode(messageId, StandardCharsets.UTF_8)
        val url = "${gmailBaseUrl.trim().removeSuffix("/")}/gmail/v1/users/me/messages/$encodedId?format=full"
        val response = executeGet(url, accessToken)
        val parsed = mapper.readValue<GmailMessageResponse>(response)
        val headers = parsed.payload?.headers.orEmpty().associate { it.name.orEmpty().lowercase() to it.value.orEmpty() }
        val subject = headers["subject"].orEmpty()
        val from = headers["from"].orEmpty()
        val date = headers["date"].orEmpty()
        val snippet = parsed.snippet.orEmpty()
        val body = extractBodyText(parsed.payload).ifBlank { snippet }
        return buildString {
            append("Gmail message ")
            append(parsed.id.orEmpty())
            append(":\n")
            if (subject.isNotBlank()) append("subject: ").append(subject).append('\n')
            if (from.isNotBlank()) append("from: ").append(from).append('\n')
            if (date.isNotBlank()) append("date: ").append(date).append('\n')
            append("snippet: ").append(TextSecurity.clamp(body, MAX_TEXT_CHARS))
        }.trim()
    }

    fun listEvents(request: CalendarEventsRequest): String {
        val accessToken = validAccessToken()
        val calendarId = URLEncoder.encode(request.calendarId.ifBlank { "primary" }, StandardCharsets.UTF_8)
        val urlBuilder = "${calendarBaseUrl.trim().removeSuffix("/")}/calendar/v3/calendars/$calendarId/events".toHttpUrl().newBuilder()
            .addQueryParameter("singleEvents", "true")
            .addQueryParameter("orderBy", "startTime")
            .addQueryParameter("maxResults", request.maxResults.coerceIn(1, 25).toString())
            .addQueryParameter("timeMin", request.timeMinIso ?: Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString())
        request.timeMaxIso?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("timeMax", it) }
        request.query?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("q", it) }
        val response = executeGet(urlBuilder.build().toString(), accessToken)
        val parsed = mapper.readValue<CalendarEventsResponse>(response)
        val items = parsed.items.orEmpty().take(request.maxResults.coerceIn(1, 25))
        if (items.isEmpty()) {
            return "Calendar query found no upcoming events."
        }
        return buildString {
            append("Calendar events (${items.size})")
            append(":\n")
            items.forEachIndexed { index, item ->
                append("${index + 1}. ")
                append(item.summary.orEmpty().ifBlank { "(untitled)" })
                item.start?.dateTime?.orEmpty()?.ifBlank { item.start?.date.orEmpty() }?.takeIf { it.isNotBlank() }?.let {
                    append(" @ ").append(it)
                }
                item.end?.dateTime?.orEmpty()?.ifBlank { item.end?.date.orEmpty() }?.takeIf { it.isNotBlank() }?.let {
                    append(" -> ").append(it)
                }
                item.location?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append("]") }
                append('\n')
            }
        }.trim()
    }

    private fun validAccessToken(): String {
        val record = credentialStore.load() ?: throw IllegalStateException("Google Workspace authorization is not configured.")
        val now = clock.instant().epochSecond
        val expiresAt = record.expiresAtEpochSec
        if (expiresAt == null || expiresAt > now + TOKEN_EXPIRY_SKEW_SEC) {
            return record.accessToken
        }
        val refreshed = refreshAccessToken(record.refreshToken)
        val refreshedAccessToken = refreshed.accessToken?.trim().orEmpty()
            .ifBlank { throw IllegalStateException("Google token refresh response missing access_token.") }
        val updated = record.copy(
            accessToken = refreshedAccessToken,
            tokenType = refreshed.tokenType?.trim().orEmpty().ifBlank { record.tokenType },
            scopes = refreshed.scope
                ?.split(' ')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?.ifEmpty { record.scopes }
                ?: record.scopes,
            expiresAtEpochSec = refreshed.expiresIn?.let { clock.instant().epochSecond + it },
            issuedAtEpochSec = clock.instant().epochSecond,
        )
        credentialStore.save(updated)
        return updated.accessToken
    }

    private fun refreshAccessToken(refreshToken: String): TokenResponse {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder()
            .url(tokenBaseUrl)
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Google token refresh failed (${response.code}): ${TextSecurity.preview(body, 160)}")
            }
            return mapper.readValue<TokenResponse>(body)
        }
    }

    private fun fetchAuthorizedEmail(accessToken: String): String {
        val response = executeGet("${gmailBaseUrl.trim().removeSuffix("/")}/gmail/v1/users/me/profile", accessToken)
        val profile = mapper.readValue<GmailProfileResponse>(response)
        return profile.emailAddress?.trim().orEmpty()
            .ifBlank { throw IllegalStateException("Google profile response did not include email address.") }
    }

    private fun executeGet(url: String, accessToken: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Google API request failed (${response.code}): ${TextSecurity.preview(body, 160)}")
            }
            return body
        }
    }

    private fun extractBodyText(payload: GmailPayload?): String {
        if (payload == null) return ""
        payload.body?.data?.takeIf { it.isNotBlank() }?.let { encoded ->
            return decodeBase64Url(encoded)
        }
        payload.parts.orEmpty().forEach { part ->
            val nested = extractBodyText(part)
            if (nested.isNotBlank()) {
                return nested
            }
        }
        return ""
    }

    private fun decodeBase64Url(encoded: String): String =
        runCatching {
            String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrDefault("")

    private data class TokenResponse(
        @field:JsonProperty("access_token")
        val accessToken: String? = null,
        @field:JsonProperty("refresh_token")
        val refreshToken: String? = null,
        @field:JsonProperty("token_type")
        val tokenType: String? = null,
        @field:JsonProperty("expires_in")
        val expiresIn: Long? = null,
        val scope: String? = null,
    )

    private data class GmailProfileResponse(
        @field:JsonProperty("emailAddress")
        val emailAddress: String? = null,
    )

    private data class GmailListResponse(
        val messages: List<GmailMessageRef>? = null,
        @field:JsonProperty("resultSizeEstimate")
        val resultSizeEstimate: Long? = null,
    )

    private data class GmailMessageRef(
        val id: String? = null,
        @field:JsonProperty("threadId")
        val threadId: String? = null,
    )

    private data class GmailMessageResponse(
        val id: String? = null,
        val snippet: String? = null,
        val payload: GmailPayload? = null,
    )

    private data class GmailPayload(
        @field:JsonProperty("mimeType")
        val mimeType: String? = null,
        val headers: List<GmailHeader>? = null,
        val body: GmailBody? = null,
        val parts: List<GmailPayload>? = null,
    )

    private data class GmailHeader(
        val name: String? = null,
        val value: String? = null,
    )

    private data class GmailBody(
        val data: String? = null,
    )

    private data class CalendarEventsResponse(
        val items: List<CalendarEvent>? = null,
    )

    private data class CalendarEvent(
        val summary: String? = null,
        val location: String? = null,
        val start: CalendarEventBoundary? = null,
        val end: CalendarEventBoundary? = null,
    )

    private data class CalendarEventBoundary(
        @field:JsonProperty("dateTime")
        val dateTime: String? = null,
        val date: String? = null,
    )

    companion object {
        private const val DEFAULT_TIMEOUT_SEC: Long = 20
        private const val TOKEN_EXPIRY_SKEW_SEC: Long = 60
        private const val MAX_TEXT_CHARS: Int = 1200
        private const val DEFAULT_GMAIL_BASE_URL: String = "https://gmail.googleapis.com"
        private const val DEFAULT_CALENDAR_BASE_URL: String = "https://www.googleapis.com"

        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
