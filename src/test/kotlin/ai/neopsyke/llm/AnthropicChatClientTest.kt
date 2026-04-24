package ai.neopsyke.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicChatClientTest {
    @Test
    fun `chat builds anthropic messages request and parses successful response`() {
        var capturedRequest: Request? = null
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient(status = 200) { request ->
            capturedRequest = request
            """
            {
              "id": "msg_123",
              "model": "claude-sonnet-4-20250514",
              "content": [
                { "type": "text", "text": "{\"decision\":\"answer\"}" }
              ],
              "stop_reason": "end_turn",
              "usage": {
                "input_tokens": 21,
                "output_tokens": 9
              }
            }
            """.trimIndent()
        }
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "planner_decision",
            schemaJson = """
                {
                  "type": "object",
                  "properties": {
                    "decision": { "type": "string" }
                  },
                  "required": ["decision"],
                  "additionalProperties": false
                }
            """.trimIndent()
        )

        AnthropicChatClient(
            apiKey = "anthropic-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val completion = client.chat(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "You are a planner."),
                    ChatMessage(ChatRole.USER, "hi")
                ),
                options = ChatRequestOptions(
                    temperature = 0.1,
                    maxTokens = 88,
                    responseFormat = responseFormat,
                    metadata = ChatCallMetadata(actor = "ego", callSite = "unit")
                )
            )

            assertEquals("""{"decision":"answer"}""", completion.content)
            assertEquals("claude-sonnet-4-20250514", completion.model)
            assertEquals("end_turn", completion.finishReason)
            assertEquals(30, completion.usage?.totalTokens)
        }

        val request = assertNotNull(capturedRequest)
        assertEquals("anthropic-key", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertTrue(request.url.toString().endsWith("/v1/messages"))
        val requestJson = jacksonObjectMapper().readTree(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals("You are a planner.", requestJson.path("system").asText())
        assertEquals(88, requestJson.path("max_tokens").asInt())
        assertEquals("user", requestJson.path("messages").first().path("role").asText())
        assertEquals("hi", requestJson.path("messages").first().path("content").asText())
        assertEquals("json_schema", requestJson.path("output_config").path("format").path("type").asText())
        assertEquals("string", requestJson.path("output_config").path("format").path("schema").path("properties").path("decision").path("type").asText())

        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.OK, record.status)
        assertEquals("ego", record.metadata.actor)
        assertEquals("unit", record.metadata.callSite)
        assertEquals(21, record.promptTokens)
        assertEquals(30, record.totalTokens)
    }

    @Test
    fun `chat reports observer error metadata on anthropic http failures`() {
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient(status = 400) {
            """{"error":{"type":"invalid_request_error","message":"bad schema"}}"""
        }

        AnthropicChatClient(
            apiKey = "anthropic-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val ex = assertFailsWith<Exception> {
                client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")))
            }
            assertTrue(ex.message.orEmpty().contains("status 400"))
        }

        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.ERROR, record.status)
        assertEquals("HTTP_400", record.errorCode)
        assertTrue(record.errorMessage.orEmpty().contains("bad schema"))
    }

    @Test
    fun `chat rejects anthropic calls with only system messages`() {
        val httpClient = fakeHttpClient(status = 200) { "{}" }

        AnthropicChatClient(
            apiKey = "anthropic-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient
        ).use { client ->
            val ex = assertFailsWith<IllegalArgumentException> {
                client.chat(messages = listOf(ChatMessage(ChatRole.SYSTEM, "rules")))
            }
            assertTrue(ex.message.orEmpty().contains("requires at least one user or assistant"))
        }
    }

    @Test
    fun `reasoning effort maps to thinking budget_tokens`() {
        var capturedBody = ""
        val httpClient = fakeHttpClient(status = 200) { request ->
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            SUCCESSFUL_RESPONSE
        }

        AnthropicChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient,
        ).use { client ->
            client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(reasoningEffort = "low")
            )
        }

        assertTrue(capturedBody.contains("\"thinking\""), "thinking field should be in the request")
        assertTrue(capturedBody.contains("\"budget_tokens\":1024"), "low effort should map to 1024 budget tokens")
        assertTrue(capturedBody.contains("\"type\":\"enabled\""), "thinking type should be enabled")
    }

    @Test
    fun `reasoning effort omitted when not set`() {
        var capturedBody = ""
        val httpClient = fakeHttpClient(status = 200) { request ->
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            SUCCESSFUL_RESPONSE
        }

        AnthropicChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient,
        ).use { client ->
            client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions()
            )
        }

        assertTrue(!capturedBody.contains("thinking"), "thinking field should not be in the request when effort is null")
    }

    companion object {
        private val SUCCESSFUL_RESPONSE = """
            {
              "id": "msg_1",
              "model": "claude-sonnet-4-20250514",
              "content": [{"type": "text", "text": "ok"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 2}
            }
        """.trimIndent()
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
