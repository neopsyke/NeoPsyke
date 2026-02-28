package psyke.agent.actions.websearch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSearchActionHandlerTest {
    @Test
    fun `execute formats summary snippets and top three sources`() {
        val handler = WebSearchActionHandler(
            engine = object : WebSearchEngine {
                override fun search(query: String, maxResults: Int): WebSearchResult {
                    assertEquals("kotlin release notes", query)
                    assertEquals(5, maxResults)
                    return WebSearchResult(
                        summary = "Kotlin 2.3 release notes are available.",
                        snippets = listOf("JetBrains post", "What's new", "Migration guidance"),
                        sources = listOf(
                            WebSearchSource("JetBrains Blog", "https://blog.jetbrains.com/kotlin"),
                            WebSearchSource("Kotlin Docs", "https://kotlinlang.org/docs/whatsnew23.html"),
                            WebSearchSource("GitHub", "https://github.com/JetBrains/kotlin/releases"),
                            WebSearchSource("Extra", "https://example.com/extra")
                        )
                    )
                }
            }
        )

        val outcome = handler.execute("kotlin release notes", 5)
        assertTrue(outcome.statusSummary.contains("Web search summary: Kotlin 2.3 release notes are available."))
        assertTrue(outcome.statusSummary.contains("JetBrains post | What's new | Migration guidance"))
        assertTrue(outcome.statusSummary.contains("Kotlin Docs - https://kotlinlang.org/docs/whatsnew23.html"))
        assertTrue(outcome.statusSummary.contains("JetBrains Blog - https://blog.jetbrains.com/kotlin"))
        assertTrue(outcome.statusSummary.contains("GitHub - https://github.com/JetBrains/kotlin/releases"))
        assertTrue(!outcome.statusSummary.contains("https://example.com/extra"))
        assertTrue(outcome.statusSummary.contains("source_confidence: medium"))
        assertTrue(outcome.plannerSignal.contains("key_sources"))
        assertEquals(true, outcome.observedEvidence)
    }

    @Test
    fun `execute renders no snippets and no sources when result is empty`() {
        val handler = WebSearchActionHandler(
            engine = object : WebSearchEngine {
                override fun search(query: String, maxResults: Int): WebSearchResult =
                    WebSearchResult(
                        summary = "Search unavailable.",
                        snippets = emptyList(),
                        sources = emptyList()
                    )
            }
        )

        val outcome = handler.execute("status", 3)
        assertTrue(outcome.statusSummary.contains("snippets: no snippets"))
        assertTrue(outcome.statusSummary.contains("sources: none"))
        assertTrue(outcome.statusSummary.contains("source_confidence: none"))
        assertEquals(false, outcome.observedEvidence)
    }
}
