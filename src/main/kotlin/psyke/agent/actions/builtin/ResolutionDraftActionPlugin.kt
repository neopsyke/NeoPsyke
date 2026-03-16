package psyke.agent.actions.builtin

import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionDeterministicReview
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
import psyke.agent.support.TextSecurity

class ResolutionDraftActionPlugin : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.RESOLUTION_DRAFT,
        dispatchable = true,
        plannerDescription = "resolution_draft: payload is an internal draft chunk for workspace synthesis; not user-visible.",
        payloadGuidance = "Plain text draft chunk used only for intermediate synthesis within plan execution.",
        payloadSchemaExample = """Draft chunk: verified pricing table from official sources...""",
        requiresFollowUpThought = false,
        followUpPrefix = "Resolution draft captured.",
        superegoDirectives = listOf(
            "Allow RESOLUTION_DRAFT always",
            "Do not treat RESOLUTION_DRAFT as a user-visible final response."
        )
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        if (action.payload.trim().isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "resolution_draft_payload_blank",
                reason = "RESOLUTION_DRAFT payload must not be blank."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val preview = TextSecurity.preview(action.payload, 180)
        return ActionOutcome(
            statusSummary = "Internal resolution draft chunk captured.",
            assistantOutput = null,
            plannerSignal = "resolution_draft chunk captured: $preview",
            observedEvidence = false
        )
    }
}

class ResolutionDraftActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ResolutionDraftActionPlugin()
}
