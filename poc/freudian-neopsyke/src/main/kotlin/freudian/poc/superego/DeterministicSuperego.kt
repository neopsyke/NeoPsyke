package freudian.poc.superego

import freudian.poc.config.SuperegoConfig
import freudian.poc.model.ActionProposal
import freudian.poc.model.ActionType
import freudian.poc.model.OriginSource
import freudian.poc.model.SuperegoDecision

interface Superego {
    fun review(action: ActionProposal): SuperegoDecision
}

class DeterministicSuperego(
    config: SuperegoConfig,
) : Superego {
    private val allowIdUserMessages: Boolean = config.allowIdUserMessages
    private val allowedIdActionTypes: Set<ActionType> = config.allowedIdActionTypes.map { ActionType.fromRaw(it) }.toSet()

    override fun review(action: ActionProposal): SuperegoDecision {
        if (action.origin == OriginSource.ID) {
            if (action.type == ActionType.USER_MESSAGE && !allowIdUserMessages) {
                return SuperegoDecision(
                    allow = false,
                    reasonCode = "ID_POLICY_USER_MESSAGE_DENIED",
                    reason = "Id-origin user-facing messages are denied by deterministic superego policy."
                )
            }
            if (action.type !in allowedIdActionTypes) {
                return SuperegoDecision(
                    allow = false,
                    reasonCode = "ID_POLICY_ACTION_NOT_ALLOWLISTED",
                    reason = "Id-origin action type ${action.type} is not allowlisted."
                )
            }
        }

        return SuperegoDecision(
            allow = true,
            reasonCode = "ALLOW",
            reason = "Action allowed by deterministic superego policy.",
        )
    }
}
