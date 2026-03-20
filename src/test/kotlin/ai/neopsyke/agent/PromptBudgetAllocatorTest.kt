package ai.neopsyke.agent

import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptBudgetAllocatorTest {
    @Test
    fun `allocator preserves required floors when budget is feasible`() {
        val result = PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    key = "system_core",
                    role = ChatRole.SYSTEM,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 18,
                    content = "System instructions " + "x".repeat(420)
                ),
                PromptBudgetAllocator.Section(
                    key = "trigger",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 12,
                    content = "Trigger:\nInput: hello world " + "y".repeat(260)
                ),
                PromptBudgetAllocator.Section(
                    key = "context",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                    floorTokens = 10,
                    content = "Context summary: " + "z".repeat(360)
                ),
                PromptBudgetAllocator.Section(
                    key = "optional",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.OPTIONAL,
                    content = "Optional dump " + "q".repeat(620)
                )
            ),
            maxTokens = 90
        )

        assertTrue(result.messages.isNotEmpty())
        assertTrue(result.messages.any { it.content.contains("Trigger:", ignoreCase = true) })
        assertTrue(result.diagnostics.floorBudgetFeasible)
        assertFalse(result.diagnostics.usedSingleMessageFallback)
        assertEquals(0, result.diagnostics.floorViolationCount)
        assertTrue(result.diagnostics.degradationPath.contains("trim_optional"))

        val estimatedPromptTokens = result.messages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 90)
    }

    @Test
    fun `allocator falls back to single message when floors exceed budget`() {
        val result = PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    key = "system_core",
                    role = ChatRole.SYSTEM,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 30,
                    content = "Core rules " + "a".repeat(300)
                ),
                PromptBudgetAllocator.Section(
                    key = "trigger",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 30,
                    content = "Trigger:\nNeed answer now " + "b".repeat(300)
                )
            ),
            maxTokens = 20
        )

        assertTrue(result.messages.size == 1)
        assertFalse(result.diagnostics.floorBudgetFeasible)
        assertTrue(result.diagnostics.usedSingleMessageFallback)
        assertTrue(result.diagnostics.floorViolationCount > 0)
        assertTrue(result.diagnostics.degradationPath.contains("single_message_fallback"))
    }

    @Test
    fun `allocator trims required context before required core`() {
        val result = PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    key = "core",
                    role = ChatRole.SYSTEM,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 10,
                    content = "Core instructions " + "c".repeat(340)
                ),
                PromptBudgetAllocator.Section(
                    key = "context",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                    floorTokens = 6,
                    content = "Context block " + "d".repeat(340)
                ),
                PromptBudgetAllocator.Section(
                    key = "optional",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.OPTIONAL,
                    content = "Optional block " + "e".repeat(500)
                )
            ),
            maxTokens = 44
        )

        val path = result.diagnostics.degradationPath
        val optionalIndex = path.indexOf("trim_optional")
        val contextIndex = path.indexOf("trim_required_context")
        assertTrue(optionalIndex >= 0)
        assertTrue(contextIndex >= 0)
        assertTrue(optionalIndex < contextIndex)

        val coreSection = result.diagnostics.sections.first { it.key == "core" }
        assertTrue(coreSection.allocatedTokens >= coreSection.reservedFloorTokens)
    }
}
