package ai.neopsyke.agent.goal

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectPersistenceTest {

    private val now = Instant.parse("2026-03-17T12:00:00Z")

    // ── EventLog ─────────────────────────────────────────────────────────

    @Test
    fun `event log append and read round-trip`() {
        val dir = Files.createTempDirectory("psyke-eventlog-test")
        try {
            val log = GoalEventLog(dir.resolve("events.jsonl"))

            val created = GoalEvent.Created(
                goalId = "proj-1",
                title = "Test",
                instruction = "Do stuff",
                priority = GoalPriority.HIGH,
                completionCriteria = "All done",
                timestamp = now,
            )
            val stepStarted = GoalEvent.StepStarted("proj-1", "s1", now)

            log.append(created)
            log.append(stepStarted)

            val events = log.readAll()
            assertEquals(2, events.size)
            val e1 = events[0] as GoalEvent.Created
            assertEquals("proj-1", e1.goalId)
            assertEquals("Test", e1.title)
            assertEquals(GoalPriority.HIGH, e1.priority)

            val e2 = events[1] as GoalEvent.StepStarted
            assertEquals("s1", e2.stepId)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `event log readFrom skips earlier events`() {
        val dir = Files.createTempDirectory("psyke-eventlog-skip")
        try {
            val log = GoalEventLog(dir.resolve("events.jsonl"))
            repeat(5) { i ->
                log.append(GoalEvent.StepStarted("proj-1", "s$i", now))
            }

            val from3 = log.readFrom(3)
            assertEquals(2, from3.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `event log handles empty file`() {
        val dir = Files.createTempDirectory("psyke-eventlog-empty")
        try {
            val log = GoalEventLog(dir.resolve("events.jsonl"))
            assertTrue(log.readAll().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── GoalStore ─────────────────────────────────────────────────────

    @Test
    fun `store creates workspace directories`() {
        val root = Files.createTempDirectory("psyke-store-test")
        try {
            val store = GoalStore(root)
            val workspace = store.createWorkspace("proj-1")

            assertTrue(Files.isDirectory(workspace))
            assertTrue(Files.isDirectory(workspace.resolve("artifacts")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store scans goals with event files`() {
        val root = Files.createTempDirectory("psyke-store-scan")
        try {
            val store = GoalStore(root)

            // Create two goal dirs, one with events, one without
            val eventLog1 = store.goalEventLog("proj-1")
            eventLog1.append(GoalEvent.Created("proj-1", "P1", "do", GoalPriority.LOW, "done", now))
            Files.createDirectories(root.resolve("proj-empty"))

            val goals = store.scanGoals()
            assertEquals(listOf("proj-1"), goals)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store snapshot round-trip preserves state`() {
        val root = Files.createTempDirectory("psyke-store-snapshot")
        try {
            val store = GoalStore(root)
            store.createWorkspace("proj-1")

            val plan = GoalPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.DONE, "verify s1",
                        produces = setOf("key-a"), completedAt = now),
                    PlanStep("s2", "Step 2", StepStatus.READY, "verify s2",
                        requires = setOf("key-a")),
                ),
                generatedAt = now,
            )
            val state = GoalState(
                goal = Goal(
                    id = "proj-1",
                    title = "Snapshot Test",
                    instruction = "test snapshotting",
                    status = GoalStatus.ACTIVE,
                    priority = GoalPriority.MEDIUM,
                    plan = plan,
                    completionCriteria = "all steps done",
                    createdAt = now,
                    lastWorkedAt = now,
                    workspacePath = root.resolve("proj-1/workspace"),
                ),
                producedKeys = setOf("key-a"),
                eventCount = 5,
            )

            store.saveSnapshot("proj-1", state)
            val loaded = store.loadGoal("proj-1")

            // loadGoal needs events to bootstrap; add a Created event first
            val goalEventLog = store.goalEventLog("proj-1")
            goalEventLog.append(GoalEvent.Created("proj-1", "Snapshot Test", "test snapshotting",
                GoalPriority.MEDIUM, "all steps done", now))
            // Now it should load from snapshot + 0 extra events (snapshot has eventCount=5,
            // but events file only has 1 event which is < 5, so it reads from seq 5 = 0 events)

            val loadedFromSnapshot = store.loadGoal("proj-1")
            assertNotNull(loadedFromSnapshot)
            assertEquals("proj-1", loadedFromSnapshot.id)
            assertEquals(GoalStatus.ACTIVE, loadedFromSnapshot.goal.status)
            assertEquals(setOf("key-a"), loadedFromSnapshot.producedKeys)
            assertEquals(5, loadedFromSnapshot.eventCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store full replay reconstructs state from events alone`() {
        val root = Files.createTempDirectory("psyke-store-replay")
        try {
            val store = GoalStore(root)
            store.createWorkspace("proj-1")
            val goalEventLog = store.goalEventLog("proj-1")

            goalEventLog.append(GoalEvent.Created("proj-1", "Replay Test", "test replay",
                GoalPriority.HIGH, "done", now))
            goalEventLog.append(GoalEvent.PlanGenerated("proj-1", GoalPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.PENDING, "verify s1"),
                ),
                generatedAt = now,
            ), now))
            goalEventLog.append(GoalEvent.StepStarted("proj-1", "s1", now))

            val loaded = store.loadGoal("proj-1")
            assertNotNull(loaded)
            assertEquals(GoalStatus.ACTIVE, loaded.goal.status)
            assertEquals(StepStatus.IN_PROGRESS, loaded.goal.plan.steps[0].status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store falls back to snapshot when goal json is corrupt`() {
        val root = Files.createTempDirectory("psyke-store-goal-fallback")
        try {
            val store = GoalStore(root)
            val workspace = store.createWorkspace("proj-1")
            val state = GoalState(
                goal = Goal(
                    id = "proj-1",
                    title = "Fallback Test",
                    instruction = "recover from corrupt goal json",
                    status = GoalStatus.ACTIVE,
                    priority = GoalPriority.MEDIUM,
                    plan = GoalPlan(
                        steps = listOf(
                            PlanStep("s1", "Step 1", StepStatus.READY, "verify s1"),
                        ),
                        generatedAt = now,
                    ),
                    completionCriteria = "done",
                    createdAt = now,
                    lastWorkedAt = now,
                    workspacePath = workspace,
                ),
                eventCount = 1,
            )
            store.saveSnapshot("proj-1", state)
            Files.writeString(root.resolve("proj-1").resolve(GoalStore.GOAL_FILE), "{not valid json")
            store.goalEventLog("proj-1").append(
                GoalEvent.Created("proj-1", "Fallback Test", "recover from corrupt goal json",
                    GoalPriority.MEDIUM, "done", now)
            )

            val loaded = store.loadGoal("proj-1")
            assertNotNull(loaded)
            assertEquals(GoalStatus.ACTIVE, loaded.goal.status)
            assertEquals(StepStatus.READY, loaded.goal.plan.steps.single().status)
            assertEquals(1, loaded.eventCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store falls back to full replay when snapshot is corrupt`() {
        val root = Files.createTempDirectory("psyke-store-snapshot-fallback")
        try {
            val store = GoalStore(root)
            store.createWorkspace("proj-1")
            Files.writeString(root.resolve("proj-1").resolve(GoalStore.SNAPSHOT_FILE), "{broken snapshot")
            val goalEventLog = store.goalEventLog("proj-1")
            goalEventLog.append(GoalEvent.Created("proj-1", "Replay Fallback", "recover from corrupt snapshot",
                GoalPriority.HIGH, "done", now))
            goalEventLog.append(GoalEvent.PlanGenerated("proj-1", GoalPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.PENDING, "verify s1"),
                ),
                generatedAt = now,
            ), now))
            goalEventLog.append(GoalEvent.StepStarted("proj-1", "s1", now))

            val loaded = store.loadGoal("proj-1")
            assertNotNull(loaded)
            assertEquals(GoalStatus.ACTIVE, loaded.goal.status)
            assertEquals(StepStatus.IN_PROGRESS, loaded.goal.plan.steps.single().status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store returns null for nonexistent goal`() {
        val root = Files.createTempDirectory("psyke-store-missing")
        try {
            val store = GoalStore(root)
            assertNull(store.loadGoal("nonexistent"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── ContextLoader ────────────────────────────────────────────────────

    @Test
    fun `tier1 summary captures current step and blockers`() {
        val plan = GoalPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.DONE, "verify s1"),
                PlanStep("s2", "Step 2", StepStatus.IN_PROGRESS, "verify s2"),
                PlanStep("s3", "Step 3", StepStatus.BLOCKED, "verify s3",
                    waitCondition = WaitCondition(WaitConditionType.TIMER, emptyMap(), now)),
            ),
            generatedAt = now,
        )
        val state = GoalState(
            goal = Goal(
                id = "proj-1", title = "T", instruction = "I",
                status = GoalStatus.ACTIVE, priority = GoalPriority.HIGH,
                plan = plan, completionCriteria = "done",
                createdAt = now, lastWorkedAt = now,
                workspacePath = Path.of("/tmp/test"),
            ),
        )

        val summary = GoalContextLoader.tier1Summary(state)
        assertEquals("proj-1", summary.goalId)
        assertEquals("Step 2", summary.currentStepDescription)
        assertEquals(1, summary.blockers.size)
        assertTrue(summary.blockers[0].contains("timer"))
    }

    @Test
    fun `tier2 context reads from workspace context file`() {
        val dir = Files.createTempDirectory("psyke-ctx-test")
        try {
            val workspace = dir.resolve("workspace")
            Files.createDirectories(workspace)
            Files.writeString(workspace.resolve("context.md"), "# Goal Context\nSome state here.")

            val ctx = GoalContextLoader.tier2Context(workspace)
            assertTrue(ctx.contains("Goal Context"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tier2 context returns empty for missing file`() {
        val dir = Files.createTempDirectory("psyke-ctx-missing")
        try {
            assertEquals("", GoalContextLoader.tier2Context(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
