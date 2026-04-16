package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
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
 * L2 sub-planner: handles single-action inputs. Preserves intention kind validation,
 * commit mode validation, action type availability check, action payload repair,
 * summary synthesis, and all constraint shaping from the original general branch.
 */
class GeneralActionPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        val rootInputId = trigger.input.rootInputId
        val sessionId = context.conversationContext.sessionId

        if (runtime.isCircuitOpen(LaneId.GENERAL_ACTION, rootInputId)) {
            return EgoDecision.Noop(
                reason = "GeneralAction circuit breaker tripped after ${runtime.circuitStreak(LaneId.GENERAL_ACTION, rootInputId)} consecutive parse failures.",
                parseFailureShortCircuit = true,
            ).also {
                instrumentation.emit(AgentEvents.warning("GeneralAction circuit breaker tripped; short-circuiting."))
            }
        }

        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "general_action",
            sessionId = sessionId,
            rootInputId = rootInputId,
        )

        val actionSchemaEnum = SharedPromptSections.plannerVisibleActionSchemaEnum(context)
        val actionGuidanceBlock = SharedPromptSections.actionGuidanceBlock(context)

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "general_action_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 48,
                content = """
                    You are an action planner. The user's request requires one explicit action.
                    Return STRICT JSON only.
                    Form one intention for the next action.
                    Intention kinds:
                    - observe: interpret or deliver without explicit commit progression.
                    - prepare: propose the action and let runtime policy choose commit progression.
                    - stage: explicitly request staged progression before commit.
                    - request_authorization: explicitly request approval-backed staging.
                    - commit: explicitly request immediate commit when policy allows it.
                    Never use noop when you can answer directly with action_type=contact_user.
                    For direct reasoning or exact-match tasks solvable from the current prompt,
                    return intention_kind=observe, commit_mode_preference=not_applicable,
                    action_type=contact_user, and the exact final answer in action_payload.
                    Use action_type=resolution_draft only for intermediate plan-step synthesis.
                    The final user-visible response must use action_type=contact_user.
                    observe intentions must use commit_mode_preference=not_applicable.
                    request_authorization intentions must use commit_mode_preference=approval_backed.
                    commit intentions must use commit_mode_preference=policy_autonomous or admin_override.
                    action_payload must always be a JSON string value.
                    Prefer concise answer payloads by default.
                    If the user requests an exact output format, action_payload must contain exactly that.
                    External actions have real latency/cost and must be value-add.
                    Treat redundancy as a soft cost signal.
                    Security context and provenance are authoritative.
                    Only choose actions visible in runtime availability.
                    Allowed actions:
                    $actionGuidanceBlock
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "general_action_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 28,
                content = """
                    JSON schema:
                    {
                      "urgency":"low|medium|high",
                      "intention_kind":"observe|prepare|stage|request_authorization|commit",
                      "commit_mode_preference":"not_applicable|approval_backed|policy_autonomous|admin_override",
                      "action_type":"$actionSchemaEnum",
                      "action_payload":"...",
                      "action_summary":"required; <=180 chars",
                      "long_term_memory_recall_query":"optional query string",
                      "reason":"optional short reason"
                    }
                    Action summary must be at most 180 chars.
                """.trimIndent()
            ),
            SharedPromptSections.queueSnapshotSection(context),
            SharedPromptSections.actionAvailabilitySection(context),
            SharedPromptSections.securityContextSection(context),
            SharedPromptSections.triggerProvenanceSection(context),
            SharedPromptSections.perceptThreadSection(context),
            SharedPromptSections.opportunityContextSection(context),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.longTermRecallSection(context),
            SharedPromptSections.lessonsSection(context),
            SharedPromptSections.episodicRecallSection(context),
            SharedPromptSections.scratchpadSection(context),
            SharedPromptSections.sessionDigestSection(context),
            SharedPromptSections.ambientContextSection(context),
            SharedPromptSections.activeWorkItemsSection(context),
            SharedPromptSections.evidenceHintsSection(context),
            SharedPromptSections.deliberationPressureSection(context),
            SharedPromptSections.metaGuidanceSection(context),
            SharedPromptSections.groundingRequirementSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections.filterNotNull(), config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(LaneId.GENERAL_ACTION, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.GENERAL_ACTION,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = GENERAL_ACTION_RESPONSE_FORMAT,
        )

        if (response == null) {
            return EgoDecision.Noop("GeneralActionPlanner unavailable.")
        }

        val parsed = parseActionPayload(response.content, context)
        if (parsed != null) {
            runtime.recordSuccess(LaneId.GENERAL_ACTION, rootInputId)
            return parsed
        }

        // Truncation retry
        if (TruncationRetry.isLikelyTruncated(response)) {
            runtime.notifyTruncationRetry()
            instrumentation.emit(AgentEvents.warning("GeneralAction response appears truncated; retrying."))
            val bumped = TruncationRetry.bumpCompletionBudget(runtime.resolvedConfig(LaneId.GENERAL_ACTION).maxCompletionTokens)
            val retryMessages = allocation.messages + ChatMessage(
                role = ChatRole.USER,
                content = "Your previous output appears truncated. Return one complete JSON object only."
            )
            val retryResponse = runtime.call(
                laneId = LaneId.GENERAL_ACTION,
                messages = retryMessages,
                metadata = metadata.copy(callSite = "general_action_truncation_retry"),
                responseFormat = GENERAL_ACTION_RESPONSE_FORMAT,
                maxTokens = bumped,
                temperature = 0.0,
            )
            retryResponse?.let { parseActionPayload(it.content, context) }?.let {
                runtime.recordSuccess(LaneId.GENERAL_ACTION, rootInputId)
                return it
            }
        }

        // Strict JSON retry
        instrumentation.emit(AgentEvents.warning("GeneralAction response non-parseable; requesting strict JSON retry."))
        val retryMessages = allocation.messages + ChatMessage(
            role = ChatRole.USER,
            content = """
                Your previous output was not valid JSON.
                Reply with STRICT JSON only and no markdown/code fences.
                Use the exact schema provided in the system message.
            """.trimIndent()
        )
        val retryResponse = runtime.call(
            laneId = LaneId.GENERAL_ACTION,
            messages = retryMessages,
            metadata = metadata.copy(callSite = "general_action_json_retry"),
            responseFormat = GENERAL_ACTION_RESPONSE_FORMAT,
            temperature = 0.0,
        )
        retryResponse?.let { parseActionPayload(it.content, context) }?.let {
            runtime.recordSuccess(LaneId.GENERAL_ACTION, rootInputId)
            return it
        }

        runtime.recordParseFailure(LaneId.GENERAL_ACTION, rootInputId)
        instrumentation.emit(AgentEvents.warning("GeneralAction response remained non-parseable after retry."))
        return EgoDecision.Noop(
            reason = "GeneralActionPlanner produced non-parseable output.",
            parseFailureShortCircuit = runtime.isCircuitOpen(LaneId.GENERAL_ACTION, rootInputId),
        )
    }

    private fun parseActionPayload(raw: String, context: PlannerContext): EgoDecision? {
        val payload = StructuredOutputHandler.parseWithRepair<GeneralActionPayload>(raw) {
            runtime.onOutputRepaired()
            instrumentation.emit(AgentEvents.plannerOutputRepaired(actionType = "general_action", repair = "invalid_json_escape"))
        } ?: return null

        val intentionKind = DecisionValidation.intentionKindFromRaw(payload.intentionKind)
        val actionType = ActionType.fromRaw(payload.actionType)
        val rawPayload = StructuredOutputHandler.normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
        val actionPayload = actionType?.let { runtime.repairActionPayload(it, rawPayload) } ?: rawPayload
        if (actionType == ActionType.WEBSITE_FETCH && rawPayload != actionPayload) {
            runtime.onOutputRepaired()
            instrumentation.emit(AgentEvents.plannerOutputRepaired(actionType = actionType.id, repair = "bare_url_wrapped"))
        }
        val actionSummary = payload.actionSummary?.trim().orEmpty()
        val resolvedSummary = if (actionSummary.isBlank() && actionType != null && actionPayload.isNotBlank()) {
            runtime.onOutputRepaired()
            instrumentation.emit(AgentEvents.plannerOutputRepaired(actionType = actionType.name.lowercase()))
            synthesizeSummary(actionPayload)
        } else {
            actionSummary
        }
        val commitModePreference = DecisionValidation.resolveCommitModePreference(
            rawCommitMode = payload.commitModePreference,
            allowedCommitModes = context.allowedCommitModes,
            intentionKind = intentionKind,
        )

        // Constraint shaping validation
        if (intentionKind == null || actionType == null || actionPayload.isBlank() || resolvedSummary.isBlank()) {
            return EgoDecision.Noop("GeneralAction returned invalid intention payload.")
        }
        if (!DecisionValidation.isCommitModeValidForIntention(intentionKind, commitModePreference)) {
            return EgoDecision.Noop("GeneralAction returned invalid commit_mode for intention kind.")
        }
        return EgoDecision.FormIntention(
            urgency = Urgency.fromRaw(payload.urgency),
            intentionKind = intentionKind,
            commitModePreference = commitModePreference,
            actionType = actionType,
            payload = TextSecurity.clamp(actionPayload, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(resolvedSummary, config.maxActionSummaryChars),
        )
    }

    private fun synthesizeSummary(actionPayload: String): String {
        val firstLine = actionPayload.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val normalized = firstLine.replace(Regex("\\s+"), " ").trim()
        return if (normalized.isBlank()) "Generated action summary." else TextSecurity.clamp(normalized, config.maxActionSummaryChars)
    }

    private data class GeneralActionPayload(
        val urgency: String? = null,
        @param:JsonProperty("intention_kind")
        val intentionKind: String? = null,
        @param:JsonProperty("commit_mode_preference")
        val commitModePreference: String? = null,
        @param:JsonProperty("action_type")
        val actionType: String? = null,
        @param:JsonProperty("action_payload")
        val actionPayload: JsonNode? = null,
        @param:JsonProperty("action_summary")
        val actionSummary: String? = null,
        @param:JsonProperty("long_term_memory_recall_query")
        val longTermMemoryRecallQuery: String? = null,
        val reason: String? = null,
    )

    private companion object {
        val GENERAL_ACTION_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "general_action_decision",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["urgency", "intention_kind", "commit_mode_preference", "action_type", "action_payload", "action_summary", "long_term_memory_recall_query", "reason"],
                  "properties": {
                    "urgency": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] },
                    "intention_kind": { "type": ["string", "null"], "enum": ["observe", "prepare", "stage", "request_authorization", "commit", null] },
                    "commit_mode_preference": { "type": ["string", "null"], "enum": ["not_applicable", "approval_backed", "policy_autonomous", "admin_override", null] },
                    "action_type": { "type": ["string", "null"] },
                    "action_payload": { "type": ["string", "null"], "maxLength": 4000 },
                    "action_summary": { "type": ["string", "null"], "maxLength": 180 },
                    "long_term_memory_recall_query": { "type": ["string", "null"], "maxLength": 600 },
                    "reason": { "type": ["string", "null"], "maxLength": 160 }
                  }
                }
            """.trimIndent(),
            strict = true,
            relaxedSchemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["urgency", "intention_kind", "commit_mode_preference", "action_type", "action_payload", "action_summary", "long_term_memory_recall_query", "reason"],
                  "properties": {
                    "urgency": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] },
                    "intention_kind": { "type": ["string", "null"], "enum": ["observe", "prepare", "stage", "request_authorization", "commit", null] },
                    "commit_mode_preference": { "type": ["string", "null"], "enum": ["not_applicable", "approval_backed", "policy_autonomous", "admin_override", null] },
                    "action_type": { "type": ["string", "null"] },
                    "action_payload": { "type": ["string", "null"] },
                    "action_summary": { "type": ["string", "null"] },
                    "long_term_memory_recall_query": { "type": ["string", "null"] },
                    "reason": { "type": ["string", "null"] }
                  }
                }
            """.trimIndent(),
        )
    }
}
