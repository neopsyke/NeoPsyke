package ai.neopsyke.agent.ego.planner.model

/**
 * Typed routing result from InputIntentRouter.
 * Each variant selects the L2 sub-planner that handles the input.
 */
sealed interface InputRoute {

    data class DirectResponse(val reasoning: String) : InputRoute

    data class GeneralAction(val reasoning: String) : InputRoute

    data class MultiStepTask(val reasoning: String) : InputRoute

    data class Goal(val reasoning: String) : InputRoute

    data class ClarificationNeeded(val question: String) : InputRoute

    data class Noop(val reason: String) : InputRoute
}
