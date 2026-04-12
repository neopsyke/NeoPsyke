package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.id.WorkItemRegistry
import ai.neopsyke.agent.id.WorkItemCommitment

/**
 * Adapter that implements [WorkItemRegistry] (used by existing Ego ambient context)
 * by delegating to [DurableWorkRuntime].
 */
class DurableWorkRegistry(
    private val durableWorkRuntime: DurableWorkRuntime,
) : WorkItemRegistry {

    override fun activeWorkItems(): List<WorkItemCommitment> =
        durableWorkRuntime.allWorkItems()
            .filter { it.status == WorkItemStatus.ACTIVE || it.status == WorkItemStatus.BLOCKED }
            .map { summary ->
                WorkItemCommitment(
                    id = summary.workItemId,
                    instruction = summary.title,
                    lastActedAt = summary.lastWorkedAt,
                )
            }
}
