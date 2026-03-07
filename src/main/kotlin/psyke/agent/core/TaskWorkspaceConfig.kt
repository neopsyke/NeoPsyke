package psyke.agent.core

data class TaskWorkspaceConfig(
    val enabled: Boolean = false,
    val maxPromptTokens: Int = 220,
    val maxSections: Int = 10,
    val maxSectionChars: Int = 1_200,
    val maxSectionSummaryChars: Int = 180,
    val maxEvidenceItems: Int = 8,
    val maxEvidenceChars: Int = 220,
    val finalCompilationMaxChars: Int = 2_800,
    val maxActiveTasks: Int = 32,
)
