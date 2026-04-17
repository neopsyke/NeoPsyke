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
import ai.neopsyke.agent.ego.planner.model.DurableWorkRouteTarget
import ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload
import ai.neopsyke.agent.ego.planner.model.WorkItemReference
import ai.neopsyke.agent.ego.planner.model.SerializedDurableWorkCommand
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.DecisionValidation
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.durablework.WorkItemKind
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
import java.util.concurrent.ConcurrentHashMap

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
    private val responsibilityIntakeStore = ResponsibilityIntakeStore()

    fun plan(
        trigger: EgoTrigger.IncomingInput,
        context: PlannerContext,
        target: DurableWorkRouteTarget = DurableWorkRouteTarget.GENERIC,
    ): EgoDecision {
        if (ActionType.DURABLE_WORK_OPERATION !in context.availableActions) {
            return unavailableDecision()
        }

        val promptProfile = promptProfileFor(target)
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = promptProfile.callSite,
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val goalsInfo = context.goalWorkSummary.ifBlank { "No active durable work items." }
        val availableChannels = context.availableContactChannels
        val channelsLine = if (availableChannels.isEmpty()) {
            "No delivery channels are currently available; set contact_channel to null."
        } else {
            "Available delivery channels: ${availableChannels.sorted().joinToString(", ")}. " +
                "Map delivery intent semantically: \"via Telegram\" or \"send me a text\" -> \"telegram\" if available; " +
                "\"on the dashboard\" or \"in the app\" -> \"dashboard\" if available."
        }
        val responsibilityDraft = if (target == DurableWorkRouteTarget.RESPONSIBILITY) {
            responsibilityIntakeStore.get(context.conversationContext.sessionId)
        } else {
            null
        }

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "durable_work_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 56,
                content = promptProfile.systemPrompt
            ),
            PromptBudgetAllocator.Section(
                key = "durable_work_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 36,
                content = DURABLE_WORK_SCHEMA_PROMPT
            ),
            PromptBudgetAllocator.Section(
                key = "durable_work_active_items",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "Active durable work items:\n$goalsInfo"
            ),
            PromptBudgetAllocator.Section(
                key = "durable_work_available_channels",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 12,
                content = channelsLine,
            ),
            responsibilityDraft?.let {
                PromptBudgetAllocator.Section(
                    key = "responsibility_intake_draft",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 24,
                    content = "Current responsibility intake draft:\n${it.render()}"
                )
            },
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.actionAvailabilitySection(context),
            PromptBudgetAllocator.Section(
                key = "durable_work_trigger",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "User request:\n${trigger.input.content}"
            )
        )

        val allocation = PromptBudgetAllocator.allocate(sections, promptBudgetFor(target))
        runtime.emitPromptBudgetTelemetry(promptProfile.laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = promptProfile.laneId,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = DURABLE_WORK_RESPONSE_FORMAT,
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
            "clarify" -> handleResponsibilityClarification(payload, trigger, context)
            "create" -> handleCreate(
                payload = payload,
                context = context,
                kind = if (target == DurableWorkRouteTarget.RESPONSIBILITY) {
                    WorkItemKind.RESPONSIBILITY
                } else {
                    WorkItemKind.RECURRENT_TASK
                },
                responsibilityDraft = responsibilityDraft,
            )
            "fallback" -> handleFallback(payload)
            else -> handleManagement(operation, payload, context)
        }
    }

    private fun promptProfileFor(target: DurableWorkRouteTarget): PlannerPromptProfile =
        when (target) {
            DurableWorkRouteTarget.GENERIC -> PlannerPromptProfile(
                laneId = LaneId.DURABLE_WORK_GENERIC,
                callSite = "goal",
                systemPrompt = GENERIC_SYSTEM_PROMPT,
            )
            DurableWorkRouteTarget.RECURRENT_TASK -> PlannerPromptProfile(
                laneId = LaneId.DURABLE_WORK_RECURRENT_TASK,
                callSite = "goal",
                systemPrompt = RECURRENT_TASK_SYSTEM_PROMPT,
            )
            DurableWorkRouteTarget.RESPONSIBILITY -> PlannerPromptProfile(
                laneId = LaneId.DURABLE_WORK_RESPONSIBILITY,
                callSite = "durable_work_responsibility",
                systemPrompt = RESPONSIBILITY_SYSTEM_PROMPT,
            )
        }

    private fun promptBudgetFor(target: DurableWorkRouteTarget): Int =
        when (target) {
            DurableWorkRouteTarget.RESPONSIBILITY ->
                maxOf(config.maxLlmPromptTokens, config.planner.responsibilityIntakePromptTokens)
            else -> config.maxLlmPromptTokens
        }

    private fun handleCreate(
        payload: GoalPayload,
        context: PlannerContext,
        kind: WorkItemKind,
        responsibilityDraft: ResponsibilityDraft? = null,
    ): EgoDecision {
        val mergedDraft = if (kind == WorkItemKind.RESPONSIBILITY) {
            responsibilityIntakeStore.merge(context.conversationContext.sessionId, responsibilityDraft, payload)
        } else {
            responsibilityDraft
        }
        val title = payload.title?.trim().orEmpty()
            .ifBlank { mergedDraft?.draftTitle.orEmpty() }
        val instruction = payload.instruction?.trim().orEmpty()
            .ifBlank { mergedDraft?.responsibilitySummary.orEmpty() }
        logger.debug {
            "handleCreate: title='${title.take(60)}' instruction='${instruction.take(80)}' " +
                "cron=${payload.cronExpression} raw_plan_steps=${payload.planSteps?.size ?: 0}"
        }

        if (instruction.isBlank()) {
            if (payload.assistantResponse?.isNotBlank() == true) {
                return contactUser(payload.assistantResponse.trim(), "Respond to goal-related request")
            }
            return contactUser(
                "I couldn't safely create durable work from that yet. Please specify the title, what it should do, and whether this is a recurrent task or an ongoing responsibility.",
                "Ask for durable-work clarification",
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
            workItemKind = kind,
            title = TextSecurity.clamp(title.ifBlank {
                if (kind == WorkItemKind.RESPONSIBILITY) "Ongoing responsibility" else "Recurrent task"
            }, GOAL_TITLE_MAX_CHARS),
            instruction = TextSecurity.clamp(instruction, GOAL_INSTRUCTION_MAX_CHARS),
            priority = priority,
            completionCriteria = TextSecurity.clamp(
                payload.completionCriteria?.trim().orEmpty().ifBlank { DEFAULT_COMPLETION_CRITERIA },
                GOAL_COMPLETION_CRITERIA_MAX_CHARS,
            ),
            cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
            contactChannel = payload.contactChannel?.trim()?.lowercase()?.ifBlank { null },
            operatorSummary = payload.operatorSummary?.trim()?.ifBlank { null }
                ?: mergedDraft?.operatorSummary(),
            planSteps = planSteps,
        )

        if (kind == WorkItemKind.RESPONSIBILITY) {
            responsibilityIntakeStore.clear(context.conversationContext.sessionId)
        }

        return goalOperationDecision(command, context)
    }

    private fun handleResponsibilityClarification(
        payload: GoalPayload,
        trigger: EgoTrigger.IncomingInput,
        context: PlannerContext,
    ): EgoDecision {
        val draft = responsibilityIntakeStore.merge(
            sessionId = context.conversationContext.sessionId,
            existing = responsibilityIntakeStore.get(context.conversationContext.sessionId),
            payload = payload,
        )
        val question = payload.clarificationQuestion?.trim()?.ifBlank { null }
            ?: draft.openQuestions.firstOrNull()
            ?: "What outcomes, constraints, and cadence should this responsibility cover?"
        logger.debug {
            "Responsibility clarification requested for session=${context.conversationContext.sessionId} " +
                "question='${question.take(80)}' user='${trigger.input.content.take(80)}'"
        }
        return contactUser(question, "Clarify responsibility setup")
    }

    private fun handleFallback(payload: GoalPayload): EgoDecision {
        val response = payload.assistantResponse?.trim().orEmpty()
        if (response.isBlank()) {
            return contactUser(
                "I couldn't determine a durable-work operation from that. Could you clarify what you'd like to do with your recurrent tasks or responsibilities?",
                "Ask for durable-work clarification",
            )
        }
        return contactUser(response, "Respond to durable-work request")
    }

    private fun handleManagement(
        operation: String,
        payload: GoalPayload,
        context: PlannerContext,
    ): EgoDecision {
        val ref = resolveWorkItemReference(
            raw = payload.workItemReference,
            goalIndex = context.goalIndex,
            reviewableResponsibilityIndex = context.reviewableResponsibilityIndex,
            preferReviewableSlate = operation == "review",
        )
        logger.debug {
            "handleManagement: operation=$operation reference_type=${ref.javaClass.simpleName}"
        }

        if (ref is WorkItemReference.Ambiguous) {
            return contactUser(
                "I found multiple durable-work items that might match: ${ref.candidates.joinToString(", ")}. Which one did you mean?",
                "Ask for durable-work clarification",
            )
        }
        if (ref is WorkItemReference.Unresolved && operation != "list" && operation != "delete_all") {
            return contactUser(
                "I couldn't find a recurrent task or responsibility matching '${ref.originalText}'. Please check the name or number.",
                "Durable work item not found",
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
            "review" -> resolvedReference(reference)?.let { DurableWorkCommand.Review(it, payload.reason?.trim()?.ifBlank { null }) }
            "complete" -> resolvedReference(reference)?.let { DurableWorkCommand.Complete(it) }
            "retire" -> resolvedReference(reference)?.let { DurableWorkCommand.Retire(it, payload.reason?.trim()?.ifBlank { null }) }
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
                    operatorSummary = payload.operatorSummary?.trim()?.ifBlank { null },
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

    private fun resolveWorkItemReference(
        raw: WorkItemReferencePayload?,
        goalIndex: Map<Int, String>,
        reviewableResponsibilityIndex: Map<Int, String> = emptyMap(),
        preferReviewableSlate: Boolean = false,
    ): WorkItemReference {
        if (raw == null) return WorkItemReference.Unresolved("")
        val candidateIndexes = referenceIndexes(
            resolvedFrom = raw.resolvedFrom,
            goalIndex = goalIndex,
            reviewableResponsibilityIndex = reviewableResponsibilityIndex,
            preferReviewableSlate = preferReviewableSlate,
        )
        return when (raw.type?.trim()?.lowercase()) {
            "by_position", "by_internal_id", "by_id" -> {
                val position = raw.id?.trim()?.toIntOrNull()
                val resolvedId = if (position != null) {
                    candidateIndexes.asSequence()
                        .mapNotNull { index -> index[position] }
                        .firstOrNull()
                } else {
                    null
                }
                if (resolvedId != null) {
                    WorkItemReference.ByInternalId(resolvedId)
                } else {
                    WorkItemReference.Unresolved(raw.originalText?.trim().orEmpty())
                }
            }
            "ambiguous" -> {
                val resolvedCandidates = raw.candidates.orEmpty()
                    .mapNotNull { candidate ->
                        val position = candidate.trim().toIntOrNull() ?: return@mapNotNull null
                        candidateIndexes.asSequence()
                            .mapNotNull { index -> index[position] }
                            .firstOrNull()
                    }
                    .distinct()
                WorkItemReference.Ambiguous(
                    candidates = resolvedCandidates,
                    originalText = raw.originalText?.trim().orEmpty(),
                )
            }
            "unresolved" -> WorkItemReference.Unresolved(raw.originalText?.trim().orEmpty())
            else -> WorkItemReference.Unresolved(raw.originalText?.trim().orEmpty())
        }
    }

    private fun referenceIndexes(
        resolvedFrom: String?,
        goalIndex: Map<Int, String>,
        reviewableResponsibilityIndex: Map<Int, String>,
        preferReviewableSlate: Boolean,
    ): List<Map<Int, String>> {
        val normalizedSource = resolvedFrom?.trim()?.lowercase()
        return when (normalizedSource) {
            "reviewable_responsibility",
            "reviewable_responsibility_slate" ->
                listOf(reviewableResponsibilityIndex, goalIndex)
            "active_durable_work",
            "active_work_item_list",
            "goal_index" ->
                listOf(goalIndex, reviewableResponsibilityIndex)
            else ->
                if (preferReviewableSlate) {
                    listOf(reviewableResponsibilityIndex, goalIndex)
                } else {
                    listOf(goalIndex, reviewableResponsibilityIndex)
                }
        }.filter { it.isNotEmpty() }
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
            when (command.workItemKind) {
                WorkItemKind.RECURRENT_TASK ->
                    if (command.cronExpression.isNullOrBlank()) "Create recurrent task" else "Create scheduled recurrent task"
                WorkItemKind.RESPONSIBILITY -> "Create responsibility"
            }
        } else {
            "Durable work operation: ${command.operationName}"
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
            is DurableWorkCommand.Review -> command.reference
            is DurableWorkCommand.Complete -> command.reference
            is DurableWorkCommand.Retire -> command.reference
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
            "Durable work is unavailable in this run. Restart with durable work enabled to create recurrent tasks or responsibilities.",
            "Explain that durable work is disabled",
        )

    data class GoalPayload(
        val operation: String? = null,
        @param:JsonProperty("work_item_kind")
        val workItemKind: String? = null,
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
        @param:JsonProperty("operator_summary")
        val operatorSummary: String? = null,
        @param:JsonProperty("plan_steps")
        val planSteps: List<PlanStepPayload>? = null,
        @param:JsonProperty("assistant_response")
        val assistantResponse: String? = null,
        @param:JsonProperty("clarification_question")
        val clarificationQuestion: String? = null,
        @param:JsonProperty("responsibility_summary")
        val responsibilitySummary: String? = null,
        @param:JsonProperty("known_preferences")
        val knownPreferences: List<String>? = null,
        @param:JsonProperty("known_constraints")
        val knownConstraints: List<String>? = null,
        @param:JsonProperty("known_signals_of_success")
        val knownSignalsOfSuccess: List<String>? = null,
        @param:JsonProperty("review_cadence_hint")
        val reviewCadenceHint: String? = null,
        @param:JsonProperty("delivery_hint")
        val deliveryHint: String? = null,
        @param:JsonProperty("open_questions")
        val openQuestions: List<String>? = null,
        @param:JsonProperty("ready_to_create")
        val readyToCreate: Boolean? = null,
        val reason: String? = null,
    )

    data class PlanStepPayload(
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

    data class WorkItemReferencePayload(
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
        private const val DURABLE_WORK_SCHEMA_PROMPT: String = """
            JSON schema:
            {
              "operation":"create|list|status|pause|resume|review|complete|retire|delete|delete_all|update|revise_plan|reprioritize|clarify|fallback",
              "work_item_kind":"RECURRENT_TASK|RESPONSIBILITY|null",
              "work_item_reference":{"type":"by_position|ambiguous|unresolved","id":"<number>","candidates":["<numbers>"],"original_text":"<user words>","resolved_from":"active_durable_work|reviewable_responsibility_slate|null"},
              "title":"for create/update",
              "instruction":"for create/update",
              "completion_criteria":"for create/update",
              "priority":"low|medium|high|critical (for create/update/reprioritize)",
              "cron_expression":"5-field cron or null (for create/update)",
              "contact_channel":"delivery channel name or null (for create/update)",
              "operator_summary":"brief operator-facing summary or null",
              "plan_steps":[{"id":"step1","description":"...","acceptance_criteria":"...","grounding_requirement":"required|not_required","requires":[],"produces":["artifact"],"max_attempts":3}],
              "assistant_response":"required when operation=fallback",
              "clarification_question":"question for responsibility intake when operation=clarify",
              "responsibility_summary":"summary for responsibility intake or null",
              "known_preferences":["..."],
              "known_constraints":["..."],
              "known_signals_of_success":["..."],
              "review_cadence_hint":"optional",
              "delivery_hint":"optional",
              "open_questions":["..."],
              "ready_to_create":true,
              "reason":"optional"
            }
        """
        private const val GENERIC_SYSTEM_PROMPT: String = """
            You are the generic durable-work operations planner.
            Return STRICT JSON only.
            Durable work items are numbered starting at 1 in the active item list.
            Allowed operations: list, status, pause, resume, review, complete, retire, delete, delete_all, reprioritize, fallback.
            Use generic lifecycle operations only. Do not create, update, or revise plans here unless the request is impossible to interpret otherwise.
            Resolve references by item number. Never invent internal IDs.
            If you select from the reviewable responsibility slate, set work_item_reference.resolved_from=reviewable_responsibility_slate.
            Otherwise set work_item_reference.resolved_from=active_durable_work.
            If the user's reference is ambiguous, return work_item_reference.type=ambiguous.
            If no item matches, return work_item_reference.type=unresolved.
            If the request is not a durable-work lifecycle operation, use fallback.
        """
        private const val RECURRENT_TASK_SYSTEM_PROMPT: String = """
            You are the recurrent-task durable-work planner.
            Return STRICT JSON only.
            Recurrent tasks are scheduled or repeated structured tasks such as reminders, recurring searches, and monitoring checks.
            Allowed operations: create, update, revise_plan, fallback.
            For create/update:
            - Set work_item_kind=RECURRENT_TASK.
            - Prefer short, concrete titles.
            - If the user requests a recurring schedule, set cron_expression to a standard 5-field cron string.
            - If not recurring, cron_expression may be null for manually-triggered recurrent work.
            - contact_channel MUST be one of the currently-available delivery channels or null.
            - Generate concise plan_steps. Runtime-controlled delivery means a final contact_user step is optional, not mandatory.
            For revise_plan, include revised plan_steps and a short reason.
            If the request is not about recurrent-task create/update/revise, use fallback.
        """
        private const val RESPONSIBILITY_SYSTEM_PROMPT: String = """
            You are the responsibility durable-work planner.
            Return STRICT JSON only.
            Responsibilities are ongoing ownership commitments that can require multi-turn intake before creation.
            Allowed operations: create, update, revise_plan, clarify, fallback.
            For responsibility intake:
            - Set work_item_kind=RESPONSIBILITY for create.
            - If critical operating details are missing, return operation=clarify.
            - Use clarification_question for the next focused question.
            - Maintain responsibility_summary, known_preferences, known_constraints, known_signals_of_success, review_cadence_hint, delivery_hint, open_questions, ready_to_create.
            - Only return create when ready_to_create=true and the instruction contains enough operational detail to be useful without repeated re-asking.
            - operator_summary should be a concise operator-facing summary of what the responsibility covers.
            - Runtime-controlled delivery means a final contact_user step is optional, not mandatory.
            If the request is not about responsibility create/update/revise, use fallback.
        """

        /** Runtime fact keys available to the executor. The refiner needs to know
         *  which facts exist (for non-redundancy checks) but not their volatile
         *  values, which change between runs and break cache hashing. */
        val RUNTIME_FACT_KEYS: Map<String, String> = mapOf(
            "date" to "available at execution time",
            "time" to "available at execution time",
            "timezone" to "available at execution time",
        )

        val DURABLE_WORK_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "durable_work_operation",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["operation", "work_item_kind", "work_item_reference", "title", "instruction", "completion_criteria", "priority", "cron_expression", "contact_channel", "operator_summary", "plan_steps", "assistant_response", "clarification_question", "responsibility_summary", "known_preferences", "known_constraints", "known_signals_of_success", "review_cadence_hint", "delivery_hint", "open_questions", "ready_to_create", "reason"],
                  "properties": {
                    "operation": { "type": "string" },
                    "work_item_kind": { "type": ["string", "null"] },
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
                    "operator_summary": { "type": ["string", "null"], "maxLength": 240 },
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
                    "clarification_question": { "type": ["string", "null"] },
                    "responsibility_summary": { "type": ["string", "null"] },
                    "known_preferences": { "type": ["array", "null"], "items": { "type": "string" } },
                    "known_constraints": { "type": ["array", "null"], "items": { "type": "string" } },
                    "known_signals_of_success": { "type": ["array", "null"], "items": { "type": "string" } },
                    "review_cadence_hint": { "type": ["string", "null"] },
                    "delivery_hint": { "type": ["string", "null"] },
                    "open_questions": { "type": ["array", "null"], "items": { "type": "string" } },
                    "ready_to_create": { "type": ["boolean", "null"] },
                    "reason": { "type": ["string", "null"], "maxLength": 160 }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}

private data class PlannerPromptProfile(
    val laneId: LaneId,
    val callSite: String,
    val systemPrompt: String,
)

private data class ResponsibilityDraft(
    val draftTitle: String? = null,
    val responsibilitySummary: String? = null,
    val knownPreferences: List<String> = emptyList(),
    val knownConstraints: List<String> = emptyList(),
    val knownSignalsOfSuccess: List<String> = emptyList(),
    val reviewCadenceHint: String? = null,
    val deliveryHint: String? = null,
    val openQuestions: List<String> = emptyList(),
    val readyToCreate: Boolean = false,
) {
    fun operatorSummary(): String =
        buildString {
            draftTitle?.takeIf { it.isNotBlank() }?.let { append(it) }
            responsibilitySummary?.takeIf { it.isNotBlank() }?.let {
                if (isNotBlank()) append(": ")
                append(it)
            }
        }.ifBlank { "Responsibility draft" }

    fun render(): String = buildString {
        appendLine("title: ${draftTitle ?: "none"}")
        appendLine("summary: ${responsibilitySummary ?: "none"}")
        appendLine("preferences: ${knownPreferences.joinToString("; ").ifBlank { "none" }}")
        appendLine("constraints: ${knownConstraints.joinToString("; ").ifBlank { "none" }}")
        appendLine("signals_of_success: ${knownSignalsOfSuccess.joinToString("; ").ifBlank { "none" }}")
        appendLine("review_cadence_hint: ${reviewCadenceHint ?: "none"}")
        appendLine("delivery_hint: ${deliveryHint ?: "none"}")
        appendLine("open_questions: ${openQuestions.joinToString("; ").ifBlank { "none" }}")
        append("ready_to_create: $readyToCreate")
    }.trim()
}

private class ResponsibilityIntakeStore {
    private val drafts = ConcurrentHashMap<String, ResponsibilityDraft>()

    fun get(sessionId: String): ResponsibilityDraft? = drafts[sessionId]

    fun clear(sessionId: String) {
        drafts.remove(sessionId)
    }

    fun merge(
        sessionId: String,
        existing: ResponsibilityDraft?,
        payload: WorkPlanBuilder.GoalPayload,
    ): ResponsibilityDraft {
        val draft = ResponsibilityDraft(
            draftTitle = payload.title?.trim()?.ifBlank { null } ?: existing?.draftTitle,
            responsibilitySummary = payload.responsibilitySummary?.trim()?.ifBlank { null }
                ?: payload.instruction?.trim()?.ifBlank { null }
                ?: existing?.responsibilitySummary,
            knownPreferences = payload.knownPreferences.orEmpty().takeIf { it.isNotEmpty() } ?: existing?.knownPreferences.orEmpty(),
            knownConstraints = payload.knownConstraints.orEmpty().takeIf { it.isNotEmpty() } ?: existing?.knownConstraints.orEmpty(),
            knownSignalsOfSuccess = payload.knownSignalsOfSuccess.orEmpty().takeIf { it.isNotEmpty() }
                ?: existing?.knownSignalsOfSuccess.orEmpty(),
            reviewCadenceHint = payload.reviewCadenceHint?.trim()?.ifBlank { null } ?: existing?.reviewCadenceHint,
            deliveryHint = payload.deliveryHint?.trim()?.ifBlank { null } ?: existing?.deliveryHint,
            openQuestions = payload.openQuestions.orEmpty().takeIf { it.isNotEmpty() } ?: existing?.openQuestions.orEmpty(),
            readyToCreate = payload.readyToCreate ?: existing?.readyToCreate ?: false,
        )
        drafts[sessionId] = draft
        return draft
    }
}
