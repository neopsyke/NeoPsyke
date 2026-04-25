package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.model.ExecutionCandidate
import ai.neopsyke.agent.ego.planner.model.PlanDecomposition
import ai.neopsyke.agent.ego.planner.model.ProgressionDecision
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.DecisionValidation
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.ego.planner.runtime.TruncationRetry
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.prompt.PromptCatalog

/**
 * L1 lane: handles both EgoTrigger.Continuation and EgoTrigger.ActionFeedback.
 *
 * Merged from the former ContinuationPlanner and FeedbackPlanner, which shared
 * identical JSON schema, validation logic, retry handling, and prompt sections.
 * The semantic difference (continuation vs feedback) is captured by the trigger
 * data passed through SharedPromptSections.triggerSection().
 *
 * No deterministic text routing. Typed facts from trigger metadata
 * (plan context, denial reason codes, pass count, execution status)
 * drive deterministic pre-checks. Semantic interpretation is always model-based.
 */
class ProgressionPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : PlannerLane {

    override val laneId: LaneId = LaneId.PROGRESSION

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val meta = extractTriggerMetadata(trigger)
            ?: return EgoDecision.Noop("ProgressionPlanner requires Continuation or ActionFeedback trigger.")

        if (runtime.isCircuitOpen(laneId, meta.rootInputId)) {
            return EgoDecision.Noop(
                reason = "Progression circuit breaker tripped.",
                parseFailureShortCircuit = true,
            )
        }

        if (trigger is EgoTrigger.Continuation &&
            trigger.continuation.passes > config.planner.maxContinuationPasses
        ) {
            return EgoDecision.Noop(
                "Max continuation passes exceeded (${trigger.continuation.passes}/${config.planner.maxContinuationPasses})."
            )
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = meta.callSite,
            sessionId = context.conversationContext.sessionId,
            rootInputId = meta.rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val prompt = promptCatalog.renderSections(
            "planner/progression",
            mapOf(
                "system_framing" to meta.systemFraming,
                "action_guidance_block" to actionGuidanceBlock,
                "action_schema_enum" to actionSchemaEnum,
            )
        )
        val schema = promptCatalog.responseFormat("ego-planner-decision")
        val sections = listOfNotNull(
            *prompt.sections.toTypedArray(),
            SharedPromptSections.actionAvailabilitySection(context),
            SharedPromptSections.securityContextSection(context),
            SharedPromptSections.opportunityContextSection(context),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.longTermRecallSection(context),
            SharedPromptSections.lessonsSection(context),
            SharedPromptSections.episodicRecallSection(context),
            SharedPromptSections.scratchpadSection(context),
            SharedPromptSections.sessionDigestSection(context),
            SharedPromptSections.evidenceHintsSection(context),
            SharedPromptSections.deliberationPressureSection(context),
            SharedPromptSections.metaGuidanceSection(context),
            SharedPromptSections.idImpulseContextSection(context),
            SharedPromptSections.groundingRequirementSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections.filterNotNull(), config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = laneId,
            messages = allocation.messages,
            metadata = promptCatalog.metadata(metadata, prompt, schema),
            responseFormat = schema.format,
        )

        if (response == null) {
            return EgoDecision.Noop("ProgressionPlanner unavailable.")
        }

        val parsedDecision = parseDecision(response.content, context)
        if (parsedDecision != null) {
            runtime.recordSuccess(laneId, meta.rootInputId)
            return toEgoDecision(parsedDecision, context, meta.allowResolutionDraft)
        }

        if (TruncationRetry.isLikelyTruncated(response)) {
            runtime.notifyTruncationRetry()
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            runtime.call(
                laneId = laneId,
                messages = allocation.messages + ChatMessage(ChatRole.USER, promptCatalog.renderText("planner/json-truncation-retry").text),
                metadata = metadata.copy(callSite = "${meta.callSite}_truncation_retry"),
                responseFormat = schema.format,
                maxTokens = bumped,
                temperature = 0.0,
            )?.let { parseDecision(it.content, context) }?.let {
                runtime.recordSuccess(laneId, meta.rootInputId)
                return toEgoDecision(it, context, meta.allowResolutionDraft)
            }
        }

        runtime.call(
            laneId = laneId,
            messages = allocation.messages + ChatMessage(ChatRole.USER, promptCatalog.renderText("planner/json-strict-retry").text),
            metadata = metadata.copy(callSite = "${meta.callSite}_json_retry"),
            responseFormat = schema.format,
            temperature = 0.0,
        )?.let { parseDecision(it.content, context) }?.let {
            runtime.recordSuccess(laneId, meta.rootInputId)
            return toEgoDecision(it, context, meta.allowResolutionDraft)
        }

        runtime.recordParseFailure(laneId, meta.rootInputId)
        instrumentation.emit(AgentEvents.warning("ProgressionPlanner response remained non-parseable after retry."))
        return EgoDecision.Noop(
            reason = "ProgressionPlanner produced non-parseable output.",
            parseFailureShortCircuit = runtime.isCircuitOpen(laneId, meta.rootInputId),
        )
    }

    private fun extractTriggerMetadata(trigger: EgoTrigger): TriggerMeta? = when (trigger) {
        is EgoTrigger.Continuation -> TriggerMeta(
            rootInputId = trigger.continuation.rootInputId,
            callSite = "continuation",
            systemFraming = "processing a continuation. This is not a fresh input. Context from previous steps is available",
            allowResolutionDraft = trigger.continuation.planContext != null,
        )
        is EgoTrigger.ActionFeedback -> TriggerMeta(
            rootInputId = trigger.feedback.cue.rootInputId,
            callSite = "feedback",
            systemFraming = "interpreting the result of a completed action. Use the outcome to decide the next step: answer the user, retry, proceed to next step, or noop",
            allowResolutionDraft = true,
        )
        else -> null
    }

    private data class TriggerMeta(
        val rootInputId: String?,
        val callSite: String,
        val systemFraming: String,
        val allowResolutionDraft: Boolean,
    )

    private fun parseDecision(raw: String, context: PlannerContext): ProgressionDecision? {
        val payload = StructuredOutputHandler.parseWithRepair<ProgressionPayload>(raw) {
            runtime.onOutputRepaired()
        } ?: return null

        return when (payload.decision?.trim()?.lowercase()) {
            "intend" -> {
                val intentionKind = DecisionValidation.intentionKindFromRaw(payload.intentionKind)
                val actionType = ActionType.fromRaw(payload.actionType)
                val rawPayload = StructuredOutputHandler.normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
                val actionPayload = actionType?.let { runtime.repairActionPayload(it, rawPayload) } ?: rawPayload
                val summary = payload.actionSummary?.trim().orEmpty()
                val commitMode = DecisionValidation.resolveCommitModePreference(
                    payload.commitModePreference,
                    context.allowedCommitModes,
                    intentionKind,
                )
                if (intentionKind == null || actionType == null || actionPayload.isBlank() || summary.isBlank()) {
                    ProgressionDecision.Fail("Invalid intention payload.")
                } else if (actionType == ActionType.CONTACT_USER && intentionKind == IntentionKind.OBSERVE) {
                    ProgressionDecision.Answer(
                        urgency = Urgency.fromRaw(payload.urgency),
                        payload = actionPayload,
                        summary = summary,
                    )
                } else {
                    ProgressionDecision.Execute(
                        ExecutionCandidate(
                            urgency = Urgency.fromRaw(payload.urgency),
                            intentionKind = intentionKind,
                            commitModePreference = commitMode,
                            actionType = actionType,
                            payload = actionPayload,
                            summary = summary,
                        )
                    )
                }
            }
            "plan" -> {
                val assignment = payload.planAssignment?.trim().orEmpty()
                val steps = payload.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?.take(config.planner.maxPlanSteps)
                    ?.map { PlanDecomposition.PlanStep(description = it) }
                    .orEmpty()
                if (assignment.isBlank() || steps.isEmpty()) {
                    ProgressionDecision.Fail("Plan with missing assignment or empty steps.")
                } else {
                    ProgressionDecision.RefinePlan(
                        urgency = Urgency.fromRaw(payload.urgency),
                        assignment = assignment,
                        steps = steps,
                    )
                }
            }
            else -> ProgressionDecision.Fail(payload.reason?.take(120) ?: "Planner returned noop.")
        }
    }

    private fun toEgoDecision(
        decision: ProgressionDecision,
        context: PlannerContext,
        allowResolutionDraft: Boolean,
    ): EgoDecision = when (decision) {
        is ProgressionDecision.Execute -> {
            val candidate = decision.candidate
            when {
                candidate.actionType == ActionType.RESOLUTION_DRAFT && !allowResolutionDraft ->
                    EgoDecision.Noop("resolution_draft outside active plan context.")
                !DecisionValidation.isCommitModeValidForIntention(candidate.intentionKind, candidate.commitModePreference) ->
                    EgoDecision.Noop("Invalid commit_mode for intention kind.")
                candidate.actionType !in context.availableActions ->
                    EgoDecision.Noop("Unavailable action type: ${candidate.actionType.id}.")
                candidate.intentionKind !in context.allowedIntentions ->
                    EgoDecision.Noop("Unavailable intention kind.")
                candidate.commitModePreference !in context.allowedCommitModes ->
                    EgoDecision.Noop("Unavailable commit mode.")
                else -> EgoDecision.FormIntention(
                    urgency = candidate.urgency,
                    intentionKind = candidate.intentionKind,
                    commitModePreference = candidate.commitModePreference,
                    actionType = candidate.actionType,
                    payload = TextSecurity.clamp(candidate.payload, config.maxActionPayloadChars),
                    summary = TextSecurity.clamp(candidate.summary, config.maxActionSummaryChars),
                )
            }
        }
        is ProgressionDecision.RefinePlan -> {
            val steps = decision.steps
                .map { it.description.trim() }
                .filter { it.isNotBlank() }
                .take(config.planner.maxPlanSteps)
                .map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
            if (decision.assignment.isBlank() || steps.isEmpty()) {
                EgoDecision.Noop("Plan with missing assignment or empty steps.")
            } else {
                EgoDecision.EnqueuePlan(
                    urgency = decision.urgency,
                    assignment = TextSecurity.clamp(decision.assignment, config.planner.maxThoughtChars),
                    steps = steps,
                )
            }
        }
        is ProgressionDecision.Answer -> EgoDecision.FormIntention(
            urgency = decision.urgency,
            intentionKind = IntentionKind.OBSERVE,
            commitModePreference = CommitMode.NOT_APPLICABLE,
            actionType = ActionType.CONTACT_USER,
            payload = TextSecurity.clamp(decision.payload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(decision.summary, config.maxActionSummaryChars),
        )
        is ProgressionDecision.Fail -> EgoDecision.Noop(decision.reason)
    }

    private data class ProgressionPayload(
        val decision: String? = null,
        val urgency: String? = null,
        @param:JsonProperty("intention_kind") val intentionKind: String? = null,
        @param:JsonProperty("commit_mode_preference") val commitModePreference: String? = null,
        @param:JsonProperty("action_type") val actionType: String? = null,
        @param:JsonProperty("action_payload") val actionPayload: JsonNode? = null,
        @param:JsonProperty("action_summary") val actionSummary: String? = null,
        val reason: String? = null,
        @param:JsonProperty("plan_assignment") val planAssignment: String? = null,
        @param:JsonProperty("plan_steps") val planSteps: List<String>? = null,
    )
}
