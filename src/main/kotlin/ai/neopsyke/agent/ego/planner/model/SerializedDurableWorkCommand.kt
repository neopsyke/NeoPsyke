package ai.neopsyke.agent.ego.planner.model

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.durablework.WorkItemKind
import ai.neopsyke.agent.durablework.WorkItemPriority

/**
 * Canonical serialized boundary between planner lanes and goal-operation execution.
 * This is the only payload contract for planner-emitted goal commands.
 */
data class SerializedDurableWorkCommand(
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
    val planSteps: List<DurableWorkPlanStepPayload>? = null,
) {
    fun toDurableWorkCommand(): DurableWorkCommand? {
        val normalizedCommand = command.trim().lowercase()
        val reference = parseReference(workItemReference, workItemId)
        return when (normalizedCommand) {
            "create" -> {
                val normalizedTitle = title?.trim().orEmpty()
                val normalizedInstruction = instruction?.trim().orEmpty()
                if (normalizedTitle.isBlank() || normalizedInstruction.isBlank()) {
                    null
                } else {
                    DurableWorkCommand.Create(
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
            "list" -> DurableWorkCommand.List
            "status" -> reference?.let { DurableWorkCommand.Status(it) }
            "pause" -> reference?.let { DurableWorkCommand.Pause(it) }
            "resume" -> reference?.let { DurableWorkCommand.Resume(it) }
            "review" -> reference?.let { DurableWorkCommand.Review(it, reason?.trim()?.ifBlank { null }) }
            "complete" -> reference?.let { DurableWorkCommand.Complete(it) }
            "retire" -> reference?.let { DurableWorkCommand.Retire(it, reason?.trim()?.ifBlank { null }) }
            "delete" -> reference?.let { DurableWorkCommand.Delete(it) }
            "delete_all" -> DurableWorkCommand.DeleteAll
            "update" -> reference?.let {
                DurableWorkCommand.Update(
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
            "revise_plan" -> reference?.let { DurableWorkCommand.RevisePlan(reference = it, reason = reason?.trim()?.ifBlank { null }, planSteps = planSteps?.takeIf { s -> s.isNotEmpty() }) }
            "reprioritize" -> {
                val parsedPriority = parsePriority(priority) ?: return null
                reference?.let { DurableWorkCommand.Reprioritize(reference = it, newPriority = parsedPriority) }
            }
            else -> null
        }
    }

    companion object {
        fun fromDurableWorkCommand(command: DurableWorkCommand): SerializedDurableWorkCommand {
            return when (command) {
                is DurableWorkCommand.Create -> SerializedDurableWorkCommand(
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
                is DurableWorkCommand.List -> SerializedDurableWorkCommand(command = command.operationName)
                is DurableWorkCommand.Status -> fromReferenceCommand(command.operationName, command.reference)
                is DurableWorkCommand.Pause -> fromReferenceCommand(command.operationName, command.reference)
                is DurableWorkCommand.Resume -> fromReferenceCommand(command.operationName, command.reference)
                is DurableWorkCommand.Review -> SerializedDurableWorkCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    reason = command.reason,
                )
                is DurableWorkCommand.Complete -> fromReferenceCommand(command.operationName, command.reference)
                is DurableWorkCommand.Retire -> SerializedDurableWorkCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    reason = command.reason,
                )
                is DurableWorkCommand.Delete -> fromReferenceCommand(command.operationName, command.reference)
                is DurableWorkCommand.DeleteAll -> SerializedDurableWorkCommand(command = command.operationName)
                is DurableWorkCommand.Update -> SerializedDurableWorkCommand(
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
                is DurableWorkCommand.RevisePlan -> SerializedDurableWorkCommand(
                    command = command.operationName,
                    workItemReference = SerializedWorkItemReference.fromWorkItemReference(command.reference),
                    workItemId = resolvedWorkItemId(command.reference),
                    reason = command.reason,
                    planSteps = command.planSteps,
                )
                is DurableWorkCommand.Reprioritize -> SerializedDurableWorkCommand(
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

        private fun fromReferenceCommand(operationName: String, reference: WorkItemReference): SerializedDurableWorkCommand =
            SerializedDurableWorkCommand(
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
