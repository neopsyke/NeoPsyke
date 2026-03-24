package ai.neopsyke.agent.actions.builtin

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.goal.GoalOperation
import ai.neopsyke.agent.goal.GoalOperationRequest

class GoalOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.GOAL_OPERATION,
        dispatchable = context.config.goals.enabled,
        plannerDescription = "goal_operation: create, status, list, pause, resume, reprioritize, complete, delete, delete_all, or revise_plan persistent goals, including recurring cron-backed reminders.",
        payloadGuidance = "Strict JSON with an operation field and the required goal arguments. Use delete_all to remove every goal. For recurring goals, include cron_expression.",
        payloadSchemaExample = """
            {"operation":"create","title":"Weather reminder","instruction":"Check the current weather and remind me every time this goal runs.","priority":"HIGH","completion_criteria":"A weather reminder is delivered for the current scheduled run.","cron_expression":"*/5 * * * *"}
        """.trimIndent(),
        requiresFollowUpThought = false,
        followUpPrefix = "Goal operation completed.",
        capabilities = setOf(ActionCapability.PRODUCES_USER_OUTPUT),
        effectClass = ActionEffectClass.CONTROL_PLANE,
        directCommitAllowed = true,
        supportsAutonomousCommit = true,
        allowedInstructionTrust = setOf(InstructionTrust.TRUSTED_INSTRUCTION),
        allowedArgumentDataTrust = setOf(DataTrust.TRUSTED_DATA),
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        if (context.threadSecurityContext.aggregatedDataTrust != DataTrust.TRUSTED_DATA) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_tainted_context",
                reason = "GOAL_OPERATION requires trusted thread data.",
            )
        }
        val payload = parsePayload(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_invalid_payload",
                reason = "GOAL_OPERATION payload must be valid JSON."
            )
        val operation = payload.operation?.trim().orEmpty()
        if (operation.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_missing_operation",
                reason = "GOAL_OPERATION payload requires an operation field."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override fun repairPlannerPayload(raw: String): String {
        val payload = parsePayload(raw) ?: return raw
        return mapper.writeValueAsString(
            payload.copy(
                operation = normalizeOperation(payload),
                goalId = payload.goalId?.trim()?.ifBlank { null },
                title = payload.title?.trim()?.ifBlank { null },
                instruction = payload.instruction?.trim()?.ifBlank { null },
                priority = payload.priority?.trim()?.uppercase()?.ifBlank { null },
                completionCriteria = payload.completionCriteria?.trim()?.ifBlank { null },
                cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
                reason = payload.reason?.trim()?.ifBlank { null },
            )
        )
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = parsePayload(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid goal_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val operation = normalizeOperation(payload)
            ?.uppercase()
            ?.let { runCatching { GoalOperation.valueOf(it) }.getOrNull() }
            ?: return ActionOutcome(
                statusSummary = "Unknown goal operation '${payload.operation?.trim()}'.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val result = this.context.goalsGateway.executeOperation(
            GoalOperationRequest(
                operation = operation,
                goalId = payload.goalId,
                title = payload.title,
                instruction = payload.instruction,
                priority = payload.priority
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { ai.neopsyke.agent.goal.GoalPriority.valueOf(it) }.getOrNull() },
                completionCriteria = payload.completionCriteria,
                cronExpression = payload.cronExpression,
                reason = payload.reason,
            )
        )
        if (result.message.isNotBlank()) {
            this.context.output("ego> ${result.message}")
        }
        return ActionOutcome(
            statusSummary = result.message,
            assistantOutput = result.message,
            executionStatus = if (result.success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
        )
    }

    private fun parsePayload(raw: String): ProjectOperationPayload? =
        runCatching { mapper.readValue<ProjectOperationPayload>(raw) }.getOrNull()

    private fun normalizeOperation(payload: ProjectOperationPayload): String? {
        val operation = payload.operation?.trim()?.lowercase()?.ifBlank { return null } ?: return null
        val hasGoalId = !payload.goalId.isNullOrBlank()
        val deleteAllIntent = looksLikeDeleteAllIntent(payload)
        return when (operation) {
            "inspect" -> if (hasGoalId) "status" else "list"
            "revise" -> if (deleteAllIntent) "delete_all" else "revise_plan"
            "delete_all" -> "delete_all"
            "delete", "remove", "clear" -> if (deleteAllIntent) "delete_all" else "delete"
            else -> operation
        }
    }

    private fun looksLikeDeleteAllIntent(payload: ProjectOperationPayload): Boolean {
        val instruction = payload.instruction?.trim()?.lowercase().orEmpty()
        if (instruction.isBlank()) {
            return false
        }
        val deleteVerbPresent = listOf("delete", "remove", "clear").any(instruction::contains)
        val goalReferencePresent = instruction.contains("goal")
        val bulkReferencePresent = instruction.contains("all") || instruction.contains("existing")
        return deleteVerbPresent && goalReferencePresent && bulkReferencePresent
    }

    private data class ProjectOperationPayload(
        val operation: String? = null,
        @field:JsonProperty("goal_id")
        @field:JsonAlias("goalId")
        val goalId: String? = null,
        val title: String? = null,
        val instruction: String? = null,
        val priority: String? = null,
        @field:JsonProperty("completion_criteria")
        @field:JsonAlias("completionCriteria")
        val completionCriteria: String? = null,
        @field:JsonProperty("cron_expression")
        @field:JsonAlias("cronExpression")
        val cronExpression: String? = null,
        val reason: String? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

class GoalOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        GoalOperationActionPlugin(context)
}
