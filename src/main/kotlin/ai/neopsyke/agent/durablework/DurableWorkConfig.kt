package ai.neopsyke.agent.durablework

import java.nio.file.Path
import java.nio.file.Paths

data class MonitoringConfig(
    val dedupeHorizonMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val quietAfterNoChangeMs: Long = 12L * 60L * 60L * 1000L,
    val reportWindowSize: Int = 20,
    val maxRetainedSeenItems: Int = 200,
    val maxRetainedChangeRecords: Int = 200,
    val staleMonitorReviewIntervalMs: Long = 24L * 60L * 60L * 1000L,
    val overdueResponsibilityReviewIntervalMs: Long = 24L * 60L * 60L * 1000L,
    val maxRetainedReviewHistory: Int = 25,
    val maxEventLogSegmentBytes: Long = 1_000_000,
    val maxArchivedEventSegments: Int = 8,
    val reviewableResponsibilityLimit: Int = 8,
)

data class DurableWorkConfig(
    val enabled: Boolean = false,
    val workspaceRoot: Path = Paths.get(
        System.getProperty("user.home"), ".neopsyke", "work-items"
    ),
    val maxActiveWorkItems: Int = 10,
    val maxStepsPerPlan: Int = 20,
    val actionsPerCycle: Int = 5,
    val snapshotEveryNEvents: Int = 50,
    val timerResolutionMs: Long = 5_000,
    val conditionCheckIntervalMs: Long = 30_000,
    val completedWorkItemRetentionDays: Int = 30,
    val maxWorkspaceBytes: Long = 10_485_760,
    // ── Lease and backpressure limits ────────────────────────────────
    val leaseTimeoutMs: Long = 300_000,
    val maxReadyItems: Int = 20,
    val maxRegisteredWaits: Int = 100,
    val maxPendingWakeReasonsPerItem: Int = 10,
    val maxPendingDigestEntries: Int = 50,
    // Explicit migration/recovery switch. Default is fail-closed for missing plans.
    val allowRuntimePlanFallback: Boolean = false,
    val monitoring: MonitoringConfig = MonitoringConfig(),
)
