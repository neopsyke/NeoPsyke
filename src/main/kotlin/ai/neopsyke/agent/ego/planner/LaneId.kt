package ai.neopsyke.agent.ego.planner

/**
 * Identifies each planner lane for configuration lookup and telemetry.
 * The [configKey] matches the YAML key under `planner.lanes.<key>`.
 */
enum class LaneId(val configKey: String) {
    INPUT_INTENT_ROUTER("input_intent_router"),
    DIRECT_RESPONSE("direct_response"),
    GENERAL_ACTION("general_action"),
    TASK_DECOMPOSITION("task_decomposition"),
    GOAL_CREATION("goal_creation"),
    GOAL_MANAGEMENT("goal_management"),
    DEFERRED_STEP("deferred_step"),
    FEEDBACK("feedback"),
    GOAL_WORK("goal_work"),
    IMPULSE("impulse"),
}
