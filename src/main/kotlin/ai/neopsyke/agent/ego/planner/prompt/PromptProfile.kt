package ai.neopsyke.agent.ego.planner.prompt

import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.support.PromptBudgetAllocator

/**
 * Each lane declares its own PromptProfile that specifies which prompt
 * sections to include, their bands, importance, and floor tokens.
 */
interface PromptProfile {
    fun sections(trigger: EgoTrigger, context: PlannerContext): List<PromptBudgetAllocator.Section>
}
