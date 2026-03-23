package ai.neopsyke.agent.memory.provider

import ai.neopsyke.agent.memory.longterm.MemoryHealth
import ai.neopsyke.agent.memory.longterm.MemoryKind
import ai.neopsyke.agent.memory.longterm.MemoryStatsResult
import ai.neopsyke.agent.memory.longterm.NarrativeImprint
import ai.neopsyke.agent.memory.longterm.RecallRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpMemoryProviderClientTest {
    @Test
    fun `client uses v1 endpoints for health recall imprint and metrics`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"provider":"external-http","available":true,"detail":"ok","degraded":false}
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"provider":"external-http","items":[],"renderedText":"","hitCount":0,"truncated":false}
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"provider":"external-http","accepted":true,"storedCount":1,"detail":"stored"}
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"database":{"searches":3}}
                """.trimIndent()
            )
        )
        server.start()

        try {
            val client = HttpMemoryProviderClient(
                providerName = "external-http",
                baseUrl = server.url("/").toString().removeSuffix("/"),
                callTimeoutMs = 1_000,
            )

            val health = client.health()
            val recall = client.recall(RecallRequest(cue = "remember this"), namespace = "ns")
            val imprint = client.imprint(
                NarrativeImprint(summary = "memory summary", kind = MemoryKind.NARRATIVE),
                namespace = "ns"
            )
            val stats = client.stats()

            assertEquals(MemoryHealth(provider = "external-http", available = true, detail = "ok", degraded = false), health)
            assertEquals("", recall.renderedText)
            assertTrue(imprint.accepted)
            assertEquals(MemoryStatsResult(stats = mapOf("database" to mapOf("searches" to 3))), stats)

            assertEquals("/v1/health", server.takeRequest().path)
            assertEquals("/v1/recall", server.takeRequest().path)
            assertEquals("/v1/imprint", server.takeRequest().path)
            assertEquals("/v1/metrics", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
