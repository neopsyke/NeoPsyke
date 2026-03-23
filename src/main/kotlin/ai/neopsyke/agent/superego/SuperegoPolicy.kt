package ai.neopsyke.agent.superego

import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.model.AuthorizationDecision
import ai.neopsyke.agent.model.AuthorizationProgress
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.InstructionTrust
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
        "Deny any actions that would result in expense for the user without being part of a user-sanctioned goal",
        //"Deny external actions unless aligned with an active user-sanctioned goal.",
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
     * The plugin's [ActionDescriptor.superegoDirectives] is the single source
     * of truth for all action directives.
     */
    private fun actionSpecificDirectives(actionType: ActionType, actionRegistry: ActionRegistry): List<String> =
        actionRegistry.superegoDirectives(actionType)

    fun authorize(
        action: PendingAction,
        conversationContext: ConversationContext,
        actionRegistry: ActionRegistry = ActionRegistry.empty(),
    ): AuthorizationDecision {
        val descriptor = actionRegistry.descriptor(action.type)
            ?: return AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_STAGE,
                commitMode = CommitMode.APPROVAL_BACKED,
                reason = "No explicit action contract was found; legacy runtime path may proceed but staging is preferred.",
                reasonCode = "LEGACY_ACTION_CONTRACT_MISSING",
            )
        val contract = descriptor.contract
        if (!contract.allowedInstructionTrust.contains(conversationContext.security.instructionTrust)) {
            return AuthorizationDecision(
                progress = AuthorizationProgress.DENY,
                commitMode = CommitMode.NOT_APPLICABLE,
                reason = "Action '${action.type.id}' is not allowed for instruction trust ${conversationContext.security.instructionTrust.name.lowercase()}.",
                reasonCode = "POLICY_INSTRUCTION_TRUST_DENY",
            )
        }
        if (contract.effectClass == ai.neopsyke.agent.model.ActionEffectClass.CONTROL_PLANE &&
            conversationContext.security.instructionTrust != InstructionTrust.TRUSTED_INSTRUCTION
        ) {
            return AuthorizationDecision(
                progress = AuthorizationProgress.DENY,
                commitMode = CommitMode.NOT_APPLICABLE,
                reason = "Control-plane action '${action.type.id}' requires trusted instruction.",
                reasonCode = "POLICY_CONTROL_PLANE_TRUST_REQUIRED",
            )
        }
        if (contract.effectClass == ai.neopsyke.agent.model.ActionEffectClass.COMMIT_PUBLIC &&
            conversationContext.security.principal.role != ai.neopsyke.agent.model.PrincipalRole.OWNER
        ) {
            return AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_STAGE,
                commitMode = CommitMode.APPROVAL_BACKED,
                reason = "Public commit action '${action.type.id}' requires owner approval until explicitly enabled.",
                reasonCode = "POLICY_PUBLIC_COMMIT_OWNER_APPROVAL",
            )
        }
        return if (contract.directCommitAllowed) {
            AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_COMMIT,
                commitMode = CommitMode.POLICY_AUTONOMOUS,
                reason = "Action contract allows direct commit in the current legacy runtime path.",
                reasonCode = "POLICY_DIRECT_COMMIT_ALLOWED",
            )
        } else {
            AuthorizationDecision(
                progress = AuthorizationProgress.ALLOW_STAGE,
                commitMode = if (contract.supportsAutonomousCommit) {
                    CommitMode.POLICY_AUTONOMOUS
                } else {
                    CommitMode.APPROVAL_BACKED
                },
                reason = "Action contract requires staged authorization before commit.",
                reasonCode = "POLICY_STAGE_REQUIRED",
            )
        }
    }

    fun allDirectives(actionRegistry: ActionRegistry = ActionRegistry.empty()): List<String> =
        (actionRegistry.actionTypes() + ActionType.entries)
            .flatMap { forAction(it, actionRegistry).all }
            .distinct()
}
