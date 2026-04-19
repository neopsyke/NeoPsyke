package ai.neopsyke.support

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.input.DirectResponder
import ai.neopsyke.agent.ego.planner.input.GeneralActionPlanner
import ai.neopsyke.agent.ego.planner.input.AssignmentCommandBuilder
import ai.neopsyke.agent.ego.planner.NoopPlanRefiner
import ai.neopsyke.agent.ego.planner.input.GroundingClassifier
import ai.neopsyke.agent.ego.planner.input.InputIntentRouter
import ai.neopsyke.agent.ego.planner.input.TaskDecompositionPlanner
import ai.neopsyke.agent.ego.planner.lane.AssignmentLanePlanner
import ai.neopsyke.agent.ego.planner.lane.ImpulsePlanner
import ai.neopsyke.agent.ego.planner.lane.InputPlanner
import ai.neopsyke.agent.ego.planner.lane.ProgressionPlanner
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatModelClient

/**
 * Builds a fully wired HierarchicalEgoPlanner for tests.
 * Mirrors the production buildHierarchicalPlanner() in AppModeRunners.
 */
fun buildTestHierarchicalPlanner(
    modelClient: ChatModelClient,
    config: AgentConfig = AgentConfig(),
    instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    onPlannerOutputRepaired: () -> Unit = {},
): Ego.Planner {
    val runtime = PlannerRuntime(
        defaultModelClient = modelClient,
        config = config,
        instrumentation = instrumentation,
        onPlannerOutputRepaired = onPlannerOutputRepaired,
        actionPayloadRepair = ::testActionPayloadRepair,
    )

    val router = InputIntentRouter(runtime, config, instrumentation)
    val groundingClassifier = GroundingClassifier(runtime, config, instrumentation)
    val directResponse = DirectResponder(runtime, config, instrumentation)
    val generalAction = GeneralActionPlanner(runtime, config, instrumentation)
    val taskDecomp = TaskDecompositionPlanner(runtime, config, instrumentation)
    val assignmentCommandBuilder = AssignmentCommandBuilder(runtime, config, instrumentation, NoopPlanRefiner())

    val inputPlanner = InputPlanner(
        runtime = runtime,
        config = config,
        instrumentation = instrumentation,
        router = router,
        groundingClassifier = groundingClassifier,
        directResponsePlanner = directResponse,
        generalActionPlanner = generalAction,
        taskDecompositionPlanner = taskDecomp,
        assignmentCommandBuilder = assignmentCommandBuilder,
    )

    val progressionPlanner = ProgressionPlanner(runtime, config, instrumentation)
    val assignmentLanePlannerLane = AssignmentLanePlanner(runtime, config, instrumentation)
    val impulsePlannerLane = ImpulsePlanner(runtime, config, instrumentation)

    return HierarchicalEgoPlanner(
        runtime = runtime,
        instrumentation = instrumentation,
        inputPlanner = inputPlanner,
        progressionPlanner = progressionPlanner,
        assignmentLanePlanner = assignmentLanePlannerLane,
        impulsePlanner = impulsePlannerLane,
    )
}

/**
 * Lightweight action-payload repair for tests. Mirrors the production
 * MotorCortex::repairPlannerPayload behaviour for WEBSITE_FETCH bare URLs.
 */
private fun testActionPayloadRepair(actionType: ActionType, raw: String): String {
    if (actionType != ActionType.WEBSITE_FETCH || raw.isBlank()) return raw
    try {
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(raw)
        return raw
    } catch (_: Exception) {
        // not valid JSON
    }
    val trimmed = raw.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .writeValueAsString(mapOf("url" to trimmed))
    }
    return raw
}
