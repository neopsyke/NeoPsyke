package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
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
import ai.neopsyke.agent.goal.GoalPriority

private val logger = KotlinLogging.logger {}

/**
 * Goal operation action plugin. Refactored to accept typed GoalCommand payloads
 * from the hierarchical planner. No deterministic text heuristics for operation
 * normalization or goal reference resolution.
 *
 * Payload contract: JSON with a "command" field (canonical operation name from
 * GoalCommand.operationName) and typed goal parameters. The "goal_id" field
 * is pre-resolved by the planner's LLM; this plugin validates and executes.
 */
class GoalOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.GOAL_OPERATION,
        dispatchable = context.config.goals.enabled,
        plannerDescription = "goal_operation: create, update, status, list, pause, resume, reprioritize, complete, delete, delete_all, or revise_plan persistent goals, including recurring cron-backed reminders.",
        payloadGuidance = "Strict JSON with a command field and the required goal arguments. goal_id is the goal number or exact ID resolved by the planner. For recurring goals, include cron_expression.",
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
        val payload = parsePayload(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_invalid_payload",
                reason = "GOAL_OPERATION payload must be valid JSON."
            )
        val operation = resolveOperation(payload)
        if (operation.isNullOrBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "goal_operation_missing_operation",
                reason = "GOAL_OPERATION payload requires a command or operation field."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override fun repairPlannerPayload(raw: String): String {
        val payload = parsePayload(raw) ?: return raw
        // Resolve goal_id: exact ID match or numeric index only (no fuzzy text matching)
        val resolvedGoalId = resolveGoalIdTyped(payload.goalId?.trim()?.ifBlank { null })
        return mapper.writeValueAsString(
            payload.copy(
                goalId = resolvedGoalId,
                title = payload.title?.trim()?.ifBlank { null },
                instruction = payload.instruction?.trim()?.ifBlank { null },
                priority = payload.priority?.trim()?.uppercase()?.ifBlank { null },
                completionCriteria = payload.completionCriteria?.trim()?.ifBlank { null },
                cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
                reason = payload.reason?.trim()?.ifBlank { null },
            )
        )
    }

    /**
     * Resolves a goal_id using only typed/exact matching:
     * 1. Exact internal ID match
     * 2. Numeric index (1-based)
     * No case-insensitive title matching or fuzzy token-overlap scoring.
     * Goal references are resolved semantically by the planner's LLM.
     */
    private fun resolveGoalIdTyped(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val goals = context.goalsGateway.allGoals()
        if (goals.isEmpty()) return raw

        // 1. Exact internal ID match
        goals.firstOrNull { it.goalId == raw }?.let { return it.goalId }

        // 2. Numeric index (1-based, matching numbered list shown to planner)
        raw.toIntOrNull()?.let { index ->
            if (index in 1..goals.size) {
                val resolved = goals[index - 1].goalId
                logger.info { "goal_id resolved: numeric index '$raw' -> '$resolved'" }
                return resolved
            }
        }

        logger.warn { "goal_id unresolved: '$raw' did not match any goal by ID or index. available=${goals.map { it.goalId }}" }
        return raw
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = parsePayload(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid goal_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val operationStr = resolveOperation(payload)
            ?.uppercase()
            ?.let { runCatching { GoalOperation.valueOf(it) }.getOrNull() }
            ?: return ActionOutcome(
                statusSummary = "Unknown goal operation '${resolveOperation(payload) ?: "null"}'.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val result = this.context.goalsGateway.executeOperation(
            GoalOperationRequest(
                operation = operationStr,
                goalId = payload.goalId,
                title = payload.title,
                instruction = payload.instruction,
                priority = payload.priority
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { GoalPriority.valueOf(it) }.getOrNull() },
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

    private fun parsePayload(raw: String): GoalOperationPayload? =
        runCatching { mapper.readValue<GoalOperationPayload>(raw) }.getOrNull()

    /**
     * Resolves the operation from the typed "command" field or legacy "operation" field.
     * No semantic normalization -- the planner emits canonical operation names.
     */
    private fun resolveOperation(payload: GoalOperationPayload): String? {
        // Prefer "command" (typed GoalCommand payload) over "operation" (legacy)
        val command = payload.command?.trim()?.lowercase()?.ifBlank { null }
        if (command != null) return command
        return payload.operation?.trim()?.lowercase()?.ifBlank { null }
    }

    private data class GoalOperationPayload(
        val command: String? = null,
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
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}

class GoalOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        GoalOperationActionPlugin(context)
}
