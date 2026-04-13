package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction

data class ActionExecutionGateDecision(
    val allow: Boolean,
    val reason: String = "",
    val reasonCode: String? = null,
    val source: String = "action_lifecycle_observer",
) {
    companion object {
        fun allow(): ActionExecutionGateDecision = ActionExecutionGateDecision(allow = true)

        fun deny(
            reason: String,
            reasonCode: String? = null,
            source: String = "action_lifecycle_observer",
        ): ActionExecutionGateDecision =
            ActionExecutionGateDecision(
                allow = false,
                reason = reason,
                reasonCode = reasonCode,
                source = source,
            )
    }
}

interface ActionLifecycleObserver {
    fun beforeActionExecution(action: PendingAction): ActionExecutionGateDecision =
        ActionExecutionGateDecision.allow()

    fun onActionExecuted(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {}
    fun onActionBlocked(action: PendingAction, reason: String, reasonCode: String?, source: String) {}
    fun allowFollowUp(action: PendingAction): Boolean = true
}

object NoopActionLifecycleObserver : ActionLifecycleObserver
