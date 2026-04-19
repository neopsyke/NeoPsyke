package ai.neopsyke.agent.ego.planner.model

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.assignments.WorkItemKind
import ai.neopsyke.agent.assignments.WorkItemPriority

/**
 * Shared step payload for assignment plans, carried in Create and RevisePlan.
 * Preserves the existing runtime step semantics.
 */
data class AssignmentPlanStepPayload(
    val id: String? = null,
    val description: String,
    @param:JsonProperty("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    @param:JsonProperty("grounding_requirement")
    val groundingRequirement: String? = null,
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
    @param:JsonProperty("max_attempts")
    val maxAttempts: Int? = null,
)

/**
 * Typed assignment command sealed hierarchy. Produced by AssignmentCommandBuilder; consumed by
 * AssignmentOperationActionPlugin as a typed contract rather than free-form text.
 */
sealed interface AssignmentCommand {

    data class Create(
        val workItemKind: WorkItemKind = WorkItemKind.RECURRENT_TASK,
        val title: String,
        val instruction: String,
        val priority: WorkItemPriority = WorkItemPriority.MEDIUM,
        val completionCriteria: String = "",
        val cronExpression: String? = null,
        val contactChannel: String? = null,
        val operatorSummary: String? = null,
        val planSteps: kotlin.collections.List<AssignmentPlanStepPayload>? = null,
    ) : AssignmentCommand

    data object List : AssignmentCommand

    data class Status(val reference: WorkItemReference) : AssignmentCommand

    data class Pause(val reference: WorkItemReference) : AssignmentCommand

    data class Resume(val reference: WorkItemReference) : AssignmentCommand

    data class Review(val reference: WorkItemReference, val reason: String? = null) : AssignmentCommand

    data class Complete(val reference: WorkItemReference) : AssignmentCommand

    data class Retire(val reference: WorkItemReference, val reason: String? = null) : AssignmentCommand

    data class Delete(val reference: WorkItemReference) : AssignmentCommand

    data object DeleteAll : AssignmentCommand

    data class Update(
        val reference: WorkItemReference,
        val title: String? = null,
        val instruction: String? = null,
        val priority: WorkItemPriority? = null,
        val completionCriteria: String? = null,
        val cronExpression: String? = null,
        val contactChannel: String? = null,
        val operatorSummary: String? = null,
    ) : AssignmentCommand

    data class RevisePlan(
        val reference: WorkItemReference,
        val reason: String? = null,
        val planSteps: kotlin.collections.List<AssignmentPlanStepPayload>? = null,
    ) : AssignmentCommand

    data class Reprioritize(
        val reference: WorkItemReference,
        val newPriority: WorkItemPriority,
    ) : AssignmentCommand

    /** The canonical operation name used in serialized payloads. */
    val operationName: String
        get() = when (this) {
            is Create -> "create"
            is List -> "list"
            is Status -> "status"
            is Pause -> "pause"
            is Resume -> "resume"
            is Review -> "review"
            is Complete -> "complete"
            is Retire -> "retire"
            is Delete -> "delete"
            is DeleteAll -> "delete_all"
            is Update -> "update"
            is RevisePlan -> "revise_plan"
            is Reprioritize -> "reprioritize"
        }
}
