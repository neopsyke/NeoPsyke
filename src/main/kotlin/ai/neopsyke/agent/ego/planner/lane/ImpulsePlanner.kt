package ai.neopsyke.agent.ego.planner.lane

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.model.ExecutionCandidate
import ai.neopsyke.agent.ego.planner.model.ImpulseDecision
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
 * L1 lane: handles EgoTrigger.IncomingImpulse (Id/self-motivated work).
 * Separate because self-motivated planning has materially different behavioral
 * rules from user-request-driven planning.
 */
class ImpulsePlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) : PlannerLane {

    override val laneId: LaneId = LaneId.IMPULSE

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val impulseTrigger = trigger as? EgoTrigger.IncomingImpulse
            ?: return EgoDecision.Noop("ImpulsePlanner requires IncomingImpulse trigger.")

        val rootInputId = impulseTrigger.impulse.rootImpulseId

        if (runtime.isCircuitOpen(laneId, rootInputId)) {
            return EgoDecision.Noop(reason = "Impulse circuit breaker tripped.", parseFailureShortCircuit = true)
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger, callSite = "impulse",
            sessionId = context.conversationContext.sessionId, rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "impulse_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 48,
                content = """
                    You are an action planner processing a self-motivated impulse.
                    Return STRICT JSON only.
                    Decisions: intend, noop.
                    This is a self-initiated trigger, not a user request.
                    Act proportionally to the motivation described in the context.
                    Only act if there is genuine value; prefer noop otherwise.
                    Only choose actions visible in runtime availability.
                    Allowed actions:
                    $actionGuidanceBlock
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "impulse_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                floorTokens = 28,
                content = """
                    JSON schema:
                    {
                      "decision":"intend|noop",
                      "urgency":"low|medium|high",
                      "intention_kind":"observe|prepare|stage|request_authorization|commit",
                      "commit_mode_preference":"not_applicable|approval_backed|policy_autonomous|admin_override",
                      "action_type":"$actionSchemaEnum",
                      "action_payload":"optional when decision=intend",
                      "action_summary":"required when decision=intend; <=180 chars",
                      "reason":"optional"
                    }
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
            SharedPromptSections.evidenceHintsSection(context),
            SharedPromptSections.deliberationPressureSection(context),
            SharedPromptSections.metaGuidanceSection(context),
            SharedPromptSections.idImpulseContextSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections.filterNotNull(), config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(laneId, allocation.diagnostics)

        val response = runtime.call(
            laneId = laneId, messages = allocation.messages, metadata = metadata,
            responseFormat = StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT,
        )

        if (response == null) return EgoDecision.Noop("ImpulsePlanner unavailable.")

        val parsedDecision = parseImpulseDecision(response.content, context)
        if (parsedDecision != null) {
            runtime.recordSuccess(laneId, rootInputId)
            return toEgoDecision(parsedDecision, context)
        }

        if (TruncationRetry.isLikelyTruncated(response)) {
            runtime.notifyTruncationRetry()
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(laneId).maxCompletionTokens)
            runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Return one complete JSON object."),
                metadata.copy(callSite = "impulse_truncation_retry"), StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT, maxTokens = bumped, temperature = 0.0)
                ?.let { parseImpulseDecision(it.content, context) }?.let {
                    runtime.recordSuccess(laneId, rootInputId)
                    return toEgoDecision(it, context)
                }
        }

        runtime.call(laneId, allocation.messages + ChatMessage(ChatRole.USER, "Reply with STRICT JSON only."),
            metadata.copy(callSite = "impulse_json_retry"), StructuredOutputHandler.PLANNER_DECISION_RESPONSE_FORMAT, temperature = 0.0)
            ?.let { parseImpulseDecision(it.content, context) }?.let {
                runtime.recordSuccess(laneId, rootInputId)
                return toEgoDecision(it, context)
            }

        runtime.recordParseFailure(laneId, rootInputId)
        instrumentation.emit(ai.neopsyke.instrumentation.AgentEvents.warning("ImpulsePlanner response remained non-parseable after retry."))
        return EgoDecision.Noop(reason = "ImpulsePlanner non-parseable.", parseFailureShortCircuit = runtime.isCircuitOpen(laneId, rootInputId))
    }

    private fun parseImpulseDecision(raw: String, context: PlannerContext): ImpulseDecision? {
        val p = StructuredOutputHandler.parseWithRepair<ImpulsePayload>(raw) { runtime.onOutputRepaired() } ?: return null
        return when (p.decision?.trim()?.lowercase()) {
            "intend" -> {
                val ik = DecisionValidation.intentionKindFromRaw(p.intentionKind); val at = ActionType.fromRaw(p.actionType)
                val rp = StructuredOutputHandler.normalizeActionPayload(p.actionPayload)?.trim().orEmpty()
                val ap = at?.let { runtime.repairActionPayload(it, rp) } ?: rp; val s = p.actionSummary?.trim().orEmpty()
                val cm = DecisionValidation.resolveCommitModePreference(p.commitModePreference, context.allowedCommitModes, ik)
                if (ik == null || at == null || ap.isBlank() || s.isBlank()) {
                    ImpulseDecision.Noop("Invalid intention.")
                } else if (at == ActionType.CONTACT_USER && ik == ai.neopsyke.agent.model.IntentionKind.OBSERVE) {
                    ImpulseDecision.ContactUser(
                        urgency = Urgency.fromRaw(p.urgency),
                        payload = ap,
                        summary = s,
                    )
                } else {
                    ImpulseDecision.Research(
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
            else -> ImpulseDecision.Noop(p.reason?.take(120) ?: "Noop.")
        }
    }

    private fun toEgoDecision(decision: ImpulseDecision, context: PlannerContext): EgoDecision {
        return when (decision) {
            is ImpulseDecision.Reflect -> EgoDecision.Noop("Impulse reflection continuation removed.")
            is ImpulseDecision.Research -> {
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
            is ImpulseDecision.ContactUser -> EgoDecision.FormIntention(
                urgency = decision.urgency,
                intentionKind = ai.neopsyke.agent.model.IntentionKind.OBSERVE,
                commitModePreference = ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
                actionType = ActionType.CONTACT_USER,
                payload = TextSecurity.clamp(decision.payload, config.maxActionPayloadChars),
                summary = TextSecurity.clamp(decision.summary, config.maxActionSummaryChars),
            )
            is ImpulseDecision.Noop -> EgoDecision.Noop(decision.reason)
        }
    }

    private data class ImpulsePayload(
        val decision: String? = null, val urgency: String? = null,
        @param:JsonProperty("intention_kind") val intentionKind: String? = null,
        @param:JsonProperty("commit_mode_preference") val commitModePreference: String? = null,
        @param:JsonProperty("action_type") val actionType: String? = null,
        @param:JsonProperty("action_payload") val actionPayload: JsonNode? = null,
        @param:JsonProperty("action_summary") val actionSummary: String? = null,
        val reason: String? = null,
    )
}
