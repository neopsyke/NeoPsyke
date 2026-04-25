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
import ai.neopsyke.agent.cortex.motor.actions.ActionPromptDescriptors
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.cortex.motor.actions.ChannelValidation
import ai.neopsyke.agent.cortex.motor.actions.ContactChannelPolicy
import ai.neopsyke.agent.model.ApprovalContextEntry
import ai.neopsyke.agent.ego.planner.model.AssignmentCommand
import ai.neopsyke.agent.ego.planner.model.WorkItemReference
import ai.neopsyke.agent.ego.planner.model.SerializedAssignmentCommand
import ai.neopsyke.agent.assignments.AssignmentOperation
import ai.neopsyke.agent.assignments.AssignmentOperationRequest
import ai.neopsyke.agent.assignments.ReviewRequestSource
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Assignment operation action plugin.
 * Planner payloads must use the typed serialized AssignmentCommand contract.
 * No execution-time semantic interpretation or work-item identifier repair heuristics.
 */
class AssignmentOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    private val promptDescriptor = ActionPromptDescriptors.load(ActionType.ASSIGNMENT_OPERATION.id)
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.ASSIGNMENT_OPERATION,
        dispatchable = context.config.assignment.enabled,
        plannerDescription = promptDescriptor.plannerDescription,
        payloadGuidance = promptDescriptor.payloadGuidance,
        payloadSchemaExample = promptDescriptor.payloadSchemaExample,
        requiresFollowUpThought = false,
        followUpPrefix = promptDescriptor.followUpPrefix ?: "Assignment operation completed.",
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
                ruleId = "assignment_operation_tainted_context",
                reason = "ASSIGNMENT_OPERATION requires trusted thread data.",
            )
        }
        val command = parseAssignmentCommand(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "assignment_operation_invalid_payload",
                reason = "ASSIGNMENT_OPERATION payload must follow the typed AssignmentCommand contract with a command field.",
            )
        return if (toRequest(command, null) == null) {
            ActionDeterministicReview(
                allow = false,
                ruleId = "assignment_operation_invalid_command",
                reason = "ASSIGNMENT_OPERATION command is incomplete or invalid for execution.",
            )
        } else {
            ActionDeterministicReview(allow = true)
        }
    }

    override fun repairPlannerPayload(raw: String): String {
        val command = parseAssignmentCommand(raw) ?: return raw
        return mapper.writeValueAsString(SerializedAssignmentCommand.fromAssignmentCommand(command))
    }

    override fun buildApprovalContext(payload: String): List<ApprovalContextEntry> {
        val command = parseAssignmentCommand(payload) ?: return emptyList()
        val entries = when (command) {
            is AssignmentCommand.Create -> buildPlanContextEntries(command.planSteps)
            else -> emptyList()
        }
        logger.debug {
            "Approval context built: command=${command.operationName} entries=${entries.size}" +
                if (command is AssignmentCommand.Create) " plan_steps=${command.planSteps?.size ?: 0}" else ""
        }
        return entries
    }

    private fun buildPlanContextEntries(
        steps: kotlin.collections.List<ai.neopsyke.agent.ego.planner.model.AssignmentPlanStepPayload>?,
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
        val parsed = parseAssignmentCommand(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid assignment_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val (command, rejectionNote) = applyChannelPolicy(parsed, action)
        val request = toRequest(command, action)
            ?: return ActionOutcome(
                statusSummary = "Assignment operation payload is missing required typed fields.",
                executionStatus = ActionExecutionStatus.FAILED,
            )

        val result = this.context.assignmentGateway.executeOperation(request)
        val summary = if (result.success && rejectionNote != null) {
            "${result.message} $rejectionNote"
        } else {
            result.message
        }
        return ActionOutcome(
            statusSummary = summary,
            executionStatus = if (result.success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
            effects = if (result.success) setOf(ActionEffect.TASK_PROGRESS) else emptySet(),
        )
    }

    /**
     * Owner-gates and availability-checks the contactChannel field on
     * Create/Update commands.
     *
     *  * Non-owner or non-user-initiated callers cannot alter the channel:
     *    the field is silently stripped (assignment step activations never
     *    reach this plugin, but this is belt-and-suspenders against any
     *    future caller).
     *  * Owner-initiated callers may only pick a currently-deliverable
     *    channel; unknown values are stripped, and a clarification note is
     *    returned so the next planning turn can forward it to the user via
     *    the normal contact_user flow.
     */
    private fun applyChannelPolicy(
        command: AssignmentCommand,
        action: PendingAction,
    ): Pair<AssignmentCommand, String?> {
        val requested = when (command) {
            is AssignmentCommand.Create -> command.contactChannel
            is AssignmentCommand.Update -> command.contactChannel
            else -> return command to null
        } ?: return command to null

        val policy = this.context.contactChannelPolicy ?: return command to null

        if (!policy.canAlterContactChannel(action.conversationContext, action.origin)) {
            logger.warn {
                "Dropping contactChannel='$requested' on ${command.operationName}: " +
                    "caller principal=${action.conversationContext.security.principal.role} " +
                    "origin=${action.origin.source} lacks OWNER+USER provenance."
            }
            return strippedContactChannel(command) to null
        }

        return when (val validation = policy.validate(requested)) {
            ChannelValidation.None,
            is ChannelValidation.Accepted -> command to null

            is ChannelValidation.Rejected -> {
                logger.info {
                    "Stripping unavailable contactChannel='${validation.requested}' " +
                        "on ${command.operationName}; available=${validation.available}."
                }
                val availableText = validation.available
                    .sorted()
                    .joinToString(", ")
                    .ifBlank { "no channels currently available" }
                val note = "Requested contact channel '${validation.requested}' is not available. " +
                    "Ask the user which to use; currently available: $availableText. " +
                    "Delivery channel was not changed."
                strippedContactChannel(command) to note
            }
        }
    }

    private fun strippedContactChannel(command: AssignmentCommand): AssignmentCommand =
        when (command) {
            is AssignmentCommand.Create -> command.copy(contactChannel = null)
            is AssignmentCommand.Update -> command.copy(contactChannel = null)
            else -> command
        }

    private fun parseAssignmentCommand(raw: String): AssignmentCommand? =
        runCatching {
            mapper.readValue<SerializedAssignmentCommand>(raw).toAssignmentCommand()
        }.getOrNull()

    private fun toRequest(command: AssignmentCommand, action: PendingAction?): AssignmentOperationRequest? {
        return when (command) {
            is AssignmentCommand.Create -> AssignmentOperationRequest(
                operation = AssignmentOperation.CREATE,
                workItemKind = command.workItemKind,
                title = command.title,
                instruction = command.instruction,
                priority = command.priority,
                completionCriteria = command.completionCriteria,
                cronExpression = command.cronExpression,
                contactChannel = command.contactChannel,
                operatorSummary = command.operatorSummary,
                planSteps = command.planSteps,
            )
            is AssignmentCommand.List -> AssignmentOperationRequest(operation = AssignmentOperation.LIST)
            is AssignmentCommand.Status -> AssignmentOperationRequest(
                operation = AssignmentOperation.STATUS,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is AssignmentCommand.Pause -> AssignmentOperationRequest(
                operation = AssignmentOperation.PAUSE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is AssignmentCommand.Resume -> AssignmentOperationRequest(
                operation = AssignmentOperation.RESUME,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is AssignmentCommand.Review -> AssignmentOperationRequest(
                operation = AssignmentOperation.REVIEW,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                reason = command.reason,
                reviewSource = if (action?.origin?.source == OriginSource.ID) {
                    ReviewRequestSource.ID
                } else {
                    ReviewRequestSource.MANUAL
                },
            )
            is AssignmentCommand.Complete -> AssignmentOperationRequest(
                operation = AssignmentOperation.COMPLETE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is AssignmentCommand.Retire -> AssignmentOperationRequest(
                operation = AssignmentOperation.RETIRE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                reason = command.reason,
            )
            is AssignmentCommand.Delete -> AssignmentOperationRequest(
                operation = AssignmentOperation.DELETE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
            )
            is AssignmentCommand.DeleteAll -> AssignmentOperationRequest(operation = AssignmentOperation.DELETE_ALL)
            is AssignmentCommand.Update -> AssignmentOperationRequest(
                operation = AssignmentOperation.UPDATE,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                title = command.title,
                instruction = command.instruction,
                priority = command.priority,
                completionCriteria = command.completionCriteria,
                cronExpression = command.cronExpression,
                contactChannel = command.contactChannel,
                operatorSummary = command.operatorSummary,
            )
            is AssignmentCommand.RevisePlan -> AssignmentOperationRequest(
                operation = AssignmentOperation.REVISE_PLAN,
                workItemId = resolvedWorkItemId(command.reference) ?: return null,
                reason = command.reason,
                planSteps = command.planSteps,
            )
            is AssignmentCommand.Reprioritize -> AssignmentOperationRequest(
                operation = AssignmentOperation.REPRIORITIZE,
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

class AssignmentOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        AssignmentOperationActionPlugin(context)
}
