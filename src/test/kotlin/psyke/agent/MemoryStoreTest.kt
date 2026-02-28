package psyke.agent

import kotlin.test.Test
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
}
