package ai.neopsyke.agent.superego

import ai.neopsyke.agent.cortex.motor.actions.control.ActionAuthorizationPolicy
import ai.neopsyke.agent.cortex.motor.actions.control.ConfiguredActionAuthorizationPolicy
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction

data class SuperegoPolicyDirectives(
    val general: List<String>,
    val actionSpecific: List<String>,
) {
    val all: List<String> = general + actionSpecific
}

object SuperegoPolicy {
    val GENERAL_DIRECTIVES: List<String> = listOf(
        "Deny actions that materially facilitate harm, fraud, unauthorized access, or disclosure of sensitive user/company data unless the user explicitly provided it for this task.",
        "Deny actions aimed at harassment, stalking, doxxing, exploitation, malware, phishing, social engineering, or unauthorized surveillance of third parties.",
        "If safety, privacy, or cost impact is unclear, deny the action and require a narrower, explicit user instruction.",
    )

    /**
     * Extra directives applied when the action originates from an internal drive (Id),
     * not from a direct user request.
     */
    val ID_ORIGIN_DIRECTIVES: List<String> = listOf(
        "This action was triggered by an internal drive, not a direct user request, " +
        "apply stricter scrutiny for externally visible actions (sending external messages, modifying data), " +
        "but always approve direct answers or contact to the user, even when proactive.",
        "Always approve internal-only actions (thinking, planning, searching, learning, self-reflection) freely.",
        "Deny external actions that would be harmful to the user's safety or privacy.",
        "Deny external actions that would modify the user's data, this system's configuration or state",
        "Deny any actions that would result in expense for the user without being part of a user-sanctioned assignment",
        //"Deny external actions unless aligned with an active user-sanctioned assignment.",
        "When in doubt about whether the user would welcome this proactive action, deny, except for proactive outreach.",
    )

    fun forAction(
        actionType: ActionType,
        actionRegistry: ActionRegistry = ActionRegistry.empty(),
        origin: ActionOrigin? = null,
    ): SuperegoPolicyDirectives {
        val actionDirectives = actionSpecificDirectives(actionType, actionRegistry)
        val idDirectives = if (origin?.source == OriginSource.ID) ID_ORIGIN_DIRECTIVES else emptyList()
        return SuperegoPolicyDirectives(
            general = GENERAL_DIRECTIVES + idDirectives,
            actionSpecific = actionDirectives,
        )
    }

    /**
     * Resolves action-specific superego directives.
     *
     * The action descriptor's Kotlin-owned [ActionDescriptor.superegoDirectives]
     * is the source of truth for action directives.
     */
    private fun actionSpecificDirectives(actionType: ActionType, actionRegistry: ActionRegistry): List<String> =
        actionRegistry.superegoDirectives(actionType)

    fun authorize(
        action: PendingAction,
        conversationContext: ConversationContext,
        actionRegistry: ActionRegistry = ActionRegistry.empty(),
        authorizationPolicy: ActionAuthorizationPolicy = ConfiguredActionAuthorizationPolicy(),
    ): AuthorizationDecision =
        authorizationPolicy.authorize(
            action = action,
            conversationContext = conversationContext,
            actionRegistry = actionRegistry,
        )

    fun allDirectives(actionRegistry: ActionRegistry = ActionRegistry.empty()): List<String> =
        (actionRegistry.actionTypes() + ActionType.entries)
            .flatMap { forAction(it, actionRegistry).all }
            .distinct()
}
