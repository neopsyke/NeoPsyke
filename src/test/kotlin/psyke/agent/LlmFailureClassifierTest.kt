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
}
