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
    GOAL("goal"),
    DURABLE_WORK_GENERIC("durable_work_generic"),
    DURABLE_WORK_RECURRENT_TASK("durable_work_recurrent_task"),
    DURABLE_WORK_RESPONSIBILITY("durable_work_responsibility"),
    PROGRESSION("progression"),
    GOAL_WORK("durable_work"),
    IMPULSE("impulse"),
    GROUNDING_CLASSIFIER("grounding_classifier"),
    PLAN_REFINER("plan_refiner"),
}
