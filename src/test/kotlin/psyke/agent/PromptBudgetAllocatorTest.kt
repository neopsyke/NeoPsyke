package psyke.agent

import psyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertTrue

class PromptBudgetAllocatorTest {
    @Test
    fun `allocator keeps required user section under tight budget`() {
        val messages = psyke.agent.support.PromptBudgetAllocator.allocate(
            sections = listOf(
                psyke.agent.support.PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = psyke.agent.support.PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 20,
                    content = "system " + "x".repeat(400)
                ),
                psyke.agent.support.PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = psyke.agent.support.PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 10,
                    content = "Trigger:\nInput: hello world " + "y".repeat(300)
                ),
                psyke.agent.support.PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = psyke.agent.support.PromptBudgetAllocator.Priority.OPTIONAL,
                    content = "Optional context " + "z".repeat(400)
                )
            ),
            maxTokens = 64
        )

        assertTrue(messages.isNotEmpty())
        assertTrue(messages.any { it.role == ChatRole.USER && it.content.contains("Trigger:", ignoreCase = true) })
        val estimatedPromptTokens = messages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 64)
    }

    @Test
    fun `allocator favors mandatory and important sections before optional`() {
        val messages = psyke.agent.support.PromptBudgetAllocator.allocate(
            sections = listOf(
                psyke.agent.support.PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = psyke.agent.support.PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 12,
                    content = "Core instructions " + "a".repeat(260)
                ),
                psyke.agent.support.PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = psyke.agent.support.PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 8,
                    content = "Memory summary: prefer concise answers. " + "b".repeat(260)
                ),
                psyke.agent.support.PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = psyke.agent.support.PromptBudgetAllocator.Priority.OPTIONAL,
                    content = "Verbose queue dump " + "c".repeat(500)
                )
            ),
            maxTokens = 52
        )

        val mergedPrompt = messages.joinToString("\n\n") { it.content }
        assertTrue(mergedPrompt.contains("Core instructions"))
        assertTrue(mergedPrompt.contains("Memory summary"))
        val estimatedPromptTokens = messages.sumOf { TextSecurity.estimateTokens(it.content) + 4 }
        assertTrue(estimatedPromptTokens <= 52)
    }
}
