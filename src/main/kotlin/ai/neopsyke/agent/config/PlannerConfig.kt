package ai.neopsyke.agent.config

import ai.neopsyke.agent.ego.planner.LaneConfig

data class PlannerConfig(
    val maxLoopStepsPerInput: Int = 180,
    val maxThoughtPasses: Int = 5,
    val maxThoughtChars: Int = 600,
    val maxInputChars: Int = 2_000,
    val maxCompletionTokens: Int = 1200,
    val maxRunTotalTokens: Int = 0,
    val maxRunTokensPerProvider: Int = 0,
    val maxRunTokensPerRole: Int = 0,
    val maxPlanSteps: Int = 6,
    val maxPlanStepDescriptionChars: Int = 120,
    val maxPlansPerInput: Int = 2,
    val actionRetryBudgetNonRetryableFailures: Int = 3,
    val actionRetryCooldownSteps: Int = 10,
    val laneDefaults: LaneConfig = LaneConfig(),
    val lanes: Map<String, LaneConfig> = emptyMap(),
)
