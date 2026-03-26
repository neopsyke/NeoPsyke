package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.TextSecurity

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
            executionStatus = ActionExecutionStatus.SUCCESS,
            effects = setOf(ActionEffect.TASK_PROGRESS),
            observedEvidence = false
        )
    }
}

class ResolutionDraftActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ResolutionDraftActionPlugin()
}
