package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.goal.GoalPriority

/**
 * Typed goal command sealed hierarchy. Produced by GoalCreationPlanner and
 * GoalManagementPlanner; consumed by GoalOperationActionPlugin as a typed
 * contract rather than free-form text.
 */
sealed interface GoalCommand {

    data class Create(
        val title: String,
        val instruction: String,
        val priority: GoalPriority = GoalPriority.MEDIUM,
        val completionCriteria: String = "",
        val cronExpression: String? = null,
        val contactChannel: String? = null,
    ) : GoalCommand

    data object List : GoalCommand

    data class Status(val reference: GoalReference) : GoalCommand

    data class Pause(val reference: GoalReference) : GoalCommand

    data class Resume(val reference: GoalReference) : GoalCommand

    data class Complete(val reference: GoalReference) : GoalCommand

    data class Delete(val reference: GoalReference) : GoalCommand

    data object DeleteAll : GoalCommand

    data class Update(
        val reference: GoalReference,
        val title: String? = null,
        val instruction: String? = null,
        val priority: GoalPriority? = null,
        val completionCriteria: String? = null,
        val cronExpression: String? = null,
        val contactChannel: String? = null,
    ) : GoalCommand

    data class RevisePlan(
        val reference: GoalReference,
        val reason: String? = null,
    ) : GoalCommand

    data class Reprioritize(
        val reference: GoalReference,
        val newPriority: GoalPriority,
    ) : GoalCommand

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
