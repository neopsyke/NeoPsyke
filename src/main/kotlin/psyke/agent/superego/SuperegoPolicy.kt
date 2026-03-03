package psyke.agent.superego

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
        "Deny redundant or low-value external calls when recent dialogue or memory already contains sufficient information and the user did not ask for refresh/retry.",
        "If safety, privacy, or cost impact is unclear, deny the action and require a narrower, explicit user instruction.",
    )

    fun forAction(actionType: ActionType): SuperegoPolicyDirectives =
        SuperegoPolicyDirectives(
            general = GENERAL_DIRECTIVES,
            actionSpecific = actionSpecificDirectives(actionType)
        )

    private fun actionSpecificDirectives(actionType: ActionType): List<String> =
        when (actionType) {
            ActionType.ANSWER -> listOf(
                "Allow ANSWER by default when it does not violate the general directives.",
            )

            ActionType.WEB_SEARCH -> listOf(
                "Deny WEB_SEARCH when payload includes or seeks credentials, API keys, tokens, cookies, private keys, or other secrets.",
                "Deny WEB_SEARCH when payload includes or seeks personal/sensitive data (PII, health, financial, legal, biometric, or location data) unless the user explicitly provided it for this task.",
            )

            ActionType.MCP_FETCH -> listOf(
                "Deny MCP_FETCH when payload includes or seeks credentials, API keys, tokens, cookies, private keys, or other secrets.",
                "Deny MCP_FETCH when payload includes or seeks personal/sensitive data (PII, health, financial, legal, biometric, or location data) unless the user explicitly provided it for this task.",
                "For MCP_FETCH, allow only public informational HTTPS pages; deny auth/account/payment/admin/metadata endpoints and URLs with obvious secret query params (token, key, password, auth, session).",
            )

            ActionType.MCP_TIME -> listOf(
                "Allow MCP_TIME for benign time/date lookup payloads.",
            )

            ActionType.MEMORY -> listOf(
                "Allow MEMORY operations by default; memory is an internal subsystem capability.",
            )
        }

    fun allDirectives(): List<String> =
        ActionType.entries.flatMap { forAction(it).all }.distinct()
}
