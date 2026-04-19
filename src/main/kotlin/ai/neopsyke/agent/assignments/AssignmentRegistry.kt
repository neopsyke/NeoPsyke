package ai.neopsyke.agent.assignments

import ai.neopsyke.agent.id.WorkItemRegistry
import ai.neopsyke.agent.id.WorkItemCommitment

/**
 * Adapter that implements [WorkItemRegistry] (used by existing Ego ambient context)
 * by delegating to [AssignmentRuntime].
 */
class AssignmentRegistry(
    private val assignmentRuntime: AssignmentRuntime,
) : WorkItemRegistry {

    override fun activeWorkItems(): List<WorkItemCommitment> =
        assignmentRuntime.allWorkItems()
            .filter { it.status == WorkItemStatus.ACTIVE || it.status == WorkItemStatus.BLOCKED }
            .map { summary ->
                WorkItemCommitment(
                    id = summary.workItemId,
                    instruction = summary.title,
                    lastActedAt = summary.lastWorkedAt,
                )
            }
}
