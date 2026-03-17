package psyke.agent.project

import java.nio.file.Path
import java.nio.file.Paths

data class ProjectConfig(
    val enabled: Boolean = false,
    val workspaceRoot: Path = Paths.get(
        System.getProperty("user.home"), ".psyke", "projects"
    ),
    val maxActiveProjects: Int = 10,
    val maxStepsPerPlan: Int = 20,
    val actionsPerCycle: Int = 5,
    val snapshotEveryNEvents: Int = 50,
    val timerResolutionMs: Long = 5_000,
    val conditionCheckIntervalMs: Long = 30_000,
    val completedProjectRetentionDays: Int = 30,
    val maxWorkspaceBytes: Long = 10_485_760,
)
