package ai.neopsyke.memory.pgvector.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class RememberToolTest {

    @Test
    fun `extractInlineTags parses comma-separated tags`() {
        val tags = extractInlineTags("some memory text tags=foo,bar,baz")
        assertEquals(listOf("foo", "bar", "baz"), tags)
    }

    @Test
    fun `extractInlineTags returns empty for no tags`() {
        val tags = extractInlineTags("some memory text without tags")
        assertEquals(emptyList(), tags)
    }

    @Test
    fun `extractInlineTags handles single tag`() {
        val tags = extractInlineTags("text tags=single")
        assertEquals(listOf("single"), tags)
    }

    @Test
    fun `removeInlineTags strips tags suffix`() {
        val cleaned = removeInlineTags("summary (source=ego; confidence=0.85 tags=a,b)")
        assertEquals("summary (source=ego; confidence=0.85)", cleaned)
    }

    @Test
    fun `removeInlineTags is no-op when no tags present`() {
        val text = "memory text with no tags"
        assertEquals(text, removeInlineTags(text))
    }

    @Test
    fun `normalizeFingerprint lowercases and collapses whitespace`() {
        val fp = normalizeFingerprint("  Hello   World  ")
        assertEquals("hello world", fp)
    }

    @Test
    fun `normalizeFingerprint handles tabs and newlines`() {
        val fp = normalizeFingerprint("hello\t\tworld\nfoo")
        assertEquals("hello world foo", fp)
    }
}
