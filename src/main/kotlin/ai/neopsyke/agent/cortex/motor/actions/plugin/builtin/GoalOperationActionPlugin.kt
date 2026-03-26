package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import com.fasterxml.jackson.annotation.JsonAlias
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

class GoalOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.GOAL_OPERATION,
        dispatchable = context.config.goals.enabled,
        plannerDescription = "goal_operation: create, update, status, list, pause, resume, reprioritize, complete, delete, delete_all, or revise_plan persistent goals, including recurring cron-backed reminders.",
        payloadGuidance = "Strict JSON with an operation field and the required goal arguments. goal_id must be the goal number (e.g. \"1\") or the exact title (e.g. \"Daily weather notification\"). Use update to change a goal's schedule, instruction, or title. Use delete_all to remove every goal. For recurring goals, include cron_expression.",
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
        val resolvedGoalId = resolveGoalId(payload.goalId?.trim()?.ifBlank { null })
        return mapper.writeValueAsString(
            payload.copy(
                operation = normalizeOperation(payload),
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
     * Resolves a planner-provided goal_id (which may be a numeric index like "1",
     * a title like "Daily weather notification", or an exact internal ID) to the
     * real internal goal ID.
     */
    private fun resolveGoalId(raw: String?): String? {
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

        // 3. Case-insensitive exact title match
        val rawLower = raw.lowercase()
        goals.firstOrNull { it.title.lowercase() == rawLower }?.let {
            logger.info { "goal_id resolved: title match '$raw' -> '${it.goalId}'" }
            return it.goalId
        }

        // 4. Fuzzy title match — token overlap
        val rawTokens = rawLower.split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (rawTokens.isNotEmpty()) {
            val best = goals.maxByOrNull { goal ->
                val goalTokens = goal.title.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
                if (goalTokens.isEmpty()) 0.0
                else rawTokens.intersect(goalTokens).size.toDouble() / maxOf(rawTokens.size, goalTokens.size)
            }
            if (best != null) {
                val bestTokens = best.title.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
                val overlap = if (bestTokens.isEmpty()) 0.0
                else rawTokens.intersect(bestTokens).size.toDouble() / maxOf(rawTokens.size, bestTokens.size)
                if (overlap >= GOAL_TITLE_FUZZY_MATCH_THRESHOLD) {
                    logger.info { "goal_id resolved: fuzzy title match '$raw' -> '${best.goalId}' (overlap=${"%.2f".format(overlap)})" }
                    return best.goalId
                }
            }
        }

        logger.warn { "goal_id unresolved: '$raw' did not match any goal. available=${goals.map { it.goalId }}" }
        return raw
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

    private fun parsePayload(raw: String): ProjectOperationPayload? =
        runCatching { mapper.readValue<ProjectOperationPayload>(raw) }.getOrNull()

    private fun normalizeOperation(payload: ProjectOperationPayload): String? {
        val operation = payload.operation?.trim()?.lowercase()?.ifBlank { return null } ?: return null
        val hasGoalId = !payload.goalId.isNullOrBlank()
        val deleteAllIntent = looksLikeDeleteAllIntent(payload)
        return when (operation) {
            "inspect" -> if (hasGoalId) "status" else "list"
            "revise" -> if (deleteAllIntent) "delete_all" else "revise_plan"
            "update", "modify", "change" -> "update"
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
        const val GOAL_TITLE_FUZZY_MATCH_THRESHOLD = 0.5
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

class GoalOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        GoalOperationActionPlugin(context)
}
