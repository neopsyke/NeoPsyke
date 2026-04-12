package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.ego.planner.model.GoalCommand
import ai.neopsyke.agent.ego.planner.model.GoalReference
import ai.neopsyke.agent.ego.planner.model.SerializedGoalCommand
import ai.neopsyke.agent.goal.GoalOperation
import ai.neopsyke.agent.goal.GoalOperationRequest
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext

/**
 * Goal operation action plugin.
 * Planner payloads must use the typed serialized GoalCommand contract.
 * No execution-time semantic interpretation or goal-id repair heuristics.
 */
class GoalOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.GOAL_OPERATION,
        dispatchable = context.config.goals.enabled,
        plannerDescription = "goal_operation: create, update, status, list, pause, resume, reprioritize, complete, delete, delete_all, or revise_plan persistent goals, including recurring cron-backed reminders.",
        payloadGuidance = "Strict JSON using the typed GoalCommand contract: command, optional goal_reference, and command-specific fields.",
        payloadSchemaExample = """
            {"command":"create","title":"Weather reminder","instruction":"Check the current weather and remind me every time this goal runs.","priority":"HIGH","completion_criteria":"A weather reminder is delivered for the current scheduled run.","cron_expression":"*/5 * * * *"}
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
        val command = parseGoalCommand(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_invalid_payload",
                reason = "GOAL_OPERATION payload must follow the typed GoalCommand contract with a command field.",
            )
        return if (toRequest(command) == null) {
            ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_invalid_command",
                reason = "GOAL_OPERATION command is incomplete or invalid for execution.",
            )
        } else {
            ActionDeterministicReview(allow = true)
        }
    }

    override fun repairPlannerPayload(raw: String): String {
        val command = parseGoalCommand(raw) ?: return raw
        return mapper.writeValueAsString(SerializedGoalCommand.fromGoalCommand(command))
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val command = parseGoalCommand(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid goal_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val request = toRequest(command)
            ?: return ActionOutcome(
                statusSummary = "Goal operation payload is missing required typed fields.",
                executionStatus = ActionExecutionStatus.FAILED,
            )

        val result = this.context.goalsGateway.executeOperation(request)
        if (result.message.isNotBlank()) {
            this.context.output("ego> ${result.message}")
        }
        return ActionOutcome(
            statusSummary = result.message,
            assistantOutput = result.message,
            executionStatus = if (result.success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
        )
    }

    private fun parseGoalCommand(raw: String): GoalCommand? =
        runCatching {
            mapper.readValue<SerializedGoalCommand>(raw).toGoalCommand()
        }.getOrNull()

    private fun toRequest(command: GoalCommand): GoalOperationRequest? {
        return when (command) {
            is GoalCommand.Create -> GoalOperationRequest(
                operation = GoalOperation.CREATE,
                title = command.title,
                instruction = command.instruction,
                priority = command.priority,
                completionCriteria = command.completionCriteria,
                cronExpression = command.cronExpression,
                contactChannel = command.contactChannel,
            )
            is GoalCommand.List -> GoalOperationRequest(operation = GoalOperation.LIST)
            is GoalCommand.Status -> GoalOperationRequest(
                operation = GoalOperation.STATUS,
                goalId = resolvedGoalId(command.reference) ?: return null,
            )
            is GoalCommand.Pause -> GoalOperationRequest(
                operation = GoalOperation.PAUSE,
                goalId = resolvedGoalId(command.reference) ?: return null,
            )
            is GoalCommand.Resume -> GoalOperationRequest(
                operation = GoalOperation.RESUME,
                goalId = resolvedGoalId(command.reference) ?: return null,
            )
            is GoalCommand.Complete -> GoalOperationRequest(
                operation = GoalOperation.COMPLETE,
                goalId = resolvedGoalId(command.reference) ?: return null,
            )
            is GoalCommand.Delete -> GoalOperationRequest(
                operation = GoalOperation.DELETE,
                goalId = resolvedGoalId(command.reference) ?: return null,
            )
            is GoalCommand.DeleteAll -> GoalOperationRequest(operation = GoalOperation.DELETE_ALL)
            is GoalCommand.Update -> GoalOperationRequest(
                operation = GoalOperation.UPDATE,
                goalId = resolvedGoalId(command.reference) ?: return null,
                title = command.title,
                instruction = command.instruction,
                priority = command.priority,
                completionCriteria = command.completionCriteria,
                cronExpression = command.cronExpression,
                contactChannel = command.contactChannel,
            )
            is GoalCommand.RevisePlan -> GoalOperationRequest(
                operation = GoalOperation.REVISE_PLAN,
                goalId = resolvedGoalId(command.reference) ?: return null,
                reason = command.reason,
            )
            is GoalCommand.Reprioritize -> GoalOperationRequest(
                operation = GoalOperation.REPRIORITIZE,
                goalId = resolvedGoalId(command.reference) ?: return null,
                priority = command.newPriority,
            )
        }
    }

    private fun resolvedGoalId(reference: GoalReference): String? {
        return when (reference) {
            is GoalReference.ByInternalId -> reference.id.trim().ifBlank { null }
            is GoalReference.ByResolvedEntity -> reference.goalId.trim().ifBlank { null }
            is GoalReference.Ambiguous -> null
            is GoalReference.Unresolved -> null
        }
    }

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}

class GoalOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        GoalOperationActionPlugin(context)
}
