package ai.neopsyke.integrations.mistral.websearch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MistralWebSearchAgentSessionTest {
    @Test
    fun `provided agent id is reused without create or delete calls`() {
        var calls = 0
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("should not be called")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        MistralWebSearchAgentSession.start(
            apiKey = "test-key",
            model = "mistral-small-latest",
            providedAgentId = "ag_existing",
            httpClient = httpClient
        ).use { session ->
            assertEquals("ag_existing", session.agentId)
        }

        assertEquals(0, calls)
    }

    @Test
    fun `session creates and deletes ephemeral agent when id is missing`() {
        val methods = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                paths += request.url.encodedPath
                when {
                    request.method == "POST" && request.url.encodedPath.endsWith("/agents") -> {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"id":"ag_created"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }

                    request.method == "DELETE" && request.url.encodedPath.endsWith("/agents/ag_created") -> {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(204)
                            .message("No Content")
                            .body("".toResponseBody("application/json".toMediaType()))
                            .build()
                    }

                    else -> {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        MistralWebSearchAgentSession.start(
            apiKey = "test-key",
            model = "mistral-small-latest",
            providedAgentId = null,
            httpClient = httpClient
        ).use { session ->
            assertEquals("ag_created", session.agentId)
        }

        assertEquals(listOf("POST", "DELETE"), methods)
        assertEquals(listOf("/v1/agents", "/v1/agents/ag_created"), paths)
    }

    @Test
    fun `session keeps null id when ephemeral creation fails`() {
        val methods = mutableListOf<String>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Error")
                    .body("""{"error":"failed"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        MistralWebSearchAgentSession.start(
            apiKey = "test-key",
            model = "mistral-small-latest",
            providedAgentId = null,
            httpClient = httpClient
        ).use { session ->
            assertNull(session.agentId)
        }

        assertEquals(listOf("POST"), methods)
    }
}
