package psyke.agent.superego

import psyke.agent.actions.ActionRegistry
import psyke.agent.core.ActionOrigin
import psyke.agent.core.ActionType
import psyke.agent.core.OriginSource

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

    /**
     * Extra directives applied when the action originates from an internal drive (Id),
     * not from a direct user request.
     */
    val ID_ORIGIN_DIRECTIVES: List<String> = listOf(
        "This action was triggered by an internal drive, not a direct user request.",
        "Apply stricter scrutiny for externally visible actions (sending messages, modifying data).",
        "Approve internal-only actions (thinking, planning, searching, learning) freely.",
        "Deny external actions unless aligned with an active user-sanctioned project.",
        "When in doubt about whether the user would welcome this proactive action, deny.",
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
