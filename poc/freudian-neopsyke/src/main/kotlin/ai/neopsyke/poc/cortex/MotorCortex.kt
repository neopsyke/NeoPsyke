package ai.neopsyke.poc.cortex

import ai.neopsyke.poc.model.ActionProposal
import ai.neopsyke.poc.model.ActionType
import ai.neopsyke.poc.model.MotorOutcome

interface MotorCortex {
    fun execute(action: ActionProposal): MotorOutcome
}

class DeterministicMotorCortex(
    executableActionTypes: Set<ActionType>,
) : MotorCortex {
    private val executableActionTypes: Set<ActionType> = executableActionTypes.toSet()

    override fun execute(action: ActionProposal): MotorOutcome {
        if (action.type !in executableActionTypes) {
            return MotorOutcome(
                success = false,
                status = "Action type ${action.type} is disabled in deterministic motor cortex."
            )
        }
        return when (action.type) {
            ActionType.REFLECT_INTERNAL -> MotorOutcome(success = true, status = "Internal reflection completed.")
            ActionType.WEB_SEARCH -> MotorOutcome(success = true, status = "Web search completed.")
            ActionType.CONTACT_USER -> MotorOutcome(success = true, status = "User contact delivered.")
        }
    }
}
