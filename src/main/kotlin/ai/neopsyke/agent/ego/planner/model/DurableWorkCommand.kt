package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.durablework.WorkItemPriority

/**
 * Typed goal command sealed hierarchy. Produced by GoalPlanner; consumed by
 * GoalOperationActionPlugin as a typed contract rather than free-form text.
 */
sealed interface DurableWorkCommand {

    data class Create(
        val title: String,
        val instruction: String,
        val priority: WorkItemPriority = WorkItemPriority.MEDIUM,
        val completionCriteria: String = "",
        val cronExpression: String? = null,
        val contactChannel: String? = null,
    ) : DurableWorkCommand

    data object List : DurableWorkCommand

    data class Status(val reference: WorkItemReference) : DurableWorkCommand

    data class Pause(val reference: WorkItemReference) : DurableWorkCommand

    data class Resume(val reference: WorkItemReference) : DurableWorkCommand

    data class Complete(val reference: WorkItemReference) : DurableWorkCommand

    data class Delete(val reference: WorkItemReference) : DurableWorkCommand

    data object DeleteAll : DurableWorkCommand

    data class Update(
        val reference: WorkItemReference,
        val title: String? = null,
        val instruction: String? = null,
        val priority: WorkItemPriority? = null,
        val completionCriteria: String? = null,
        val cronExpression: String? = null,
        val contactChannel: String? = null,
    ) : DurableWorkCommand

    data class RevisePlan(
        val reference: WorkItemReference,
        val reason: String? = null,
    ) : DurableWorkCommand

    data class Reprioritize(
        val reference: WorkItemReference,
        val newPriority: WorkItemPriority,
    ) : DurableWorkCommand

    /** The canonical operation name used in serialized payloads. */
    val operationName: String
        get() = when (this) {
            is Create -> "create"
            is List -> "list"
            is Status -> "status"
            is Pause -> "pause"
            is Resume -> "resume"
            is Complete -> "complete"
            is Delete -> "delete"
            is DeleteAll -> "delete_all"
            is Update -> "update"
            is RevisePlan -> "revise_plan"
            is Reprioritize -> "reprioritize"
        }
}
