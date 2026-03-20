package ai.neopsyke.integrations.mistral.websearch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import ai.neopsyke.llm.ChatCallObserver
import ai.neopsyke.llm.ChatCallRecord
import ai.neopsyke.llm.ChatCallStatus
import ai.neopsyke.support.RecordingInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MistralConversationsWebSearchEngineTest {
    @Test
    fun `agent mode sends agent_id payload and parses structured response`() {
        var capturedBody = ""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                capturedBody = buffer.readUtf8()
                val responseBody = """
                    {
                      "model":"mistral-small-latest",
                      "usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16},
                      "outputs":[
                        {
                          "content":[
                            {
                              "type":"text",
                              "text":"{\"summary\":\"Found docs\",\"snippets\":[\"snippet one\",\"snippet two\"],\"sources\":[{\"title\":\"Kotlin Docs\",\"url\":\"https://kotlinlang.org/docs/home.html\"}]}"
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val recordedCalls = mutableListOf<ChatCallRecord>()
        val instrumentation = RecordingInstrumentation()
        val engine = MistralConversationsWebSearchEngine(
            apiKey = "test-key",
            profile = MistralWebSearchProfile(
                mode = MistralWebSearchMode.AGENT_ID,
                model = "mistral-small-latest",
                agentId = "ag_123"
            ),
            httpClient = httpClient,
            callObserver = ChatCallObserver { recordedCalls += it },
            instrumentation = instrumentation
        )

        val result = engine.search("latest kotlin release", maxResults = 3)

        assertTrue(capturedBody.contains("\"agent_id\":\"ag_123\""))
        assertTrue(capturedBody.contains("\"inputs\""))
        assertTrue(!capturedBody.contains("\"tools\""))
        assertEquals("Found docs", result.summary)
        assertEquals(listOf("snippet one", "snippet two"), result.snippets)
        assertEquals(1, result.sources.size)
        assertEquals("https://kotlinlang.org/docs/home.html", result.sources.first().url)
        assertEquals(1, recordedCalls.size)
        assertEquals(ChatCallStatus.OK, recordedCalls.first().status)
        assertEquals("web_search", recordedCalls.first().metadata.callSite)
        assertTrue(instrumentation.events.any { it.type == "llm_raw_response" })
    }

    @Test
    fun `per-request tools mode sends model and tools payload`() {
        var capturedBody = ""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                capturedBody = buffer.readUtf8()
                val responseBody = """
                    {
                      "model":"mistral-medium-latest",
                      "outputs":[
                        {
                          "content":[
                            {
                              "type":"text",
                              "text":"Top sources: https://example.com/a and https://example.com/b"
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val engine = MistralConversationsWebSearchEngine(
            apiKey = "test-key",
            profile = MistralWebSearchProfile(
                mode = MistralWebSearchMode.PER_REQUEST_TOOLS,
                model = "mistral-medium-latest",
                tool = MistralWebSearchTool.WEB_SEARCH_PREMIUM
            ),
            httpClient = httpClient
        )

        val result = engine.search("news", maxResults = 2)

        assertTrue(capturedBody.contains("\"model\":\"mistral-medium-latest\""))
        assertTrue(capturedBody.contains("\"tools\""))
        assertTrue(capturedBody.contains("\"type\":\"web_search_premium\""))
        assertEquals(2, result.sources.size)
        assertEquals("https://example.com/a", result.sources[0].url)
        assertEquals("https://example.com/b", result.sources[1].url)
        assertTrue(result.summary.contains("Top sources"))
    }

    @Test
    fun `search fails fast on transport errors`() {
        val recordedCalls = mutableListOf<ChatCallRecord>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Error")
                    .body("""{"error":"failed"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val engine = MistralConversationsWebSearchEngine(
            apiKey = "test-key",
            profile = MistralWebSearchProfile(
                mode = MistralWebSearchMode.PER_REQUEST_TOOLS,
                model = "mistral-small-latest"
            ),
            httpClient = httpClient,
            callObserver = ChatCallObserver { recordedCalls += it }
        )

        val result = engine.search("failure case", maxResults = 2)
        assertTrue(result.summary.contains("unavailable", ignoreCase = true))
        assertTrue(result.snippets.isEmpty())
        assertTrue(result.sources.isEmpty())
        assertEquals(ChatCallStatus.ERROR, recordedCalls.single().status)
    }

    @Test
    fun `health check requires agent id in agent mode`() {
        val engine = MistralConversationsWebSearchEngine(
            apiKey = "test-key",
            profile = MistralWebSearchProfile(
                mode = MistralWebSearchMode.AGENT_ID,
                model = "mistral-small-latest",
                agentId = null
            )
        )

        val health = engine.healthCheck()
        assertEquals(false, health.available)
        assertTrue(health.detail.contains("MISTRAL_WEBSEARCH_AGENT_ID"))
        val failed = engine.search("kotlin", 2)
        assertTrue(failed.summary.contains("MISTRAL_WEBSEARCH_AGENT_ID"))
        assertTrue(failed.snippets.isEmpty())
    }

    @Test
    fun `search extracts usage tokens when provided`() {
        val observed = mutableListOf<ChatCallRecord>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val responseBody = """
                    {
                      "model":"mistral-small-latest",
                      "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7},
                      "outputs":[{"content":[{"type":"text","text":"{\"summary\":\"ok\",\"snippets\":[]}"}]}]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val engine = MistralConversationsWebSearchEngine(
            apiKey = "test-key",
            profile = MistralWebSearchProfile(
                mode = MistralWebSearchMode.PER_REQUEST_TOOLS,
                model = "mistral-small-latest"
            ),
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed += it }
        )

        engine.search("usage", 1)
        val call = observed.single()
        assertNotNull(call.promptTokens)
        assertNotNull(call.completionTokens)
        assertNotNull(call.totalTokens)
        assertEquals(3, call.promptTokens)
        assertEquals(4, call.completionTokens)
        assertEquals(7, call.totalTokens)
    }
}
