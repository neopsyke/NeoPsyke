package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.GoalCommand
import ai.neopsyke.agent.ego.planner.model.GoalReference
import ai.neopsyke.agent.ego.planner.model.SerializedGoalCommand
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.DecisionValidation
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.goal.GoalPriority
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole

/**
 * L2 sub-planner: unified goal planner handling both creation and management.
 * Merged from GoalCreationPlanner and GoalManagementPlanner to eliminate the
 * hard classification boundary between "create" and "manage" in the router.
 *
 * The LLM decides the operation type (create, list, pause, etc.) in a single
 * call, removing the need for the InputIntentRouter to distinguish between
 * goal creation and goal management.
 */
class GoalPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        if (ActionType.GOAL_OPERATION !in context.dispatchableActions) {
            return unavailableDecision()
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "goal",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val goalsInfo = context.goalWorkSummary.ifBlank { "No active goals." }

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "goal_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 56,
                content = """
                    You are a goal planner. Given a user request about goals, determine and execute the appropriate operation.
                    Return STRICT JSON only.

                    Operations:
                    - create: create a new persistent goal, reminder, or monitoring task.
                    - list: list all active goals.
                    - status: show status of a specific goal.
                    - pause, resume, complete, delete: manage a specific goal's lifecycle.
                    - delete_all: remove all goals.
                    - update: modify a goal's title, instruction, priority, or schedule.
                    - revise_plan: request a new plan for a goal.
                    - reprioritize: change a goal's priority.
                    - fallback: the request is not a clear goal operation.

                    For "create":
                    - The instruction must describe what the goal should accomplish on each run.
                    - Prefer short, concrete titles.
                    - Do not mention JSON, tools, cron syntax, or internal systems in the title/instruction.
                    - If the user requests a recurring schedule, set cron_expression to a standard 5-field cron string (minute hour day-of-month month day-of-week).
                    - Examples: "every 5 minutes" -> "*/5 * * * *", "daily at 9:30 am" -> "30 9 * * *", "every Monday at 8am" -> "0 8 * * 1", "hourly" -> "0 * * * *".
                    - If the request is not recurring, set cron_expression to null.

                    For management operations (list, status, pause, resume, complete, delete, delete_all, update, revise_plan, reprioritize):
                    - Goal reference resolution: the active goals list is numbered starting at 1.
                    - Set goal_reference.id to the NUMBER of the goal from the list (e.g. "1", "2", "3").
                    - Do NOT return the goal's internal ID or title, only the number.
                    - If the user's reference clearly matches one goal, set type=by_position with that number.
                    - If the user's reference is ambiguous (could match more than one), set type=ambiguous with candidate numbers in the candidates array.
                    - If no goal matches, set type=unresolved with original_text set to the user's words.
                    - Never silently guess when uncertain. Return ambiguous instead.
                    - For list, delete_all, and create, goal_reference can be null.

                    For "update": set relevant fields (title, instruction, priority, completion_criteria, cron_expression) to new values; leave others null.
                    For "reprioritize": set priority to the new priority value.
                    For "revise_plan": optionally set reason to explain why.
                    For "fallback": provide a helpful response in assistant_response.
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "goal_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 36,
                content = """
                    JSON schema:
                    {
                      "operation":"create|list|status|pause|resume|complete|delete|delete_all|update|revise_plan|reprioritize|fallback",
                      "goal_reference":{"type":"by_position|ambiguous|unresolved","id":"<number>","candidates":["<numbers>"],"original_text":"<user words>","resolved_from":null},
                      "title":"for create/update",
                      "instruction":"for create/update",
                      "completion_criteria":"for create/update",
                      "priority":"low|medium|high|critical (for create/update/reprioritize)",
                      "cron_expression":"5-field cron or null (for create/update)",
                      "assistant_response":"required when operation=fallback",
                      "reason":"optional"
                    }
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "goal_active_goals",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "Active goals:\n$goalsInfo"
            ),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            PromptBudgetAllocator.Section(
                key = "goal_trigger",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "User request:\n${trigger.input.content}"
            )
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(LaneId.GOAL, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.GOAL,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = GOAL_RESPONSE_FORMAT,
        )

        if (response == null) {
            return EgoDecision.Noop("GoalPlanner unavailable.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<GoalPayload>(response.content)
        if (payload == null) {
            return EgoDecision.Noop("GoalPlanner parse failure.")
        }

        val operation = payload.operation?.trim()?.lowercase()
        if (operation.isNullOrBlank()) {
            return EgoDecision.Noop("GoalPlanner returned missing operation.")
        }

        return when (operation) {
            "create" -> handleCreate(payload, context)
            "fallback" -> handleFallback(payload)
            else -> handleManagement(operation, payload, context)
        }
    }

    private fun handleCreate(payload: GoalPayload, context: PlannerContext): EgoDecision {
        val title = payload.title?.trim().orEmpty()
        val instruction = payload.instruction?.trim().orEmpty()

        if (instruction.isBlank()) {
            if (payload.assistantResponse?.isNotBlank() == true) {
                return contactUser(payload.assistantResponse.trim(), "Respond to goal-related request")
            }
            return contactUser(
                "I couldn't safely create a persistent goal from that. Please specify the goal title, what it should do, and whether it should recur.",
                "Ask for goal creation clarification",
            )
        }

        val priority = payload.priority?.trim()?.uppercase()
            ?.let { runCatching { GoalPriority.valueOf(it) }.getOrNull() }
            ?: GoalPriority.MEDIUM

        val command = GoalCommand.Create(
            title = TextSecurity.clamp(title.ifBlank { "Persistent goal" }, GOAL_TITLE_MAX_CHARS),
            instruction = TextSecurity.clamp(instruction, GOAL_INSTRUCTION_MAX_CHARS),
            priority = priority,
            completionCriteria = TextSecurity.clamp(
                payload.completionCriteria?.trim().orEmpty().ifBlank { DEFAULT_COMPLETION_CRITERIA },
                GOAL_COMPLETION_CRITERIA_MAX_CHARS,
            ),
            cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
        )

        return goalOperationDecision(command, context)
    }

    private fun handleFallback(payload: GoalPayload): EgoDecision {
        val response = payload.assistantResponse?.trim().orEmpty()
        if (response.isBlank()) {
            return contactUser(
                "I couldn't determine a goal operation from that. Could you clarify what you'd like to do with your goals?",
                "Ask for goal clarification",
            )
        }
        return contactUser(response, "Respond to goal-related request")
    }

    private fun handleManagement(
        operation: String,
        payload: GoalPayload,
        context: PlannerContext,
    ): EgoDecision {
        val ref = resolveGoalReference(payload.goalReference, context.goalIndex)

        if (ref is GoalReference.Ambiguous) {
            return contactUser(
                "I found multiple goals that might match: ${ref.candidates.joinToString(", ")}. Which one did you mean?",
                "Ask for goal clarification",
            )
        }
        if (ref is GoalReference.Unresolved && operation != "list" && operation != "delete_all") {
            return contactUser(
                "I couldn't find a goal matching '${ref.originalText}'. Please check the goal name or number.",
                "Goal not found",
            )
        }

        val command = buildGoalCommand(operation, ref, payload)
            ?: return EgoDecision.Noop("GoalPlanner returned invalid typed command.")

        return goalOperationDecision(command, context)
    }

    private fun buildGoalCommand(
        operation: String,
        reference: GoalReference,
        payload: GoalPayload,
    ): GoalCommand? {
        return when (operation) {
            "list" -> GoalCommand.List
            "delete_all" -> GoalCommand.DeleteAll
            "status" -> resolvedReference(reference)?.let { GoalCommand.Status(it) }
            "pause" -> resolvedReference(reference)?.let { GoalCommand.Pause(it) }
            "resume" -> resolvedReference(reference)?.let { GoalCommand.Resume(it) }
            "complete" -> resolvedReference(reference)?.let { GoalCommand.Complete(it) }
            "delete" -> resolvedReference(reference)?.let { GoalCommand.Delete(it) }
            "update" -> resolvedReference(reference)?.let {
                GoalCommand.Update(
                    reference = it,
                    title = payload.title?.trim()?.ifBlank { null },
                    instruction = payload.instruction?.trim()?.ifBlank { null },
                    priority = payload.priority?.trim()?.uppercase()?.let { raw ->
                        runCatching { GoalPriority.valueOf(raw) }.getOrNull()
                    },
                    completionCriteria = payload.completionCriteria?.trim()?.ifBlank { null },
                    cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
                )
            }
            "revise_plan" -> resolvedReference(reference)?.let {
                GoalCommand.RevisePlan(
                    reference = it,
                    reason = payload.reason?.trim()?.ifBlank { null },
                )
            }
            "reprioritize" -> {
                val parsedPriority = payload.priority?.trim()?.uppercase()?.let { raw ->
                    runCatching { GoalPriority.valueOf(raw) }.getOrNull()
                } ?: return null
                resolvedReference(reference)?.let { GoalCommand.Reprioritize(it, parsedPriority) }
            }
            else -> null
        }
    }

    private fun resolvedReference(reference: GoalReference): GoalReference? =
        when (reference) {
            is GoalReference.ByInternalId -> reference
            is GoalReference.ByResolvedEntity -> reference
            is GoalReference.Ambiguous -> null
            is GoalReference.Unresolved -> null
        }

    private fun resolveGoalReference(raw: GoalReferencePayload?, goalIndex: Map<Int, String>): GoalReference {
        if (raw == null) return GoalReference.Unresolved("")
        return when (raw.type?.trim()?.lowercase()) {
            "by_position", "by_internal_id", "by_id" -> {
                val position = raw.id?.trim()?.toIntOrNull()
                val resolvedId = if (position != null) goalIndex[position] else null
                if (resolvedId != null) {
                    GoalReference.ByInternalId(resolvedId)
                } else {
                    GoalReference.Unresolved(raw.originalText?.trim().orEmpty())
                }
            }
            "ambiguous" -> {
                val resolvedCandidates = raw.candidates.orEmpty().mapNotNull { c ->
                    c.trim().toIntOrNull()?.let { goalIndex[it] }
                }
                GoalReference.Ambiguous(
                    candidates = resolvedCandidates,
                    originalText = raw.originalText?.trim().orEmpty(),
                )
            }
            "unresolved" -> GoalReference.Unresolved(raw.originalText?.trim().orEmpty())
            else -> GoalReference.Unresolved(raw.originalText?.trim().orEmpty())
        }
    }

    private fun goalOperationDecision(command: GoalCommand, context: PlannerContext): EgoDecision {
        val serializedPayload = StructuredOutputHandler.mapper.writeValueAsString(
            SerializedGoalCommand.fromGoalCommand(command)
        )
        val referenceLabel = commandReference(command)?.let { ref ->
            when (ref) {
                is GoalReference.ByInternalId -> ref.id
                is GoalReference.ByResolvedEntity -> ref.goalId
                else -> null
            }
        }

        val summaryPrefix = if (command is GoalCommand.Create) {
            if (command.cronExpression.isNullOrBlank()) "Create persistent goal" else "Create recurring goal"
        } else {
            "Goal operation: ${command.operationName}"
        }

        return EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.PREPARE,
            commitModePreference = DecisionValidation.preferredCommitMode(
                context.allowedCommitModes, IntentionKind.PREPARE
            ),
            actionType = ActionType.GOAL_OPERATION,
            payload = TextSecurity.clamp(serializedPayload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(
                "$summaryPrefix${referenceLabel?.let { " on $it" } ?: if (command is GoalCommand.Create) ": ${(command as GoalCommand.Create).title}" else ""}",
                config.maxActionSummaryChars,
            ),
        )
    }

    private fun commandReference(command: GoalCommand): GoalReference? =
        when (command) {
            is GoalCommand.Status -> command.reference
            is GoalCommand.Pause -> command.reference
            is GoalCommand.Resume -> command.reference
            is GoalCommand.Complete -> command.reference
            is GoalCommand.Delete -> command.reference
            is GoalCommand.Update -> command.reference
            is GoalCommand.RevisePlan -> command.reference
            is GoalCommand.Reprioritize -> command.reference
            is GoalCommand.Create -> null
            is GoalCommand.List -> null
            is GoalCommand.DeleteAll -> null
        }

    private fun contactUser(payload: String, summary: String): EgoDecision =
        EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.OBSERVE,
            commitModePreference = CommitMode.NOT_APPLICABLE,
            actionType = ActionType.CONTACT_USER,
            payload = TextSecurity.clamp(payload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(summary, config.maxActionSummaryChars),
        )

    private fun unavailableDecision(): EgoDecision =
        contactUser(
            "Persistent goals are unavailable in this run. Restart with goals enabled to create recurring reminders or monitoring tasks.",
            "Explain that persistent goals are disabled",
        )

    private data class GoalPayload(
        val operation: String? = null,
        @param:JsonProperty("goal_reference")
        val goalReference: GoalReferencePayload? = null,
        val title: String? = null,
        val instruction: String? = null,
        @param:JsonProperty("completion_criteria")
        val completionCriteria: String? = null,
        val priority: String? = null,
        @param:JsonProperty("cron_expression")
        val cronExpression: String? = null,
        @param:JsonProperty("assistant_response")
        val assistantResponse: String? = null,
        val reason: String? = null,
    )

    private data class GoalReferencePayload(
        val type: String? = null,
        val id: String? = null,
        val candidates: List<String>? = null,
        @param:JsonProperty("original_text")
        val originalText: String? = null,
        @param:JsonProperty("resolved_from")
        val resolvedFrom: String? = null,
    )

    companion object {
        const val GOAL_TITLE_MAX_CHARS: Int = 80
        const val GOAL_INSTRUCTION_MAX_CHARS: Int = 400
        const val GOAL_COMPLETION_CRITERIA_MAX_CHARS: Int = 200
        const val DEFAULT_COMPLETION_CRITERIA: String = "User confirms the goal is met."

        val GOAL_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "goal_operation",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["operation", "goal_reference", "title", "instruction", "completion_criteria", "priority", "cron_expression", "assistant_response", "reason"],
                  "properties": {
                    "operation": { "type": "string" },
                    "goal_reference": {
                      "type": ["object", "null"],
                      "properties": {
                        "type": { "type": "string" },
                        "id": { "type": ["string", "null"] },
                        "candidates": { "type": ["array", "null"], "items": { "type": "string" } },
                        "original_text": { "type": ["string", "null"] },
                        "resolved_from": { "type": ["string", "null"] }
                      },
                      "required": ["type", "id", "candidates", "original_text", "resolved_from"],
                      "additionalProperties": false
                    },
                    "title": { "type": ["string", "null"], "maxLength": 80 },
                    "instruction": { "type": ["string", "null"], "maxLength": 400 },
                    "completion_criteria": { "type": ["string", "null"], "maxLength": 200 },
                    "priority": { "type": ["string", "null"], "enum": ["low", "medium", "high", "critical", null] },
                    "cron_expression": { "type": ["string", "null"], "maxLength": 40 },
                    "assistant_response": { "type": ["string", "null"] },
                    "reason": { "type": ["string", "null"], "maxLength": 160 }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
