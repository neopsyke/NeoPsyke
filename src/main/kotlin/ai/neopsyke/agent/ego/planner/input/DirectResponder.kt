package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
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
import ai.neopsyke.prompt.PromptCatalog

/**
 * L2 sub-planner: generates terminal answers or clarification requests
 * for inputs that can be answered directly from current context.
 */
class DirectResponder(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "direct_response",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val prompt = promptCatalog.renderSections("planner/direct-response")
        val schema = promptCatalog.responseFormat("direct-response")
        val sections = listOfNotNull(
            *prompt.sections.toTypedArray(),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.longTermRecallSection(context),
            SharedPromptSections.episodicRecallSection(context),
            SharedPromptSections.evidenceHintsSection(context),
            SharedPromptSections.scratchpadSection(context),
            SharedPromptSections.groundingRequirementSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(LaneId.DIRECT_RESPONSE, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.DIRECT_RESPONSE,
            messages = allocation.messages,
            metadata = promptCatalog.metadata(metadata, prompt, schema),
            responseFormat = schema.format,
        )

        if (response == null) {
            return EgoDecision.Noop("DirectResponder unavailable.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<DirectResponsePayload>(response.content)
        if (payload == null) {
            return EgoDecision.Noop("DirectResponder parse failure.")
        }

        if (payload.needsMoreContext == true) {
            return EgoDecision.Noop("Direct response requires more context.")
        }

        val answer = payload.answer?.trim().orEmpty()
        val summary = payload.summary?.trim().orEmpty().ifBlank { synthesizeSummary(answer) }

        if (answer.isBlank()) {
            return EgoDecision.Noop("DirectResponder returned empty answer.")
        }

        return EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.OBSERVE,
            commitModePreference = CommitMode.NOT_APPLICABLE,
            actionType = ActionType.CONTACT_USER,
            payload = TextSecurity.clamp(answer, config.maxActionPayloadChars),
            summary = TextSecurity.clamp(summary, config.maxActionSummaryChars),
        )
    }

    private fun synthesizeSummary(payload: String): String {
        val firstLine = payload.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val normalized = firstLine.replace(Regex("\\s+"), " ").trim()
        return if (normalized.isBlank()) "Direct response" else TextSecurity.clamp(normalized, config.maxActionSummaryChars)
    }

    private data class DirectResponsePayload(
        val answer: String? = null,
        val summary: String? = null,
        @param:JsonProperty("needs_more_context")
        val needsMoreContext: Boolean? = null,
    )

}
