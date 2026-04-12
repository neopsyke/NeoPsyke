package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Urgency

/**
 * Typed single-action candidate. Standardizes how lanes produce
 * FormIntention decisions.
 */
data class ExecutionCandidate(
    val urgency: Urgency = Urgency.MEDIUM,
    val intentionKind: IntentionKind,
    val commitModePreference: CommitMode = CommitMode.NOT_APPLICABLE,
    val actionType: ActionType,
    val payload: String,
    val summary: String,
)
