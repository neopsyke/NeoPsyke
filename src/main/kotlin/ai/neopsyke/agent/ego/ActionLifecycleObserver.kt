package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction

interface ActionLifecycleObserver {
    fun onActionExecuted(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {}
    fun onActionBlocked(action: PendingAction, reason: String, reasonCode: String?, source: String) {}
    fun allowFollowUp(action: PendingAction): Boolean = true
}

object NoopActionLifecycleObserver : ActionLifecycleObserver
