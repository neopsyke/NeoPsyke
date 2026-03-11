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
        "Deny actions that materially facilitate harm, fraud, unauthorized access, or disclosure of sensitive data.",
        "Deny actions aimed at harassment, stalking, doxxing, exploitation, malware, phishing, social engineering, or unauthorized surveillance of third parties.",
        "If safety, privacy, or cost impact is unclear, deny the action and require a narrower, explicit user instruction.",
    )

    fun forAction(actionType: ActionType, actionRegistry: ActionRegistry = ActionRegistry.empty()): SuperegoPolicyDirectives =
        SuperegoPolicyDirectives(
            general = GENERAL_DIRECTIVES,
            actionSpecific = actionSpecificDirectives(actionType, actionRegistry)
        )

    private fun actionSpecificDirectives(actionType: ActionType, actionRegistry: ActionRegistry): List<String> =
        actionRegistry.superegoDirectives(actionType).ifEmpty { builtinDirectives(actionType) }

    private fun builtinDirectives(actionType: ActionType): List<String> =
        when (actionType) {
            ActionType.ANSWER -> listOf(
                "Allow ANSWER by default when it does not violate the general directives.",
            )
            ActionType.ANSWER_DRAFT -> listOf(
                "Allow ANSWER_DRAFT for internal, non-terminal synthesis steps.",
                "Deny using ANSWER_DRAFT as a final user-visible response.",
            )
            ActionType.WEB_SEARCH -> listOf(
                "Allow WEB_SEARCH for general-information queries by default.",
                "Deny WEB_SEARCH when payload includes or seeks credentials, API keys, tokens, cookies, private keys, or other secrets.",
                "Deny WEB_SEARCH when payload includes or seeks personal/sensitive data unless the user explicitly provided it for this task.",
            )
            ActionType.WEBSITE_FETCH -> listOf(
                "Deny WEBSITE_FETCH when payload includes or seeks credentials, API keys, tokens, cookies, private keys, or other secrets.",
                "Deny WEBSITE_FETCH when payload includes or seeks personal/sensitive data unless the user explicitly provided it for this task.",
                "For WEBSITE_FETCH, allow only public informational HTTPS pages; deny auth/account/payment/admin/metadata endpoints and URLs with obvious secret query params.",
            )
            ActionType.MCP_TIME -> listOf(
                "Allow MCP_TIME for benign time/date lookup payloads.",
            )
            ActionType.MEMORY -> listOf(
                "Allow MEMORY operations by default; memory is an internal subsystem capability.",
            )
            else -> emptyList()
        }

    fun allDirectives(actionRegistry: ActionRegistry = ActionRegistry.empty()): List<String> =
        (actionRegistry.actionTypes() + ActionType.entries)
            .flatMap { forAction(it, actionRegistry).all }
            .distinct()
}
