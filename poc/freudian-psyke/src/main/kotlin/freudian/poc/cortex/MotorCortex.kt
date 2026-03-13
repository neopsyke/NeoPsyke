package freudian.poc.cortex

import freudian.poc.model.ActionProposal
import freudian.poc.model.ActionType
import freudian.poc.model.MotorOutcome

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
            ActionType.INTERNAL_REFLECTION -> MotorOutcome(success = true, status = "Internal reflection completed.")
            ActionType.WEB_LOOKUP -> MotorOutcome(success = true, status = "Web lookup completed.")
            ActionType.USER_MESSAGE -> MotorOutcome(success = true, status = "User message sent.")
        }
    }
}
