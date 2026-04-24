package ai.neopsyke.agent.ego.planner.model

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.assignments.WorkItemKind
import ai.neopsyke.agent.assignments.WorkItemPriority

/**
 * Canonical serialized boundary between planner lanes and assignment-operation execution.
 * This is the only payload contract for planner-emitted assignment commands.
 */
data class SerializedAssignmentCommand(
    val command: String,
    @param:JsonProperty("work_item_kind")
    val workItemKind: String? = null,
    @param:JsonProperty("work_item_reference")
    val workItemReference: SerializedWorkItemReference? = null,
    @param:JsonProperty("work_item_id")
    val workItemId: String? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: String? = null,
    @param:JsonProperty("completion_criteria")
    val completionCriteria: String? = null,
    @param:JsonProperty("cron_expression")
    val cronExpression: String? = null,
    @param:JsonProperty("contact_channel")
    val contactChannel: String? = null,
    @param:JsonProperty("operator_summary")
    val operatorSummary: String? = null,
    val reason: String? = null,
    @param:JsonProperty("plan_steps")
    val planSteps: List<AssignmentPlanStepPayload>? = null,
) {
    fun toAssignmentCommand(): AssignmentCommand? {
        val normalizedCommand = command.trim().lowercase()
        val reference = parseReference(workItemReference, workItemId)
        return when (normalizedCommand) {
            "create" -> {
                val normalizedTitle = title?.trim().orEmpty()
                val normalizedInstruction = instruction?.trim().orEmpty()
                if (normalizedTitle.isBlank() || normalizedInstruction.isBlank()) {
                    null
                } else {
                    AssignmentCommand.Create(
                        workItemKind = WorkItemKind.fromSerialized(workItemKind),
                        title = normalizedTitle,
                        instruction = normalizedInstruction,
                        priority = parsePriority(priority) ?: WorkItemPriority.MEDIUM,
                        completionCriteria = completionCriteria?.trim().orEmpty(),
                        cronExpression = cronExpression?.trim()?.ifBlank { null },
                        contactChannel = contactChannel?.trim()?.ifBlank { null },
                        operatorSummary = operatorSummary?.trim()?.ifBlank { null },
                        planSteps = planSteps?.takeIf { it.isNotEmpty() },
                    )
                }
            }
            "list" -> AssignmentCommand.List
            "status" -> reference?.let { AssignmentCommand.Status(it) }
            "pause" -> reference?.let { AssignmentCommand.Pause(it) }
            "resume" -> reference?.let { AssignmentCommand.Resume(it) }
            "review" -> reference?.let { AssignmentCommand.Review(it, reason?.trim()?.ifBlank { null }) }
            "complete" -> reference?.let { AssignmentCommand.Complete(it) }
            "retire" -> reference?.let { AssignmentCommand.Retire(it, reason?.trim()?.ifBlank { null }) }
            "delete" -> reference?.let { AssignmentCommand.Delete(it) }
            "delete_all" -> AssignmentCommand.DeleteAll
            "update" -> reference?.let {
                AssignmentCommand.Update(
                    reference = it,
                    title = title?.trim()?.ifBlank { null },
                    instruction = instruction?.trim()?.ifBlank { null },
                    priority = parsePriority(priority),
                    completionCriteria = completionCriteria?.trim()?.ifBlank { null },
                    cronExpression = cronExpression?.trim()?.ifBlank { null },
                    contactChannel = contactChannel?.trim()?.ifBlank { null },
                    operatorSummary = operatorSummary?.trim()?.ifBlank { null },
                )
            }
            "revise_plan" -> reference?.let { AssignmentCommand.RevisePlan(reference = it, reason = reason?.trim()?.ifBlank { null }, planSteps = planSteps?.takeIf { s -> s.isNotEmpty() }) }
            "reprioritize" -> {
                val parsedPriority = parsePriority(priority) ?: return null
                reference?.let { AssignmentCommand.Reprioritize(reference = it, newPriority = parsedPriority) }
            }
            else -> null
        }
    }

    companion object {
        fun fromAssignmentCommand(command: AssignmentCommand): SerializedAssignmentCommand {
            return when (command) {
                is AssignmentCommand.Create -> SerializedAssignmentCommand(
                    command = command.operationName,
                    workItemKind = command.workItemKind.name,
                    title = command.title,
                    instruction = command.instruction,
                    priority = command.priority.name,
                    completionCriteria = command.completionCriteria,
                    cronExpression = command.cronExpression,
                    contactChannel = command.contactChannel,
                    operatorSummary = command.operatorSummary,
                    planSteps = command.planSteps,
                )
                is AssignmentCommand.List -> SerializedAssignmentCommand(command = command.operationName)
                is AssignmentCommand.Status -> fromReferenceCommand(command.operationName, command.reference)
                is AssignmentCommand.Pause -> fromReferenceCommand(command.operationName, command.reference)
                is AssignmentCommand.Resume -> fromReferenceCommand(command.operationName, command.reference)
                is AssignmentCommand.Review -> SerializedAssignmentCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    reason = command.reason,
                )
                is AssignmentCommand.Complete -> fromReferenceCommand(command.operationName, command.reference)
                is AssignmentCommand.Retire -> SerializedAssignmentCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    reason = command.reason,
                )
                is AssignmentCommand.Delete -> fromReferenceCommand(command.operationName, command.reference)
                is AssignmentCommand.DeleteAll -> SerializedAssignmentCommand(command = command.operationName)
                is AssignmentCommand.Update -> SerializedAssignmentCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    title = command.title,
                    instruction = command.instruction,
                    priority = command.priority?.name,
                    completionCriteria = command.completionCriteria,
                    cronExpression = command.cronExpression,
                    contactChannel = command.contactChannel,
                    operatorSummary = command.operatorSummary,
                )
                is AssignmentCommand.RevisePlan -> SerializedAssignmentCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    reason = command.reason,
                    planSteps = command.planSteps,
                )
                is AssignmentCommand.Reprioritize -> SerializedAssignmentCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    priority = command.newPriority.name,
                )
            }
        }

        private fun parsePriority(raw: String?): WorkItemPriority? =
            raw?.trim()?.uppercase()?.let { runCatching { WorkItemPriority.valueOf(it) }.getOrNull() }

        private fun parseReference(
            serializedReference: SerializedWorkItemReference?,
            fallbackWorkItemId: String?,
        ): WorkItemReference? {
            val parsed = serializedReference?.toWorkItemReference()
            if (parsed != null) return parsed
            return fallbackWorkItemId?.trim()?.ifBlank { null }?.let { WorkItemReference.ByInternalId(it) }
        }

        private fun fromReferenceCommand(operationName: String, reference: WorkItemReference): SerializedAssignmentCommand =
            SerializedAssignmentCommand(
                command = operationName,
                workItemReference = SerializedWorkItemReference.fromWorkItemReference(reference),
                workItemId = resolvedWorkItemId(reference),
            )

        private fun resolvedWorkItemId(reference: WorkItemReference): String? =
            when (reference) {
                is WorkItemReference.ByInternalId -> reference.id
                is WorkItemReference.ByResolvedEntity -> reference.workItemId
                is WorkItemReference.Ambiguous -> null
                is WorkItemReference.Unresolved -> null
            }
    }
}

data class SerializedWorkItemReference(
    val type: String,
    val id: String? = null,
    val candidates: List<String>? = null,
    @param:JsonProperty("original_text")
    val originalText: String? = null,
    @param:JsonProperty("resolved_from")
    val resolvedFrom: String? = null,
) {
    fun toWorkItemReference(): WorkItemReference? {
        val normalizedType = type.trim().lowercase()
        return when (normalizedType) {
            "by_internal_id", "by_id" -> id?.trim()?.ifBlank { null }?.let { WorkItemReference.ByInternalId(it) }
            "by_resolved_entity", "by_resolved" -> id?.trim()?.ifBlank { null }?.let {
                WorkItemReference.ByResolvedEntity(
                    workItemId = it,
                    resolvedFrom = resolvedFrom?.trim().orEmpty(),
                )
            }
            "ambiguous" -> WorkItemReference.Ambiguous(
                candidates = candidates.orEmpty().mapNotNull { candidate ->
                    candidate.trim().ifBlank { null }
                },
                originalText = originalText?.trim().orEmpty(),
            )
            "unresolved" -> WorkItemReference.Unresolved(originalText = originalText?.trim().orEmpty())
            else -> null
        }
    }

    companion object {
        fun fromWorkItemReference(reference: WorkItemReference): SerializedWorkItemReference {
            return when (reference) {
                is WorkItemReference.ByInternalId -> SerializedWorkItemReference(
                    type = "by_internal_id",
                    id = reference.id,
                )
                is WorkItemReference.ByResolvedEntity -> SerializedWorkItemReference(
                    type = "by_resolved_entity",
                    id = reference.workItemId,
                    resolvedFrom = reference.resolvedFrom,
                )
                is WorkItemReference.Ambiguous -> SerializedWorkItemReference(
                    type = "ambiguous",
                    candidates = reference.candidates,
                    originalText = reference.originalText,
                )
                is WorkItemReference.Unresolved -> SerializedWorkItemReference(
                    type = "unresolved",
                    originalText = reference.originalText,
                )
            }
        }
    }
}
