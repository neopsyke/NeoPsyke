package psyke.agent

import psyke.support.StubChatModelClient
import psyke.llm.ChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MistralWebSearchProviderTest {
    @Test
    fun `search parses json and clamps snippets and summary`() {
        val llm = StubChatModelClient()
        val longSummary = "s".repeat(400)
        val longSnippet = "x".repeat(260)
        llm.enqueueRawResponse(
            """
            {
              "summary":"$longSummary",
              "snippets":["$longSnippet", "two", "three"]
            }
            """.trimIndent()
        )
        val provider = MistralWebSearchProvider(
            modelClient = llm,
            config = AgentConfig(maxCompletionTokens = 99)
        )

        val result = provider.search("latest kotlin", maxResults = 2)
        assertEquals(280, result.summary.length)
        assertEquals(2, result.snippets.size)
        assertEquals(200, result.snippets.first().length)
        assertEquals("two", result.snippets[1])

        assertEquals("ego", llm.lastOptions.metadata.actor)
        assertEquals("web_search", llm.lastOptions.metadata.callSite)
        assertEquals("web_search", llm.lastOptions.metadata.actionType)
        assertEquals(99, llm.lastOptions.maxTokens)
    }

    @Test
    fun `search returns fallback result on parse failures`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse("not-json")
        val provider = MistralWebSearchProvider(
            modelClient = llm,
            config = AgentConfig()
        )

        val result = provider.search("bad response", maxResults = 4)
        assertEquals("Search output could not be parsed.", result.summary)
        assertTrue(result.snippets.isEmpty())
    }

    @Test
    fun `search returns fallback result when model call fails`() {
        val failingClient = object : ChatModelClient {
            override val modelName: String = "failing"

            override fun chat(
                messages: List<psyke.llm.ChatMessage>,
                options: psyke.llm.ChatRequestOptions
            ) = throw IllegalStateException("search unavailable")
        }
        val provider = MistralWebSearchProvider(modelClient = failingClient, config = AgentConfig())

        val result = provider.search("bad response", maxResults = 4)
        assertEquals("Search unavailable due to model error.", result.summary)
        assertTrue(result.snippets.isEmpty())
    }
}
