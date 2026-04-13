package ai.neopsyke.agent

import ai.neopsyke.agent.model.GroundingMetadata
import kotlinx.coroutines.runBlocking
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngineHealth
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotorCortexTest {
    @Test
    fun `answer action writes output and returns assistant output`() = runBlocking {
        val captured = mutableListOf<String>()
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                    WebSearchResult("unused", emptyList())
                }
            ),
            output = { captured.add(it) },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 1,
                urgency = Urgency.MEDIUM,
                type = ActionType.CONTACT_USER,
                payload = "Hello there",
                summary = "reply",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            searchResultCount = 3
        )

        assertEquals("Message delivered to interlocutor.", outcome.statusSummary)
        assertEquals(listOf("ego> Hello there"), captured)
    }

    @Test
    fun `web search action formats summary and snippets`() = runBlocking {
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
            ),
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 2,
                urgency = Urgency.HIGH,
                type = ActionType.WEB_SEARCH,
                payload = "kotlin testing",
                summary = "search",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            searchResultCount = 2
        )

        assertTrue(outcome.statusSummary.contains("Found docs"))
        assertTrue(outcome.statusSummary.contains("snippet one | snippet two"))
        assertTrue(outcome.statusSummary.contains("sources: [1] Kotlin Docs - https://kotlinlang.org/docs/home.html"))
    }

    @Test
    fun `fetch action delegates to configured tool and propagates success category`() = runBlocking {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            fetchTool = object : FetchTool {
                override suspend fun fetch(payload: String): String = "unused"
                override suspend fun fetchWithOutcome(payload: String): FetchOutcome {
                    assertEquals("""{"url":"https://example.com","max_chars":500}""", payload)
                    return FetchOutcome(
                        message = "Fetch completed for https://example.com.",
                        errorCategory = FetchErrorCategory.NONE
                    )
                }
            },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 4,
                urgency = Urgency.HIGH,
                type = ActionType.WEBSITE_FETCH,
                payload = """{"url":"https://example.com","max_chars":500}""",
                summary = "fetch page",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            searchResultCount = 1
        )

        assertEquals("Fetch completed for https://example.com.", outcome.statusSummary)
        assertEquals("none", outcome.fetchErrorCategory)
    }

    @Test
    fun `fetch propagates non retryable error category through action outcome`() = runBlocking {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            fetchTool = object : FetchTool {
                override suspend fun fetch(payload: String): String = "unused"
                override suspend fun fetchWithOutcome(payload: String): FetchOutcome =
                    FetchOutcome(
                        message = "Fetch tool returned an error: 403 Forbidden",
                        errorCategory = FetchErrorCategory.NON_RETRYABLE
                    )
            },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 5,
                urgency = Urgency.HIGH,
                type = ActionType.WEBSITE_FETCH,
                payload = """{"url":"https://example.com"}""",
                summary = "fetch page",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            searchResultCount = 1
        )

        assertEquals("non_retryable", outcome.fetchErrorCategory)
    }

    @Test
    fun `fetch propagates malformed request category through action outcome`() = runBlocking {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            fetchTool = object : FetchTool {
                override suspend fun fetch(payload: String): String = "unused"
                override suspend fun fetchWithOutcome(payload: String): FetchOutcome =
                    FetchOutcome(
                        message = "Fetch payload is invalid.",
                        errorCategory = FetchErrorCategory.MALFORMED_REQUEST
                    )
            },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 6,
                urgency = Urgency.MEDIUM,
                type = ActionType.WEBSITE_FETCH,
                payload = "bad payload",
                summary = "fetch page",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            searchResultCount = 1
        )

        assertEquals("malformed_request", outcome.fetchErrorCategory)
    }

    @Test
    fun `startup smoke test exposes per-action availability`() = runBlocking {
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
            fetchTool = object : FetchTool {
                override suspend fun fetch(payload: String): String = "unused"

                override suspend fun healthCheck(): ToolHealthStatus =
                    ToolHealthStatus(available = true, detail = "fetch ok")
            },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val statuses = cortex.startupSmokeTest()
        val byType = statuses.associateBy { it.actionType }

        assertEquals(true, byType[ActionType.CONTACT_USER]?.available)
        assertEquals(false, byType[ActionType.WEB_SEARCH]?.available)
        assertEquals(true, byType[ActionType.WEBSITE_FETCH]?.available)
        assertEquals(
            setOf(ActionType.CONTACT_USER, ActionType.RESOLUTION_DRAFT, ActionType.WEBSITE_FETCH, ActionType.REFLECT_INTERNAL, ActionType.REFLECT_EVIDENCE),
            cortex.availableActionTypes()
        )
    }

    @Test
    fun `private commit action without authorization is rejected by motor cortex`() = runBlocking {
        val cortex = MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult("unused", emptyList())
                }
            ),
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

        val outcome = cortex.execute(
            PendingAction(
                id = 7,
                urgency = Urgency.HIGH,
                type = ActionType("email_send"),
                payload = """{"to":["user@example.com"],"subject":"Status","body_text":"Done."}""",
                summary = "send email",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            searchResultCount = 1
        )

        assertFalse(outcome.successful)
        assertEquals("commit_authorization_required", outcome.actionErrorCategory)
    }
}
