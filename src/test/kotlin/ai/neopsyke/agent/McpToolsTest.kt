package ai.neopsyke.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ai.neopsyke.agent.tools.mcp.NativeFetchTool.Companion.htmlToReadableText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpToolsTest {
    @Test
    fun `parse command keeps quoted segments together`() {
        val parsed = McpStdioClient.parseCommand("uvx mcp-server-fetch --header \"User Agent\"")

        assertEquals(listOf("uvx", "mcp-server-fetch", "--header", "User Agent"), parsed)
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

    // ── NativeFetchTool pre-call validation tests ──

    @Test
    fun `native fetch returns MALFORMED_REQUEST for invalid json payload`() = runBlocking {
        val tool = NativeFetchTool(callTimeoutMs = 1000, maxChars = 2000)

        val outcome = tool.fetchWithOutcome("not json at all")

        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, outcome.errorCategory)
        assertTrue(outcome.message.contains("invalid", ignoreCase = true))
    }

    @Test
    fun `native fetch returns MALFORMED_REQUEST for missing url`() = runBlocking {
        val tool = NativeFetchTool(callTimeoutMs = 1000, maxChars = 2000)

        val outcome = tool.fetchWithOutcome("""{"max_chars":500}""")

        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, outcome.errorCategory)
        assertTrue(outcome.message.contains("missing url", ignoreCase = true))
    }

    @Test
    fun `native fetch returns MALFORMED_REQUEST for blocked urls`() = runBlocking {
        val tool = NativeFetchTool(callTimeoutMs = 1000, maxChars = 2000)

        val httpOutcome = tool.fetchWithOutcome("""{"url":"http://example.com"}""")
        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, httpOutcome.errorCategory)
        assertTrue(httpOutcome.message.contains("blocked", ignoreCase = true))

        val localhostOutcome = tool.fetchWithOutcome("""{"url":"https://localhost/api"}""")
        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, localhostOutcome.errorCategory)

        val privateOutcome = tool.fetchWithOutcome("""{"url":"https://192.168.1.1/path"}""")
        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, privateOutcome.errorCategory)
    }

    @Test
    fun `native fetch returns MALFORMED_REQUEST for empty url`() = runBlocking {
        val tool = NativeFetchTool(callTimeoutMs = 1000, maxChars = 2000)

        val outcome = tool.fetchWithOutcome("""{"url":""}""")

        assertEquals(FetchErrorCategory.MALFORMED_REQUEST, outcome.errorCategory)
        assertTrue(outcome.message.contains("missing url", ignoreCase = true))
    }

    @Test
    fun `native fetch health check is always available`() = runBlocking {
        val tool = NativeFetchTool(callTimeoutMs = 1000, maxChars = 2000)

        val status = tool.healthCheck()

        assertTrue(status.available)
        assertTrue(status.detail.contains("OkHttp", ignoreCase = true))
    }

    // ── htmlToReadableText tests ──

    @Test
    fun `htmlToReadableText extracts paragraphs as plain text`() {
        val html = "<html><body><p>Hello world.</p><p>Second paragraph.</p></body></html>"

        val text = htmlToReadableText(html, "https://example.com")

        assertTrue(text.contains("Hello world."))
        assertTrue(text.contains("Second paragraph."))
    }

    @Test
    fun `htmlToReadableText strips script and style elements`() {
        val html = """
            <html><body>
                <script>alert('xss')</script>
                <style>.red { color: red }</style>
                <p>Visible content.</p>
            </body></html>
        """.trimIndent()

        val text = htmlToReadableText(html, "https://example.com")

        assertTrue(text.contains("Visible content."))
        assertTrue(!text.contains("alert"))
        assertTrue(!text.contains(".red"))
    }

    @Test
    fun `htmlToReadableText preserves heading markers`() {
        val html = "<html><body><h1>Title</h1><h2>Subtitle</h2><p>Body.</p></body></html>"

        val text = htmlToReadableText(html, "https://example.com")

        assertTrue(text.contains("# Title"))
        assertTrue(text.contains("## Subtitle"))
    }

    @Test
    fun `htmlToReadableText preserves list items`() {
        val html = "<html><body><ul><li>First</li><li>Second</li></ul></body></html>"

        val text = htmlToReadableText(html, "https://example.com")

        assertTrue(text.contains("- First"))
        assertTrue(text.contains("- Second"))
    }

    @Test
    fun `htmlToReadableText preserves preformatted code blocks`() {
        val html = "<html><body><pre>val x = 42</pre></body></html>"

        val text = htmlToReadableText(html, "https://example.com")

        assertTrue(text.contains("```"))
        assertTrue(text.contains("val x = 42"))
    }

    @Test
    fun `htmlToReadableText collapses excessive newlines`() {
        val html = "<html><body><p>One</p><p></p><p></p><p></p><p>Two</p></body></html>"

        val text = htmlToReadableText(html, "https://example.com")

        // Should not have more than 2 consecutive newlines.
        assertTrue(!text.contains("\n\n\n"))
        assertTrue(text.contains("One"))
        assertTrue(text.contains("Two"))
    }
}
