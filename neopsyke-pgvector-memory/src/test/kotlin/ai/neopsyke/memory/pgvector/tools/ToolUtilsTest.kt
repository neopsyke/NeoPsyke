package ai.neopsyke.memory.pgvector.tools

import kotlinx.serialization.json.JsonPrimitive
import ai.neopsyke.memory.pgvector.db.MemoryWriteMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolUtilsTest {

    @Test
    fun `resolveNamespace falls back when no namespace args are provided`() {
        val namespace = resolveNamespace(emptyMap(), defaultNamespace = "neopsyke")
        assertEquals("neopsyke", namespace)
    }

    @Test
    fun `resolveNamespace prefers explicit namespace argument`() {
        val namespace = resolveNamespace(
            mapOf("namespace" to JsonPrimitive("codex.project.alpha")),
            defaultNamespace = "neopsyke"
        )
        assertEquals("codex.project.alpha", namespace)
    }

    @Test
    fun `sanitizeNamespace normalizes whitespace and special chars`() {
        val namespace = sanitizeNamespace("  codex project/@alpha  ", fallback = "default")
        assertEquals("codex_project--alpha", namespace)
    }

    @Test
    fun `sanitizeNamespace falls back for effectively empty values`() {
        val namespace = sanitizeNamespace("***", fallback = "default")
        assertEquals("default", namespace)
    }

    @Test
    fun `resolveWriteMode defaults to append`() {
        val mode = resolveWriteMode(emptyMap())
        assertEquals(MemoryWriteMode.APPEND, mode)
    }

    @Test
    fun `resolveWriteMode accepts supported mode`() {
        val mode = resolveWriteMode(mapOf("write_mode" to JsonPrimitive("upsert_fact")))
        assertEquals(MemoryWriteMode.UPSERT_FACT, mode)
    }

    @Test
    fun `sanitizeFactKey normalizes format`() {
        val key = sanitizeFactKey(" Profile Name / Preferred ")
        assertEquals("profile_name_-_preferred", key)
    }

    @Test
    fun `sanitizeFactKey returns null when empty after cleanup`() {
        val key = sanitizeFactKey("****")
        assertEquals(null, key)
    }
}
