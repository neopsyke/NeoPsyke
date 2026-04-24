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
import ai.neopsyke.agent.ego.planner.model.AssignmentCommand
import ai.neopsyke.agent.ego.planner.model.AssignmentRouteTarget
import ai.neopsyke.agent.ego.planner.model.AssignmentPlanStepPayload
import ai.neopsyke.agent.ego.planner.model.WorkItemReference
import ai.neopsyke.agent.ego.planner.model.SerializedAssignmentCommand
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.DecisionValidation
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.assignments.WorkItemKind
import ai.neopsyke.agent.assignments.WorkItemPriority
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.AssignmentItemSnapshot
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.DialogueTurn
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
 * L2 sub-planner: unified assignment command builder handling both creation and management.
 *
 * The LLM decides the assignment operation type (create, list, pause, etc.) in a single
 * call, removing the need for the InputIntentRouter to split assignment creation from
 * assignment management.
 */
class AssignmentCommandBuilder(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val planRefiner: PlanRefiner,
) {
    private val responsibilityIntakeStore = ResponsibilityIntakeStore()

    fun plan(
        trigger: EgoTrigger.IncomingInput,
        context: PlannerContext,
        target: AssignmentRouteTarget = AssignmentRouteTarget.GENERIC,
    ): EgoDecision {
        if (ActionType.ASSIGNMENT_OPERATION !in context.availableActions) {
            return unavailableDecision()
        }

        val promptProfile = promptProfileFor(target)
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = promptProfile.callSite,
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val assignmentInfo = context.assignmentSummary.ifBlank { "No active assignments." }
        val availableChannels = context.availableContactChannels
        val channelsLine = if (availableChannels.isEmpty()) {
            "No delivery channels are currently available; set contact_channel to null."
        } else {
            "Available delivery channels: ${availableChannels.sorted().joinToString(", ")}. " +
                "Map delivery intent semantically: \"via Telegram\" or \"send me a text\" -> \"telegram\" if available; " +
                "\"on the dashboard\" or \"in the app\" -> \"dashboard\" if available."
        }
        val responsibilityDraft = if (target == AssignmentRouteTarget.RESPONSIBILITY) {
            responsibilityIntakeStore.activeForContinuation(
                sessionId = context.conversationContext.sessionId,
                recentDialogue = context.recentDialogue,
            )
        } else {
            null
        }

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "assignment_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 56,
                content = promptProfile.systemPrompt
            ),
            PromptBudgetAllocator.Section(
                key = "assignment_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 36,
                content = ASSIGNMENT_SCHEMA_PROMPT
            ),
            PromptBudgetAllocator.Section(
                key = "assignment_active_items",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "Active assignments:\n$assignmentInfo"
            ),
            PromptBudgetAllocator.Section(
                key = "assignment_available_channels",
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
                key = "assignment_trigger",
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
            responseFormat = ASSIGNMENT_RESPONSE_FORMAT,
        )

        if (response == null) {
            return EgoDecision.Noop("AssignmentCommandBuilder unavailable.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<AssignmentPayload>(response.content)
        if (payload == null) {
            return EgoDecision.Noop("AssignmentCommandBuilder parse failure.")
        }

        val command = payload.command?.trim()?.lowercase()
        if (command.isNullOrBlank()) {
            return EgoDecision.Noop("AssignmentCommandBuilder returned missing command.")
        }

        return when (command) {
            "clarify" -> handleResponsibilityClarification(payload, trigger, context, responsibilityDraft)
            "create" -> handleCreate(
                payload = payload,
                context = context,
                kind = if (target == AssignmentRouteTarget.RESPONSIBILITY) {
                    WorkItemKind.RESPONSIBILITY
                } else {
                    WorkItemKind.RECURRENT_TASK
                },
                responsibilityDraft = responsibilityDraft,
            )
            "fallback" -> handleFallback(payload, context, target)
            else -> handleManagement(command, payload, context)
        }
    }

    private fun promptProfileFor(target: AssignmentRouteTarget): PlannerPromptProfile =
        when (target) {
            AssignmentRouteTarget.GENERIC -> PlannerPromptProfile(
                laneId = LaneId.ASSIGNMENT_GENERIC,
                callSite = "assignment_generic",
                systemPrompt = GENERIC_SYSTEM_PROMPT,
            )
            AssignmentRouteTarget.RECURRENT_TASK -> PlannerPromptProfile(
                laneId = LaneId.ASSIGNMENT_RECURRENT_TASK,
                callSite = "assignment_recurrent_task",
                systemPrompt = RECURRENT_TASK_SYSTEM_PROMPT,
            )
            AssignmentRouteTarget.RESPONSIBILITY -> PlannerPromptProfile(
                laneId = LaneId.ASSIGNMENT_RESPONSIBILITY,
                callSite = "assignment_responsibility",
                systemPrompt = RESPONSIBILITY_SYSTEM_PROMPT,
            )
        }

    private fun promptBudgetFor(target: AssignmentRouteTarget): Int =
        when (target) {
            AssignmentRouteTarget.RESPONSIBILITY ->
                maxOf(config.maxLlmPromptTokens, config.planner.responsibilityIntakePromptTokens)
            else -> config.maxLlmPromptTokens
        }

    private fun handleCreate(
        payload: AssignmentPayload,
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
                return contactUser(payload.assistantResponse.trim(), "Respond to assignment request")
            }
            return contactUser(
                "I couldn't safely create an assignment from that yet. Please specify the title, what it should do, and whether this is a recurrent task or an ongoing responsibility.",
                "Ask for assignment clarification",
            )
        }

        val priority = payload.priority?.trim()?.uppercase()
            ?.let { runCatching { WorkItemPriority.valueOf(it) }.getOrNull() }
            ?: WorkItemPriority.MEDIUM

        val rawPlanSteps = payload.planSteps
            ?.mapNotNull { step ->
                val desc = step.description?.trim().orEmpty()
                if (desc.isBlank()) null
                else AssignmentPlanStepPayload(
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

        val planSteps = refineAssignmentPlan(
            rawSteps = rawPlanSteps,
            planKind = PlanKind.ASSIGNMENT_CREATE,
            assignment = title.ifBlank { instruction },
            instruction = instruction,
            completionCriteria = payload.completionCriteria?.trim().orEmpty(),
            context = context,
            cronExpression = payload.cronExpression?.trim()?.ifBlank { null },
        )

        val command = AssignmentCommand.Create(
            workItemKind = kind,
            title = TextSecurity.clamp(title.ifBlank {
                if (kind == WorkItemKind.RESPONSIBILITY) "Ongoing responsibility" else "Recurrent task"
            }, ASSIGNMENT_TITLE_MAX_CHARS),
            instruction = TextSecurity.clamp(instruction, ASSIGNMENT_INSTRUCTION_MAX_CHARS),
            priority = priority,
            completionCriteria = TextSecurity.clamp(
                payload.completionCriteria?.trim().orEmpty().ifBlank { DEFAULT_COMPLETION_CRITERIA },
                ASSIGNMENT_COMPLETION_CRITERIA_MAX_CHARS,
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

        return assignmentOperationDecision(command, context)
    }

    private fun handleResponsibilityClarification(
        payload: AssignmentPayload,
        trigger: EgoTrigger.IncomingInput,
        context: PlannerContext,
        existingDraft: ResponsibilityDraft?,
    ): EgoDecision {
        val draft = responsibilityIntakeStore.merge(
            sessionId = context.conversationContext.sessionId,
            existing = existingDraft,
            payload = payload,
        )
        val question = payload.clarificationQuestion?.trim()?.ifBlank { null }
            ?: draft.openQuestions.firstOrNull()
            ?: "What outcomes, constraints, and cadence should this responsibility cover?"
        responsibilityIntakeStore.recordClarificationQuestion(
            sessionId = context.conversationContext.sessionId,
            question = question,
        )
        logger.debug {
            "Responsibility clarification requested for session=${context.conversationContext.sessionId} " +
                "question='${question.take(80)}' user='${trigger.input.content.take(80)}'"
        }
        return contactUser(question, "Clarify responsibility setup")
    }

    private fun handleFallback(
        payload: AssignmentPayload,
        context: PlannerContext,
        target: AssignmentRouteTarget,
    ): EgoDecision {
        if (target == AssignmentRouteTarget.RESPONSIBILITY) {
            responsibilityIntakeStore.clear(context.conversationContext.sessionId)
        }
        val response = payload.assistantResponse?.trim().orEmpty()
        if (response.isBlank()) {
            return contactUser(
                "I couldn't determine an assignment operation from that. Could you clarify what you'd like to do with your recurrent tasks or responsibilities?",
                "Ask for assignment clarification",
            )
        }
        return contactUser(response, "Respond to assignment request")
    }

    private fun handleManagement(
        operation: String,
        payload: AssignmentPayload,
        context: PlannerContext,
    ): EgoDecision {
        val ref = resolveWorkItemReference(
            raw = payload.workItemReference,
            assignmentIndex = context.assignmentIndex,
            reviewableResponsibilityIndex = context.reviewableResponsibilityIndex,
            preferReviewableSlate = operation == "review",
        )
        logger.debug {
            "handleManagement: command=$operation reference_type=${ref.javaClass.simpleName}"
        }

        if (ref is WorkItemReference.Ambiguous) {
            return contactUser(
                "I found multiple assignment items that might match: ${ref.candidates.joinToString(", ")}. Which one did you mean?",
                "Ask for assignment clarification",
            )
        }
        if (ref is WorkItemReference.Unresolved && operation != "list" && operation != "delete_all") {
            return contactUser(
                "I couldn't find a recurrent task or responsibility matching '${ref.originalText}'. Please check the name or number.",
                "Assignment not found",
            )
        }

        val command = buildAssignmentCommand(operation, ref, payload, context)
            ?: return EgoDecision.Noop("AssignmentCommandBuilder returned invalid typed command.")

        return assignmentOperationDecision(command, context)
    }

    private fun buildAssignmentCommand(
        operation: String,
        reference: WorkItemReference,
        payload: AssignmentPayload,
        context: PlannerContext,
    ): AssignmentCommand? {
        return when (operation) {
            "list" -> AssignmentCommand.List
            "delete_all" -> AssignmentCommand.DeleteAll
            "status" -> resolvedReference(reference)?.let { AssignmentCommand.Status(it) }
            "pause" -> resolvedReference(reference)?.let { AssignmentCommand.Pause(it) }
            "resume" -> resolvedReference(reference)?.let { AssignmentCommand.Resume(it) }
            "review" -> resolvedReference(reference)?.let { AssignmentCommand.Review(it, payload.reason?.trim()?.ifBlank { null }) }
            "complete" -> resolvedReference(reference)?.let { AssignmentCommand.Complete(it) }
            "retire" -> resolvedReference(reference)?.let { AssignmentCommand.Retire(it, payload.reason?.trim()?.ifBlank { null }) }
            "delete" -> resolvedReference(reference)?.let { AssignmentCommand.Delete(it) }
            "update" -> resolvedReference(reference)?.let {
                AssignmentCommand.Update(
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
                val snapshot = resolvedAssignmentSnapshot(ref, context)
                val rawPlanSteps = payload.planSteps
                    ?.mapNotNull { step ->
                        val desc = step.description?.trim().orEmpty()
                        if (desc.isBlank()) null
                        else AssignmentPlanStepPayload(
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
                            AssignmentPlanStepPayload(
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

                val refinedSteps = refineAssignmentPlan(
                    rawSteps = rawPlanSteps,
                    planKind = PlanKind.ASSIGNMENT_REVISE,
                    assignment = snapshot?.title?.ifBlank { null }
                        ?: payload.title?.trim()?.ifBlank { null }
                        ?: "Revise plan",
                    instruction = snapshot?.instruction?.ifBlank { null }
                        ?: payload.instruction?.trim().orEmpty(),
                    completionCriteria = snapshot?.completionCriteria.orEmpty(),
                    context = context,
                    userFeedbackHint = payload.reason?.trim(),
                    revisionContextHint = snapshotContextHint,
                )

                AssignmentCommand.RevisePlan(
                    reference = ref,
                    reason = payload.reason?.trim()?.ifBlank { null },
                    planSteps = refinedSteps,
                )
            }
            "reprioritize" -> {
                val parsedPriority = payload.priority?.trim()?.uppercase()?.let { raw ->
                    runCatching { WorkItemPriority.valueOf(raw) }.getOrNull()
                } ?: return null
                resolvedReference(reference)?.let { AssignmentCommand.Reprioritize(it, parsedPriority) }
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

    private fun resolvedAssignmentSnapshot(reference: WorkItemReference, context: PlannerContext): AssignmentItemSnapshot? =
        when (reference) {
            is WorkItemReference.ByInternalId -> context.assignmentSnapshots[reference.id]
            is WorkItemReference.ByResolvedEntity -> context.assignmentSnapshots[reference.workItemId]
            is WorkItemReference.Ambiguous -> null
            is WorkItemReference.Unresolved -> null
        }

    private fun resolveWorkItemReference(
        raw: WorkItemReferencePayload?,
        assignmentIndex: Map<Int, String>,
        reviewableResponsibilityIndex: Map<Int, String> = emptyMap(),
        preferReviewableSlate: Boolean = false,
    ): WorkItemReference {
        if (raw == null) return WorkItemReference.Unresolved("")
        val candidateIndexes = referenceIndexes(
            resolvedFrom = raw.resolvedFrom,
            assignmentIndex = assignmentIndex,
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
        assignmentIndex: Map<Int, String>,
        reviewableResponsibilityIndex: Map<Int, String>,
        preferReviewableSlate: Boolean,
    ): List<Map<Int, String>> {
        val normalizedSource = resolvedFrom?.trim()?.lowercase()
        return when (normalizedSource) {
            "reviewable_responsibility",
            "reviewable_responsibility_slate" ->
                listOf(reviewableResponsibilityIndex, assignmentIndex)
            "active_assignment",
            "active_work_item_list",
            "assignment_index" ->
                listOf(assignmentIndex, reviewableResponsibilityIndex)
            else ->
                if (preferReviewableSlate) {
                    listOf(reviewableResponsibilityIndex, assignmentIndex)
                } else {
                    listOf(assignmentIndex, reviewableResponsibilityIndex)
                }
        }.filter { it.isNotEmpty() }
    }

    private fun assignmentOperationDecision(command: AssignmentCommand, context: PlannerContext): EgoDecision {
        val serializedPayload = StructuredOutputHandler.mapper.writeValueAsString(
            SerializedAssignmentCommand.fromAssignmentCommand(command)
        )
        val referenceLabel = commandReference(command)?.let { ref ->
            when (ref) {
                is WorkItemReference.ByInternalId -> ref.id
                is WorkItemReference.ByResolvedEntity -> ref.workItemId
                else -> null
            }
        }

        val summaryPrefix = if (command is AssignmentCommand.Create) {
            when (command.workItemKind) {
                WorkItemKind.RECURRENT_TASK ->
                    if (command.cronExpression.isNullOrBlank()) "Create recurrent task" else "Create scheduled recurrent task"
                WorkItemKind.RESPONSIBILITY -> "Create responsibility"
            }
        } else {
            "Assignment operation: ${command.operationName}"
        }

        return EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.PREPARE,
            commitModePreference = DecisionValidation.preferredCommitMode(
                context.allowedCommitModes, IntentionKind.PREPARE
            ),
            actionType = ActionType.ASSIGNMENT_OPERATION,
            payload = TextSecurity.clamp(serializedPayload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(
                "$summaryPrefix${referenceLabel?.let { " on $it" } ?: if (command is AssignmentCommand.Create) ": ${command.title}" else ""}",
                config.maxActionSummaryChars,
            ),
        )
    }

    private fun commandReference(command: AssignmentCommand): WorkItemReference? =
        when (command) {
            is AssignmentCommand.Status -> command.reference
            is AssignmentCommand.Pause -> command.reference
            is AssignmentCommand.Resume -> command.reference
            is AssignmentCommand.Review -> command.reference
            is AssignmentCommand.Complete -> command.reference
            is AssignmentCommand.Retire -> command.reference
            is AssignmentCommand.Delete -> command.reference
            is AssignmentCommand.Update -> command.reference
            is AssignmentCommand.RevisePlan -> command.reference
            is AssignmentCommand.Reprioritize -> command.reference
            is AssignmentCommand.Create -> null
            is AssignmentCommand.List -> null
            is AssignmentCommand.DeleteAll -> null
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

    private fun refineAssignmentPlan(
        rawSteps: List<AssignmentPlanStepPayload>?,
        planKind: PlanKind,
        assignment: String,
        instruction: String,
        completionCriteria: String = "",
        context: PlannerContext,
        userFeedbackHint: String? = null,
        revisionContextHint: String? = null,
        cronExpression: String? = null,
    ): List<AssignmentPlanStepPayload>? {
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
            assignment = assignment,
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
            AssignmentPlanStepPayload(
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

    private fun renderRevisionSnapshotContext(snapshot: AssignmentItemSnapshot): String = buildString {
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
            "Assignments are unavailable in this run. Restart with assignments enabled to create recurrent tasks or responsibilities.",
            "Explain that assignments are disabled",
        )

    data class AssignmentPayload(
        val command: String? = null,
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
        const val ASSIGNMENT_TITLE_MAX_CHARS: Int = 80
        const val ASSIGNMENT_INSTRUCTION_MAX_CHARS: Int = 400
        const val ASSIGNMENT_COMPLETION_CRITERIA_MAX_CHARS: Int = 200
        const val DEFAULT_COMPLETION_CRITERIA: String = "User confirms the assignment is complete."
        private const val ASSIGNMENT_SCHEMA_PROMPT: String = """
            JSON schema:
            {
              "command":"create|list|status|pause|resume|review|complete|retire|delete|delete_all|update|revise_plan|reprioritize|clarify|fallback",
              "work_item_kind":"RECURRENT_TASK|RESPONSIBILITY|null",
              "work_item_reference":{"type":"by_position|ambiguous|unresolved","id":"<number>","candidates":["<numbers>"],"original_text":"<user words>","resolved_from":"active_assignment|reviewable_responsibility_slate|null"},
              "title":"for create/update",
              "instruction":"for create/update",
              "completion_criteria":"for create/update",
              "priority":"low|medium|high|critical (for create/update/reprioritize)",
              "cron_expression":"5-field cron or null (for create/update)",
              "contact_channel":"delivery channel name or null (for create/update)",
              "operator_summary":"brief operator-facing summary or null",
              "plan_steps":[{"id":"step1","description":"...","acceptance_criteria":"...","grounding_requirement":"required|not_required","requires":[],"produces":["artifact"],"max_attempts":3}],
              "assistant_response":"required when command=fallback",
              "clarification_question":"question for responsibility intake when command=clarify",
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
            You are the generic assignment operations planner.
            Return STRICT JSON only.
            Assignments are numbered starting at 1 in the active item list.
            Allowed operations: list, status, pause, resume, review, complete, retire, delete, delete_all, reprioritize, fallback.
            Use generic lifecycle operations only. Do not create, update, or revise plans here unless the request is impossible to interpret otherwise.
            Resolve references by item number. Never invent internal IDs.
            If you select from the reviewable responsibility slate, set work_item_reference.resolved_from=reviewable_responsibility_slate.
            Otherwise set work_item_reference.resolved_from=active_assignment.
            If the user's reference is ambiguous, return work_item_reference.type=ambiguous.
            If no item matches, return work_item_reference.type=unresolved.
            If the request is not an assignment lifecycle operation, use fallback.
        """
        private const val RECURRENT_TASK_SYSTEM_PROMPT: String = """
            You are the recurrent-task assignment planner.
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
            You are the responsibility assignment planner.
            Return STRICT JSON only.
            Responsibilities are ongoing ownership commitments that can require multi-turn intake before creation.
            Allowed operations: create, update, revise_plan, clarify, fallback.
            For responsibility intake:
            - Set work_item_kind=RESPONSIBILITY for create.
            - If critical operating details are missing, return command=clarify.
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

        val ASSIGNMENT_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "assignment_operation",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["command", "work_item_kind", "work_item_reference", "title", "instruction", "completion_criteria", "priority", "cron_expression", "contact_channel", "operator_summary", "plan_steps", "assistant_response", "clarification_question", "responsibility_summary", "known_preferences", "known_constraints", "known_signals_of_success", "review_cadence_hint", "delivery_hint", "open_questions", "ready_to_create", "reason"],
                  "properties": {
                    "command": { "type": "string" },
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
    val lastClarificationQuestion: String? = null,
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

    fun activeForContinuation(sessionId: String, recentDialogue: List<DialogueTurn>): ResponsibilityDraft? {
        val draft = drafts[sessionId] ?: return null
        val expectedQuestion = draft.lastClarificationQuestion?.trim()?.ifBlank { null } ?: return null
        val lastAssistantTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.ASSISTANT }
            ?.content
            ?.trim()
            ?.ifBlank { null }
        return if (lastAssistantTurn == expectedQuestion) draft else null
    }

    fun clear(sessionId: String) {
        drafts.remove(sessionId)
    }

    fun recordClarificationQuestion(sessionId: String, question: String) {
        drafts.computeIfPresent(sessionId) { _, draft ->
            draft.copy(lastClarificationQuestion = question.trim().ifBlank { null })
        }
    }

    fun merge(
        sessionId: String,
        existing: ResponsibilityDraft?,
        payload: AssignmentCommandBuilder.AssignmentPayload,
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
            lastClarificationQuestion = existing?.lastClarificationQuestion,
        )
        drafts[sessionId] = draft
        return draft
    }
}
