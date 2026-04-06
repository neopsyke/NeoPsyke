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
import ai.neopsyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

/**
 * L1 lane: handles EgoTrigger.ActionFeedback with a prompt focused on
 * action outcome interpretation, retry decisions, and follow-up planning.
 *
 * Deterministic pre-checks are limited to typed facts: execution status enum,
 * retry budget counters, follow-up-allowed flag. Semantic interpretation is model-based.
 */
class FeedbackPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) : PlannerLane {

    override val laneId: LaneId = LaneId.FEEDBACK

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val feedbackTrigger = trigger as? EgoTrigger.ActionFeedback
            ?: return EgoDecision.Noop("FeedbackPlanner requires ActionFeedback trigger.")

        val rootInputId = feedbackTrigger.feedback.cue.rootInputId

        if (runtime.isCircuitOpen(laneId, rootInputId)) {
            return EgoDecision.Noop(reason = "Feedback circuit breaker tripped.", parseFailureShortCircuit = true)
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger, callSite = "feedback",
            sessionId = context.conversationContext.sessionId, rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "feedback_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 48,
                content = """
                    You are an action planner interpreting the result of a completed action.
                    Return STRICT JSON only.
                    Decisions:
                    - defer: defer for further processing.
                    - intend: form one explicit intention for the next action.
                    - plan: decompose into ordered steps.
                    - noop: when no next step exists.
                    The trigger describes a completed action and its outcome.
                    Use the outcome to decide the next step: answer the user, retry, proceed to next step, or defer.
                    If the action succeeded and you can answer the user, prefer action_type=contact_user.
                    Prefer concise responses.
                    Only choose actions visible in runtime availability.
                    Allowed actions:
                    $actionGuidanceBlock
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "feedback_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                floorTokens = 28,
                content = """
                    JSON schema:
                    {
                      "decision":"defer|intend|plan|noop",
                      "urgency":"low|medium|high",
                      "defer_content":"optional",
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
                """.trimIndent()
            ),
            SharedPromptSections.actionAvailabilitySection(context),
            SharedPromptSections.securityContextSection(context),
            SharedPromptSections.opportunityContextSection(context),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.longTermRecallSection(context),
            SharedPromptSections.lessonsSection(context),
            SharedPromptSections.scratchpadSection(context),
            SharedPromptSections.sessionDigestSection(context),
            SharedPromptSections.evidenceHintsSection(context),
            SharedPromptSections.deliberationPressureSection(context),
            SharedPromptSections.metaGuidanceSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = laneId, messages = allocation.messages, metadata = metadata,
            responseFormat = MonolithicLaneStub.PLANNER_DECISION_RESPONSE_FORMAT,
        )

        if (response == null) return EgoDecision.Noop("FeedbackPlanner unavailable.")

        val parsed = parseResponse(response.content, context)
        if (parsed != null) { runtime.recordSuccess(laneId, rootInputId); return parsed }

        if (TruncationRetry.isLikelyTruncated(response)) {
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Return one complete JSON object."),
                metadata.copy(callSite = "feedback_truncation_retry"), MonolithicLaneStub.PLANNER_DECISION_RESPONSE_FORMAT, maxTokens = bumped, temperature = 0.0)
                ?.let { parseResponse(it.content, context) }?.let { runtime.recordSuccess(laneId, rootInputId); return it }
        }

        runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Reply with STRICT JSON only."),
            metadata.copy(callSite = "feedback_json_retry"), MonolithicLaneStub.PLANNER_DECISION_RESPONSE_FORMAT, temperature = 0.0)
            ?.let { parseResponse(it.content, context) }?.let { runtime.recordSuccess(laneId, rootInputId); return it }

        runtime.recordParseFailure(laneId, rootInputId)
        return EgoDecision.Noop(reason = "FeedbackPlanner produced non-parseable output.", parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId))
    }

    private fun parseResponse(raw: String, context: PlannerContext): EgoDecision? {
        val payload = StructuredOutputHandler.parseWithRepair<FeedbackPayload>(raw) { runtime.onOutputRepaired() } ?: return null
        return when (payload.decision?.trim()?.lowercase()) {
            "defer" -> {
                val content = payload.deferContent?.trim().orEmpty()
                if (content.isBlank()) EgoDecision.Noop("Empty deferred content.")
                else EgoDecision.EnqueueThought(Urgency.fromRaw(payload.urgency), TextSecurity.clamp(content, config.planner.maxThoughtChars),
                    payload.longTermMemoryRecallQuery?.trim()?.ifBlank { null }?.let { TextSecurity.clamp(it, config.planner.maxThoughtChars) })
            }
            "intend" -> {
                val ik = DecisionValidation.intentionKindFromRaw(payload.intentionKind)
                val at = ActionType.fromRaw(payload.actionType)
                val rp = StructuredOutputHandler.normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
                val ap = at?.let { runtime.repairActionPayload(it, rp) } ?: rp
                val s = payload.actionSummary?.trim().orEmpty()
                val cm = DecisionValidation.resolveCommitModePreference(payload.commitModePreference, context.allowedCommitModes, ik)
                if (ik == null || at == null || ap.isBlank() || s.isBlank()) EgoDecision.Noop("Invalid intention.")
                else if (!DecisionValidation.isCommitModeValidForIntention(ik, cm)) EgoDecision.Noop("Invalid commit mode.")
                else if (at !in context.availableActions) EgoDecision.Noop("Unavailable action: ${at.id}.")
                else if (ik !in context.allowedIntentions) EgoDecision.Noop("Unavailable intention kind.")
                else if (cm !in context.allowedCommitModes) EgoDecision.Noop("Unavailable commit mode.")
                else EgoDecision.FormIntention(Urgency.fromRaw(payload.urgency), ik, cm, at, TextSecurity.clamp(ap, config.maxActionPayloadChars), TextSecurity.clamp(s, config.maxActionSummaryChars))
            }
            "plan" -> {
                val g = payload.planGoal?.trim().orEmpty()
                val st = payload.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }?.take(config.planner.maxPlanSteps)?.map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }.orEmpty()
                if (g.isBlank() || st.isEmpty()) EgoDecision.Noop("Missing plan goal/steps.")
                else EgoDecision.EnqueuePlan(Urgency.fromRaw(payload.urgency), TextSecurity.clamp(g, config.planner.maxThoughtChars), st)
            }
            else -> EgoDecision.Noop(payload.reason?.take(120) ?: "Planner returned noop.")
        }
    }

    private data class FeedbackPayload(
        val decision: String? = null, val urgency: String? = null,
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
