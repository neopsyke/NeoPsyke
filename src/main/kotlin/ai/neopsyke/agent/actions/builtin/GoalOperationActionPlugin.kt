package ai.neopsyke.agent.actions.builtin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
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
        plannerDescription = "goal_operation: create, inspect, pause, resume, reprioritize, complete, list, or revise persistent goals.",
        payloadGuidance = "Strict JSON with an operation field and the required goal arguments.",
        payloadSchemaExample = """
            {"operation":"create","title":"Inbox cleanup","instruction":"Keep my inbox triaged daily","priority":"HIGH","completion_criteria":"Inbox is triaged and rules are documented"}
        """.trimIndent(),
        requiresFollowUpThought = false,
        followUpPrefix = "Goal operation completed.",
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
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

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = parsePayload(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid goal_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val operation = payload.operation
            ?.trim()
            ?.uppercase()
            ?.let { runCatching { GoalOperation.valueOf(it) }.getOrNull() }
            ?: return ActionOutcome(
                statusSummary = "Unknown goal operation '${payload.operation}'.",
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
                reason = payload.reason,
            )
        )
        return ActionOutcome(
            statusSummary = result.message,
            assistantOutput = result.message,
            executionStatus = if (result.success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
        )
    }

    private fun parsePayload(raw: String): ProjectOperationPayload? =
        runCatching { mapper.readValue<ProjectOperationPayload>(raw) }.getOrNull()

    private data class ProjectOperationPayload(
        val operation: String? = null,
        val goalId: String? = null,
        val title: String? = null,
        val instruction: String? = null,
        val priority: String? = null,
        val completionCriteria: String? = null,
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
