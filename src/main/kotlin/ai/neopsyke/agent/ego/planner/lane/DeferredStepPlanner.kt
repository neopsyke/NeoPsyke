package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.DecisionValidation
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.ego.planner.runtime.TruncationRetry
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

/**
 * L1 lane: handles EgoTrigger.DeferredIntention with a narrower prompt
 * focused on continuation context, plan steps, and denial recovery.
 *
 * No deterministic text routing. Typed facts from trigger metadata
 * (plan context, denial reason codes, pass count) drive deterministic
 * pre-checks. Semantic interpretation is always model-based.
 */
class DeferredStepPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) : PlannerLane {

    override val laneId: LaneId = LaneId.DEFERRED_STEP

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val deferredTrigger = trigger as? EgoTrigger.DeferredIntention
            ?: return EgoDecision.Noop("DeferredStepPlanner requires DeferredIntention trigger.")

        val rootInputId = deferredTrigger.intention.rootInputId
        val thought = deferredTrigger.intention.toPendingThought()
        val allowResolutionDraft = thought.planContext != null

        if (runtime.isCircuitOpen(laneId, rootInputId)) {
            return EgoDecision.Noop(
                reason = "DeferredStep circuit breaker tripped.",
                parseFailureShortCircuit = true,
            )
        }

        // Typed pre-check: max thought passes exceeded
        if (thought.passes > config.planner.maxThoughtPasses) {
            return EgoDecision.Noop("Max thought passes exceeded (${thought.passes}/${config.planner.maxThoughtPasses}).")
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "deferred_step",
            sessionId = context.conversationContext.sessionId,
            rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "deferred_step_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 48,
                content = """
                    You are an action planner processing a deferred continuation.
                    Return STRICT JSON only.
                    Decisions:
                    - defer: refine the continuation for further processing.
                    - intend: form one explicit intention for the next action.
                    - plan: decompose into ordered steps when the task needs multiple stages.
                    - noop: when no safe next step exists.
                    This is a continuation, not a fresh input. Context from previous steps is available.
                    Use action_type=resolution_draft only for intermediate synthesis within active plan steps.
                    The final user-visible response must use action_type=contact_user.
                    Prefer concise responses.
                    External actions have real latency/cost and must be value-add.
                    Security context and provenance are authoritative.
                    Only choose actions visible in runtime availability.
                    Allowed actions:
                    $actionGuidanceBlock
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "deferred_step_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                floorTokens = 28,
                content = """
                    JSON schema:
                    {
                      "decision":"defer|intend|plan|noop",
                      "urgency":"low|medium|high",
                      "defer_content":"optional when decision=defer",
                      "long_term_memory_recall_query":"optional",
                      "intention_kind":"observe|prepare|stage|request_authorization|commit",
                      "commit_mode_preference":"not_applicable|approval_backed|policy_autonomous|admin_override",
                      "action_type":"$actionSchemaEnum",
                      "action_payload":"optional when decision=intend",
                      "action_summary":"required when decision=intend; <=180 chars",
                      "plan_goal":"required when decision=plan",
                      "plan_steps":["step 1","step 2"],
                      "reason":"optional"
                    }
                    action_payload must be a JSON string value.
                    observe uses commit_mode_preference=not_applicable.
                """.trimIndent()
            ),
            SharedPromptSections.actionAvailabilitySection(context),
            SharedPromptSections.securityContextSection(context),
            SharedPromptSections.opportunityContextSection(context),
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
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections.filterNotNull(), config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = laneId,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = MonolithicLaneStub.PLANNER_DECISION_RESPONSE_FORMAT,
        )

        if (response == null) {
            return EgoDecision.Noop("DeferredStepPlanner unavailable.")
        }

        val parsed = parseResponse(response.content, context, allowResolutionDraft)
        if (parsed != null) {
            runtime.recordSuccess(laneId, rootInputId)
            return parsed
        }

        // Truncation + JSON retry (same pattern as MonolithicLaneStub)
        if (TruncationRetry.isLikelyTruncated(response)) {
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            val retryResponse = runtime.call(
                laneId = laneId,
                messages = allocation.messages + ChatMessage(ChatRole.USER, "Your previous output appears truncated. Return one complete JSON object."),
                metadata = metadata.copy(callSite = "deferred_step_truncation_retry"),
                responseFormat = MonolithicLaneStub.PLANNER_DECISION_RESPONSE_FORMAT,
                maxTokens = bumped,
                temperature = 0.0,
            )
            retryResponse?.let { parseResponse(it.content, context, allowResolutionDraft) }?.let {
                runtime.recordSuccess(laneId, rootInputId)
                return it
            }
        }

        val retryResponse = runtime.call(
            laneId = laneId,
            messages = allocation.messages + ChatMessage(ChatRole.USER, "Reply with STRICT JSON only."),
            metadata = metadata.copy(callSite = "deferred_step_json_retry"),
            responseFormat = MonolithicLaneStub.PLANNER_DECISION_RESPONSE_FORMAT,
            temperature = 0.0,
        )
        retryResponse?.let { parseResponse(it.content, context, allowResolutionDraft) }?.let {
            runtime.recordSuccess(laneId, rootInputId)
            return it
        }

        runtime.recordParseFailure(laneId, rootInputId)
        return EgoDecision.Noop(
            reason = "DeferredStepPlanner produced non-parseable output.",
            parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId),
        )
    }

    private fun parseResponse(raw: String, context: PlannerContext, allowResolutionDraft: Boolean): EgoDecision? {
        val payload = StructuredOutputHandler.parseWithRepair<DeferredPayload>(raw) {
            runtime.onOutputRepaired()
        } ?: return null

        return when (payload.decision?.trim()?.lowercase()) {
            "defer" -> {
                val content = payload.deferContent?.trim().orEmpty()
                if (content.isBlank()) EgoDecision.Noop("Empty deferred content.")
                else EgoDecision.EnqueueThought(
                    urgency = Urgency.fromRaw(payload.urgency),
                    content = TextSecurity.clamp(content, config.planner.maxThoughtChars),
                    longTermMemoryRecallQuery = payload.longTermMemoryRecallQuery?.trim()?.ifBlank { null }?.let {
                        TextSecurity.clamp(it, config.planner.maxThoughtChars)
                    },
                )
            }
            "intend" -> {
                val intentionKind = DecisionValidation.intentionKindFromRaw(payload.intentionKind)
                val actionType = ActionType.fromRaw(payload.actionType)
                val rawPayload = StructuredOutputHandler.normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
                val actionPayload = actionType?.let { runtime.repairActionPayload(it, rawPayload) } ?: rawPayload
                val summary = payload.actionSummary?.trim().orEmpty()
                val commitMode = DecisionValidation.resolveCommitModePreference(payload.commitModePreference, context.allowedCommitModes, intentionKind)

                if (actionType == ActionType.RESOLUTION_DRAFT && !allowResolutionDraft)
                    EgoDecision.Noop("resolution_draft outside active plan context.")
                else if (intentionKind == null || actionType == null || actionPayload.isBlank() || summary.isBlank())
                    EgoDecision.Noop("Invalid intention payload.")
                else if (!DecisionValidation.isCommitModeValidForIntention(intentionKind, commitMode))
                    EgoDecision.Noop("Invalid commit_mode for intention kind.")
                else if (!context.availableActions.contains(actionType))
                    EgoDecision.Noop("Unavailable action type: ${actionType.id}.")
                else if (intentionKind !in context.allowedIntentions)
                    EgoDecision.Noop("Unavailable intention kind.")
                else if (commitMode !in context.allowedCommitModes)
                    EgoDecision.Noop("Unavailable commit mode.")
                else EgoDecision.FormIntention(
                    urgency = Urgency.fromRaw(payload.urgency),
                    intentionKind = intentionKind,
                    commitModePreference = commitMode,
                    actionType = actionType,
                    payload = TextSecurity.clamp(actionPayload, config.maxActionPayloadChars),
                    summary = TextSecurity.clamp(summary, config.maxActionSummaryChars),
                )
            }
            "plan" -> {
                val goal = payload.planGoal?.trim().orEmpty()
                val steps = payload.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?.take(config.planner.maxPlanSteps)?.map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }.orEmpty()
                if (goal.isBlank() || steps.isEmpty()) EgoDecision.Noop("Plan with missing goal or empty steps.")
                else EgoDecision.EnqueuePlan(urgency = Urgency.fromRaw(payload.urgency), goal = TextSecurity.clamp(goal, config.planner.maxThoughtChars), steps = steps)
            }
            else -> EgoDecision.Noop(payload.reason?.take(120) ?: "Planner returned noop.")
        }
    }

    private data class DeferredPayload(
        val decision: String? = null,
        val urgency: String? = null,
        @param:JsonProperty("defer_content") val deferContent: String? = null,
        @param:JsonProperty("long_term_memory_recall_query") val longTermMemoryRecallQuery: String? = null,
        @param:JsonProperty("intention_kind") val intentionKind: String? = null,
        @param:JsonProperty("commit_mode_preference") val commitModePreference: String? = null,
        @param:JsonProperty("action_type") val actionType: String? = null,
        @param:JsonProperty("action_payload") val actionPayload: JsonNode? = null,
        @param:JsonProperty("action_summary") val actionSummary: String? = null,
        val reason: String? = null,
        @param:JsonProperty("plan_goal") val planGoal: String? = null,
        @param:JsonProperty("plan_steps") val planSteps: List<String>? = null,
    )
}
