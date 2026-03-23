package ai.neopsyke.llm

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiModerationClientTest {
    @Test
    fun `moderateWithOpenAi uses omni moderation model by default`() {
        val capturedBody = mutableListOf<String>()
        val httpClient = fakeHttpClient { request ->
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            capturedBody += body
            assertEquals("/v1/moderations", request.url.encodedPath)
            200 to """
            {
              "id": "modr-1",
              "model": "omni-moderation-latest",
              "results": [{"flagged": false, "categories": {"violence": false}}]
            }
            """.trimIndent()
        }

        val decision = moderateWithOpenAi(
            input = "hello world",
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient
        )

        assertFalse(decision.flagged)
        assertEquals("omni-moderation-latest", decision.model)
        assertTrue(capturedBody.first().contains("\"model\":\"omni-moderation-latest\""))
        assertTrue(capturedBody.first().contains("\"input\":\"hello world\""))
    }

    @Test
    fun `moderation client returns flagged categories`() {
        val httpClient = fakeHttpClient {
            200 to """
            {
              "id": "modr-2",
              "model": "omni-moderation-latest",
              "results": [{"flagged": true, "categories": {"hate": true, "violence": false}}]
            }
            """.trimIndent()
        }
        OpenAiModerationClient(
            apiKey = "test-key",
            baseUrl = "https://mock.test/v1",
            httpClient = httpClient
        ).use { client ->
            val decision = client.moderate("unsafe")
            assertTrue(decision.flagged)
            assertEquals(setOf("hate"), decision.categories)
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
