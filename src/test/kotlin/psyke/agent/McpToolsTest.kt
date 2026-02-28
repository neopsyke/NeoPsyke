package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
