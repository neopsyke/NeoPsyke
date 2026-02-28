package psyke.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
}
