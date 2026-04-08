package ai.neopsyke.agent.cortex.motor.actions.plugin.google

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.plugin.google.CalendarObserveEventsActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.plugin.google.GmailObserveSearchActionPlugin
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.integrations.google.GoogleWorkspaceApiClient
import ai.neopsyke.integrations.google.GoogleWorkspaceCredentialRecord
import ai.neopsyke.integrations.google.GoogleWorkspaceCredentialStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleWorkspaceObserveActionPluginsTest {
    @Test
    fun `gmail search action returns observed evidence`() = runBlocking {
        val tempDir = Files.createTempDirectory("neopsyke-google-plugin-search")
        val credentialStore = GoogleWorkspaceCredentialStore(
            rootDir = tempDir.resolve("creds"),
            encryptionSecret = "token-encryption-secret",
        )
        credentialStore.save(
            GoogleWorkspaceCredentialRecord(
                ownerEmail = "owner@example.com",
                accessToken = "access-token-1",
                refreshToken = "refresh-token-1",
                tokenType = "Bearer",
                scopes = setOf("gmail.readonly"),
                expiresAtEpochSec = Long.MAX_VALUE,
                issuedAtEpochSec = 1L,
            )
        )
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "messages":[
                        {"id":"m-1","threadId":"t-1"},
                        {"id":"m-2","threadId":"t-2"}
                      ],
                      "resultSizeEstimate":2
                    }
                    """.trimIndent()
                )
            )
            val client = GoogleWorkspaceApiClient(
                clientId = "client-id",
                clientSecret = "client-secret",
                tokenBaseUrl = server.url("/oauth2/token").toString(),
                gmailBaseUrl = server.url("").toString().removeSuffix("/"),
                calendarBaseUrl = server.url("").toString().removeSuffix("/"),
                credentialStore = credentialStore,
                httpClient = OkHttpClient(),
            )
            val plugin = GmailObserveSearchActionPlugin(clientFactory = { client })
            val outcome = plugin.execute(
                PendingAction(
                    id = 1,
                    urgency = Urgency.MEDIUM,
                    type = ActionType("gmail_observe_search"),
                    payload = """{"query":"from:alice","max_results":2}""",
                    summary = "Search gmail",
                    attempts = 0,
                    groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                ActionExecutionContext(searchResultCount = 5),
            )
            assertEquals("SUCCESS", outcome.executionStatus.name)
            assertTrue(outcome.observedEvidence == true)
            assertTrue(outcome.statusSummary.contains("m-1"))
        }
    }

    @Test
    fun `calendar events action returns event summary`() = runBlocking {
        val tempDir = Files.createTempDirectory("neopsyke-google-plugin-calendar")
        val credentialStore = GoogleWorkspaceCredentialStore(
            rootDir = tempDir.resolve("creds"),
            encryptionSecret = "token-encryption-secret",
        )
        credentialStore.save(
            GoogleWorkspaceCredentialRecord(
                ownerEmail = "owner@example.com",
                accessToken = "access-token-1",
                refreshToken = "refresh-token-1",
                tokenType = "Bearer",
                scopes = setOf("calendar.readonly"),
                expiresAtEpochSec = Long.MAX_VALUE,
                issuedAtEpochSec = 1L,
            )
        )
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "items":[
                        {
                          "summary":"Daily Standup",
                          "location":"Meet",
                          "start":{"dateTime":"2026-03-24T09:00:00Z"},
                          "end":{"dateTime":"2026-03-24T09:15:00Z"}
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )
            val client = GoogleWorkspaceApiClient(
                clientId = "client-id",
                clientSecret = "client-secret",
                tokenBaseUrl = server.url("/oauth2/token").toString(),
                gmailBaseUrl = server.url("").toString().removeSuffix("/"),
                calendarBaseUrl = server.url("").toString().removeSuffix("/"),
                credentialStore = credentialStore,
                httpClient = OkHttpClient(),
            )
            val plugin = CalendarObserveEventsActionPlugin(clientFactory = { client })
            val outcome = plugin.execute(
                PendingAction(
                    id = 1,
                    urgency = Urgency.MEDIUM,
                    type = ActionType("calendar_observe_events"),
                    payload = """{"calendar_id":"primary","max_results":1}""",
                    summary = "List calendar events",
                    attempts = 0,
                    groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                ActionExecutionContext(searchResultCount = 5),
            )
            assertEquals("SUCCESS", outcome.executionStatus.name)
            assertTrue(outcome.statusSummary.contains("Daily Standup"))
        }
    }
}
