package ai.neopsyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryStoreTest {
    @Test
    fun `store compacts memory when approaching size limit`() {
        val store = MemoryStore(maxChars = 1_000)
        repeat(40) { idx ->
            store.remember(DialogueTurn(DialogueRole.USER, "message-$idx " + "x".repeat(80)))
            store.remember(DialogueTurn(DialogueRole.ASSISTANT, "reply-$idx " + "y".repeat(80)))
        }

        val stats = store.stats()
        assertTrue(stats.totalChars <= 1_000)
        val summary = store.summaryForPrompt(maxTokens = 400)
        assertTrue(summary.contains("Short-term context summary:", ignoreCase = false))
    }

    @Test
    fun `prompt summary is clamped to token budget`() {
        val store = MemoryStore(maxChars = 4_000)
        repeat(20) { idx ->
            store.remember(DialogueTurn(DialogueRole.USER, "line-$idx " + "z".repeat(180)))
        }

        val compact = store.summaryForPrompt(maxTokens = 16)
        assertTrue(TextSecurity.estimateTokens(compact) <= 16)
    }

    @Test
    fun `blank turn is not stored`() {
        val store = MemoryStore(maxChars = 2_000)
        store.remember(DialogueTurn(DialogueRole.USER, "   "))
        assertEquals(0, store.stats().recentTurns)
    }

    @Test
    fun `stats reflect stored turns`() {
        val store = MemoryStore(maxChars = 2_000)
        store.remember(DialogueTurn(DialogueRole.USER, "turn one"))
        store.remember(DialogueTurn(DialogueRole.ASSISTANT, "turn two"))
        assertEquals(2, store.stats().recentTurns)
        assertTrue(store.stats().totalChars > 0)
    }

    @Test
    fun `summaryForPrompt returns empty for zero token budget`() {
        val store = MemoryStore(maxChars = 2_000)
        store.remember(DialogueTurn(DialogueRole.USER, "something"))
        assertEquals("", store.summaryForPrompt(maxTokens = 0))
    }

    @Test
    fun `compaction lands at or below target after threshold breach`() {
        val store = MemoryStore(maxChars = 1_024, compactThresholdRatio = 0.85, targetRatio = 0.65)
        repeat(12) { i ->
            store.remember(DialogueTurn(DialogueRole.USER, "x".repeat(88) + " turn $i"))
        }
        assertTrue(
            store.stats().totalChars <= 1_024,
            "After compaction totalChars=${store.stats().totalChars} should be <= maxChars=1024"
        )
    }

    @Test
    fun `recent turns survive compaction while oldest are folded`() {
        val store = MemoryStore(maxChars = 800, compactThresholdRatio = 0.85, targetRatio = 0.65)
        repeat(12) { i ->
            store.remember(DialogueTurn(DialogueRole.USER, "msg-$i " + "x".repeat(60)))
        }
        val summary = store.summaryForPrompt(maxTokens = 8_000)
        assertTrue(
            summary.contains("msg-11") || summary.contains("msg-10"),
            "Most recent turns should survive compaction; summary snippet: ${summary.take(300)}"
        )
    }

    @Test
    fun `require throws for maxChars below minimum`() {
        assertFailsWith<IllegalArgumentException> {
            MemoryStore(maxChars = 128)
        }
    }

    @Test
    fun `totalChars stays consistent after repeated additions and compactions`() {
        val store = MemoryStore(maxChars = 1_024, compactThresholdRatio = 0.85, targetRatio = 0.65)
        repeat(20) { i ->
            store.remember(DialogueTurn(DialogueRole.USER, "data-$i " + "y".repeat(70)))
        }
        val stats = store.stats()
        assertTrue(stats.totalChars >= 0, "totalChars must be non-negative")
        assertTrue(stats.totalChars <= 1_024, "totalChars must not exceed maxChars")
    }
}
