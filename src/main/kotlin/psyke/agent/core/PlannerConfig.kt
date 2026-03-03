package psyke.agent.core

data class PlannerConfig(
    val maxLoopStepsPerInput: Int = 180,
    val maxThoughtPasses: Int = 5,
    val maxThoughtChars: Int = 600,
    val maxInputChars: Int = 2_000,
    val maxActionPayloadChars: Int = 4_000,
    val maxActionSummaryChars: Int = 180,
    val maxPromptTokens: Int = 2_400,
    val maxCompletionTokens: Int = 900,
    val llmRetryAttempts: Int = 2,
    val maxPlanSteps: Int = 6,
    val maxPlanStepDescriptionChars: Int = 120,
    val maxPlansPerInput: Int = 2,
    val planEmissionPressureThreshold: Double = 0.55,
)
