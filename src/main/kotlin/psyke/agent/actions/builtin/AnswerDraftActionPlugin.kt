package psyke.agent.actions.builtin

import psyke.agent.actions.ActionCapability
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

class AnswerDraftActionPlugin : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.ANSWER_DRAFT,
        dispatchable = true,
        plannerDescription = "answer_draft: payload is an internal answer chunk for workspace synthesis; not user-visible.",
        payloadGuidance = "Plain text draft chunk used only for intermediate synthesis within plan execution.",
        payloadSchemaExample = """Draft chunk: verified pricing table from official sources...""",
        requiresFollowUpThought = false,
        followUpPrefix = "Answer draft captured.",
        superegoDirectives = listOf(
            "Allow ANSWER_DRAFT always",
            "Do not treat ANSWER_DRAFT as a user-visible final response."
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
                ruleId = "answer_payload_blank",
                reason = "ANSWER_DRAFT payload must not be blank."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val preview = TextSecurity.preview(action.payload, 180)
        return ActionOutcome(
            statusSummary = "Internal answer draft chunk captured.",
            assistantOutput = null,
            plannerSignal = "answer_draft chunk captured: $preview",
            observedEvidence = false
        )
    }
}

class AnswerDraftActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        AnswerDraftActionPlugin()
}
