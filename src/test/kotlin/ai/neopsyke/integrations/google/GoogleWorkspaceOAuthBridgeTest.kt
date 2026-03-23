package ai.neopsyke.integrations.google

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ai.neopsyke.agent.config.GoogleWorkspaceConfig
import ai.neopsyke.integrations.auth.OAuthPendingAuthorizationStore
import ai.neopsyke.integrations.auth.OAuthStateCodec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoogleWorkspaceOAuthBridgeTest {
    @Test
    fun `start authorization returns google auth url and callback stores credentials`() {
        val tempDir = Files.createTempDirectory("neopsyke-google-oauth")
        val credentialStore = GoogleWorkspaceCredentialStore(
            rootDir = tempDir.resolve("creds"),
            encryptionSecret = "token-encryption-secret",
        )
        val pendingStore = OAuthPendingAuthorizationStore(
            rootDir = tempDir.resolve("pending"),
            encryptionSecret = "token-encryption-secret",
        )
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "access_token":"access-token-1",
                      "refresh_token":"refresh-token-1",
                      "token_type":"Bearer",
                      "expires_in":3600,
                      "scope":"https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/calendar.readonly"
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"emailAddress":"owner@example.com"}""")
            )
            val apiClient = GoogleWorkspaceApiClient(
                clientId = "client-id",
                clientSecret = "client-secret",
                tokenBaseUrl = server.url("/oauth2/token").toString(),
                gmailBaseUrl = server.url("").toString().removeSuffix("/"),
                calendarBaseUrl = server.url("").toString().removeSuffix("/"),
                credentialStore = credentialStore,
                httpClient = OkHttpClient(),
            )
            val bridge = GoogleWorkspaceOAuthBridge(
                config = googleConfig(),
                clientId = "client-id",
                clientSecret = "client-secret",
                stateCodec = OAuthStateCodec(signingSecret = "state-signing-secret"),
                pendingAuthorizationStore = pendingStore,
                credentialStore = credentialStore,
                apiClient = apiClient,
            )

            val start = bridge.startAuthorization()
            assertEquals(200, start.statusCode)
            assertTrue(start.ok)
            val authorizationUrl = start.authorizationUrl
            assertNotNull(authorizationUrl)
            val parsed = authorizationUrl.toHttpUrl()
            assertEquals("accounts.example.test", parsed.host)
            assertEquals("client-id", parsed.queryParameter("client_id"))
            assertEquals("https://neopsyke.example.test/api/channels/google/oauth/callback", parsed.queryParameter("redirect_uri"))
            val stateToken = parsed.queryParameter("state")
            assertNotNull(stateToken)

            val callback = bridge.completeAuthorization(
                code = "authorization-code",
                stateToken = stateToken,
            )
            assertEquals(200, callback.statusCode)
            assertTrue(callback.ok)

            val stored = credentialStore.load()
            assertNotNull(stored)
            assertEquals("owner@example.com", stored.ownerEmail)
            assertEquals("refresh-token-1", stored.refreshToken)
        }
    }

    private fun googleConfig(): GoogleWorkspaceConfig =
        GoogleWorkspaceConfig(
            enabled = true,
            tokenStoreDir = ".neopsyke/auth/google",
            allowedOwnerEmail = "owner@example.com",
            publicBaseUrl = "https://neopsyke.example.test",
            oauthStartPath = "/api/channels/google/oauth/start",
            oauthClientIdHandle = "GOOGLE_OAUTH_CLIENT_ID",
            oauthClientSecretHandle = "GOOGLE_OAUTH_CLIENT_SECRET",
            oauthStateSigningSecretHandle = "GOOGLE_OAUTH_STATE_SIGNING_SECRET",
            oauthTokenEncryptionSecretHandle = "GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET",
            callbackPath = "/api/channels/google/oauth/callback",
            authorizationBaseUrl = "https://accounts.example.test/o/oauth2/v2/auth",
            tokenBaseUrl = "https://oauth2.example.test/token",
            requirePkce = true,
            requireRefreshToken = true,
            oauthStateTtlSeconds = 600L,
            scopes = setOf(
                "https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/calendar.readonly",
            ),
        )
}
