package ai.neopsyke.agent.ego.planner.lane

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.PlannerLane
import ai.neopsyke.agent.ego.planner.input.DirectResponder
import ai.neopsyke.agent.ego.planner.input.GeneralActionPlanner
import ai.neopsyke.agent.ego.planner.input.AssignmentCommandBuilder
import ai.neopsyke.agent.ego.planner.input.GroundingClassifier
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.ego.planner.input.InputIntentRouter
import ai.neopsyke.agent.ego.planner.input.TaskDecompositionPlanner
import ai.neopsyke.agent.ego.planner.model.InputRoute
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation

/**
 * L1 lane: orchestrates fresh user input through InputIntentRouter -> L2 sub-planner.
 *
 * The two-call pattern (classify then decide) is consistent with the spec's
 * principle "One semantic interpretation pass per domain boundary whenever possible"
 * because the router and the sub-planner operate at different domain boundaries.
 *
 * Dispatch from InputRoute to sub-planner is deterministic on a typed result
 * from an LLM call, which is allowed by the mandatory routing rule.
 */
class InputPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val router: InputIntentRouter,
    private val groundingClassifier: GroundingClassifier,
    private val directResponsePlanner: DirectResponder,
    private val generalActionPlanner: GeneralActionPlanner,
    private val taskDecompositionPlanner: TaskDecompositionPlanner,
    private val assignmentCommandBuilder: AssignmentCommandBuilder,
) : PlannerLane {

    override val laneId: LaneId = LaneId.INPUT_INTENT_ROUTER

    /** Grounded input envelope resolved by [GroundingClassifier]. Read after [plan] returns. */
    @Volatile var lastResolvedInput: PendingInput? = null
        private set

    /** Last resolved grounding metadata from [GroundingClassifier]. Read after [plan] returns. */
    @Volatile var lastResolvedGrounding: GroundingMetadata? = null
        private set

    override fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val inputTrigger = trigger as? EgoTrigger.IncomingInput
            ?: return EgoDecision.Noop("InputPlanner requires IncomingInput trigger.")

        val route = router.route(inputTrigger, context)

        instrumentation.emit(
            AgentEvent(
                type = "input_route_selected",
                data = mapOf(
                    "route" to route::class.simpleName?.lowercase(),
                    "root_input_id" to inputTrigger.input.rootInputId,
                )
            )
        )

        // Classify grounding after route, before L2 sub-planner dispatch.
        val groundingMetadata = groundingClassifier.classify(route, inputTrigger, context)
        val groundedInput = inputTrigger.input.copy(groundingMetadata = groundingMetadata)
        val groundedTrigger = EgoTrigger.IncomingInput(groundedInput)
        instrumentation.emit(
            AgentEvent(
                type = "grounding_metadata_propagated",
                data = mapOf(
                    "root_input_id" to groundedInput.rootInputId,
                    "from_envelope_type" to "grounding_classifier",
                    "to_envelope_type" to "pending_input",
                    "grounding_required" to (groundingMetadata.requirement == GroundingRequirement.REQUIRED),
                    "source" to groundingMetadata.source.name.lowercase(),
                )
            )
        )
        lastResolvedInput = groundedInput
        lastResolvedGrounding = groundingMetadata
        val groundedContext = context.copy(groundingMetadata = groundingMetadata)

        return when (route) {
            is InputRoute.DirectResponse -> directResponsePlanner.plan(groundedTrigger, groundedContext)
            is InputRoute.GeneralAction -> generalActionPlanner.plan(groundedTrigger, groundedContext)
            is InputRoute.MultiStepTask -> taskDecompositionPlanner.plan(groundedTrigger, groundedContext)
            is InputRoute.Assignment -> {
                val decision = assignmentCommandBuilder.plan(groundedTrigger, groundedContext, route.target)
                if (decision is EgoDecision.Noop) {
                    instrumentation.emit(
                        AgentEvent(
                            type = "assignment_command_builder_fallback",
                            data = mapOf(
                                "reason" to decision.reason,
                                "root_input_id" to groundedInput.rootInputId,
                            )
                        )
                    )
                    generalActionPlanner.plan(groundedTrigger, groundedContext)
                } else {
                    decision
                }
            }
            is InputRoute.ClarificationNeeded -> {
                EgoDecision.FormIntention(
                    urgency = Urgency.MEDIUM,
                    intentionKind = IntentionKind.OBSERVE,
                    commitModePreference = CommitMode.NOT_APPLICABLE,
                    actionType = ActionType.CONTACT_USER,
                    payload = TextSecurity.clamp(route.question, config.maxActionPayloadChars),
                    summary = TextSecurity.clamp("Ask for clarification", config.maxActionSummaryChars),
                )
            }
            is InputRoute.Noop -> EgoDecision.Noop(route.reason)
        }
    }
}
