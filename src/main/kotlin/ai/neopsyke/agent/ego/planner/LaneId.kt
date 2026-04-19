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
    ASSIGNMENT_GENERIC("assignment_generic"),
    ASSIGNMENT_RECURRENT_TASK("assignment_recurrent_task"),
    ASSIGNMENT_RESPONSIBILITY("assignment_responsibility"),
    PROGRESSION("progression"),
    ASSIGNMENT("assignment"),
    IMPULSE("impulse"),
    GROUNDING_CLASSIFIER("grounding_classifier"),
    PLAN_REFINER("plan_refiner"),
}
