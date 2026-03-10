package psyke.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import psyke.agent.support.LlmFailureClassifier

class LlmFailureClassifierTest {
    @Test
    fun `classifies empty-content transport failures`() {
        val error = IllegalStateException(
            "OpenAI chat returned empty message content (finish_reason=length, content_chars=0)."
        )
        assertTrue(LlmFailureClassifier.isEmptyContentTransportFailure(error))
    }

    @Test
    fun `ignores unrelated model errors`() {
        val error = IllegalStateException("HTTP 429 rate limit")
        assertFalse(LlmFailureClassifier.isEmptyContentTransportFailure(error))
    }

    @Test
    fun `classifies structured output schema validation failures`() {
        val error = IllegalStateException(
            """[HTTP_400] Generated JSON does not match the expected schema. Error: jsonschema: '/reason' does not validate"""
        )
        assertTrue(LlmFailureClassifier.isStructuredOutputSchemaValidationFailure(error))
    }

    @Test
    fun `ignores non schema related http 400 errors`() {
        val error = IllegalStateException("[HTTP_400] invalid_api_key")
        assertFalse(LlmFailureClassifier.isStructuredOutputSchemaValidationFailure(error))
    }
}
