package psyke.agent.ego

import psyke.agent.model.ActionOutcome
import psyke.agent.model.PendingAction

interface ActionLifecycleObserver {
    fun onActionExecuted(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {}
    fun onActionBlocked(action: PendingAction, reason: String, reasonCode: String?, source: String) {}
    fun allowFollowUp(action: PendingAction): Boolean = true
}

object NoopActionLifecycleObserver : ActionLifecycleObserver
