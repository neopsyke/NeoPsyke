package ai.neopsyke.integrations.groq.websearch

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
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroqConversationsWebSearchEngineTest {
    @Test
    fun `browser_search mode sends tools payload and parses structured response`() {
        var capturedBody = ""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                capturedBody = buffer.readUtf8()
                val responseBody = """
                    {
                      "model":"openai/gpt-oss-20b",
                      "usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16},
                      "choices":[
                        {
                          "message":{
                            "role":"assistant",
                            "content":"{\"summary\":\"Found docs\",\"snippets\":[\"snippet one\",\"snippet two\"],\"sources\":[{\"title\":\"Kotlin Docs\",\"url\":\"https://kotlinlang.org/docs/home.html\"}]}"
                          }
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
        val engine = GroqConversationsWebSearchEngine(
            apiKey = "test-key",
            model = "openai/gpt-oss-20b",
            httpClient = httpClient,
            callObserver = ChatCallObserver { recordedCalls += it },
            instrumentation = instrumentation
        )

        val result = engine.search("latest kotlin release", maxResults = 3)

        assertTrue(capturedBody.contains("\"model\":\"openai/gpt-oss-20b\""))
        assertTrue(capturedBody.contains("\"tool_choice\":\"required\""))
        assertTrue(capturedBody.contains("\"type\":\"browser_search\""))
        assertTrue(capturedBody.contains("\"reasoning_effort\":\"low\""))
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
    fun `compound mode omits tools and extracts sources from response body`() {
        var capturedBody = ""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                capturedBody = buffer.readUtf8()
                val responseBody = """
                    {
                      "model":"groq/compound-mini",
                      "choices":[
                        {
                          "message":{
                            "role":"assistant",
                            "content":"Top sources for Kotlin updates."
                          }
                        }
                      ],
                      "executed_tools":[
                        {
                          "type":"browser_search",
                          "search_results":[
                            {"title":"Kotlin Docs","url":"https://kotlinlang.org/docs/home.html","snippet":"Official docs"},
                            {"title":"Kotlin Blog","url":"https://blog.jetbrains.com/kotlin/"}
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

        val engine = GroqConversationsWebSearchEngine(
            apiKey = "test-key",
            model = "groq/compound-mini",
            httpClient = httpClient
        )

        val result = engine.search("news", maxResults = 2)

        assertTrue(capturedBody.contains("\"model\":\"groq/compound-mini\""))
        assertTrue(!capturedBody.contains("\"tool_choice\""))
        assertTrue(!capturedBody.contains("\"tools\""))
        assertTrue(!capturedBody.contains("\"reasoning_effort\""))
        assertEquals(2, result.sources.size)
        assertEquals("https://kotlinlang.org/docs/home.html", result.sources[0].url)
        assertEquals("https://blog.jetbrains.com/kotlin/", result.sources[1].url)
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
        val engine = GroqConversationsWebSearchEngine(
            apiKey = "test-key",
            model = "openai/gpt-oss-20b",
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
    fun `search extracts usage tokens when provided`() {
        val observed = mutableListOf<ChatCallRecord>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val responseBody = """
                    {
                      "model":"openai/gpt-oss-20b",
                      "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7},
                      "choices":[{"message":{"role":"assistant","content":"{\"summary\":\"ok\",\"snippets\":[]}"}}]
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
        val engine = GroqConversationsWebSearchEngine(
            apiKey = "test-key",
            model = "openai/gpt-oss-20b",
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

    @Test
    fun `search retries timeout errors and succeeds on later attempt`() {
        var attempts = 0
        val observed = mutableListOf<ChatCallRecord>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts += 1
                if (attempts < 3) {
                    throw SocketTimeoutException("timeout")
                }
                val responseBody = """
                    {
                      "model":"openai/gpt-oss-20b",
                      "usage":{"prompt_tokens":5,"completion_tokens":6,"total_tokens":11},
                      "choices":[{"message":{"role":"assistant","content":"{\"summary\":\"ok\",\"snippets\":[],\"sources\":[]}"}}]
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
        val engine = GroqConversationsWebSearchEngine(
            apiKey = "test-key",
            model = "openai/gpt-oss-20b",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed += it }
        )

        val result = engine.search("timeout then success", 2)

        assertEquals(3, attempts)
        assertEquals("ok", result.summary)
        assertEquals(1, observed.size)
        assertEquals(ChatCallStatus.OK, observed.single().status)
    }
}
