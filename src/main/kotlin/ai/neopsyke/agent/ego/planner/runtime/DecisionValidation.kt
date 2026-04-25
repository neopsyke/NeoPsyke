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
            IntentionKind.PREPARE -> commitModePreference != CommitMode.NOT_APPLICABLE
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
        // If the model picked a commit mode that is not valid for the chosen intention
        // (e.g. intention=COMMIT with commit_mode=APPROVAL_BACKED), do not pass it through —
        // that would force a noop downstream and burn assignment retry budget. Auto-correct
        // to a valid preferred mode for the intention so a single bad combination does not
        // wedge the assignment loop.
        if (explicit != null && intentionKind != null && isCommitModeValidForIntention(intentionKind, explicit)) {
            return explicit
        }
        return preferredCommitMode(allowedCommitModes, intentionKind)
    }

    fun preferredCommitMode(
        allowedCommitModes: Set<CommitMode>,
        intentionKind: IntentionKind?,
    ): CommitMode {
        if (intentionKind == null || intentionKind == IntentionKind.OBSERVE) {
            return CommitMode.NOT_APPLICABLE
        }
        // Walk the preferred ladder but skip entries that are not valid for this intention,
        // so callers always get a runnable combination (e.g. COMMIT must not get APPROVAL_BACKED).
        val ladder = listOf(CommitMode.APPROVAL_BACKED, CommitMode.POLICY_AUTONOMOUS, CommitMode.ADMIN_OVERRIDE)
        return ladder.firstOrNull { it in allowedCommitModes && isCommitModeValidForIntention(intentionKind, it) }
            ?: CommitMode.NOT_APPLICABLE
    }
}
