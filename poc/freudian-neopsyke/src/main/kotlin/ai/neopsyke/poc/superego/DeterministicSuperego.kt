package ai.neopsyke.poc.superego

import ai.neopsyke.poc.config.SuperegoConfig
import ai.neopsyke.poc.model.ActionProposal
import ai.neopsyke.poc.model.ActionType
import ai.neopsyke.poc.model.OriginSource
import ai.neopsyke.poc.model.SuperegoDecision

interface Superego {
    fun review(action: ActionProposal): SuperegoDecision
}

class DeterministicSuperego(
    config: SuperegoConfig,
) : Superego {
    private val allowIdContactUser: Boolean = config.allowIdContactUser
    private val allowedIdActionTypes: Set<ActionType> = config.allowedIdActionTypes.map { ActionType.fromRaw(it) }.toSet()

    override fun review(action: ActionProposal): SuperegoDecision {
        if (action.origin == OriginSource.ID) {
            if (action.type == ActionType.CONTACT_USER && !allowIdContactUser) {
                return SuperegoDecision(
                    allow = false,
                    reasonCode = "ID_POLICY_CONTACT_USER_DENIED",
                    reason = "Id-origin user-facing contact is denied by deterministic superego policy."
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
