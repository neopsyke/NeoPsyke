package ai.neopsyke.agent.memory.episodic

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmLogbookSummarizerTest {

    private fun fakeClient(response: String): ChatModelClient = object : ChatModelClient {
        override val modelName: String = "test-model"
        override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion =
            ChatCompletion(content = response, model = modelName)
    }

    private fun throwingClient(): ChatModelClient = object : ChatModelClient {
        override val modelName: String = "test-model"
        override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion =
            throw RuntimeException("LLM unavailable")
    }

    private val config = AgentConfig()

    @Test
    fun `extractKeywords parses valid LLM response`() {
        val client = fakeClient("""{"keywords":["weather","berlin","temperature"]}""")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val keywords = summarizer.extractKeywords("what is the weather in Berlin?")

        assertEquals(listOf("weather", "berlin", "temperature"), keywords)
    }

    @Test
    fun `extractKeywords falls back on parse failure`() {
        val client = fakeClient("this is not valid json at all")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val keywords = summarizer.extractKeywords("what is the weather in Berlin?")

        // Should fall back to deterministic — which produces lowercase, filtered tokens
        assertTrue(keywords.isNotEmpty(), "Expected fallback keywords")
        assertTrue(keywords.contains("weather"), "Expected deterministic fallback to include 'weather'")
    }

    @Test
    fun `extractKeywords falls back on LLM exception`() {
        val summarizer = LlmLogbookSummarizer(modelClient = throwingClient(), config = config)

        val keywords = summarizer.extractKeywords("what is the weather in Berlin?")

        assertTrue(keywords.isNotEmpty(), "Expected fallback keywords on LLM error")
        assertTrue(keywords.contains("weather"), "Expected deterministic fallback to include 'weather'")
    }

    @Test
    fun `extractKeywords returns empty for blank text`() {
        val client = fakeClient("""{"keywords":["should","not","reach"]}""")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val keywords = summarizer.extractKeywords("   ")

        assertTrue(keywords.isEmpty(), "Expected empty keywords for blank text")
    }

    @Test
    fun `summarizeInput parses valid LLM response`() {
        val client = fakeClient("""{"summary":"User: asking about Berlin weather"}""")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val summary = summarizer.summarizeInput("what is the weather in Berlin?", 200)

        assertEquals("User: asking about Berlin weather", summary)
    }

    @Test
    fun `summarizeInput falls back on blank LLM response`() {
        val client = fakeClient("""{"summary":""}""")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val summary = summarizer.summarizeInput("hello world", 200)

        // Deterministic fallback should produce "User: hello world"
        assertTrue(summary.startsWith("User: "), "Expected User: prefix from fallback")
        assertTrue(summary.contains("hello"), "Expected fallback to contain input content")
    }

    @Test
    fun `summarizeInput falls back on LLM exception`() {
        val summarizer = LlmLogbookSummarizer(modelClient = throwingClient(), config = config)

        val summary = summarizer.summarizeInput("hello world", 200)

        assertTrue(summary.startsWith("User: "), "Expected User: prefix from fallback")
        assertTrue(summary.contains("hello"), "Expected fallback to contain input content")
    }

    @Test
    fun `summarizeInput adds User prefix if missing from LLM response`() {
        val client = fakeClient("""{"summary":"asking about the moon"}""")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val summary = summarizer.summarizeInput("tell me about the moon", 200)

        assertTrue(summary.startsWith("User: "), "Expected User: prefix to be added")
    }

    @Test
    fun `extractKeywords deduplicates and lowercases`() {
        val client = fakeClient("""{"keywords":["Weather","WEATHER","berlin","Berlin"]}""")
        val summarizer = LlmLogbookSummarizer(modelClient = client, config = config)

        val keywords = summarizer.extractKeywords("weather in Berlin")

        assertEquals(listOf("weather", "berlin"), keywords)
    }
}
