package psyke.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ConversationModelsTest {

    // --- Interlocutor ---

    @Test
    fun `Interlocutor displayName falls back to id when label is null`() {
        val interlocutor = Interlocutor(id = "abc123")
        assertEquals("abc123", interlocutor.displayName())
    }

    @Test
    fun `Interlocutor displayName returns label when present`() {
        val interlocutor = Interlocutor(id = "abc123", label = "Alice")
        assertEquals("Alice", interlocutor.displayName())
    }

    @Test
    fun `Interlocutor named factory creates id and label`() {
        val interlocutor = Interlocutor.named("Bob")
        assertEquals("Bob", interlocutor.id)
        assertEquals("Bob", interlocutor.label)
        assertEquals("Bob", interlocutor.displayName())
    }

    @Test
    fun `Interlocutor UNKNOWN sentinel has id unknown`() {
        assertEquals("unknown", Interlocutor.UNKNOWN.id)
        assertEquals("unknown", Interlocutor.UNKNOWN.displayName())
    }

    @Test
    fun `Interlocutor data class equality`() {
        val a = Interlocutor(id = "x", label = "X")
        val b = Interlocutor(id = "x", label = "X")
        val c = Interlocutor(id = "y", label = "Y")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // --- ConversationContext ---

    @Test
    fun `ConversationContext default factory`() {
        val ctx = ConversationContext.default()
        assertEquals(ConversationContext.DEFAULT_SESSION_ID, ctx.sessionId)
        assertEquals(Interlocutor.UNKNOWN, ctx.interlocutor)
    }

    @Test
    fun `ConversationContext default factory with interlocutor`() {
        val interlocutor = Interlocutor.named("Alice")
        val ctx = ConversationContext.default(interlocutor)
        assertEquals(ConversationContext.DEFAULT_SESSION_ID, ctx.sessionId)
        assertEquals(interlocutor, ctx.interlocutor)
    }

    @Test
    fun `ConversationContext data class equality`() {
        val a = ConversationContext("s1", Interlocutor.named("A"))
        val b = ConversationContext("s1", Interlocutor.named("A"))
        val c = ConversationContext("s2", Interlocutor.named("A"))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // --- DefaultInterlocutorResolver ---

    @Test
    fun `DefaultInterlocutorResolver returns default interlocutor`() {
        val resolver = DefaultInterlocutorResolver()
        val result = resolver.resolve("chat:1")
        assertEquals("Victor", result.id)
        assertEquals("Victor", result.displayName())
    }

    @Test
    fun `DefaultInterlocutorResolver accepts custom default`() {
        val custom = Interlocutor.named("CustomUser")
        val resolver = DefaultInterlocutorResolver(custom)
        val result = resolver.resolve("stdin", mapOf("extra" to "data"))
        assertEquals(custom, result)
    }
}
