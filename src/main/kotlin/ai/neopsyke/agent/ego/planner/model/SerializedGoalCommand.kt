package ai.neopsyke.agent.ego.planner.model

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.goal.GoalPriority

/**
 * Canonical serialized boundary between planner lanes and goal-operation execution.
 * This is the only payload contract for planner-emitted goal commands.
 */
data class SerializedGoalCommand(
    val command: String,
    @param:JsonProperty("goal_reference")
    val goalReference: SerializedGoalReference? = null,
    @param:JsonProperty("goal_id")
    val goalId: String? = null,
    val title: String? = null,
    val instruction: String? = null,
    val priority: String? = null,
    @param:JsonProperty("completion_criteria")
    val completionCriteria: String? = null,
    @param:JsonProperty("cron_expression")
    val cronExpression: String? = null,
    val reason: String? = null,
) {
    fun toGoalCommand(): GoalCommand? {
        val normalizedCommand = command.trim().lowercase()
        val reference = parseReference(goalReference, goalId)
        return when (normalizedCommand) {
            "create" -> {
                val normalizedTitle = title?.trim().orEmpty()
                val normalizedInstruction = instruction?.trim().orEmpty()
                if (normalizedTitle.isBlank() || normalizedInstruction.isBlank()) {
                    null
                } else {
                    GoalCommand.Create(
                        title = normalizedTitle,
                        instruction = normalizedInstruction,
                        priority = parsePriority(priority) ?: GoalPriority.MEDIUM,
                        completionCriteria = completionCriteria?.trim().orEmpty(),
                        cronExpression = cronExpression?.trim()?.ifBlank { null },
                    )
                }
            }
            "list" -> GoalCommand.List
            "status" -> reference?.let { GoalCommand.Status(it) }
            "pause" -> reference?.let { GoalCommand.Pause(it) }
            "resume" -> reference?.let { GoalCommand.Resume(it) }
            "complete" -> reference?.let { GoalCommand.Complete(it) }
            "delete" -> reference?.let { GoalCommand.Delete(it) }
            "delete_all" -> GoalCommand.DeleteAll
            "update" -> reference?.let {
                GoalCommand.Update(
                    reference = it,
                    title = title?.trim()?.ifBlank { null },
                    instruction = instruction?.trim()?.ifBlank { null },
                    priority = parsePriority(priority),
                    completionCriteria = completionCriteria?.trim()?.ifBlank { null },
                    cronExpression = cronExpression?.trim()?.ifBlank { null },
                )
            }
            "revise_plan" -> reference?.let { GoalCommand.RevisePlan(reference = it, reason = reason?.trim()?.ifBlank { null }) }
            "reprioritize" -> {
                val parsedPriority = parsePriority(priority) ?: return null
                reference?.let { GoalCommand.Reprioritize(reference = it, newPriority = parsedPriority) }
            }
            else -> null
        }
    }

    companion object {
        fun fromGoalCommand(command: GoalCommand): SerializedGoalCommand {
            return when (command) {
                is GoalCommand.Create -> SerializedGoalCommand(
                    command = command.operationName,
                    title = command.title,
                    instruction = command.instruction,
                    priority = command.priority.name,
                    completionCriteria = command.completionCriteria,
                    cronExpression = command.cronExpression,
                )
                is GoalCommand.List -> SerializedGoalCommand(command = command.operationName)
                is GoalCommand.Status -> fromReferenceCommand(command.operationName, command.reference)
                is GoalCommand.Pause -> fromReferenceCommand(command.operationName, command.reference)
                is GoalCommand.Resume -> fromReferenceCommand(command.operationName, command.reference)
                is GoalCommand.Complete -> fromReferenceCommand(command.operationName, command.reference)
                is GoalCommand.Delete -> fromReferenceCommand(command.operationName, command.reference)
                is GoalCommand.DeleteAll -> SerializedGoalCommand(command = command.operationName)
                is GoalCommand.Update -> SerializedGoalCommand(
                    command = command.operationName,
                    goalReference = SerializedGoalReference.fromGoalReference(command.reference),
                    goalId = resolvedGoalId(command.reference),
                    title = command.title,
                    instruction = command.instruction,
                    priority = command.priority?.name,
                    completionCriteria = command.completionCriteria,
                    cronExpression = command.cronExpression,
                )
                is GoalCommand.RevisePlan -> SerializedGoalCommand(
                    command = command.operationName,
                    goalReference = SerializedGoalReference.fromGoalReference(command.reference),
                    goalId = resolvedGoalId(command.reference),
                    reason = command.reason,
                )
                is GoalCommand.Reprioritize -> SerializedGoalCommand(
                    command = command.operationName,
                    goalReference = SerializedGoalReference.fromGoalReference(command.reference),
                    goalId = resolvedGoalId(command.reference),
                    priority = command.newPriority.name,
                )
            }
        }

        private fun parsePriority(raw: String?): GoalPriority? =
            raw?.trim()?.uppercase()?.let { runCatching { GoalPriority.valueOf(it) }.getOrNull() }

        private fun parseReference(
            serializedReference: SerializedGoalReference?,
            fallbackGoalId: String?,
        ): GoalReference? {
            val parsed = serializedReference?.toGoalReference()
            if (parsed != null) return parsed
            return fallbackGoalId?.trim()?.ifBlank { null }?.let { GoalReference.ByInternalId(it) }
        }

        private fun fromReferenceCommand(operationName: String, reference: GoalReference): SerializedGoalCommand =
            SerializedGoalCommand(
                command = operationName,
                goalReference = SerializedGoalReference.fromGoalReference(reference),
                goalId = resolvedGoalId(reference),
            )

        private fun resolvedGoalId(reference: GoalReference): String? =
            when (reference) {
                is GoalReference.ByInternalId -> reference.id
                is GoalReference.ByResolvedEntity -> reference.goalId
                is GoalReference.Ambiguous -> null
                is GoalReference.Unresolved -> null
            }
    }
}

data class SerializedGoalReference(
    val type: String,
    val id: String? = null,
    val candidates: List<String>? = null,
    @param:JsonProperty("original_text")
    val originalText: String? = null,
    @param:JsonProperty("resolved_from")
    val resolvedFrom: String? = null,
) {
    fun toGoalReference(): GoalReference? {
        val normalizedType = type.trim().lowercase()
        return when (normalizedType) {
            "by_internal_id", "by_id" -> id?.trim()?.ifBlank { null }?.let { GoalReference.ByInternalId(it) }
            "by_resolved_entity", "by_resolved" -> id?.trim()?.ifBlank { null }?.let {
                GoalReference.ByResolvedEntity(
                    goalId = it,
                    resolvedFrom = resolvedFrom?.trim().orEmpty(),
                )
            }
            "ambiguous" -> GoalReference.Ambiguous(
                candidates = candidates.orEmpty().mapNotNull { candidate ->
                    candidate.trim().ifBlank { null }
                },
                originalText = originalText?.trim().orEmpty(),
            )
            "unresolved" -> GoalReference.Unresolved(originalText = originalText?.trim().orEmpty())
            else -> null
        }
    }

    companion object {
        fun fromGoalReference(reference: GoalReference): SerializedGoalReference {
            return when (reference) {
                is GoalReference.ByInternalId -> SerializedGoalReference(
                    type = "by_internal_id",
                    id = reference.id,
                )
                is GoalReference.ByResolvedEntity -> SerializedGoalReference(
                    type = "by_resolved_entity",
                    id = reference.goalId,
                    resolvedFrom = reference.resolvedFrom,
                )
                is GoalReference.Ambiguous -> SerializedGoalReference(
                    type = "ambiguous",
                    candidates = reference.candidates,
                    originalText = reference.originalText,
                )
                is GoalReference.Unresolved -> SerializedGoalReference(
                    type = "unresolved",
                    originalText = reference.originalText,
                )
            }
        }
    }
}
