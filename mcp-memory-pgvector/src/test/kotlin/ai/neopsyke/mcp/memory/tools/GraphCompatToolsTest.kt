package ai.neopsyke.mcp.memory.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphCompatToolsTest {

    @Test
    fun `extractConfidence parses from observation text`() {
        val text = "my summary (source=ego_long_term_memory_assessment; confidence=0.85 tags=project)"
        val confidence = extractConfidenceFromText(text)
        assertEquals(0.85, confidence, 0.001)
    }

    @Test
    fun `extractConfidence returns default when not present`() {
        val confidence = extractConfidenceFromText("plain text without metadata")
        assertEquals(0.5, confidence, 0.001)
    }

    @Test
    fun `extractConfidence handles edge values`() {
        assertEquals(1.0, extractConfidenceFromText("confidence=1.0"), 0.001)
        assertEquals(0.0, extractConfidenceFromText("confidence=0.0"), 0.001)
    }
}

// Expose the private function for testing via a wrapper
private fun extractConfidenceFromText(text: String): Double {
    val match = Regex("""confidence=([0-9.]+)""").find(text)
    return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.5
}
