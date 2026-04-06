package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.GoalCommand
import ai.neopsyke.agent.ego.planner.model.GoalReference
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
                    - If you can confidently identify the goal, return type=by_internal_id with the goal's number or exact ID.
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
                      "goal_reference":{"type":"by_internal_id|by_resolved_entity|ambiguous|unresolved","id":"...","candidates":["..."],"original_text":"...","resolved_from":"..."},
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

        val command = buildGoalCommand(
            operation = operation,
            reference = ref,
            params = payload.params,
        ) ?: return EgoDecision.Noop("GoalManagementPlanner returned invalid typed command.")

        val serialized = StructuredOutputHandler.mapper.writeValueAsString(
            SerializedGoalCommand.fromGoalCommand(command)
        )
        val referenceLabel = when (val commandReference = commandReference(command)) {
            is GoalReference.ByInternalId -> commandReference.id
            is GoalReference.ByResolvedEntity -> commandReference.goalId
            else -> null
        }

        return EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.PREPARE,
            commitModePreference = ai.neopsyke.agent.ego.planner.runtime.DecisionValidation.preferredCommitMode(
                context.allowedCommitModes, IntentionKind.PREPARE
            ),
            actionType = ActionType.GOAL_OPERATION,
            payload = TextSecurity.clamp(serialized, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(
                "Goal operation: ${command.operationName}${referenceLabel?.let { " on $it" } ?: ""}",
                config.maxActionSummaryChars
            ),
        )
    }

    private fun buildGoalCommand(
        operation: String,
        reference: GoalReference,
        params: GoalMgmtParams?,
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
                    title = params?.title?.trim()?.ifBlank { null },
                    instruction = params?.instruction?.trim()?.ifBlank { null },
                    priority = params?.priority?.trim()?.uppercase()?.let { raw ->
                        runCatching { GoalPriority.valueOf(raw) }.getOrNull()
                    },
                    completionCriteria = params?.completionCriteria?.trim()?.ifBlank { null },
                    cronExpression = params?.cronExpression?.trim()?.ifBlank { null },
                )
            }
            "revise_plan" -> resolvedReference(reference)?.let {
                GoalCommand.RevisePlan(
                    reference = it,
                    reason = params?.reason?.trim()?.ifBlank { null },
                )
            }
            "reprioritize" -> {
                val parsedPriority = params?.newPriority?.trim()?.uppercase()?.let { raw ->
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

    private fun resolveGoalReference(raw: GoalReferencePayload?): GoalReference {
        if (raw == null) return GoalReference.Unresolved("")
        return when (raw.type?.trim()?.lowercase()) {
            "by_internal_id", "by_id" -> GoalReference.ByInternalId(raw.id?.trim().orEmpty())
            "by_resolved_entity", "by_resolved" -> GoalReference.ByResolvedEntity(
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
