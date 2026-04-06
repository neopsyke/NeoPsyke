package ai.neopsyke.agent.ego.planner.runtime

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.IntentionKind

/**
 * Shared validation logic for planner decision outputs.
 * Extracted from LlmEgoPlanner to be reused across lanes.
 */
object DecisionValidation {

    fun intentionKindFromRaw(raw: String?): IntentionKind? =
        when (raw?.trim()?.uppercase()) {
            IntentionKind.OBSERVE.name -> IntentionKind.OBSERVE
            IntentionKind.PREPARE.name -> IntentionKind.PREPARE
            IntentionKind.STAGE.name -> IntentionKind.STAGE
            IntentionKind.REQUEST_AUTHORIZATION.name -> IntentionKind.REQUEST_AUTHORIZATION
            IntentionKind.COMMIT.name -> IntentionKind.COMMIT
            else -> null
        }

    fun isCommitModeValidForIntention(
        intentionKind: IntentionKind,
        commitModePreference: CommitMode,
    ): Boolean =
        when (intentionKind) {
            IntentionKind.OBSERVE -> commitModePreference == CommitMode.NOT_APPLICABLE
            IntentionKind.REQUEST_AUTHORIZATION -> commitModePreference == CommitMode.APPROVAL_BACKED
            IntentionKind.STAGE ->
                commitModePreference in setOf(
                    CommitMode.APPROVAL_BACKED,
                    CommitMode.POLICY_AUTONOMOUS,
                    CommitMode.ADMIN_OVERRIDE,
                )
            IntentionKind.COMMIT ->
                commitModePreference in setOf(
                    CommitMode.POLICY_AUTONOMOUS,
                    CommitMode.ADMIN_OVERRIDE,
                )
            IntentionKind.PREPARE, IntentionKind.DEFER -> commitModePreference != CommitMode.NOT_APPLICABLE
        }

    fun resolveCommitModePreference(
        rawCommitMode: String?,
        allowedCommitModes: Set<CommitMode>,
        intentionKind: IntentionKind?,
    ): CommitMode {
        if (intentionKind == IntentionKind.OBSERVE) {
            return CommitMode.NOT_APPLICABLE
        }
        val explicit = CommitMode.entries.firstOrNull { it.name.equals(rawCommitMode?.trim(), ignoreCase = true) }
        return explicit ?: preferredCommitMode(allowedCommitModes, intentionKind)
    }

    fun preferredCommitMode(
        allowedCommitModes: Set<CommitMode>,
        intentionKind: IntentionKind?,
    ): CommitMode {
        if (intentionKind == null || intentionKind == IntentionKind.OBSERVE) {
            return CommitMode.NOT_APPLICABLE
        }
        return when {
            CommitMode.APPROVAL_BACKED in allowedCommitModes -> CommitMode.APPROVAL_BACKED
            CommitMode.POLICY_AUTONOMOUS in allowedCommitModes -> CommitMode.POLICY_AUTONOMOUS
            CommitMode.ADMIN_OVERRIDE in allowedCommitModes -> CommitMode.ADMIN_OVERRIDE
            else -> CommitMode.NOT_APPLICABLE
        }
    }
}
