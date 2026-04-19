package ai.neopsyke.agent.assignments

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
            val log = WorkItemEventLog(dir.resolve("events.jsonl"))

            val created = WorkItemEvent.Created(
                workItemId = "proj-1",
                title = "Test",
                instruction = "Do stuff",
                priority = WorkItemPriority.HIGH,
                completionCriteria = "All done",
                timestamp = now,
            )
            val stepStarted = WorkItemEvent.StepStarted("proj-1", "s1", now)

            log.append(created)
            log.append(stepStarted)

            val events = log.readAll()
            assertEquals(2, events.size)
            val e1 = events[0] as WorkItemEvent.Created
            assertEquals("proj-1", e1.workItemId)
            assertEquals("Test", e1.title)
            assertEquals(WorkItemPriority.HIGH, e1.priority)

            val e2 = events[1] as WorkItemEvent.StepStarted
            assertEquals("s1", e2.stepId)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `event log readFrom skips earlier events`() {
        val dir = Files.createTempDirectory("psyke-eventlog-skip")
        try {
            val log = WorkItemEventLog(dir.resolve("events.jsonl"))
            repeat(5) { i ->
                log.append(WorkItemEvent.StepStarted("proj-1", "s$i", now))
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
            val log = WorkItemEventLog(dir.resolve("events.jsonl"))
            assertTrue(log.readAll().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── WorkItemStore ─────────────────────────────────────────────────────

    @Test
    fun `store creates workspace directories`() {
        val root = Files.createTempDirectory("psyke-store-test")
        try {
            val store = WorkItemStore(root)
            val workspace = store.createWorkspace("proj-1")

            assertTrue(Files.isDirectory(workspace))
            assertTrue(Files.isDirectory(workspace.resolve("artifacts")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store scans assignments with event files`() {
        val root = Files.createTempDirectory("psyke-store-scan")
        try {
            val store = WorkItemStore(root)

            // Create two assignment dirs, one with events, one without
            val eventLog1 = store.workItemEventLog("proj-1")
            eventLog1.append(WorkItemEvent.Created("proj-1", "P1", "do", WorkItemPriority.LOW, "done", timestamp = now))
            Files.createDirectories(root.resolve("proj-empty"))

            val assignments = store.scanWorkItems()
            assertEquals(listOf("proj-1"), assignments)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store snapshot round-trip preserves state`() {
        val root = Files.createTempDirectory("psyke-store-snapshot")
        try {
            val store = WorkItemStore(root)
            store.createWorkspace("proj-1")

            val plan = WorkItemPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.DONE, "verify s1",
                        produces = setOf("key-a"), completedAt = now),
                    PlanStep("s2", "Step 2", StepStatus.READY, "verify s2",
                        requires = setOf("key-a")),
                ),
                generatedAt = now,
            )
            val state = WorkItemState(
                workItem = WorkItem(
                    id = "proj-1",
                    title = "Snapshot Test",
                    instruction = "test snapshotting",
                    status = WorkItemStatus.ACTIVE,
                    priority = WorkItemPriority.MEDIUM,
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
            val loaded = store.loadWorkItem("proj-1")

            // loadWorkItem needs events to bootstrap; add a Created event first
            val workItemEventLog = store.workItemEventLog("proj-1")
            workItemEventLog.append(WorkItemEvent.Created("proj-1", "Snapshot Test", "test snapshotting",
                WorkItemPriority.MEDIUM, "all steps done", timestamp = now))
            // Now it should load from snapshot + 0 extra events (snapshot has eventCount=5,
            // but events file only has 1 event which is < 5, so it reads from seq 5 = 0 events)

            val loadedFromSnapshot = store.loadWorkItem("proj-1")
            assertNotNull(loadedFromSnapshot)
            assertEquals("proj-1", loadedFromSnapshot.id)
            assertEquals(WorkItemStatus.ACTIVE, loadedFromSnapshot.workItem.status)
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
            val store = WorkItemStore(root)
            store.createWorkspace("proj-1")
            val workItemEventLog = store.workItemEventLog("proj-1")

            workItemEventLog.append(WorkItemEvent.Created("proj-1", "Replay Test", "test replay",
                WorkItemPriority.HIGH, "done", timestamp = now))
            workItemEventLog.append(WorkItemEvent.PlanGenerated("proj-1", WorkItemPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.PENDING, "verify s1"),
                ),
                generatedAt = now,
            ), now))
            workItemEventLog.append(WorkItemEvent.StepStarted("proj-1", "s1", now))

            val loaded = store.loadWorkItem("proj-1")
            assertNotNull(loaded)
            assertEquals(WorkItemStatus.ACTIVE, loaded.workItem.status)
            assertEquals(StepStatus.IN_PROGRESS, loaded.workItem.plan.steps[0].status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store falls back to snapshot when assignment json is corrupt`() {
        val root = Files.createTempDirectory("psyke-store-assignment-fallback")
        try {
            val store = WorkItemStore(root)
            val workspace = store.createWorkspace("proj-1")
            val state = WorkItemState(
                workItem = WorkItem(
                    id = "proj-1",
                    title = "Fallback Test",
                    instruction = "recover from corrupt assignment json",
                    status = WorkItemStatus.ACTIVE,
                    priority = WorkItemPriority.MEDIUM,
                    plan = WorkItemPlan(
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
            Files.writeString(root.resolve("proj-1").resolve(WorkItemStore.ASSIGNMENT_FILE), "{not valid json")
            store.workItemEventLog("proj-1").append(
                WorkItemEvent.Created("proj-1", "Fallback Test", "recover from corrupt assignment json",
                    WorkItemPriority.MEDIUM, "done", timestamp = now)
            )

            val loaded = store.loadWorkItem("proj-1")
            assertNotNull(loaded)
            assertEquals(WorkItemStatus.ACTIVE, loaded.workItem.status)
            assertEquals(StepStatus.READY, loaded.workItem.plan.steps.single().status)
            assertEquals(1, loaded.eventCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store falls back to full replay when snapshot is corrupt`() {
        val root = Files.createTempDirectory("psyke-store-snapshot-fallback")
        try {
            val store = WorkItemStore(root)
            store.createWorkspace("proj-1")
            Files.writeString(root.resolve("proj-1").resolve(WorkItemStore.SNAPSHOT_FILE), "{broken snapshot")
            val workItemEventLog = store.workItemEventLog("proj-1")
            workItemEventLog.append(WorkItemEvent.Created("proj-1", "Replay Fallback", "recover from corrupt snapshot",
                WorkItemPriority.HIGH, "done", timestamp = now))
            workItemEventLog.append(WorkItemEvent.PlanGenerated("proj-1", WorkItemPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.PENDING, "verify s1"),
                ),
                generatedAt = now,
            ), now))
            workItemEventLog.append(WorkItemEvent.StepStarted("proj-1", "s1", now))

            val loaded = store.loadWorkItem("proj-1")
            assertNotNull(loaded)
            assertEquals(WorkItemStatus.ACTIVE, loaded.workItem.status)
            assertEquals(StepStatus.IN_PROGRESS, loaded.workItem.plan.steps.single().status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store returns null for nonexistent assignment`() {
        val root = Files.createTempDirectory("psyke-store-missing")
        try {
            val store = WorkItemStore(root)
            assertNull(store.loadWorkItem("nonexistent"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── ContextLoader ────────────────────────────────────────────────────

    @Test
    fun `tier1 summary captures current step and blockers`() {
        val plan = WorkItemPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.DONE, "verify s1"),
                PlanStep("s2", "Step 2", StepStatus.IN_PROGRESS, "verify s2"),
                PlanStep("s3", "Step 3", StepStatus.BLOCKED, "verify s3",
                    waitCondition = WaitCondition(WaitConditionType.TIMER, emptyMap(), now)),
            ),
            generatedAt = now,
        )
        val state = WorkItemState(
            workItem = WorkItem(
                id = "proj-1", title = "T", instruction = "I",
                status = WorkItemStatus.ACTIVE, priority = WorkItemPriority.HIGH,
                plan = plan, completionCriteria = "done",
                createdAt = now, lastWorkedAt = now,
                workspacePath = Path.of("/tmp/test"),
            ),
        )

        val summary = WorkContextLoader.tier1Summary(state)
        assertEquals("proj-1", summary.workItemId)
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
            Files.writeString(workspace.resolve("context.md"), "# Assignment Context\nSome state here.")

            val ctx = WorkContextLoader.tier2Context(workspace)
            assertTrue(ctx.contains("Assignment Context"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tier2 context returns empty for missing file`() {
        val dir = Files.createTempDirectory("psyke-ctx-missing")
        try {
            assertEquals("", WorkContextLoader.tier2Context(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
