package psyke.agent

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchEngineHealth
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.actions.websearch.WebSearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotorCortexTest {
    @Test
    fun `answer action writes output and returns assistant output`() {
        val captured = mutableListOf<String>()
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                    WebSearchResult("unused", emptyList())
                }
            ),
            output = { captured.add(it) }
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 1,
                urgency = Urgency.MEDIUM,
                type = ActionType.ANSWER,
                payload = "Hello there",
                summary = "reply"
            ),
            searchResultCount = 3
        )

        assertEquals("Answer delivered to interlocutor.", outcome.statusSummary)
        assertEquals("Hello there", outcome.assistantOutput)
        assertEquals(listOf("ego> Hello there"), captured)
    }

    @Test
    fun `web search action formats summary and snippets`() {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult {
                        assertEquals("kotlin testing", query)
                        assertEquals(2, maxResults)
                        return WebSearchResult(
                            summary = "Found docs",
                            snippets = listOf("snippet one", "snippet two"),
                            sources = listOf(
                                WebSearchSource(title = "Kotlin Docs", url = "https://kotlinlang.org/docs/home.html")
                            )
                        )
                    }
                }
            )
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 2,
                urgency = Urgency.HIGH,
                type = ActionType.WEB_SEARCH,
                payload = "kotlin testing",
                summary = "search"
            ),
            searchResultCount = 2
        )

        assertTrue(outcome.statusSummary.contains("Found docs"))
        assertTrue(outcome.statusSummary.contains("snippet one | snippet two"))
        assertTrue(outcome.statusSummary.contains("sources: [1] Kotlin Docs - https://kotlinlang.org/docs/home.html"))
        assertNull(outcome.assistantOutput)
    }

    @Test
    fun `mcp time action delegates to configured tool`() {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            mcpTimeTool = object : McpTimeTool {
                override fun getCurrentTime(payload: String): String {
                    assertEquals("""{"timezone":"Europe/Berlin"}""", payload)
                    return "MCP time result: 2026-02-28T10:15:00+01:00"
                }
            }
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 3,
                urgency = Urgency.MEDIUM,
                type = ActionType.MCP_TIME,
                payload = """{"timezone":"Europe/Berlin"}""",
                summary = "time lookup"
            ),
            searchResultCount = 1
        )

        assertEquals("MCP time result: 2026-02-28T10:15:00+01:00", outcome.statusSummary)
        assertNull(outcome.assistantOutput)
    }

    @Test
    fun `mcp fetch action delegates to configured tool and propagates success category`() {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            fetchTool = object : FetchTool {
                override fun fetch(payload: String): String = "unused"
                override fun fetchWithOutcome(payload: String): FetchOutcome {
                    assertEquals("""{"url":"https://example.com","max_chars":500}""", payload)
                    return FetchOutcome(
                        message = "MCP fetch completed for https://example.com.",
                        errorCategory = FetchErrorCategory.NONE
                    )
                }
            }
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 4,
                urgency = Urgency.HIGH,
                type = ActionType.MCP_FETCH,
                payload = """{"url":"https://example.com","max_chars":500}""",
                summary = "fetch page"
            ),
            searchResultCount = 1
        )

        assertEquals("MCP fetch completed for https://example.com.", outcome.statusSummary)
        assertEquals("none", outcome.fetchErrorCategory)
        assertNull(outcome.assistantOutput)
    }

    @Test
    fun `mcp fetch propagates non retryable error category through action outcome`() {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            fetchTool = object : FetchTool {
                override fun fetch(payload: String): String = "unused"
                override fun fetchWithOutcome(payload: String): FetchOutcome =
                    FetchOutcome(
                        message = "MCP fetch tool returned an error: 403 Forbidden",
                        errorCategory = FetchErrorCategory.NON_RETRYABLE
                    )
            }
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 5,
                urgency = Urgency.HIGH,
                type = ActionType.MCP_FETCH,
                payload = """{"url":"https://example.com"}""",
                summary = "fetch page"
            ),
            searchResultCount = 1
        )

        assertEquals("non_retryable", outcome.fetchErrorCategory)
    }

    @Test
    fun `mcp fetch propagates malformed request category through action outcome`() {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            fetchTool = object : FetchTool {
                override fun fetch(payload: String): String = "unused"
                override fun fetchWithOutcome(payload: String): FetchOutcome =
                    FetchOutcome(
                        message = "MCP fetch payload is invalid.",
                        errorCategory = FetchErrorCategory.MALFORMED_REQUEST
                    )
            }
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 6,
                urgency = Urgency.MEDIUM,
                type = ActionType.MCP_FETCH,
                payload = "bad payload",
                summary = "fetch page"
            ),
            searchResultCount = 1
        )

        assertEquals("malformed_request", outcome.fetchErrorCategory)
    }

    @Test
    fun `startup smoke test exposes per-action availability`() {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())

                    override fun healthCheck(): WebSearchEngineHealth =
                        WebSearchEngineHealth(
                            available = false,
                            detail = "web search offline"
                        )
                }
            ),
            mcpTimeTool = object : McpTimeTool {
                override fun getCurrentTime(payload: String): String = "unused"

                override fun healthCheck(): ToolHealthStatus =
                    ToolHealthStatus(available = false, detail = "time server offline")
            },
            fetchTool = object : FetchTool {
                override fun fetch(payload: String): String = "unused"

                override fun healthCheck(): ToolHealthStatus =
                    ToolHealthStatus(available = true, detail = "fetch ok")
            }
        )

        val statuses = cortex.startupSmokeTest()
        val byType = statuses.associateBy { it.actionType }

        assertEquals(true, byType[ActionType.ANSWER]?.available)
        assertEquals(false, byType[ActionType.WEB_SEARCH]?.available)
        assertEquals(false, byType[ActionType.MCP_TIME]?.available)
        assertEquals(true, byType[ActionType.MCP_FETCH]?.available)
        assertEquals(
            setOf(ActionType.ANSWER, ActionType.MCP_FETCH),
            cortex.availableActionTypes()
        )
    }
}
