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

class OllamaChatClientTest {
    @Test
    fun `chat builds native ollama request without auth for local mode`() {
        var capturedRequest: Request? = null
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient(status = 200) { request ->
            capturedRequest = request
            """
            {
              "model": "gpt-oss",
              "message": {
                "role": "assistant",
                "content": "{\"decision\":\"answer\"}"
              },
              "done": true,
              "done_reason": "stop",
              "prompt_eval_count": 13,
              "eval_count": 6
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

        OllamaChatClient(
            apiKey = "",
            baseUrl = "http://mock.test/api",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val completion = client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hi")),
                options = ChatRequestOptions(
                    temperature = 0.2,
                    maxTokens = 55,
                    responseFormat = responseFormat,
                    metadata = ChatCallMetadata(actor = "ego", callSite = "unit")
                )
            )

            assertEquals("""{"decision":"answer"}""", completion.content)
            assertEquals("gpt-oss", completion.model)
            assertEquals("stop", completion.finishReason)
            assertEquals(19, completion.usage?.totalTokens)
        }

        val request = assertNotNull(capturedRequest)
        assertNull(request.header("Authorization"))
        assertTrue(request.url.toString().endsWith("/api/chat"))
        val requestJson = jacksonObjectMapper().readTree(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(false, requestJson.path("stream").asBoolean())
        assertEquals(0.2, requestJson.path("options").path("temperature").asDouble())
        assertEquals(55, requestJson.path("options").path("num_predict").asInt())
        assertEquals("string", requestJson.path("format").path("properties").path("decision").path("type").asText())

        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.OK, record.status)
        assertEquals("ego", record.metadata.actor)
        assertEquals(13, record.promptTokens)
        assertEquals(19, record.totalTokens)
    }

    @Test
    fun `chat reports observer error metadata on ollama http failures`() {
        var observed: ChatCallRecord? = null
        val httpClient = fakeHttpClient(status = 500) {
            """{"error":"model unavailable"}"""
        }

        OllamaChatClient(
            apiKey = "",
            baseUrl = "http://mock.test/api",
            httpClient = httpClient,
            callObserver = ChatCallObserver { observed = it }
        ).use { client ->
            val ex = assertFailsWith<Exception> {
                client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")))
            }
            assertTrue(ex.message.orEmpty().contains("status 500"))
        }

        val record = assertNotNull(observed)
        assertEquals(ChatCallStatus.ERROR, record.status)
        assertEquals("HTTP_500", record.errorCode)
        assertTrue(record.errorMessage.orEmpty().contains("model unavailable"))
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
