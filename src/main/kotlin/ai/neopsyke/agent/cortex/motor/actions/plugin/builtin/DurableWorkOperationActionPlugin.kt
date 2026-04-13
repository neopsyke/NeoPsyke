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
import ai.neopsyke.agent.model.ApprovalContextEntry
import ai.neopsyke.agent.ego.planner.model.DurableWorkCommand
import ai.neopsyke.agent.ego.planner.model.WorkItemReference
import ai.neopsyke.agent.ego.planner.model.SerializedDurableWorkCommand
import ai.neopsyke.agent.durablework.DurableWorkOperation
import ai.neopsyke.agent.durablework.DurableWorkOperationRequest
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Goal operation action plugin.
 * Planner payloads must use the typed serialized DurableWorkCommand contract.
 * No execution-time semantic interpretation or goal-id repair heuristics.
 */
class DurableWorkOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.DURABLE_WORK_OPERATION,
        dispatchable = context.config.durableWork.enabled,
        plannerDescription = "durable_work_operation: create, update, status, list, pause, resume, reprioritize, complete, delete, delete_all, or revise_plan persistent goals, including recurring cron-backed reminders.",
        payloadGuidance = "Strict JSON using the typed DurableWorkCommand contract: command, optional goal_reference, and command-specific fields.",
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
                ruleId = "durable_work_operation_tainted_context",
                reason = "DURABLE_WORK_OPERATION requires trusted thread data.",
            )
        }
        val command = parseDurableWorkCommand(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "durable_work_operation_invalid_payload",
                reason = "DURABLE_WORK_OPERATION payload must follow the typed DurableWorkCommand contract with a command field.",
            )
        return if (toRequest(command) == null) {
            ActionDeterministicReview(
                allow = false,
                ruleId = "durable_work_operation_invalid_command",
                reason = "DURABLE_WORK_OPERATION command is incomplete or invalid for execution.",
            )
        } else {
            ActionDeterministicReview(allow = true)
        }
    }

    override fun repairPlannerPayload(raw: String): String {
        val command = parseDurableWorkCommand(raw) ?: return raw
        return mapper.writeValueAsString(SerializedDurableWorkCommand.fromDurableWorkCommand(command))
    }

    override fun buildApprovalContext(payload: String): List<ApprovalContextEntry> {
        val command = parseDurableWorkCommand(payload) ?: return emptyList()
        val entries = when (command) {
            is DurableWorkCommand.Create -> buildPlanContextEntries(command.planSteps)
            else -> emptyList()
        }
        logger.debug {
            "Approval context built: command=${command.operationName} entries=${entries.size}" +
                if (command is DurableWorkCommand.Create) " plan_steps=${command.planSteps?.size ?: 0}" else ""
        }
        return entries
    }

    private fun buildPlanContextEntries(
        steps: kotlin.collections.List<ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload>?,
    ): List<ApprovalContextEntry> {
        if (steps.isNullOrEmpty()) return emptyList()
        val content = steps.mapIndexed { i, step ->
            val desc = step.description
            val acc = step.acceptanceCriteria?.takeIf { a -> a.isNotBlank() }?.let { a -> " (acceptance: $a)" } ?: ""
            val req = if (step.requires.isNotEmpty()) " (requires: ${step.requires.joinToString(",")})" else ""
            val prod = if (step.produces.isNotEmpty()) " (produces: ${step.produces.joinToString(",")})" else ""
            "${i + 1}. $desc$acc$req$prod"
        }.joinToString("\n")
        return listOf(ApprovalContextEntry(label = "Plan", content = content))
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val command = parseDurableWorkCommand(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid durable_work_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val request = toRequest(command)
            ?: return ActionOutcome(
                statusSummary = "Goal operation payload is missing required typed fields.",
                executionStatus = ActionExecutionStatus.FAILED,
            )

        val result = this.context.durableWorkGateway.executeOperation(request)
        return ActionOutcome(
            statusSummary = result.message,
            executionStatus = if (result.success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
        )
    }

    private fun parseDurableWorkCommand(raw: String): DurableWorkCommand? =
        runCatching {
            mapper.readValue<SerializedDurableWorkCommand>(raw).toDurableWorkCommand()
        }.getOrNull()

    private fun toRequest(command: DurableWorkCommand): DurableWorkOperationRequest? {
        return when (command) {
            is DurableWorkCommand.Create -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.CREATE,
                title = command.title,
                instruction = command.instruction,
                priority = command.priority,
                completionCriteria = command.completionCriteria,
                cronExpression = command.cronExpression,
                contactChannel = command.contactChannel,
                planSteps = command.planSteps,
            )
            is DurableWorkCommand.List -> DurableWorkOperationRequest(operation = DurableWorkOperation.LIST)
            is DurableWorkCommand.Status -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.STATUS,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is DurableWorkCommand.Pause -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.PAUSE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is DurableWorkCommand.Resume -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.RESUME,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is DurableWorkCommand.Complete -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.COMPLETE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is DurableWorkCommand.Delete -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.DELETE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is DurableWorkCommand.DeleteAll -> DurableWorkOperationRequest(operation = DurableWorkOperation.DELETE_ALL)
            is DurableWorkCommand.Update -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.UPDATE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                title = command.title,
                instruction = command.instruction,
                priority = command.priority,
                completionCriteria = command.completionCriteria,
                cronExpression = command.cronExpression,
                contactChannel = command.contactChannel,
            )
            is DurableWorkCommand.RevisePlan -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.REVISE_PLAN,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                reason = command.reason,
                planSteps = command.planSteps,
            )
            is DurableWorkCommand.Reprioritize -> DurableWorkOperationRequest(
                operation = DurableWorkOperation.REPRIORITIZE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                priority = command.newPriority,
            )
        }
    }

    private fun resolvedWorkItemId(reference: WorkItemReference): String? {
        return when (reference) {
            is WorkItemReference.ByInternalId -> reference.id.trim().ifBlank { null }
            is WorkItemReference.ByResolvedEntity -> reference.workItemId.trim().ifBlank { null }
            is WorkItemReference.Ambiguous -> null
            is WorkItemReference.Unresolved -> null
        }
    }

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}

class DurableWorkOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        DurableWorkOperationActionPlugin(context)
}
