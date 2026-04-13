package ai.neopsyke.agent.durablework

import java.time.Instant

interface WorkPlanBuilder {
    fun generatePlan(workItem: WorkItem): WorkItemPlan
}

/**
 * Deterministic fallback plan builder. Produces a single-step plan mirroring
 * the work item instruction. Used as an explicit migration/recovery path when
 * Ego-generated plan steps are missing.
 */
class DeterministicWorkPlanBuilder : WorkPlanBuilder {
    override fun generatePlan(workItem: WorkItem): WorkItemPlan =
        WorkItemPlan(
            steps = listOf(
                PlanStep(
                    id = "step-1",
                    description = workItem.instruction,
                    status = StepStatus.PENDING,
                    acceptanceCriteria = workItem.completionCriteria,
                )
            ),
            generatedAt = Instant.now(),
        )
}
