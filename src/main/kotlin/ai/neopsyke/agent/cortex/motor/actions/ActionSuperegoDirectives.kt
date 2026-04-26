package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.agent.model.ActionType

/**
 * Action-specific semantic governance instructions for Superego review.
 *
 * These directives intentionally live in Kotlin, not PromptCatalog assets: changing
 * them changes authorization behavior and should go through normal code review.
 */
object ActionSuperegoDirectives {
    fun forAction(actionType: ActionType): List<String> =
        when (actionType.id) {
            ActionType.CONTACT_USER.id -> CONTACT_USER
            EMAIL_SEND_ACTION_ID -> EMAIL_SEND
            ActionType.REFLECT_EVIDENCE.id -> REFLECT_EVIDENCE
            ActionType.REFLECT_INTERNAL.id -> REFLECT_INTERNAL
            ActionType.RESOLUTION_DRAFT.id -> RESOLUTION_DRAFT
            ActionType.WEB_SEARCH.id -> WEB_SEARCH
            ActionType.WEBSITE_FETCH.id -> WEBSITE_FETCH
            else -> emptyList()
        }

    private val CONTACT_USER: List<String> = listOf(
        "Allow CONTACT_USER by default when it does not violate the general directives.",
    )

    private val EMAIL_SEND: List<String> = listOf(
        "Deny EMAIL_SEND when recipients are missing or ambiguous.",
        "Deny EMAIL_SEND when payload includes inline secrets, credentials, or key material.",
        "Deny EMAIL_SEND to out-of-policy recipient domains when domain restrictions are configured.",
        "Allow EMAIL_SEND only when sender identity is explicit or a configured default sender exists.",
    )

    private val REFLECT_EVIDENCE: List<String> = listOf(
        "Allow REFLECT_EVIDENCE only when same-request evidence artifacts exist in scope.",
        "Deny REFLECT_EVIDENCE when no evidence artifacts are available for the current request.",
    )

    private val REFLECT_INTERNAL: List<String> = listOf(
        "Allow REFLECT_INTERNAL only for trusted self-observation and internally generated lessons.",
        "Deny REFLECT_INTERNAL when the current thread trust includes tainted external data.",
    )

    private val RESOLUTION_DRAFT: List<String> = listOf(
        "Allow RESOLUTION_DRAFT always.",
        "Do not treat RESOLUTION_DRAFT as a user-visible final response.",
    )

    private val WEB_SEARCH: List<String> = listOf(
        "Allow WEB_SEARCH for general-information or public data queries by default.",
        "Deny WEB_SEARCH when payload includes unencrypted credentials, API keys, tokens, cookies, private keys, or other software secrets.",
        "Deny WEB_SEARCH when payload seeks credentials, API keys, tokens, cookies, private keys, or other software secrets.",
        "Deny WEB_SEARCH when the request includes private sensitive data unless the user explicitly provided it for this task.",
    )

    private val WEBSITE_FETCH: List<String> = listOf(
        "Allow WEBSITE_FETCH for public websites by default.",
        "Deny WEBSITE_FETCH when payload includes unencrypted API keys, tokens, cookies, private keys, or other secrets.",
        "Deny WEBSITE_FETCH when payload includes personal/sensitive data unless the user explicitly provided it for this task.",
        "For WEBSITE_FETCH, allow only public HTTPS pages; deny auth/account/payment/admin endpoints and URLs with obvious secret query params.",
    )

    private const val EMAIL_SEND_ACTION_ID: String = "email_send"
}
