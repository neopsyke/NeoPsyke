package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.GoalCommand
import ai.neopsyke.agent.ego.planner.model.SerializedGoalCommand
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
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
 * L2 sub-planner: semantic goal creation. No regex heuristics.
 * All goal parameters including recurring intent and cron expression
 * are resolved by the LLM.
 */
class GoalCreationPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        if (ActionType.GOAL_OPERATION !in context.dispatchableActions) {
            return unavailableGoalCreationDecision()
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "goal_creation",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "goal_creation_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 56,
                content = """
                    You convert a user request into a persistent goal creation request.
                    Return STRICT JSON only.
                    The instruction must describe what the goal should accomplish on each run.
                    Prefer short, concrete titles.
                    Do not mention JSON, tools, cron syntax, or internal systems in the title/instruction.
                    If the user requests a recurring schedule, set cron_expression to a standard 5-field cron string (minute hour day-of-month month day-of-week).
                    Examples: "every 5 minutes" -> "*/5 * * * *", "daily at 9:30 am" -> "30 9 * * *", "every Monday at 8am" -> "0 8 * * 1", "hourly" -> "0 * * * *".
                    If the request is not recurring, set cron_expression to null.
                    If the request does not clearly specify a persistent goal, set decision=fallback and provide a helpful response.
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "goal_creation_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 36,
                content = """
                    JSON schema:
                    {
                      "decision":"create_goal|fallback",
                      "title":"short title",
                      "instruction":"what the goal should do on each run",
                      "completion_criteria":"how to tell the current run succeeded",
                      "priority":"low|medium|high|critical",
                      "cron_expression":"5-field cron string or null",
                      "assistant_response":"required when decision=fallback",
                      "reason":"optional short note"
                    }
                """.trimIndent()
            ),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            PromptBudgetAllocator.Section(
                key = "goal_creation_trigger",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 28,
                content = "User request:\n${trigger.input.content}"
            )
        )

        val allocation = PromptBudgetAllocator.allocate(
            sections,
            minOf(config.maxLlmPromptTokens, GOAL_CREATION_PROMPT_MAX_TOKENS)
        )
        runtime.emitPromptBudgetTelemetry(LaneId.GOAL_CREATION, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.GOAL_CREATION,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = GOAL_CREATION_FORMAT,
        )

        val responsePayload = response?.let { StructuredOutputHandler.parseWithRepair<GoalCreationPayload>(it.content) }
        val spec = responsePayload
            ?.takeIf { it.decision.equals("create_goal", ignoreCase = true) }
            ?.takeIf { !it.instruction.isNullOrBlank() }

        if (spec == null) {
            // Fallback: if the LLM produced a fallback response, deliver it as contact_user
            if (responsePayload?.assistantResponse?.isNotBlank() == true) {
                return EgoDecision.FormIntention(
                    urgency = Urgency.MEDIUM,
                    intentionKind = IntentionKind.OBSERVE,
                    commitModePreference = CommitMode.NOT_APPLICABLE,
                    actionType = ActionType.CONTACT_USER,
                    payload = TextSecurity.clamp(responsePayload.assistantResponse.trim(), config.maxActionPayloadChars),
                    summary = "Respond to goal-related request",
                )
            }
            return EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = IntentionKind.OBSERVE,
                commitModePreference = CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(
                    "I couldn't safely create a persistent goal from that. Please specify the goal title, what it should do, and whether it should recur.",
                    config.maxActionPayloadChars
                ),
                summary = "Ask for goal creation clarification",
            )
        }

        val priority = spec.priority?.trim()?.uppercase()
            ?.let { runCatching { GoalPriority.valueOf(it) }.getOrNull() }
            ?: GoalPriority.MEDIUM

        val command = GoalCommand.Create(
            title = TextSecurity.clamp(spec.title?.trim().orEmpty().ifBlank { "Persistent goal" }, GOAL_TITLE_MAX_CHARS),
            instruction = TextSecurity.clamp(spec.instruction!!.trim(), GOAL_INSTRUCTION_MAX_CHARS),
            priority = priority,
            completionCriteria = TextSecurity.clamp(
                spec.completionCriteria?.trim().orEmpty().ifBlank { DEFAULT_COMPLETION_CRITERIA },
                GOAL_COMPLETION_CRITERIA_MAX_CHARS
            ),
            cronExpression = spec.cronExpression?.trim()?.ifBlank { null },
        )

        val serializedPayload = StructuredOutputHandler.mapper.writeValueAsString(
            SerializedGoalCommand.fromGoalCommand(command)
        )

        val summaryPrefix = if (command.cronExpression.isNullOrBlank()) "Create persistent goal" else "Create recurring goal"
        return EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.PREPARE,
            commitModePreference = ai.neopsyke.agent.ego.planner.runtime.DecisionValidation.preferredCommitMode(
                context.allowedCommitModes, IntentionKind.PREPARE
            ),
            actionType = ActionType.GOAL_OPERATION,
            payload = TextSecurity.clamp(serializedPayload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp("$summaryPrefix: ${command.title}", config.maxActionSummaryChars),
        )
    }

    private fun unavailableGoalCreationDecision(): EgoDecision =
        EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.OBSERVE,
            commitModePreference = CommitMode.NOT_APPLICABLE,
            actionType = ActionType.CONTACT_USER,
            payload = "Persistent goals are unavailable in this run. Restart with goals enabled to create recurring reminders or monitoring tasks.",
            summary = "Explain that persistent goals are disabled"
        )

    private data class GoalCreationPayload(
        val decision: String? = null,
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

    companion object {
        const val GOAL_TITLE_MAX_CHARS: Int = 80
        const val GOAL_INSTRUCTION_MAX_CHARS: Int = 400
        const val GOAL_COMPLETION_CRITERIA_MAX_CHARS: Int = 200
        const val GOAL_CREATION_PROMPT_MAX_TOKENS: Int = 900
        const val DEFAULT_COMPLETION_CRITERIA: String = "User confirms the goal is met."

        val GOAL_CREATION_FORMAT = ChatResponseFormat.JsonSchema(
            name = "goal_creation",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["decision", "title", "instruction", "completion_criteria", "priority", "cron_expression", "assistant_response", "reason"],
                  "properties": {
                    "decision": { "type": "string", "enum": ["create_goal", "fallback"] },
                    "title": { "type": ["string", "null"], "maxLength": 80 },
                    "instruction": { "type": ["string", "null"], "maxLength": 400 },
                    "completion_criteria": { "type": ["string", "null"], "maxLength": 200 },
                    "priority": { "type": ["string", "null"], "enum": ["low", "medium", "high", "critical", null] },
                    "cron_expression": { "type": ["string", "null"], "maxLength": 40 },
                    "assistant_response": { "type": ["string", "null"], "maxLength": 220 },
                    "reason": { "type": ["string", "null"], "maxLength": 160 }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
