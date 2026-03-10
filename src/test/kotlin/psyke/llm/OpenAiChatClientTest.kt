package psyke.llm

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
import kotlin.test.assertTrue

class OpenAiChatClientTest {
    @Test
    fun `chat calls only chat completions endpoint`() {
        val seenPaths = mutableListOf<String>()
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            seenPaths += request.url.encodedPath
            assertTrue(body.contains("\"max_tokens\":64"))
            assertTrue(body.contains("\"temperature\":0.2"))
            assertTrue(!body.contains("omni-moderation-latest"))
            200 to """
            {
              "id": "chat-1",
              "model": "gpt-4o-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "hello"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 11, "completion_tokens": 4, "total_tokens": 15}
            }
            """.trimIndent()
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(
                    temperature = 0.2,
                    maxTokens = 64,
                    metadata = ChatCallMetadata(actor = "ego", callSite = "unit")
                )
            )
            assertEquals("hello", completion.content)
            assertEquals("gpt-4o-mini", completion.model)
            assertEquals(15, completion.usage?.totalTokens)
        }

        assertEquals(listOf("/v1/chat/completions"), seenPaths)
        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.OK, record.status)
        assertEquals("ego", record.metadata.actor)
        assertEquals("unit", record.metadata.callSite)
        assertEquals(15, record.totalTokens)
    }

    @Test
    fun `chat uses max completion tokens for gpt5 models`() {
        val bodies = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            200 to """
            {
              "id": "chat-2",
              "model": "gpt-5-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10}
            }
            """.trimIndent()
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-5-mini",
            httpClient = httpClient
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(maxTokens = 77)
            )
            assertEquals("ok", completion.content)
        }

        assertEquals(1, bodies.size)
        assertTrue(bodies.first().contains("\"max_completion_tokens\":77"))
        assertTrue(!bodies.first().contains("\"max_tokens\":77"))
    }

    @Test
    fun `chat adapts unsupported schema keywords before sending response_format`() {
        val bodies = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            200 to """
            {
              "id": "chat-2b",
              "model": "gpt-4o-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "{\"ok\":true}"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10}
            }
            """.trimIndent()
        }
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "test_schema",
            schemaJson = """
                {
                  "type": "object",
                  "properties": {
                    "ok": { "type": "boolean" }
                  },
                  "allOf": [
                    {
                      "if": { "properties": { "ok": { "const": true } } },
                      "then": { "required": ["ok"] }
                    }
                  ]
                }
            """.trimIndent(),
            strict = true
        )

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-4o-mini",
            httpClient = httpClient
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(responseFormat = responseFormat)
            )
            assertEquals("""{"ok":true}""", completion.content)
        }

        assertEquals(1, bodies.size)
        assertTrue(bodies.first().contains("\"response_format\""))
        assertTrue(!bodies.first().contains("\"allOf\""))
    }

    @Test
    fun `chat normalizes strict object required fields for openai response_format`() {
        val bodies = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            200 to """
            {
              "id": "chat-2c",
              "model": "gpt-4o-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "{\"allow\":true,\"reason\":null,\"reason_code\":null,\"confidence\":0.8,\"policy_risk\":\"low\"}"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10}
            }
            """.trimIndent()
        }
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "superego_review",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["allow", "confidence", "policy_risk"],
                  "properties": {
                    "allow": { "type": "boolean" },
                    "reason": { "type": "string", "maxLength": 180 },
                    "reason_code": { "type": "string" },
                    "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
                    "policy_risk": { "type": "string", "enum": ["low", "medium", "high"] }
                  }
                }
            """.trimIndent(),
            strict = true
        )

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-4o-mini",
            httpClient = httpClient
        ).use { client ->
            client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(responseFormat = responseFormat)
            )
        }

        assertEquals(1, bodies.size)
        val requestJson = jacksonObjectMapper().readTree(bodies.first())
        val required = requestJson
            .path("response_format")
            .path("json_schema")
            .path("schema")
            .path("required")
        val requiredSet = required.map { it.asText() }.toSet()
        assertTrue(requiredSet.contains("reason"))
        assertTrue(requiredSet.contains("reason_code"))
    }

    @Test
    fun `chat retries with max completion tokens when max tokens unsupported`() {
        var calls = 0
        val bodies = mutableListOf<String>()
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            calls += 1
            if (calls == 1) {
                400 to """
                {
                  "error": {
                    "message": "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.",
                    "type": "invalid_request_error",
                    "param": "max_tokens",
                    "code": "unsupported_parameter"
                  }
                }
                """.trimIndent()
            } else {
                200 to """
                {
                  "id": "chat-3",
                  "model": "gpt-4o-mini",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "retry ok"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """.trimIndent()
            }
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-4o-mini",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                options = ChatRequestOptions(maxTokens = 42)
            )
            assertEquals("retry ok", completion.content)
        }

        assertEquals(2, calls)
        assertTrue(bodies[0].contains("\"max_tokens\":42"))
        assertTrue(bodies[1].contains("\"max_completion_tokens\":42"))
        assertTrue(!bodies[1].contains("\"max_tokens\":42"))
        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.OK, record.status)
        assertEquals(15, record.totalTokens)
    }

    @Test
    fun `chat retries without temperature when unsupported`() {
        var calls = 0
        val bodies = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            calls += 1
            if (calls == 1) {
                400 to """
                {
                  "error": {
                    "message": "Unsupported parameter: 'temperature' is not supported with this model.",
                    "type": "invalid_request_error",
                    "param": "temperature",
                    "code": "unsupported_parameter"
                  }
                }
                """.trimIndent()
            } else {
                200 to """
                {
                  "id": "chat-4",
                  "model": "gpt-4o-mini",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "temp ok"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
                }
                """.trimIndent()
            }
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-4o-mini",
            httpClient = httpClient
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                options = ChatRequestOptions(temperature = 0.4)
            )
            assertEquals("temp ok", completion.content)
        }

        assertEquals(2, calls)
        assertTrue(bodies[0].contains("\"temperature\":0.4"))
        assertTrue(!bodies[1].contains("\"temperature\":0.4"))
    }

    @Test
    fun `chat retries without temperature when unsupported value is returned`() {
        var calls = 0
        val bodies = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            calls += 1
            if (calls == 1) {
                400 to """
                {
                  "error": {
                    "message": "Unsupported value: 'temperature' does not support 0.0 with this model. Only the default (1) value is supported.",
                    "type": "invalid_request_error",
                    "param": "temperature",
                    "code": "unsupported_value"
                  }
                }
                """.trimIndent()
            } else {
                200 to """
                {
                  "id": "chat-5",
                  "model": "gpt-5-mini",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "value ok"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 9, "completion_tokens": 3, "total_tokens": 12}
                }
                """.trimIndent()
            }
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-4o-mini",
            httpClient = httpClient
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                options = ChatRequestOptions(temperature = 0.0)
            )
            assertEquals("value ok", completion.content)
        }

        assertEquals(2, calls)
        assertTrue(bodies[0].contains("\"temperature\":0.0"))
        assertTrue(!bodies[1].contains("\"temperature\":0.0"))
    }

    @Test
    fun `chat omits temperature by default for gpt5 models`() {
        val bodies = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            bodies += body
            200 to """
            {
              "id": "chat-5b",
              "model": "gpt-5-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 9, "completion_tokens": 3, "total_tokens": 12}
            }
            """.trimIndent()
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-5-mini",
            httpClient = httpClient
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                options = ChatRequestOptions(temperature = 0.0, maxTokens = 33)
            )
            assertEquals("ok", completion.content)
        }

        assertEquals(1, bodies.size)
        assertTrue(!bodies[0].contains("\"temperature\":"))
        assertTrue(bodies[0].contains("\"max_completion_tokens\":33"))
    }

    @Test
    fun `chat reports observer error metadata on http failures`() {
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient {
            422 to """{"message":"bad request"}"""
        }
        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
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
    fun `chat falls back to first non-empty choice content`() {
        val httpClient = fakeHttpClient {
            200 to """
            {
              "id": "chat-6",
              "model": "gpt-5-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "   "}, "finish_reason": "length"},
                {"index": 1, "message": {"role": "assistant", "content": "usable"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
            }
            """.trimIndent()
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-5-mini",
            httpClient = httpClient
        ).use { client ->
            val completion = client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")))
            assertEquals("usable", completion.content)
            assertEquals("stop", completion.finishReason)
        }
    }

    @Test
    fun `chat includes response-shape diagnostics when content is empty`() {
        val httpClient = fakeHttpClient {
            200 to """
            {
              "id": "chat-7",
              "model": "gpt-5-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "", "refusal": "blocked"}, "finish_reason": "stop"}
              ]
            }
            """.trimIndent()
        }

        OpenAiChatClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            modelName = "gpt-5-mini",
            httpClient = httpClient
        ).use { client ->
            val ex = assertFailsWith<Exception> {
                client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")))
            }
            assertTrue(ex.message.orEmpty().contains("empty message content"))
            assertTrue(ex.message.orEmpty().contains("finish_reason=stop"))
            assertTrue(ex.message.orEmpty().contains("refusal_chars=7"))
        }
    }

    private fun fakeHttpClient(
        responder: (Request) -> Pair<Int, String>,
    ): OkHttpClient {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val (status, body) = responder(request)
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
