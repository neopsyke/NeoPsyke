package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotorCortexTest {
    @Test
    fun `answer action writes output and returns assistant output`() {
        val captured = mutableListOf<String>()
        val cortex = MotorCortex(
            webSearchProvider = object : WebSearchProvider {
                override fun search(query: String, maxResults: Int): WebSearchResult =
                    WebSearchResult("unused", emptyList())
            },
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
            webSearchProvider = object : WebSearchProvider {
                override fun search(query: String, maxResults: Int): WebSearchResult {
                    assertEquals("kotlin testing", query)
                    assertEquals(2, maxResults)
                    return WebSearchResult(
                        summary = "Found docs",
                        snippets = listOf("snippet one", "snippet two")
                    )
                }
            }
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
        assertNull(outcome.assistantOutput)
    }
}
