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
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.prompt.PromptCatalog

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
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
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

        val personaSections = SharedPromptSections.egoPersonaSections(promptCatalog, config.persona.name)
        val prompt = promptCatalog.renderSections(
            "planner/general-action",
            mapOf(
                "action_guidance_block" to actionGuidanceBlock,
                "action_schema_enum" to actionSchemaEnum,
            )
        )
        val schema = promptCatalog.responseFormat("general-action")
        val sections = listOfNotNull(
            *personaSections.toTypedArray(),
            *prompt.sections.toTypedArray(),
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
            metadata = promptCatalog.metadata(metadata, prompt, schema),
            responseFormat = schema.format,
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
            val truncationPrompt = promptCatalog.renderText("planner/json-truncation-retry")
            val retryMessages = allocation.messages + ChatMessage(
                role = ChatRole.USER,
                content = truncationPrompt.text
            )
            val retryResponse = runtime.call(
                laneId = LaneId.GENERAL_ACTION,
                messages = retryMessages,
                metadata = promptCatalog.metadata(metadata.copy(callSite = "general_action_truncation_retry"), truncationPrompt, schema),
                responseFormat = schema.format,
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
        val strictRetryPrompt = promptCatalog.renderText("planner/json-strict-retry")
        val retryMessages = allocation.messages + ChatMessage(
            role = ChatRole.USER,
            content = strictRetryPrompt.text
        )
        val retryResponse = runtime.call(
            laneId = LaneId.GENERAL_ACTION,
            messages = retryMessages,
            metadata = promptCatalog.metadata(metadata.copy(callSite = "general_action_json_retry"), strictRetryPrompt, schema),
            responseFormat = schema.format,
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

}
