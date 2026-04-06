package ai.neopsyke.agent.ego.planner.prompt

import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.support.PromptBudgetAllocator

/**
 * Shared prompt assembly logic. Converts a PromptProfile + trigger + context
 * into PromptBudgetAllocator sections and calls allocate().
 */
object PlannerPromptAssembler {

    fun assemble(
        profile: PromptProfile,
        trigger: EgoTrigger,
        context: PlannerContext,
        maxTokens: Int,
    ): PromptBudgetAllocator.AllocationResult {
        val sections = profile.sections(trigger, context)
        return PromptBudgetAllocator.allocate(
            sections = sections,
            maxTokens = maxTokens,
        )
    }
}
