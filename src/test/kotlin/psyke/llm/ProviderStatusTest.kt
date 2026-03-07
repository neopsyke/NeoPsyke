package psyke.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderStatusTest {
    @Test
    fun `buildHealthModelsUrl trims trailing slash from baseUrl`() {
        val url = buildHealthModelsUrl(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
            modelsPath = "models"
        )

        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models", url)
    }

    @Test
    fun `buildHealthModelsUrl trims leading slash from models path`() {
        val url = buildHealthModelsUrl(
            baseUrl = "https://api.groq.com/openai/v1",
            modelsPath = "/models"
        )

        assertEquals("https://api.groq.com/openai/v1/models", url)
    }

    @Test
    fun `deriveGoogleNativeModelsBaseUrl removes openai suffix`() {
        val nativeBaseUrl = deriveGoogleNativeModelsBaseUrl(
            "https://generativelanguage.googleapis.com/v1beta/openai/"
        )

        assertEquals("https://generativelanguage.googleapis.com/v1beta", nativeBaseUrl)
    }

    @Test
    fun `deriveGoogleNativeModelsBaseUrl returns null when baseUrl is not openai compat path`() {
        val nativeBaseUrl = deriveGoogleNativeModelsBaseUrl(
            "https://generativelanguage.googleapis.com/v1beta"
        )

        assertNull(nativeBaseUrl)
    }
}
