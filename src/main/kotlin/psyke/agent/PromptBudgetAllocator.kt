package psyke.agent

import psyke.llm.ChatMessage
import psyke.llm.ChatRole

object PromptBudgetAllocator {
    private const val MESSAGE_OVERHEAD_TOKENS = 4

    enum class Priority(val weight: Int) {
        MANDATORY(5),
        IMPORTANT(3),
        OPTIONAL(1)
    }

    data class Section(
        val role: ChatRole,
        val content: String,
        val priority: Priority = Priority.OPTIONAL,
        val required: Boolean = false,
        val minTokens: Int = 0,
    )

    fun allocate(sections: List<Section>, maxTokens: Int): List<ChatMessage> {
        if (sections.isEmpty() || maxTokens <= 0) {
            return emptyList()
        }

        val budgeted = sections.mapNotNull { section ->
            val normalized = section.content.trim()
            if (normalized.isBlank()) {
                null
            } else {
                MutableSection(
                    section = section.copy(content = normalized),
                    estimatedTokens = TextSecurity.estimateTokens(normalized)
                )
            }
        }

        if (budgeted.isEmpty()) {
            return emptyList()
        }

        budgeted.forEach { item ->
            if (item.section.required) {
                val requiredMin = maxOf(1, item.section.minTokens)
                item.allocatedTokens = minOf(item.estimatedTokens, requiredMin)
            }
        }

        trimToFitBudget(budgeted, maxTokens)
        distributeRemainingBudget(budgeted, maxTokens)
        trimToFitBudget(budgeted, maxTokens)

        val messages = budgeted.mapNotNull { item ->
            if (item.allocatedTokens <= 0) {
                return@mapNotNull null
            }
            val clamped = TextSecurity.clampToTokenBudget(item.section.content, item.allocatedTokens)
            if (clamped.isBlank()) {
                null
            } else {
                ChatMessage(role = item.section.role, content = clamped)
            }
        }

        if (messages.isEmpty()) {
            val fallback = budgeted.first()
            val clamped = TextSecurity.clampToTokenBudget(fallback.section.content, 1)
            if (clamped.isBlank()) {
                return emptyList()
            }
            return listOf(ChatMessage(role = fallback.section.role, content = clamped))
        }

        return mergeByRole(messages)
    }

    private fun distributeRemainingBudget(
        sections: List<MutableSection>,
        maxTokens: Int,
    ) {
        var remaining = maxTokens - totalCost(sections)
        if (remaining <= 0) {
            return
        }

        val roundRobin = sections.sortedWith(
            compareByDescending<MutableSection> { it.section.required }
                .thenByDescending { it.section.priority.weight }
        )

        while (remaining > 0) {
            var progressed = false
            for (item in roundRobin) {
                if (item.allocatedTokens >= item.estimatedTokens) {
                    continue
                }
                val deltaCost = if (item.allocatedTokens == 0) MESSAGE_OVERHEAD_TOKENS + 1 else 1
                if (deltaCost > remaining) {
                    continue
                }
                item.allocatedTokens += 1
                remaining -= deltaCost
                progressed = true
                if (remaining <= 0) {
                    break
                }
            }
            if (!progressed) {
                break
            }
        }
    }

    private fun trimToFitBudget(
        sections: List<MutableSection>,
        maxTokens: Int,
    ) {
        while (totalCost(sections) > maxTokens) {
            val candidate = sections.firstOrNull { it.allocatedTokens > 0 }?.let {
                sections
                    .filter { item -> item.allocatedTokens > 0 }
                    .minWithOrNull(
                        compareBy<MutableSection> { it.section.required }
                            .thenBy { it.section.priority.weight }
                            .thenByDescending { it.allocatedTokens }
                    )
            } ?: break

            val minimum = if (candidate.section.required) 1 else 0
            if (candidate.allocatedTokens > minimum) {
                candidate.allocatedTokens -= 1
                continue
            }
            candidate.allocatedTokens = 0
        }
    }

    private fun totalCost(sections: List<MutableSection>): Int =
        sections.sumOf { item ->
            if (item.allocatedTokens <= 0) {
                0
            } else {
                item.allocatedTokens + MESSAGE_OVERHEAD_TOKENS
            }
        }

    private fun mergeByRole(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) {
            return messages
        }
        val merged = mutableListOf<ChatMessage>()
        messages.forEach { message ->
            val last = merged.lastOrNull()
            if (last != null && last.role == message.role) {
                merged[merged.lastIndex] = last.copy(content = "${last.content}\n\n${message.content}")
            } else {
                merged.add(message)
            }
        }
        return merged
    }

    private data class MutableSection(
        val section: Section,
        val estimatedTokens: Int,
        var allocatedTokens: Int = 0,
    )
}
