package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.GoalCommand
import ai.neopsyke.agent.ego.planner.model.GoalReference
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
 * L2 sub-planner: semantic goal management operations on existing goals.
 * No text heuristics for goal ID resolution -- the LLM resolves references.
 */
class GoalManagementPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "goal_management",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val goalsInfo = context.goalWorkSummary.ifBlank { "No active goals." }

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "goal_mgmt_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 48,
                content = """
                    You determine which goal management operation the user wants and resolve the goal reference.
                    Return STRICT JSON only.
                    Operations: list, status, pause, resume, complete, delete, delete_all, update, revise_plan, reprioritize
                    Goal reference resolution:
                    - If you can confidently identify the goal, return type=by_id with the goal's number or exact ID.
                    - If the user's reference is ambiguous (multiple matches), return type=ambiguous with candidate IDs.
                    - If no goal matches, return type=unresolved.
                    - Never silently guess when uncertain -- return ambiguous instead.
                    For delete_all, goal_reference is not required.
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "goal_mgmt_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                floorTokens = 24,
                content = """
                    JSON schema:
                    {
                      "operation":"list|status|pause|resume|complete|delete|delete_all|update|revise_plan|reprioritize",
                      "goal_reference":{"type":"by_id|by_resolved|ambiguous|unresolved","id":"...","candidates":["..."],"original_text":"...","resolved_from":"..."},
                      "params":{"title":"...","instruction":"...","priority":"...","completion_criteria":"...","cron_expression":"...","reason":"...","new_priority":"..."}
                    }
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "goal_mgmt_active_goals",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "Active goals:\n$goalsInfo"
            ),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            PromptBudgetAllocator.Section(
                key = "goal_mgmt_trigger",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "User request:\n${trigger.input.content}"
            )
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(LaneId.GOAL_MANAGEMENT, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.GOAL_MANAGEMENT,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = GOAL_MGMT_FORMAT,
            temperature = 0.0,
            maxTokens = 400,
        )

        if (response == null) {
            return EgoDecision.Noop("GoalManagementPlanner unavailable.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<GoalMgmtPayload>(response.content)
        if (payload == null) {
            return EgoDecision.Noop("GoalManagementPlanner parse failure.")
        }

        val operation = payload.operation?.trim()?.lowercase()
        if (operation.isNullOrBlank()) {
            return EgoDecision.Noop("GoalManagementPlanner returned missing operation.")
        }

        // Resolve goal reference
        val ref = resolveGoalReference(payload.goalReference)

        // For ambiguous or unresolved references, return clarification
        if (ref is GoalReference.Ambiguous) {
            return EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = IntentionKind.OBSERVE,
                commitModePreference = CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = "I found multiple goals that might match: ${ref.candidates.joinToString(", ")}. Which one did you mean?",
                summary = "Ask for goal clarification",
            )
        }
        if (ref is GoalReference.Unresolved && operation != "list" && operation != "delete_all") {
            return EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = IntentionKind.OBSERVE,
                commitModePreference = CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = "I couldn't find a goal matching '${ref.originalText}'. Please check the goal name or number.",
                summary = "Goal not found",
            )
        }

        // Build typed command payload
        val goalId = when (ref) {
            is GoalReference.ByInternalId -> ref.id
            is GoalReference.ByResolvedEntity -> ref.goalId
            else -> null
        }
        val params = payload.params

        val commandPayload = buildMap<String, Any?> {
            put("command", operation)
            goalId?.let { put("goal_id", it) }
            when (operation) {
                "update" -> {
                    params?.title?.let { put("title", it) }
                    params?.instruction?.let { put("instruction", it) }
                    params?.priority?.let { put("priority", it.uppercase()) }
                    params?.completionCriteria?.let { put("completion_criteria", it) }
                    params?.cronExpression?.let { put("cron_expression", it) }
                }
                "revise_plan" -> params?.reason?.let { put("reason", it) }
                "reprioritize" -> params?.newPriority?.let { put("priority", it.uppercase()) }
            }
        }

        val serialized = StructuredOutputHandler.mapper.writeValueAsString(commandPayload)
        return EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.PREPARE,
            commitModePreference = ai.neopsyke.agent.ego.planner.runtime.DecisionValidation.preferredCommitMode(
                context.allowedCommitModes, IntentionKind.PREPARE
            ),
            actionType = ActionType.GOAL_OPERATION,
            payload = TextSecurity.clamp(serialized, config.maxActionPayloadChars),
            summary = TextSecurity.clamp("Goal operation: $operation${goalId?.let { " on $it" } ?: ""}", config.maxActionSummaryChars),
        )
    }

    private fun resolveGoalReference(raw: GoalReferencePayload?): GoalReference {
        if (raw == null) return GoalReference.Unresolved("")
        return when (raw.type?.trim()?.lowercase()) {
            "by_id" -> GoalReference.ByInternalId(raw.id?.trim().orEmpty())
            "by_resolved" -> GoalReference.ByResolvedEntity(
                goalId = raw.id?.trim().orEmpty(),
                resolvedFrom = raw.resolvedFrom?.trim().orEmpty(),
            )
            "ambiguous" -> GoalReference.Ambiguous(
                candidates = raw.candidates.orEmpty(),
                originalText = raw.originalText?.trim().orEmpty(),
            )
            "unresolved" -> GoalReference.Unresolved(raw.originalText?.trim().orEmpty())
            else -> GoalReference.Unresolved(raw.originalText?.trim().orEmpty())
        }
    }

    private data class GoalMgmtPayload(
        val operation: String? = null,
        @param:JsonProperty("goal_reference")
        val goalReference: GoalReferencePayload? = null,
        val params: GoalMgmtParams? = null,
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

    private data class GoalMgmtParams(
        val title: String? = null,
        val instruction: String? = null,
        val priority: String? = null,
        @param:JsonProperty("completion_criteria")
        val completionCriteria: String? = null,
        @param:JsonProperty("cron_expression")
        val cronExpression: String? = null,
        val reason: String? = null,
        @param:JsonProperty("new_priority")
        val newPriority: String? = null,
    )

    private companion object {
        val GOAL_MGMT_FORMAT = ChatResponseFormat.JsonSchema(
            name = "goal_management",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["operation", "goal_reference", "params"],
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
                      "required": ["type"],
                      "additionalProperties": false
                    },
                    "params": {
                      "type": ["object", "null"],
                      "properties": {
                        "title": { "type": ["string", "null"] },
                        "instruction": { "type": ["string", "null"] },
                        "priority": { "type": ["string", "null"] },
                        "completion_criteria": { "type": ["string", "null"] },
                        "cron_expression": { "type": ["string", "null"] },
                        "reason": { "type": ["string", "null"] },
                        "new_priority": { "type": ["string", "null"] }
                      },
                      "additionalProperties": false
                    }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
