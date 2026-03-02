package psyke.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import psyke.agent.tools.mcp.SdkMcpFetchTool.Companion.classifyFetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpToolsTest {
    @Test
    fun `parse command keeps quoted segments together`() {
        val parsed = McpStdioClient.parseCommand("uvx mcp-server-fetch --header \"User Agent\"")

        assertEquals(listOf("uvx", "mcp-server-fetch", "--header", "User Agent"), parsed)
    }

    @Test
    fun `fetch tool blocks non public urls before any tool call`() {
        val tool = SdkMcpFetchTool(
            command = listOf("uvx", "mcp-server-fetch"),
            callTimeoutMs = 1000,
            maxChars = 2000
        )

        val blockedHttp = tool.fetch("{\"url\":\"http://example.com\"}")
        val blockedLocal = tool.fetch("{\"url\":\"https://localhost/path\"}")
        val blockedPrivate = tool.fetch("{\"url\":\"https://192.168.1.10/path\"}")

        assertTrue(blockedHttp.contains("blocked URL", ignoreCase = true))
        assertTrue(blockedLocal.contains("blocked URL", ignoreCase = true))
        assertTrue(blockedPrivate.contains("blocked URL", ignoreCase = true))
    }

    @Test
    fun `encode mcp arguments preserves nested arrays and objects`() {
        val encoded = encodeMcpArguments(
            mapOf(
                "observations" to listOf(
                    mapOf(
                        "entityName" to "psyke_long_term_memory",
                        "contents" to listOf("remember this")
                    )
                ),
                "limit" to 4
            )
        )

        val observations = encoded["observations"]
        assertIs<JsonArray>(observations)
        assertEquals(1, observations.size)
        val item = observations[0]
        assertIs<JsonObject>(item)
        assertEquals(JsonPrimitive("psyke_long_term_memory"), item["entityName"])
        assertIs<JsonArray>(item["contents"])
        assertEquals(JsonPrimitive("remember this"), (item["contents"] as JsonArray)[0])
        assertEquals(JsonPrimitive(4), encoded["limit"])
    }

    @Test
    fun `encode mcp arguments handles null and unknown value types`() {
        val encoded = encodeMcpArguments(
            mapOf(
                "nullable" to null,
                "custom" to object {
                    override fun toString(): String = "custom-value"
                }
            )
        )

        assertEquals(JsonNull, encoded["nullable"])
        assertEquals(JsonPrimitive("custom-value"), encoded["custom"])
    }

    // ── classifyFetchError tests ──

    @Test
    fun `classifyFetchError returns NON_RETRYABLE for http status code patterns`() {
        for (code in listOf("403", "404", "401", "410", "451")) {
            assertEquals(
                FetchErrorCategory.NON_RETRYABLE,
                classifyFetchError("http $code response from server"),
                "Expected NON_RETRYABLE for status code $code"
            )
        }
    }

    @Test
    fun `classifyFetchError returns NON_RETRYABLE for keyword patterns`() {
        val errorTexts = listOf(
            "access forbidden by firewall",
            "page not found on this server",
            "unauthorized request rejected",
            "resource is gone permanently",
            "process exited with non-zero exit status 1",
            "failed to install mcp-server-fetch",
            "enoent: no such file or directory",
            "request blocked by cloudflare",
            "content removed per safety policy",
        )
        for (text in errorTexts) {
            assertEquals(
                FetchErrorCategory.NON_RETRYABLE,
                classifyFetchError(text),
                "Expected NON_RETRYABLE for: $text"
            )
        }
    }

    @Test
    fun `classifyFetchError returns RETRYABLE for transient errors`() {
        val errorTexts = listOf(
            "connection timed out after 8000ms",
            "network unreachable",
            "dns resolution failed for example.com",
            "ssl handshake error",
            "read timeout",
            "unexpected eof",
            "remote host closed connection",
        )
        for (text in errorTexts) {
            assertEquals(
                FetchErrorCategory.RETRYABLE,
                classifyFetchError(text),
                "Expected RETRYABLE for: $text"
            )
        }
    }

    // ── fetchWithOutcome pre-call validation tests ──

    @Test
    fun `fetchWithOutcome returns MALFORMED_REQUEST for invalid json payload`() {
        val tool = SdkMcpFetchTool(
            command = listOf("uvx", "mcp-server-fetch"),
            callTimeoutMs = 1000,
            maxChars = 2000
        )

        val outcome = tool.fetchWithOutcome("not json at all")

        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, outcome.errorCategory)
        assertTrue(outcome.message.contains("invalid", ignoreCase = true))
    }

    @Test
    fun `fetchWithOutcome returns MALFORMED_REQUEST for missing url`() {
        val tool = SdkMcpFetchTool(
            command = listOf("uvx", "mcp-server-fetch"),
            callTimeoutMs = 1000,
            maxChars = 2000
        )

        val outcome = tool.fetchWithOutcome("""{"max_chars":500}""")

        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, outcome.errorCategory)
        assertTrue(outcome.message.contains("missing url", ignoreCase = true))
    }

    @Test
    fun `fetchWithOutcome returns MALFORMED_REQUEST for blocked urls`() {
        val tool = SdkMcpFetchTool(
            command = listOf("uvx", "mcp-server-fetch"),
            callTimeoutMs = 1000,
            maxChars = 2000
        )

        val httpOutcome = tool.fetchWithOutcome("""{"url":"http://example.com"}""")
        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, httpOutcome.errorCategory)
        assertTrue(httpOutcome.message.contains("blocked", ignoreCase = true))

        val localhostOutcome = tool.fetchWithOutcome("""{"url":"https://localhost/api"}""")
        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, localhostOutcome.errorCategory)

        val privateOutcome = tool.fetchWithOutcome("""{"url":"https://192.168.1.1/path"}""")
        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, privateOutcome.errorCategory)
    }

    @Test
    fun `fetchWithOutcome returns MALFORMED_REQUEST for empty url`() {
        val tool = SdkMcpFetchTool(
            command = listOf("uvx", "mcp-server-fetch"),
            callTimeoutMs = 1000,
            maxChars = 2000
        )

        val outcome = tool.fetchWithOutcome("""{"url":""}""")

        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, outcome.errorCategory)
        assertTrue(outcome.message.contains("missing url", ignoreCase = true))
    }
}
