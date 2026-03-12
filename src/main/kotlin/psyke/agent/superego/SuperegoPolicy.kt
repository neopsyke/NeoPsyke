package psyke.agent.superego

import psyke.agent.actions.ActionRegistry
import psyke.agent.core.ActionType

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

    fun forAction(actionType: ActionType, actionRegistry: ActionRegistry = ActionRegistry.empty()): SuperegoPolicyDirectives =
        SuperegoPolicyDirectives(
            general = GENERAL_DIRECTIVES,
            actionSpecific = actionSpecificDirectives(actionType, actionRegistry)
        )

    /**
     * Resolves action-specific superego directives.
     *
     * The plugin's [ActionDescriptor.superegoDirectives] is the single source
     * of truth for all action directives.
     */
    private fun actionSpecificDirectives(actionType: ActionType, actionRegistry: ActionRegistry): List<String> =
        actionRegistry.superegoDirectives(actionType)

    fun allDirectives(actionRegistry: ActionRegistry = ActionRegistry.empty()): List<String> =
        (actionRegistry.actionTypes() + ActionType.entries)
            .flatMap { forAction(it, actionRegistry).all }
            .distinct()
}
