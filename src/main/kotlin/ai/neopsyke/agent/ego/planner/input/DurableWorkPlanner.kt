package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.ActionSummary
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlanKind
import ai.neopsyke.agent.ego.planner.PlanRefiner
import ai.neopsyke.agent.ego.planner.PlanRefinementMode
import ai.neopsyke.agent.ego.planner.PlanRefinementRequest
import ai.neopsyke.agent.ego.planner.PlanStepCandidate
import ai.neopsyke.agent.ego.planner.TerminalPolicy
import ai.neopsyke.agent.ego.planner.model.DurableWorkCommand
import ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload
import ai.neopsyke.agent.ego.planner.model.WorkItemReference
import ai.neopsyke.agent.ego.planner.model.SerializedDurableWorkCommand
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.DecisionValidation
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.durablework.WorkItemPriority
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.DurableWorkItemSnapshot
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * L2 sub-planner: unified goal planner handling both creation and management.
 * Merged from GoalCreationPlanner and GoalManagementPlanner to eliminate the
 * hard classification boundary between "create" and "manage" in the router.
 *
 * The LLM decides the operation type (create, list, pause, etc.) in a single
 * call, removing the need for the InputIntentRouter to distinguish between
 * goal creation and goal management.
 */
class WorkPlanBuilder(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val planRefiner: PlanRefiner,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        if (ActionType.DURABLE_WORK_OPERATION !in context.availableActions) {
            return unavailableDecision()
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "goal",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val goalsInfo = context.goalWorkSummary.ifBlank { "No active goals." }
        val availableChannels = context.availableContactChannels
        val channelsLine = if (availableChannels.isEmpty()) {
            "No delivery channels are currently available; set contact_channel to null."
        } else {
            "Available delivery channels: ${availableChannels.sorted().joinToString(", ")}."
        }

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
                    - contact_channel MUST be one of the currently-available delivery channels (listed below) or null. Map the user's intent semantically: "via Telegram" or "send me a text" -> "telegram" if available; "on the dashboard" or "in the app" -> "dashboard" if available. If the user's intent does not match any available channel, set contact_channel to null (do NOT invent a channel name); the runtime will ask them to clarify.
                    - Always generate plan_steps for create: a short list of concrete execution steps.
                    - Each step should map to an available action (web_search, website_fetch, contact_user, etc.).
                    - Steps that fetch external data should set grounding_requirement to "required".
                    - The final step should deliver the result to the user (contact_user).
                    - Runtime facts (date, time, timezone) are always available; do NOT create steps for these.
                    - Step outputs flow forward via requires/produces, so later steps can reference earlier step outputs.

                    For management operations (list, status, pause, resume, complete, delete, delete_all, update, revise_plan, reprioritize):
                    - Goal reference resolution: the active goals list is numbered starting at 1.
                    - Set work_item_reference.id to the NUMBER of the goal from the list (e.g. "1", "2", "3").
                    - Do NOT return the goal's internal ID or title, only the number.
                    - If the user's reference clearly matches one goal, set type=by_position with that number.
                    - If the user's reference is ambiguous (could match more than one), set type=ambiguous with candidate numbers in the candidates array.
                    - If no goal matches, set type=unresolved with original_text set to the user's words.
                    - Never silently guess when uncertain. Return ambiguous instead.
                    - For list, delete_all, and create, work_item_reference can be null.

                    For "update": set relevant fields (title, instruction, priority, completion_criteria, cron_expression, contact_channel) to new values; leave others null.
                    For "reprioritize": set priority to the new priority value.
                    For "revise_plan": set reason to explain why, and generate new plan_steps with the revised execution plan.
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
                      "work_item_reference":{"type":"by_position|ambiguous|unresolved","id":"<number>","candidates":["<numbers>"],"original_text":"<user words>","resolved_from":null},
                      "title":"for create/update",
                      "instruction":"for create/update",
                      "completion_criteria":"for create/update",
                      "priority":"low|medium|high|critical (for create/update/reprioritize)",
                      "cron_expression":"5-field cron or null (for create/update)",
                      "contact_channel":"delivery channel name or null (for create/update)",
                      "plan_steps":[{"id":"step1","description":"...","acceptance_criteria":"...","grounding_requirement":"required|not_required","requires":[],"produces":["artifact"],"max_attempts":3}],
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
            PromptBudgetAllocator.Section(
                key = "goal_available_channels",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 12,
                content = channelsLine,
            ),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.actionAvailabilitySection(context),
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
            return EgoDecision.Noop("WorkPlanBuilder unavailable.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<GoalPayload>(response.content)
        if (payload == null) {
            return EgoDecision.Noop("WorkPlanBuilder parse failure.")
        }

        val operation = payload.operation?.trim()?.lowercase()
        if (operation.isNullOrBlank()) {
            return EgoDecision.Noop("WorkPlanBuilder returned missing operation.")
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
        logger.debug {
            "handleCreate: title='${title.take(60)}' instruction='${instruction.take(80)}' " +
                "cron=${payload.cronExpression} raw_plan_steps=${payload.planSteps?.size ?: 0}"
        }

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
            ?.let { runCatching { WorkItemPriority.valueOf(it) }.getOrNull() }
            ?: WorkItemPriority.MEDIUM

        val rawPlanSteps = payload.planSteps
            ?.mapNotNull { step ->
                val desc = step.description?.trim().orEmpty()
                if (desc.isBlank()) null
                else DurableWorkPlanStepPayload(
                    id = step.id?.trim()?.ifBlank { null },
                    description = desc,
                    acceptanceCriteria = step.acceptanceCriteria?.trim().orEmpty(),
                    groundingRequirement = step.groundingRequirement?.trim()?.lowercase(),
                    requires = step.requires.orEmpty().toSet(),
                    produces = step.produces.orEmpty().toSet(),
                    maxAttempts = step.maxAttempts?.coerceIn(1, 10),
                )
            }
            ?.take(config.planner.maxPlanSteps)
            ?.takeIf { it.isNotEmpty() }

        val planSteps = refineDurableWorkPlan(
            rawSteps = rawPlanSteps,
            planKind = PlanKind.DURABLE_WORK_CREATE,
            goal = title.ifBlank { instruction },
            instruction = instruction,
            completionCriteria = payload.completionCriteria?.trim().orEmpty(),
            context = context,
            cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
        )

        val command = DurableWorkCommand.Create(
            title = TextSecurity.clamp(title.ifBlank { "Persistent goal" }, GOAL_TITLE_MAX_CHARS),
            instruction = TextSecurity.clamp(instruction, GOAL_INSTRUCTION_MAX_CHARS),
            priority = priority,
            completionCriteria = TextSecurity.clamp(
                payload.completionCriteria?.trim().orEmpty().ifBlank { DEFAULT_COMPLETION_CRITERIA },
                GOAL_COMPLETION_CRITERIA_MAX_CHARS,
            ),
            cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
            contactChannel = payload.contactChannel?.trim()?.lowercase()?.ifBlank { null },
            planSteps = planSteps,
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
        val ref = resolveWorkItemReference(payload.workItemReference, context.goalIndex)
        logger.debug {
            "handleManagement: operation=$operation reference_type=${ref.javaClass.simpleName}"
        }

        if (ref is WorkItemReference.Ambiguous) {
            return contactUser(
                "I found multiple goals that might match: ${ref.candidates.joinToString(", ")}. Which one did you mean?",
                "Ask for goal clarification",
            )
        }
        if (ref is WorkItemReference.Unresolved && operation != "list" && operation != "delete_all") {
            return contactUser(
                "I couldn't find a goal matching '${ref.originalText}'. Please check the goal name or number.",
                "Goal not found",
            )
        }

        val command = buildDurableWorkCommand(operation, ref, payload, context)
            ?: return EgoDecision.Noop("WorkPlanBuilder returned invalid typed command.")

        return goalOperationDecision(command, context)
    }

    private fun buildDurableWorkCommand(
        operation: String,
        reference: WorkItemReference,
        payload: GoalPayload,
        context: PlannerContext,
    ): DurableWorkCommand? {
        return when (operation) {
            "list" -> DurableWorkCommand.List
            "delete_all" -> DurableWorkCommand.DeleteAll
            "status" -> resolvedReference(reference)?.let { DurableWorkCommand.Status(it) }
            "pause" -> resolvedReference(reference)?.let { DurableWorkCommand.Pause(it) }
            "resume" -> resolvedReference(reference)?.let { DurableWorkCommand.Resume(it) }
            "complete" -> resolvedReference(reference)?.let { DurableWorkCommand.Complete(it) }
            "delete" -> resolvedReference(reference)?.let { DurableWorkCommand.Delete(it) }
            "update" -> resolvedReference(reference)?.let {
                DurableWorkCommand.Update(
                    reference = it,
                    title = payload.title?.trim()?.ifBlank { null },
                    instruction = payload.instruction?.trim()?.ifBlank { null },
                    priority = payload.priority?.trim()?.uppercase()?.let { raw ->
                        runCatching { WorkItemPriority.valueOf(raw) }.getOrNull()
                    },
                    completionCriteria = payload.completionCriteria?.trim()?.ifBlank { null },
                    cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
                    contactChannel = payload.contactChannel?.trim()?.lowercase()?.ifBlank { null },
                )
            }
            "revise_plan" -> resolvedReference(reference)?.let { ref ->
                val snapshot = resolvedGoalSnapshot(ref, context)
                val rawPlanSteps = payload.planSteps
                    ?.mapNotNull { step ->
                        val desc = step.description?.trim().orEmpty()
                        if (desc.isBlank()) null
                        else DurableWorkPlanStepPayload(
                            id = step.id?.trim()?.ifBlank { null },
                            description = desc,
                            acceptanceCriteria = step.acceptanceCriteria?.trim().orEmpty(),
                            groundingRequirement = step.groundingRequirement?.trim()?.lowercase(),
                            requires = step.requires.orEmpty().toSet(),
                            produces = step.produces.orEmpty().toSet(),
                            maxAttempts = step.maxAttempts?.coerceIn(1, 10),
                        )
                    }
                    ?.take(config.planner.maxPlanSteps)
                    ?.takeIf { it.isNotEmpty() }
                    ?: snapshot?.planSteps
                        ?.map { step ->
                            DurableWorkPlanStepPayload(
                                id = step.id,
                                description = step.description,
                                acceptanceCriteria = step.acceptanceCriteria,
                                groundingRequirement = "not_required",
                                requires = step.requires,
                                produces = step.produces,
                                maxAttempts = step.maxAttempts,
                            )
                        }
                        ?.take(config.planner.maxPlanSteps)
                        ?.takeIf { it.isNotEmpty() }

                val snapshotContextHint = snapshot?.let { renderRevisionSnapshotContext(it) }

                val refinedSteps = refineDurableWorkPlan(
                    rawSteps = rawPlanSteps,
                    planKind = PlanKind.DURABLE_WORK_REVISE,
                    goal = snapshot?.title?.ifBlank { null }
                        ?: payload.title?.trim()?.ifBlank { null }
                        ?: "Revise plan",
                    instruction = snapshot?.instruction?.ifBlank { null }
                        ?: payload.instruction?.trim().orEmpty(),
                    completionCriteria = snapshot?.completionCriteria.orEmpty(),
                    context = context,
                    userFeedbackHint = payload.reason?.trim(),
                    revisionContextHint = snapshotContextHint,
                )

                DurableWorkCommand.RevisePlan(
                    reference = ref,
                    reason = payload.reason?.trim()?.ifBlank { null },
                    planSteps = refinedSteps,
                )
            }
            "reprioritize" -> {
                val parsedPriority = payload.priority?.trim()?.uppercase()?.let { raw ->
                    runCatching { WorkItemPriority.valueOf(raw) }.getOrNull()
                } ?: return null
                resolvedReference(reference)?.let { DurableWorkCommand.Reprioritize(it, parsedPriority) }
            }
            else -> null
        }
    }

    private fun resolvedReference(reference: WorkItemReference): WorkItemReference? =
        when (reference) {
            is WorkItemReference.ByInternalId -> reference
            is WorkItemReference.ByResolvedEntity -> reference
            is WorkItemReference.Ambiguous -> null
            is WorkItemReference.Unresolved -> null
        }

    private fun resolvedGoalSnapshot(reference: WorkItemReference, context: PlannerContext): DurableWorkItemSnapshot? =
        when (reference) {
            is WorkItemReference.ByInternalId -> context.goalSnapshots[reference.id]
            is WorkItemReference.ByResolvedEntity -> context.goalSnapshots[reference.workItemId]
            is WorkItemReference.Ambiguous -> null
            is WorkItemReference.Unresolved -> null
        }

    private fun resolveWorkItemReference(raw: WorkItemReferencePayload?, goalIndex: Map<Int, String>): WorkItemReference {
        if (raw == null) return WorkItemReference.Unresolved("")
        return when (raw.type?.trim()?.lowercase()) {
            "by_position", "by_internal_id", "by_id" -> {
                val position = raw.id?.trim()?.toIntOrNull()
                val resolvedId = if (position != null) goalIndex[position] else null
                if (resolvedId != null) {
                    WorkItemReference.ByInternalId(resolvedId)
                } else {
                    WorkItemReference.Unresolved(raw.originalText?.trim().orEmpty())
                }
            }
            "ambiguous" -> {
                val resolvedCandidates = raw.candidates.orEmpty().mapNotNull { c ->
                    c.trim().toIntOrNull()?.let { goalIndex[it] }
                }
                WorkItemReference.Ambiguous(
                    candidates = resolvedCandidates,
                    originalText = raw.originalText?.trim().orEmpty(),
                )
            }
            "unresolved" -> WorkItemReference.Unresolved(raw.originalText?.trim().orEmpty())
            else -> WorkItemReference.Unresolved(raw.originalText?.trim().orEmpty())
        }
    }

    private fun goalOperationDecision(command: DurableWorkCommand, context: PlannerContext): EgoDecision {
        val serializedPayload = StructuredOutputHandler.mapper.writeValueAsString(
            SerializedDurableWorkCommand.fromDurableWorkCommand(command)
        )
        val referenceLabel = commandReference(command)?.let { ref ->
            when (ref) {
                is WorkItemReference.ByInternalId -> ref.id
                is WorkItemReference.ByResolvedEntity -> ref.workItemId
                else -> null
            }
        }

        val summaryPrefix = if (command is DurableWorkCommand.Create) {
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
            actionType = ActionType.DURABLE_WORK_OPERATION,
            payload = TextSecurity.clamp(serializedPayload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(
                "$summaryPrefix${referenceLabel?.let { " on $it" } ?: if (command is DurableWorkCommand.Create) ": ${command.title}" else ""}",
                config.maxActionSummaryChars,
            ),
        )
    }

    private fun commandReference(command: DurableWorkCommand): WorkItemReference? =
        when (command) {
            is DurableWorkCommand.Status -> command.reference
            is DurableWorkCommand.Pause -> command.reference
            is DurableWorkCommand.Resume -> command.reference
            is DurableWorkCommand.Complete -> command.reference
            is DurableWorkCommand.Delete -> command.reference
            is DurableWorkCommand.Update -> command.reference
            is DurableWorkCommand.RevisePlan -> command.reference
            is DurableWorkCommand.Reprioritize -> command.reference
            is DurableWorkCommand.Create -> null
            is DurableWorkCommand.List -> null
            is DurableWorkCommand.DeleteAll -> null
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

    private fun refineDurableWorkPlan(
        rawSteps: List<DurableWorkPlanStepPayload>?,
        planKind: PlanKind,
        goal: String,
        instruction: String,
        completionCriteria: String = "",
        context: PlannerContext,
        userFeedbackHint: String? = null,
        revisionContextHint: String? = null,
        cronExpression: String? = null,
    ): List<DurableWorkPlanStepPayload>? {
        if (rawSteps.isNullOrEmpty()) return rawSteps
        if (!config.planner.planRefinementEnabled) return rawSteps

        val candidates = rawSteps.mapIndexed { i, step ->
            PlanStepCandidate(
                id = step.id ?: "step-${i + 1}",
                description = step.description,
                acceptanceCriteria = step.acceptanceCriteria.orEmpty(),
                groundingRequirement = step.groundingRequirement ?: "not_required",
                requires = step.requires,
                produces = step.produces,
                maxAttempts = step.maxAttempts ?: 3,
            )
        }

        val availableActions = context.availableActions.map { actionType ->
            ActionSummary(actionType = actionType.id, description = actionType.id)
        }

        val runtimeFacts = RUNTIME_FACT_KEYS

        val request = PlanRefinementRequest(
            planKind = planKind,
            terminalPolicy = TerminalPolicy.DELIVERY_CONTROLLED_BY_WORK_ITEM,
            goal = goal,
            instruction = instruction,
            completionCriteria = completionCriteria,
            steps = candidates,
            availableActions = availableActions,
            runtimeFacts = runtimeFacts,
            recentDialogue = context.recentDialogue.map { "${it.role.name.lowercase()}: ${it.content}" },
            shortTermContextSummary = mergeContextHints(
                context.shortTermContextSummary,
                revisionContextHint,
            ),
            longTermMemoryRecall = context.longTermMemoryRecall,
            episodicRecall = context.episodicRecall,
            userFeedbackHint = userFeedbackHint,
            cronExpression = cronExpression,
        )

        val result = planRefiner.refine(request)

        instrumentation.emit(
            AgentEvent(
                type = "plan_refinement_completed",
                data = mapOf(
                    "plan_kind" to planKind.name.lowercase(),
                    "refinement_mode" to result.refinementMode.name.lowercase(),
                    "original_step_count" to rawSteps.size,
                    "refined_step_count" to result.steps.size,
                    "dropped_step_count" to result.droppedSteps.size,
                )
            )
        )

        if (result.refinementMode == PlanRefinementMode.UNCHANGED) return rawSteps

        return result.steps.map { step ->
            DurableWorkPlanStepPayload(
                id = step.id.ifBlank { null },
                description = step.description,
                acceptanceCriteria = step.acceptanceCriteria.ifBlank { null },
                groundingRequirement = step.groundingRequirement,
                requires = step.requires,
                produces = step.produces,
                maxAttempts = step.maxAttempts,
            )
        }.takeIf { it.isNotEmpty() } ?: rawSteps
    }

    private fun mergeContextHints(primary: String, secondary: String?): String {
        if (secondary.isNullOrBlank()) return primary
        if (primary.isBlank()) return secondary
        return "$primary\n\n$secondary"
    }

    private fun renderRevisionSnapshotContext(snapshot: DurableWorkItemSnapshot): String = buildString {
        appendLine("Current work-item state:")
        appendLine("- id: ${snapshot.workItemId}")
        appendLine("- title: ${snapshot.title}")
        appendLine("- status: ${snapshot.status.name.lowercase()}")
        appendLine("- plan_revision: ${snapshot.planRevision}")
        appendLine("- failure_count_in_window: ${snapshot.failureCountInWindow}")
        if (!snapshot.latestArtifactSummary.isNullOrBlank()) {
            appendLine("- latest_artifact_summary: ${snapshot.latestArtifactSummary}")
        }
        appendLine("Current plan steps:")
        snapshot.planSteps.forEach { step ->
            appendLine("- ${step.id} [${step.status.name.lowercase()}] attempts=${step.attempts}/${step.maxAttempts}: ${step.description}")
        }
    }.trimEnd()

    private fun unavailableDecision(): EgoDecision =
        contactUser(
            "Persistent goals are unavailable in this run. Restart with goals enabled to create recurring reminders or monitoring tasks.",
            "Explain that persistent goals are disabled",
        )

    private data class GoalPayload(
        val operation: String? = null,
        @param:JsonProperty("work_item_reference")
        val workItemReference: WorkItemReferencePayload? = null,
        val title: String? = null,
        val instruction: String? = null,
        @param:JsonProperty("completion_criteria")
        val completionCriteria: String? = null,
        val priority: String? = null,
        @param:JsonProperty("cron_expression")
        val cronExpression: String? = null,
        @param:JsonProperty("contact_channel")
        val contactChannel: String? = null,
        @param:JsonProperty("plan_steps")
        val planSteps: List<PlanStepPayload>? = null,
        @param:JsonProperty("assistant_response")
        val assistantResponse: String? = null,
        val reason: String? = null,
    )

    private data class PlanStepPayload(
        val id: String? = null,
        val description: String? = null,
        @param:JsonProperty("acceptance_criteria")
        val acceptanceCriteria: String? = null,
        @param:JsonProperty("grounding_requirement")
        val groundingRequirement: String? = null,
        val requires: List<String>? = null,
        val produces: List<String>? = null,
        @param:JsonProperty("max_attempts")
        val maxAttempts: Int? = null,
    )

    private data class WorkItemReferencePayload(
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

        /** Runtime fact keys available to the executor. The refiner needs to know
         *  which facts exist (for non-redundancy checks) but not their volatile
         *  values, which change between runs and break cache hashing. */
        val RUNTIME_FACT_KEYS: Map<String, String> = mapOf(
            "date" to "available at execution time",
            "time" to "available at execution time",
            "timezone" to "available at execution time",
        )

        val GOAL_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "durable_work_operation",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["operation", "work_item_reference", "title", "instruction", "completion_criteria", "priority", "cron_expression", "contact_channel", "plan_steps", "assistant_response", "reason"],
                  "properties": {
                    "operation": { "type": "string" },
                    "work_item_reference": {
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
                    "contact_channel": { "type": ["string", "null"], "maxLength": 40 },
                    "plan_steps": {
                      "type": ["array", "null"],
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["id", "description", "acceptance_criteria", "grounding_requirement", "requires", "produces", "max_attempts"],
                        "properties": {
                          "id": { "type": "string" },
                          "description": { "type": "string" },
                          "acceptance_criteria": { "type": "string" },
                          "grounding_requirement": { "type": "string", "enum": ["required", "not_required"] },
                          "requires": { "type": "array", "items": { "type": "string" } },
                          "produces": { "type": "array", "items": { "type": "string" } },
                          "max_attempts": { "type": "integer", "minimum": 1 }
                        }
                      }
                    },
                    "assistant_response": { "type": ["string", "null"] },
                    "reason": { "type": ["string", "null"], "maxLength": 160 }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
