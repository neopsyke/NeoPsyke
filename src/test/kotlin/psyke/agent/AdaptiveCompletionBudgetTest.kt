package psyke.agent

import psyke.agent.support.AdaptiveCompletionBudget
import psyke.llm.ChatMessage
import psyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveCompletionBudgetTest {
    @Test
    fun `resolve keeps base tokens when prompt estimate is below scaling threshold`() {
        val resolved = AdaptiveCompletionBudget.resolve(
            AdaptiveCompletionBudget.Request(
                messages = listOf(ChatMessage(ChatRole.USER, "short input")),
                baseMaxTokens = 192,
                hardMaxTokens = 640,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 500,
                modelTokenWeight = 1.0
            )
        )

        assertEquals(192, resolved)
    }

    @Test
    fun `resolve increases budget for long prompts and respects hard max`() {
        val resolved = AdaptiveCompletionBudget.resolve(
            AdaptiveCompletionBudget.Request(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "s".repeat(2200)),
                    ChatMessage(ChatRole.USER, "u".repeat(2200))
                ),
                baseMaxTokens = 256,
                hardMaxTokens = 512,
                promptToCompletionRatio = 0.25,
                minPromptTokensForScaling = 100,
                modelTokenWeight = 1.0
            )
        )

        assertTrue(resolved > 256)
        assertTrue(resolved <= 512)
    }

    @Test
    fun `resolve applies model token weight as cost pressure`() {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "s".repeat(1800)),
            ChatMessage(ChatRole.USER, "u".repeat(1800))
        )

        val cheapModelBudget = AdaptiveCompletionBudget.resolve(
            AdaptiveCompletionBudget.Request(
                messages = messages,
                baseMaxTokens = 224,
                hardMaxTokens = 512,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 100,
                modelTokenWeight = 0.75
            )
        )
        val expensiveModelBudget = AdaptiveCompletionBudget.resolve(
            AdaptiveCompletionBudget.Request(
                messages = messages,
                baseMaxTokens = 224,
                hardMaxTokens = 512,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 100,
                modelTokenWeight = 2.5
            )
        )

        assertTrue(cheapModelBudget > expensiveModelBudget)
    }
}
