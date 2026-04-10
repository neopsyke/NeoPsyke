package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.model.ContinuationDecision
import ai.neopsyke.agent.ego.planner.model.ExecutionCandidate
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
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRole

/**
 * L1 lane: handles EgoTrigger.Continuation with a narrower prompt
 * focused on continuation context, plan steps, and denial recovery.
 *
 * No deterministic text routing. Typed facts from trigger metadata
 * (plan context, denial reason codes, pass count) drive deterministic
 * pre-checks. Semantic interpretation is always model-based.
 */
class ContinuationPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) : PlannerLane {

    override val laneId: LaneId = LaneId.CONTINUATION

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val continuationTrigger = trigger as? EgoTrigger.Continuation
            ?: return EgoDecision.Noop("ContinuationPlanner requires Continuation trigger.")

        val rootInputId = continuationTrigger.continuation.rootInputId
        val continuation = continuationTrigger.continuation
        val allowResolutionDraft = continuation.planContext != null

        if (runtime.isCircuitOpen(laneId, rootInputId)) {
            return EgoDecision.Noop(
                reason = "Continuation circuit breaker tripped.",
                parseFailureShortCircuit = true,
            )
        }

        if (continuation.passes > config.planner.maxContinuationPasses) {
            return EgoDecision.Noop("Max continuation passes exceeded (${continuation.passes}/${config.planner.maxContinuationPasses}).")
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "continuation",
            sessionId = context.conversationContext.sessionId,
            rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "continuation_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 48,
                content = """
                    You are an action planner processing a continuation.
                    Return STRICT JSON only.
                    Decisions:
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
                key = "continuation_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                floorTokens = 28,
                content = """
                    JSON schema:
                    {
                      "decision":"intend|plan|noop",
                      "urgency":"low|medium|high",
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
            SharedPromptSections.groundingRequirementSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections.filterNotNull(), config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = laneId,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT,
        )

        if (response == null) {
            return EgoDecision.Noop("ContinuationPlanner unavailable.")
        }

        val parsedDecision = parseContinuationDecision(response.content, context)
        if (parsedDecision != null) {
            runtime.recordSuccess(laneId, rootInputId)
            return toEgoDecision(parsedDecision, context, allowResolutionDraft)
        }

        // Truncation + JSON retry (same pattern as MonolithicLaneStub)
        if (TruncationRetry.isLikelyTruncated(response)) {
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            val retryResponse = runtime.call(
                laneId = laneId,
                messages = allocation.messages + ChatMessage(ChatRole.USER, "Your previous output appears truncated. Return one complete JSON object."),
                metadata = metadata.copy(callSite = "continuation_truncation_retry"),
                responseFormat = StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT,
                maxTokens = bumped,
                temperature = 0.0,
            )
            retryResponse?.let { parseContinuationDecision(it.content, context) }?.let {
                runtime.recordSuccess(laneId, rootInputId)
                return toEgoDecision(it, context, allowResolutionDraft)
            }
        }

        val retryResponse = runtime.call(
            laneId = laneId,
            messages = allocation.messages + ChatMessage(ChatRole.USER, "Reply with STRICT JSON only."),
            metadata = metadata.copy(callSite = "continuation_json_retry"),
            responseFormat = StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT,
            temperature = 0.0,
        )
        retryResponse?.let { parseContinuationDecision(it.content, context) }?.let {
            runtime.recordSuccess(laneId, rootInputId)
            return toEgoDecision(it, context, allowResolutionDraft)
        }

        runtime.recordParseFailure(laneId, rootInputId)
        instrumentation.emit(AgentEvents.warning("ContinuationPlanner response remained non-parseable after retry."))
        return EgoDecision.Noop(
            reason = "ContinuationPlanner produced non-parseable output.",
            parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId),
        )
    }

    private fun parseContinuationDecision(raw: String, context: PlannerContext): ContinuationDecision? {
        val payload = StructuredOutputHandler.parseWithRepair<ContinuationPayload>(raw) {
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
                    intentionKind
                )
                if (intentionKind == null || actionType == null || actionPayload.isBlank() || summary.isBlank()) {
                    ContinuationDecision.Fail("Invalid intention payload.")
                } else {
                    ContinuationDecision.Execute(
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
                val goal = payload.planGoal?.trim().orEmpty()
                val steps = payload.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?.take(config.planner.maxPlanSteps)
                    ?.map { PlanDecomposition.PlanStep(description = it) }
                    .orEmpty()
                if (goal.isBlank() || steps.isEmpty()) {
                    ContinuationDecision.Fail("Plan with missing goal or empty steps.")
                } else {
                    ContinuationDecision.RefinePlan(
                        urgency = Urgency.fromRaw(payload.urgency),
                        goal = goal,
                        steps = steps,
                    )
                }
            }
            else -> ContinuationDecision.Fail(payload.reason?.take(120) ?: "Planner returned noop.")
        }
    }

    private fun toEgoDecision(
        decision: ContinuationDecision,
        context: PlannerContext,
        allowResolutionDraft: Boolean,
    ): EgoDecision {
        return when (decision) {
            is ContinuationDecision.Execute -> {
                val candidate = decision.candidate
                if (candidate.actionType == ActionType.RESOLUTION_DRAFT && !allowResolutionDraft) {
                    EgoDecision.Noop("resolution_draft outside active plan context.")
                } else if (!DecisionValidation.isCommitModeValidForIntention(
                        candidate.intentionKind,
                        candidate.commitModePreference
                    )
                ) {
                    EgoDecision.Noop("Invalid commit_mode for intention kind.")
                } else if (!context.availableActions.contains(candidate.actionType)) {
                    EgoDecision.Noop("Unavailable action type: ${candidate.actionType.id}.")
                } else if (candidate.intentionKind !in context.allowedIntentions) {
                    EgoDecision.Noop("Unavailable intention kind.")
                } else if (candidate.commitModePreference !in context.allowedCommitModes) {
                    EgoDecision.Noop("Unavailable commit mode.")
                } else {
                    EgoDecision.FormIntention(
                        urgency = candidate.urgency,
                        intentionKind = candidate.intentionKind,
                        commitModePreference = candidate.commitModePreference,
                        actionType = candidate.actionType,
                        payload = TextSecurity.clamp(candidate.payload, config.maxActionPayloadChars),
                        summary = TextSecurity.clamp(candidate.summary, config.maxActionSummaryChars),
                    )
                }
            }
            is ContinuationDecision.RefinePlan -> {
                val steps = decision.steps
                    .map { it.description.trim() }
                    .filter { it.isNotBlank() }
                    .take(config.planner.maxPlanSteps)
                    .map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
                if (decision.goal.isBlank() || steps.isEmpty()) {
                    EgoDecision.Noop("Plan with missing goal or empty steps.")
                } else {
                    EgoDecision.EnqueuePlan(
                        urgency = decision.urgency,
                        goal = TextSecurity.clamp(decision.goal, config.planner.maxThoughtChars),
                        steps = steps,
                    )
                }
            }
            is ContinuationDecision.Answer -> EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = ai.neopsyke.agent.model.IntentionKind.OBSERVE,
                commitModePreference = ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(decision.payload, config.maxActionPayloadChars),
                summary = TextSecurity.clamp(decision.summary, config.maxActionSummaryChars),
            )
            is ContinuationDecision.Clarify -> EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = ai.neopsyke.agent.model.IntentionKind.OBSERVE,
                commitModePreference = ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(decision.question, config.maxActionPayloadChars),
                summary = "Ask for clarification",
            )
            is ContinuationDecision.SkipStep -> EgoDecision.Noop(decision.reason)
            is ContinuationDecision.Fail -> EgoDecision.Noop(decision.reason)
        }
    }

    private data class ContinuationPayload(
        val decision: String? = null,
        val urgency: String? = null,
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
