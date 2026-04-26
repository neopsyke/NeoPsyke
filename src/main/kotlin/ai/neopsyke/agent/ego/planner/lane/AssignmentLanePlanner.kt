package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.model.ExecutionCandidate
import ai.neopsyke.agent.ego.planner.model.AssignmentDecision
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
import ai.neopsyke.prompt.PromptCatalog

/**
 * L1 lane: handles EgoTrigger.Assignment with an assignment-step prompt.
 * Includes assignment summary, step details, acceptance criteria, and available actions.
 */
class AssignmentLanePlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : PlannerLane {

    override val laneId: LaneId = LaneId.ASSIGNMENT

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val assignmentTrigger = trigger as? EgoTrigger.Assignment
            ?: return EgoDecision.Noop("AssignmentLanePlanner requires Assignment trigger.")

        val rootInputId = assignmentTrigger.workUnit.workItemId

        if (runtime.isCircuitOpen(laneId, rootInputId)) {
            return EgoDecision.Noop(reason = "Assignment circuit breaker tripped.", parseFailureShortCircuit = true)
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger, callSite = "assignment",
            sessionId = context.conversationContext.sessionId, rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val personaSections = SharedPromptSections.egoPersonaSections(promptCatalog, config.persona.name)
        val prompt = promptCatalog.renderSections(
            "planner/assignment-lane",
            mapOf(
                "action_guidance_block" to actionGuidanceBlock,
                "action_schema_enum" to actionSchemaEnum,
            )
        )
        val schema = promptCatalog.responseFormat("ego-planner-decision")
        val sections = listOfNotNull(
            *personaSections.toTypedArray(),
            *prompt.sections.toTypedArray(),
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
            laneId = laneId, messages = allocation.messages, metadata = promptCatalog.metadata(metadata, prompt, schema),
            responseFormat = schema.format,
        )

        if (response == null) return EgoDecision.Noop("AssignmentLanePlanner unavailable.")

        val parsedDecision = parseAssignmentDecision(response.content, context)
        if (parsedDecision != null) {
            runtime.recordSuccess(laneId, rootInputId)
            return toEgoDecision(parsedDecision, context)
        }

        if (TruncationRetry.isLikelyTruncated(response)) {
            runtime.notifyTruncationRetry()
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            val truncationPrompt = promptCatalog.renderText("planner/json-truncation-retry")
            runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, truncationPrompt.text),
                promptCatalog.metadata(metadata.copy(callSite = "assignment_truncation_retry"), truncationPrompt, schema),
                schema.format, maxTokens = bumped, temperature = 0.0)
                ?.let { parseAssignmentDecision(it.content, context) }?.let {
                    runtime.recordSuccess(laneId, rootInputId)
                    return toEgoDecision(it, context)
                }
        }

        val strictRetryPrompt = promptCatalog.renderText("planner/json-strict-retry")
        runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, strictRetryPrompt.text),
            promptCatalog.metadata(metadata.copy(callSite = "assignment_json_retry"), strictRetryPrompt, schema),
            schema.format, temperature = 0.0)
            ?.let { parseAssignmentDecision(it.content, context) }?.let {
                runtime.recordSuccess(laneId, rootInputId)
                return toEgoDecision(it, context)
            }

        runtime.recordParseFailure(laneId, rootInputId)
        instrumentation.emit(ai.neopsyke.instrumentation.AgentEvents.warning("AssignmentLanePlanner response remained non-parseable after retry."))
        return EgoDecision.Noop(reason = "AssignmentLanePlanner non-parseable.", parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId))
    }

    private fun parseAssignmentDecision(raw: String, context: PlannerContext): AssignmentDecision? {
        val p = StructuredOutputHandler.parseWithRepair<AssignmentPayload>(raw) { runtime.onOutputRepaired() } ?: return null
        return when (p.decision?.trim()?.lowercase()) {
            "intend" -> {
                val ik = DecisionValidation.intentionKindFromRaw(p.intentionKind); val at = ActionType.fromRaw(p.actionType)
                val rp = StructuredOutputHandler.normalizeActionPayload(p.actionPayload)?.trim().orEmpty()
                val ap = at?.let { runtime.repairActionPayload(it, rp) } ?: rp; val s = p.actionSummary?.trim().orEmpty()
                val cm = DecisionValidation.resolveCommitModePreference(p.commitModePreference, context.allowedCommitModes, ik)
                if (ik == null || at == null || ap.isBlank() || s.isBlank()) {
                    AssignmentDecision.FailStep("Invalid intention.")
                } else {
                    AssignmentDecision.ExecuteStep(
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
                val assignment = p.planAssignment?.trim().orEmpty()
                val st = p.planSteps?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?.take(config.planner.maxPlanSteps)
                    ?.map { PlanDecomposition.PlanStep(description = it) }
                    .orEmpty()
                if (assignment.isBlank() || st.isEmpty()) {
                    AssignmentDecision.FailStep("Missing plan.")
                } else {
                    AssignmentDecision.RefinePlan(
                        urgency = Urgency.fromRaw(p.urgency),
                        assignment = assignment,
                        steps = st,
                    )
                }
            }
            else -> AssignmentDecision.FailStep(p.reason?.take(120) ?: "Noop.")
        }
    }

    private fun toEgoDecision(decision: AssignmentDecision, context: PlannerContext): EgoDecision {
        return when (decision) {
            is AssignmentDecision.ExecuteStep -> {
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
            is AssignmentDecision.RefinePlan -> {
                val steps = decision.steps
                    .map { it.description.trim() }
                    .filter { it.isNotBlank() }
                    .take(config.planner.maxPlanSteps)
                    .map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
                if (decision.assignment.isBlank() || steps.isEmpty()) {
                    EgoDecision.Noop("Missing plan.")
                } else {
                    EgoDecision.EnqueuePlan(
                        urgency = decision.urgency,
                        assignment = TextSecurity.clamp(decision.assignment, config.planner.maxThoughtChars),
                        steps = steps,
                    )
                }
            }
            is AssignmentDecision.MarkStepComplete -> EgoDecision.Noop(decision.reason)
            is AssignmentDecision.RequestClarification -> EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = ai.neopsyke.agent.model.IntentionKind.OBSERVE,
                commitModePreference = ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(decision.question, config.maxActionPayloadChars),
                summary = "Ask for clarification",
            )
            is AssignmentDecision.FailStep -> EgoDecision.Noop(decision.reason)
        }
    }

    private data class AssignmentPayload(
        val decision: String? = null, val urgency: String? = null,
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
