package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.model.ExecutionCandidate
import ai.neopsyke.agent.ego.planner.model.GoalWorkDecision
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
 * L1 lane: handles EgoTrigger.GoalWork with a goal-focused prompt.
 * Includes goal work summary, step details, acceptance criteria, available actions.
 */
class GoalWorkPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) : PlannerLane {

    override val laneId: LaneId = LaneId.GOAL_WORK

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val goalTrigger = trigger as? EgoTrigger.GoalWork
            ?: return EgoDecision.Noop("GoalWorkPlanner requires GoalWork trigger.")

        val rootInputId = goalTrigger.workUnit.goalId

        if (runtime.isCircuitOpen(laneId, rootInputId)) {
            return EgoDecision.Noop(reason = "GoalWork circuit breaker tripped.", parseFailureShortCircuit = true)
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger, callSite = "goal_work",
            sessionId = context.conversationContext.sessionId, rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "goal_work_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 48,
                content = """
                    You are an action planner executing a goal step.
                    Return STRICT JSON only.
                    Decisions: intend, plan, noop.
                    You are working on a persistent goal step. The trigger contains the step details and acceptance criteria.
                    Execute the step by choosing an appropriate action.
                    If the step is already satisfied by available evidence, deliver the result using contact_user.
                    If you need more information, use available tools.
                    Only choose actions visible in runtime availability.
                    Allowed actions:
                    $actionGuidanceBlock
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "goal_work_schema",
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
                """.trimIndent()
            ),
            SharedPromptSections.actionAvailabilitySection(context),
            SharedPromptSections.securityContextSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.longTermRecallSection(context),
            SharedPromptSections.scratchpadSection(context),
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

        if (response == null) return EgoDecision.Noop("GoalWorkPlanner unavailable.")

        val parsedDecision = parseGoalWorkDecision(response.content, context)
        if (parsedDecision != null) {
            runtime.recordSuccess(laneId, rootInputId)
            return toEgoDecision(parsedDecision, context)
        }

        if (TruncationRetry.isLikelyTruncated(response)) {
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Return one complete JSON object."),
                metadata.copy(callSite = "goal_work_truncation_retry"), StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT, maxTokens = bumped, temperature = 0.0)
                ?.let { parseGoalWorkDecision(it.content, context) }?.let {
                    runtime.recordSuccess(laneId, rootInputId)
                    return toEgoDecision(it, context)
                }
        }

        runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Reply with STRICT JSON only."),
            metadata.copy(callSite = "goal_work_json_retry"), StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT, temperature = 0.0)
            ?.let { parseGoalWorkDecision(it.content, context) }?.let {
                runtime.recordSuccess(laneId, rootInputId)
                return toEgoDecision(it, context)
            }

        runtime.recordParseFailure(laneId, rootInputId)
        instrumentation.emit(ai.neopsyke.instrumentation.AgentEvents.warning("GoalWorkPlanner response remained non-parseable after retry."))
        return EgoDecision.Noop(reason = "GoalWorkPlanner non-parseable.", parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId))
    }

    private fun parseGoalWorkDecision(raw: String, context: PlannerContext): GoalWorkDecision? {
        val p = StructuredOutputHandler.parseWithRepair<GoalWorkPayload>(raw) { runtime.onOutputRepaired() } ?: return null
        return when (p.decision?.trim()?.lowercase()) {
            "intend" -> {
                val ik = DecisionValidation.intentionKindFromRaw(p.intentionKind); val at = ActionType.fromRaw(p.actionType)
                val rp = StructuredOutputHandler.normalizeActionPayload(p.actionPayload)?.trim().orEmpty()
                val ap = at?.let { runtime.repairActionPayload(it, rp) } ?: rp; val s = p.actionSummary?.trim().orEmpty()
                val cm = DecisionValidation.resolveCommitModePreference(p.commitModePreference, context.allowedCommitModes, ik)
                if (ik == null || at == null || ap.isBlank() || s.isBlank()) {
                    GoalWorkDecision.FailStep("Invalid intention.")
                } else {
                    GoalWorkDecision.ExecuteStep(
                        ExecutionCandidate(
                            urgency = Urgency.fromRaw(p.urgency),
                            intentionKind = ik,
                            commitModePreference = cm,
                            actionType = at,
                            payload = ap,
                            summary = s,
                        )
                    )
                }
            }
            "plan" -> {
                val g = p.planGoal?.trim().orEmpty()
                val st = p.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?.take(config.planner.maxPlanSteps)
                    ?.map { PlanDecomposition.PlanStep(description = it) }
                    .orEmpty()
                if (g.isBlank() || st.isEmpty()) {
                    GoalWorkDecision.FailStep("Missing plan.")
                } else {
                    GoalWorkDecision.RefinePlan(
                        urgency = Urgency.fromRaw(p.urgency),
                        goal = g,
                        steps = st,
                    )
                }
            }
            else -> GoalWorkDecision.FailStep(p.reason?.take(120) ?: "Noop.")
        }
    }

    private fun toEgoDecision(decision: GoalWorkDecision, context: PlannerContext): EgoDecision {
        return when (decision) {
            is GoalWorkDecision.ExecuteStep -> {
                val candidate = decision.candidate
                if (!DecisionValidation.isCommitModeValidForIntention(candidate.intentionKind, candidate.commitModePreference)) {
                    EgoDecision.Noop("Invalid commit mode.")
                } else if (candidate.actionType !in context.availableActions) {
                    EgoDecision.Noop("Unavailable action.")
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
            is GoalWorkDecision.RefinePlan -> {
                val steps = decision.steps
                    .map { it.description.trim() }
                    .filter { it.isNotBlank() }
                    .take(config.planner.maxPlanSteps)
                    .map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
                if (decision.goal.isBlank() || steps.isEmpty()) {
                    EgoDecision.Noop("Missing plan.")
                } else {
                    EgoDecision.EnqueuePlan(
                        urgency = decision.urgency,
                        goal = TextSecurity.clamp(decision.goal, config.planner.maxThoughtChars),
                        steps = steps,
                    )
                }
            }
            is GoalWorkDecision.MarkStepComplete -> EgoDecision.Noop(decision.reason)
            is GoalWorkDecision.RequestClarification -> EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = ai.neopsyke.agent.model.IntentionKind.OBSERVE,
                commitModePreference = ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(decision.question, config.maxActionPayloadChars),
                summary = "Ask for clarification",
            )
            is GoalWorkDecision.FailStep -> EgoDecision.Noop(decision.reason)
        }
    }

    private data class GoalWorkPayload(
        val decision: String? = null, val urgency: String? = null,
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
