package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.model.ExecutionCandidate
import ai.neopsyke.agent.ego.planner.model.FeedbackDecision
import ai.neopsyke.agent.ego.planner.model.PlanDecomposition
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
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRole

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
            SharedPromptSections.groundingRequirementSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = laneId, messages = allocation.messages, metadata = metadata,
            responseFormat = StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT,
        )

        if (response == null) return EgoDecision.Noop("FeedbackPlanner unavailable.")

        val parsedDecision = parseFeedbackDecision(response.content, context)
        if (parsedDecision != null) {
            runtime.recordSuccess(laneId, rootInputId)
            return toEgoDecision(parsedDecision, context)
        }

        if (TruncationRetry.isLikelyTruncated(response)) {
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Return one complete JSON object."),
                metadata.copy(callSite = "feedback_truncation_retry"), StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT, maxTokens = bumped, temperature = 0.0)
                ?.let { parseFeedbackDecision(it.content, context) }?.let {
                    runtime.recordSuccess(laneId, rootInputId)
                    return toEgoDecision(it, context)
                }
        }

        runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Reply with STRICT JSON only."),
            metadata.copy(callSite = "feedback_json_retry"), StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT, temperature = 0.0)
            ?.let { parseFeedbackDecision(it.content, context) }?.let {
                runtime.recordSuccess(laneId, rootInputId)
                return toEgoDecision(it, context)
            }

        runtime.recordParseFailure(laneId, rootInputId)
        instrumentation.emit(ai.neopsyke.instrumentation.AgentEvents.warning("FeedbackPlanner response remained non-parseable after retry."))
        return EgoDecision.Noop(reason = "FeedbackPlanner produced non-parseable output.", parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId))
    }

    private fun parseFeedbackDecision(raw: String, context: PlannerContext): FeedbackDecision? {
        val payload = StructuredOutputHandler.parseWithRepair<FeedbackPayload>(raw) { runtime.onOutputRepaired() } ?: return null
        return when (payload.decision?.trim()?.lowercase()) {
            "defer" -> {
                val content = payload.deferContent?.trim().orEmpty()
                if (content.isBlank()) {
                    FeedbackDecision.MarkBlocked("Empty deferred content.")
                } else {
                    FeedbackDecision.Defer(
                        urgency = Urgency.fromRaw(payload.urgency),
                        content = content,
                        longTermMemoryRecallQuery = payload.longTermMemoryRecallQuery?.trim()?.ifBlank { null },
                    )
                }
            }
            "intend" -> {
                val ik = DecisionValidation.intentionKindFromRaw(payload.intentionKind)
                val at = ActionType.fromRaw(payload.actionType)
                val rp = StructuredOutputHandler.normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
                val ap = at?.let { runtime.repairActionPayload(it, rp) } ?: rp
                val s = payload.actionSummary?.trim().orEmpty()
                val cm = DecisionValidation.resolveCommitModePreference(payload.commitModePreference, context.allowedCommitModes, ik)
                if (ik == null || at == null || ap.isBlank() || s.isBlank()) {
                    FeedbackDecision.MarkBlocked("Invalid intention.")
                } else {
                    val candidate = ExecutionCandidate(
                        urgency = Urgency.fromRaw(payload.urgency),
                        intentionKind = ik,
                        commitModePreference = cm,
                        actionType = at,
                        payload = ap,
                        summary = s,
                    )
                    if (at == ActionType.CONTACT_USER && ik == ai.neopsyke.agent.model.IntentionKind.OBSERVE) {
                        FeedbackDecision.Answer(
                            urgency = candidate.urgency,
                            payload = candidate.payload,
                            summary = candidate.summary,
                        )
                    } else {
                        FeedbackDecision.NextStep(candidate)
                    }
                }
            }
            "plan" -> {
                val g = payload.planGoal?.trim().orEmpty()
                val st = payload.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?.take(config.planner.maxPlanSteps)
                    ?.map { PlanDecomposition.PlanStep(description = it) }
                    .orEmpty()
                if (g.isBlank() || st.isEmpty()) {
                    FeedbackDecision.MarkBlocked("Missing plan goal/steps.")
                } else {
                    FeedbackDecision.RefinePlan(
                        urgency = Urgency.fromRaw(payload.urgency),
                        goal = g,
                        steps = st,
                    )
                }
            }
            else -> FeedbackDecision.MarkBlocked(payload.reason?.take(120) ?: "Planner returned noop.")
        }
    }

    private fun toEgoDecision(decision: FeedbackDecision, context: PlannerContext): EgoDecision {
        return when (decision) {
            is FeedbackDecision.Answer -> EgoDecision.FormIntention(
                urgency = decision.urgency,
                intentionKind = ai.neopsyke.agent.model.IntentionKind.OBSERVE,
                commitModePreference = ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(decision.payload, config.maxActionPayloadChars),
                summary = TextSecurity.clamp(decision.summary, config.maxActionSummaryChars),
            )
            is FeedbackDecision.NextStep -> toExecutionDecision(decision.candidate, context)
            is FeedbackDecision.Retry -> toExecutionDecision(decision.candidate, context)
            is FeedbackDecision.Defer -> EgoDecision.EnqueueThought(
                urgency = decision.urgency,
                content = TextSecurity.clamp(decision.content, config.planner.maxThoughtChars),
                longTermMemoryRecallQuery = decision.longTermMemoryRecallQuery?.let {
                    TextSecurity.clamp(it, config.planner.maxThoughtChars)
                },
            )
            is FeedbackDecision.RefinePlan -> {
                val steps = decision.steps
                    .map { it.description.trim() }
                    .filter { it.isNotBlank() }
                    .take(config.planner.maxPlanSteps)
                    .map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
                if (decision.goal.isBlank() || steps.isEmpty()) {
                    EgoDecision.Noop("Missing plan goal/steps.")
                } else {
                    EgoDecision.EnqueuePlan(
                        urgency = decision.urgency,
                        goal = TextSecurity.clamp(decision.goal, config.planner.maxThoughtChars),
                        steps = steps,
                    )
                }
            }
            is FeedbackDecision.MarkBlocked -> EgoDecision.Noop(decision.reason)
            is FeedbackDecision.MarkDone -> EgoDecision.Noop(decision.reason)
        }
    }

    private fun toExecutionDecision(candidate: ExecutionCandidate, context: PlannerContext): EgoDecision {
        if (!DecisionValidation.isCommitModeValidForIntention(candidate.intentionKind, candidate.commitModePreference)) {
            return EgoDecision.Noop("Invalid commit mode.")
        }
        if (candidate.actionType !in context.availableActions) {
            return EgoDecision.Noop("Unavailable action: ${candidate.actionType.id}.")
        }
        if (candidate.intentionKind !in context.allowedIntentions) {
            return EgoDecision.Noop("Unavailable intention kind.")
        }
        if (candidate.commitModePreference !in context.allowedCommitModes) {
            return EgoDecision.Noop("Unavailable commit mode.")
        }
        return EgoDecision.FormIntention(
            urgency = candidate.urgency,
            intentionKind = candidate.intentionKind,
            commitModePreference = candidate.commitModePreference,
            actionType = candidate.actionType,
            payload = TextSecurity.clamp(candidate.payload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(candidate.summary, config.maxActionSummaryChars),
        )
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
