package psyke.agent

import psyke.agent.memory.longterm.MemoryImprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHippocampusNormalizationTest {
    private val hippocampus = McpHippocampus(
        command = listOf("unused"),
        callTimeoutMs = 50L
    )

    @Test
    fun `normalizer treats empty graph payload as zero hits`() {
        val normalized = hippocampus.normalizeResultText(
            raw = """{"entities":[],"relations":[]}""",
            maxChars = 1200
        )

        assertEquals("", normalized.text)
        assertEquals(0, normalized.hitCount)
        assertFalse(normalized.truncated)
    }

    @Test
    fun `normalizer extracts entity content when present`() {
        val normalized = hippocampus.normalizeResultText(
            raw = """{"entities":[{"name":"favorite color teal"}],"relations":[]}""",
            maxChars = 1200
        )

        assertEquals("- favorite color teal", normalized.text)
        assertEquals(1, normalized.hitCount)
        assertFalse(normalized.truncated)
    }

    @Test
    fun `normalizer prioritizes observations matching query hint`() {
        val normalized = hippocampus.normalizeResultText(
            raw = """
                {
                  "entities":[
                    {
                      "name":"psyke_long_term_memory",
                      "entityType":"memory_log",
                      "observations":[
                        "[memory-eval:old-session:user-preference-color] old fact",
                        "[memory-eval:new-session:user-preference-color] latest fact"
                      ]
                    }
                  ],
                  "relations":[]
                }
            """.trimIndent(),
            maxChars = 1200,
            queryHint = "memory-eval:new-session:user-preference-color"
        )

        assertEquals("- [memory-eval:new-session:user-preference-color] latest fact", normalized.text)
        assertEquals(1, normalized.hitCount)
        assertFalse(normalized.truncated)
    }

    @Test
    fun `plain non-list text does not force a fake hit count`() {
        val normalized = hippocampus.normalizeResultText(
            raw = "memory service unavailable",
            maxChars = 1200
        )

        assertEquals("memory service unavailable", normalized.text)
        assertEquals(0, normalized.hitCount)
        assertFalse(normalized.truncated)
    }

    @Test
    fun `build observation deletions only includes eval tagged observations`() {
        val deletions = hippocampus.buildObservationDeletions(
            rawGraph = """
                {
                  "entities":[
                    {
                      "name":"psyke_long_term_memory",
                      "entityType":"memory_log",
                      "observations":[
                        "[memory-eval:session-x:task-a] eval memory",
                        "real user memory should remain"
                      ]
                    }
                  ],
                  "relations":[]
                }
            """.trimIndent(),
            tagMarkers = listOf("[memory-eval:")
        )

        assertEquals(1, deletions.size)
        val deletion = deletions.single()
        assertEquals("psyke_long_term_memory", deletion["entityName"])
        @Suppress("UNCHECKED_CAST")
        val observations = deletion["observations"] as List<String>
        assertEquals(listOf("[memory-eval:session-x:task-a] eval memory"), observations)
    }

    @Test
    fun `generic imprint argument candidates stamp agent self fact subject`() {
        val candidates = hippocampus.buildImprintArgumentCandidates(
            toolName = "create_memory",
            summary = "I learned: the user prefers concise answers.",
            imprint = MemoryImprint(
                summary = "I learned: the user prefers concise answers.",
                source = "ego_long_term_memory_assessment",
                confidence = 0.9,
                tags = listOf("preference")
            )
        )

        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            assertEquals("me", candidate["fact_subject"])
            assertEquals("me", candidate["subject"])
        }
    }

    @Test
    fun `remember tool imprint candidates stamp agent self fact subject`() {
        val candidates = hippocampus.buildImprintArgumentCandidates(
            toolName = "remember",
            summary = "I should remember that the user's timezone is Europe-Berlin.",
            imprint = MemoryImprint(
                summary = "I should remember that the user's timezone is Europe-Berlin.",
                source = "ego_long_term_memory_assessment",
                confidence = 0.9
            )
        )

        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            assertEquals("me", candidate["fact_subject"])
            assertEquals("me", candidate["subject"])
        }
    }
}
