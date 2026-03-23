package ai.neopsyke.agent

import ai.neopsyke.agent.support.AdaptiveCompletionBudget
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `null context window preserves existing behavior`() {
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            AdaptiveCompletionBudget.Request(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "s".repeat(2000)),
                    ChatMessage(ChatRole.USER, "u".repeat(2000))
                ),
                baseMaxTokens = 192,
                hardMaxTokens = 640,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 100,
                modelTokenWeight = 1.0,
                modelContextWindow = null
            )
        )

        assertFalse(resolution.contextClamped)
        assertTrue(resolution.budget in 192..640)
    }

    @Test
    fun `context window clamps budget when prompt is large relative to context`() {
        // Prompt of ~1008 tokens (4000 chars / 4) + overhead, context window = 1200
        // Available ≈ 1200 - 1016 - 64 = 120 tokens
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            AdaptiveCompletionBudget.Request(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "s".repeat(2000)),
                    ChatMessage(ChatRole.USER, "u".repeat(2000))
                ),
                baseMaxTokens = 192,
                hardMaxTokens = 640,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 100,
                modelTokenWeight = 1.0,
                modelContextWindow = 1200
            )
        )

        assertTrue(resolution.contextClamped)
        assertTrue(resolution.budget < 192, "Budget should be clamped below base when context is tight")
        assertTrue(resolution.budget > 0, "Budget should still be positive")
    }

    @Test
    fun `context window returns min viable tokens when prompt nearly fills context`() {
        // Prompt of ~1008 tokens, context window = 1050 → available ≈ 1050 - 1016 - 64 = negative
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            AdaptiveCompletionBudget.Request(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "s".repeat(2000)),
                    ChatMessage(ChatRole.USER, "u".repeat(2000))
                ),
                baseMaxTokens = 192,
                hardMaxTokens = 640,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 100,
                modelTokenWeight = 1.0,
                modelContextWindow = 1050
            )
        )

        assertTrue(resolution.contextClamped)
        assertEquals(16, resolution.budget, "Should return MIN_VIABLE_COMPLETION_TOKENS when context nearly exhausted")
    }

    @Test
    fun `large context window does not clamp budget`() {
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            AdaptiveCompletionBudget.Request(
                messages = listOf(ChatMessage(ChatRole.USER, "short input")),
                baseMaxTokens = 192,
                hardMaxTokens = 640,
                promptToCompletionRatio = 0.10,
                minPromptTokensForScaling = 500,
                modelTokenWeight = 1.0,
                modelContextWindow = 128_000
            )
        )

        assertFalse(resolution.contextClamped)
        assertEquals(192, resolution.budget)
    }
}
