package psyke.agent.core

data class TaskWorkspaceConfig(
    val enabled: Boolean = false,
    val activationMinPlanSteps: Int = 3,
    val maxPromptTokens: Int = 220,
    val maxSections: Int = 10,
    val maxSectionChars: Int = 1_200,
    val maxSectionSummaryChars: Int = 180,
    val maxEvidenceItems: Int = 8,
    val maxEvidenceChars: Int = 220,
    val finalCompilationMaxChars: Int = 2_800,
    val finalPassRewriteEnabled: Boolean = true,
    val finalPassMaxTokens: Int = 260,
    val finalPassMinWorkspaceConfidence: Double = 0.35,
    val finalPassMinModelConfidence: Double = 0.55,
    val debugCaptureEnabled: Boolean = true,
    val maxActiveTasks: Int = 32,
)
