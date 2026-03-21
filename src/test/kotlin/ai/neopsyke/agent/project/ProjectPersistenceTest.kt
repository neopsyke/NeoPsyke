package ai.neopsyke.agent.project

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
            val log = ProjectEventLog(dir.resolve("events.jsonl"))

            val created = ProjectEvent.Created(
                projectId = "proj-1",
                title = "Test",
                instruction = "Do stuff",
                priority = ProjectPriority.HIGH,
                completionCriteria = "All done",
                timestamp = now,
            )
            val stepStarted = ProjectEvent.StepStarted("proj-1", "s1", now)

            log.append(created)
            log.append(stepStarted)

            val events = log.readAll()
            assertEquals(2, events.size)
            val e1 = events[0] as ProjectEvent.Created
            assertEquals("proj-1", e1.projectId)
            assertEquals("Test", e1.title)
            assertEquals(ProjectPriority.HIGH, e1.priority)

            val e2 = events[1] as ProjectEvent.StepStarted
            assertEquals("s1", e2.stepId)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `event log readFrom skips earlier events`() {
        val dir = Files.createTempDirectory("psyke-eventlog-skip")
        try {
            val log = ProjectEventLog(dir.resolve("events.jsonl"))
            repeat(5) { i ->
                log.append(ProjectEvent.StepStarted("proj-1", "s$i", now))
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
            val log = ProjectEventLog(dir.resolve("events.jsonl"))
            assertTrue(log.readAll().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── ProjectStore ─────────────────────────────────────────────────────

    @Test
    fun `store creates workspace directories`() {
        val root = Files.createTempDirectory("psyke-store-test")
        try {
            val store = ProjectStore(root)
            val workspace = store.createWorkspace("proj-1")

            assertTrue(Files.isDirectory(workspace))
            assertTrue(Files.isDirectory(workspace.resolve("artifacts")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store scans projects with event files`() {
        val root = Files.createTempDirectory("psyke-store-scan")
        try {
            val store = ProjectStore(root)

            // Create two project dirs, one with events, one without
            val eventLog1 = store.eventLog("proj-1")
            eventLog1.append(ProjectEvent.Created("proj-1", "P1", "do", ProjectPriority.LOW, "done", now))
            Files.createDirectories(root.resolve("proj-empty"))

            val projects = store.scanProjects()
            assertEquals(listOf("proj-1"), projects)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store snapshot round-trip preserves state`() {
        val root = Files.createTempDirectory("psyke-store-snapshot")
        try {
            val store = ProjectStore(root)
            store.createWorkspace("proj-1")

            val plan = ProjectPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.DONE, "verify s1",
                        produces = setOf("key-a"), completedAt = now),
                    PlanStep("s2", "Step 2", StepStatus.READY, "verify s2",
                        requires = setOf("key-a")),
                ),
                generatedAt = now,
            )
            val state = ProjectState(
                project = Project(
                    id = "proj-1",
                    title = "Snapshot Test",
                    instruction = "test snapshotting",
                    status = ProjectStatus.ACTIVE,
                    priority = ProjectPriority.MEDIUM,
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
            val loaded = store.loadProject("proj-1")

            // loadProject needs events to bootstrap; add a Created event first
            val eventLog = store.eventLog("proj-1")
            eventLog.append(ProjectEvent.Created("proj-1", "Snapshot Test", "test snapshotting",
                ProjectPriority.MEDIUM, "all steps done", now))
            // Now it should load from snapshot + 0 extra events (snapshot has eventCount=5,
            // but events file only has 1 event which is < 5, so it reads from seq 5 = 0 events)

            val loadedFromSnapshot = store.loadProject("proj-1")
            assertNotNull(loadedFromSnapshot)
            assertEquals("proj-1", loadedFromSnapshot.id)
            assertEquals(ProjectStatus.ACTIVE, loadedFromSnapshot.project.status)
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
            val store = ProjectStore(root)
            store.createWorkspace("proj-1")
            val eventLog = store.eventLog("proj-1")

            eventLog.append(ProjectEvent.Created("proj-1", "Replay Test", "test replay",
                ProjectPriority.HIGH, "done", now))
            eventLog.append(ProjectEvent.PlanGenerated("proj-1", ProjectPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.PENDING, "verify s1"),
                ),
                generatedAt = now,
            ), now))
            eventLog.append(ProjectEvent.StepStarted("proj-1", "s1", now))

            val loaded = store.loadProject("proj-1")
            assertNotNull(loaded)
            assertEquals(ProjectStatus.ACTIVE, loaded.project.status)
            assertEquals(StepStatus.IN_PROGRESS, loaded.project.plan.steps[0].status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store falls back to snapshot when project json is corrupt`() {
        val root = Files.createTempDirectory("psyke-store-project-fallback")
        try {
            val store = ProjectStore(root)
            val workspace = store.createWorkspace("proj-1")
            val state = ProjectState(
                project = Project(
                    id = "proj-1",
                    title = "Fallback Test",
                    instruction = "recover from corrupt project json",
                    status = ProjectStatus.ACTIVE,
                    priority = ProjectPriority.MEDIUM,
                    plan = ProjectPlan(
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
            Files.writeString(root.resolve("proj-1").resolve(ProjectStore.PROJECT_FILE), "{not valid json")
            store.eventLog("proj-1").append(
                ProjectEvent.Created("proj-1", "Fallback Test", "recover from corrupt project json",
                    ProjectPriority.MEDIUM, "done", now)
            )

            val loaded = store.loadProject("proj-1")
            assertNotNull(loaded)
            assertEquals(ProjectStatus.ACTIVE, loaded.project.status)
            assertEquals(StepStatus.READY, loaded.project.plan.steps.single().status)
            assertEquals(1, loaded.eventCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store falls back to full replay when snapshot is corrupt`() {
        val root = Files.createTempDirectory("psyke-store-snapshot-fallback")
        try {
            val store = ProjectStore(root)
            store.createWorkspace("proj-1")
            Files.writeString(root.resolve("proj-1").resolve(ProjectStore.SNAPSHOT_FILE), "{broken snapshot")
            val eventLog = store.eventLog("proj-1")
            eventLog.append(ProjectEvent.Created("proj-1", "Replay Fallback", "recover from corrupt snapshot",
                ProjectPriority.HIGH, "done", now))
            eventLog.append(ProjectEvent.PlanGenerated("proj-1", ProjectPlan(
                steps = listOf(
                    PlanStep("s1", "Step 1", StepStatus.PENDING, "verify s1"),
                ),
                generatedAt = now,
            ), now))
            eventLog.append(ProjectEvent.StepStarted("proj-1", "s1", now))

            val loaded = store.loadProject("proj-1")
            assertNotNull(loaded)
            assertEquals(ProjectStatus.ACTIVE, loaded.project.status)
            assertEquals(StepStatus.IN_PROGRESS, loaded.project.plan.steps.single().status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store returns null for nonexistent project`() {
        val root = Files.createTempDirectory("psyke-store-missing")
        try {
            val store = ProjectStore(root)
            assertNull(store.loadProject("nonexistent"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── ContextLoader ────────────────────────────────────────────────────

    @Test
    fun `tier1 summary captures current step and blockers`() {
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.DONE, "verify s1"),
                PlanStep("s2", "Step 2", StepStatus.IN_PROGRESS, "verify s2"),
                PlanStep("s3", "Step 3", StepStatus.BLOCKED, "verify s3",
                    waitCondition = WaitCondition(WaitConditionType.TIMER, emptyMap(), now)),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = Project(
                id = "proj-1", title = "T", instruction = "I",
                status = ProjectStatus.ACTIVE, priority = ProjectPriority.HIGH,
                plan = plan, completionCriteria = "done",
                createdAt = now, lastWorkedAt = now,
                workspacePath = Path.of("/tmp/test"),
            ),
        )

        val summary = ProjectContextLoader.tier1Summary(state)
        assertEquals("proj-1", summary.projectId)
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
            Files.writeString(workspace.resolve("context.md"), "# Project Context\nSome state here.")

            val ctx = ProjectContextLoader.tier2Context(workspace)
            assertTrue(ctx.contains("Project Context"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tier2 context returns empty for missing file`() {
        val dir = Files.createTempDirectory("psyke-ctx-missing")
        try {
            assertEquals("", ProjectContextLoader.tier2Context(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
