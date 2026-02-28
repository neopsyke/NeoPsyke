package psyke.llm

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroqChatClientTest {
    @Test
    fun `chat builds request and parses successful response`() {
        var capturedRequest: Request? = null
        val httpClient = fakeHttpClient(status = 200) { request ->
            capturedRequest = request
            """
            {
              "id": "resp-1",
              "model": "openai/gpt-oss-20b",
              "choices": [
                {"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}
              ],
              "usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16}
            }
            """.trimIndent()
        }
        var observed: ChatCallRecord? = null
        GroqChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/openai/v1",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(
                    temperature = 0.3,
                    maxTokens = 77,
                    metadata = ChatCallMetadata(actor = "ego", callSite = "unit")
                )
            )

            assertEquals("hello", completion.content)
            assertEquals("openai/gpt-oss-20b", completion.model)
            assertEquals(16, completion.usage?.totalTokens)
            assertEquals("stop", completion.finishReason)
        }

        val request = assertNotNull(capturedRequest)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertTrue(request.url.toString().endsWith("/chat/completions"))
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"max_tokens\":77"))
        assertTrue(body.contains("\"temperature\":0.3"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(!body.contains("\"safe_prompt\""))

        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.OK, record.status)
        assertEquals("ego", record.metadata.actor)
        assertEquals("unit", record.metadata.callSite)
        assertEquals(12, record.promptTokens)
        assertEquals(16, record.totalTokens)
    }

    @Test
    fun `chat reports observer error metadata on http failures`() {
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient(status = 422) {
            """{"message":"bad request"}"""
        }
        GroqChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/openai/v1",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val ex = assertFailsWith<Exception> {
                client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")))
            }
            assertTrue(ex.message.orEmpty().contains("status 422"))
        }

        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.ERROR, record.status)
        assertEquals("HTTP_422", record.errorCode)
        assertTrue(record.errorMessage.orEmpty().contains("bad request"))
    }

    @Test
    fun `chat requires at least one message`() {
        val httpClient = fakeHttpClient(status = 200) { "{}" }
        GroqChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/openai/v1",
            httpClient = httpClient
        ).use { client ->
            assertFailsWith<IllegalArgumentException> {
                client.chat(messages = emptyList())
            }
        }
    }

    @Test
    fun `chat persists metrics without explicit observer`() {
        val dbPath = Files.createTempFile("groq-client-metrics", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            val httpClient = fakeHttpClient(status = 200) {
                """
                {
                  "id": "resp-1",
                  "model": "openai/gpt-oss-20b",
                  "choices": [
                    {"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}
                  ],
                  "usage":{"prompt_tokens":4,"completion_tokens":6,"total_tokens":10}
                }
                """.trimIndent()
            }
            GroqChatClient(
                apiKey = "test-key",
                baseUrl = "https://mock.test/openai/v1",
                httpClient = httpClient
            ).use { client ->
                client.chat(messages = listOf(ChatMessage(ChatRole.USER, "persist this")))
            }

            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COALESCE(SUM(total_tokens), 0) AS tokens FROM llm_calls WHERE provider = 'groq'"
                    ).use { rs ->
                        rs.next()
                        assertTrue(rs.getInt("tokens") >= 10)
                    }
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty("psyke.metrics.db")
            } else {
                System.setProperty("psyke.metrics.db", previous)
            }
            Files.deleteIfExists(dbPath)
            Files.deleteIfExists(dbPath.resolveSibling("metrics.salt"))
        }
    }

    private fun fakeHttpClient(
        status: Int,
        bodyProvider: (Request) -> String,
    ): OkHttpClient {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = bodyProvider(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message(if (status in 200..299) "OK" else "ERR")
                        .body(body.toResponseBody(mediaType))
                        .build()
                }
            )
            .build()
    }
}
