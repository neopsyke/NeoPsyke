package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import psyke.agent.cortex.sensory.ProjectSignal
import psyke.agent.model.ActionExecutionStatus
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.model.ConversationContext
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingAction
import psyke.agent.model.Urgency
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectManagerTest {

    private fun testConfig(root: java.nio.file.Path) = ProjectConfig(
        enabled = true,
        workspaceRoot = root,
        maxActiveProjects = 5,
        timerResolutionMs = 100,
        conditionCheckIntervalMs = 100,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `createProject generates plan persists state and emits work-ready signal`() {
        val root = Files.createTempDirectory("psyke-pm-create")
        try {
            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
                planner = DeterministicProjectPlanner(),
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager.start(testScope())

            val id = manager.createProject(
                instruction = "Monitor stock prices daily",
                title = "Stock Monitor",
                priority = ProjectPriority.HIGH,
            )

            assertTrue(id.isNotBlank())
            val workReady = assertIs<ProjectSignal.WorkReady>(signals.last())
            assertEquals(id, workReady.projectId)
            val state = manager.projectStatus(id)
            assertNotNull(state)
            assertEquals(ProjectStatus.ACTIVE, state.project.status)
            assertTrue(Files.exists(root.resolve(id).resolve(ProjectStore.PROJECT_FILE)))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromSignal creates project session and returns work unit`() {
        val root = Files.createTempDirectory("psyke-pm-work")
        try {
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
                planner = DeterministicProjectPlanner(),
            )
            manager.start(testScope())
            val id = manager.createProject("Persistent task")

            val work = manager.nextWorkFromSignal(ProjectSignal.WorkReady(id, "step-1", "test"))
            assertNotNull(work)
            assertEquals(id, work.projectId)
            assertTrue(work.rootInputId.startsWith("project:$id"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `project-origin action outcome completes final step and project`() {
        val root = Files.createTempDirectory("psyke-pm-complete")
        try {
            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
                planner = DeterministicProjectPlanner(),
                verifier = DeterministicProjectStepVerifier(),
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager.start(testScope())
            val id = manager.createProject("Ship release checklist")
            val work = manager.nextWorkFromSignal(assertIs<ProjectSignal.WorkReady>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = PendingAction(
                    id = 1L,
                    urgency = Urgency.MEDIUM,
                    type = ActionType.REFLECT,
                    payload = """{"note":"done"}""",
                    summary = "done",
                    rootInputId = work.rootInputId,
                    conversationContext = ConversationContext.default(),
                    origin = psyke.agent.model.ActionOrigin(source = OriginSource.PROJECT),
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeProjectCycle(work.rootInputId)

            val state = manager.projectStatus(id)
            assertNotNull(state)
            assertEquals(ProjectStatus.COMPLETED, state.project.status)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pendingWorkSummary returns empty when no projects exist`() {
        val root = Files.createTempDirectory("psyke-pm-summary")
        try {
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
            )
            manager.start(testScope())
            assertEquals("", manager.pendingWorkSummary())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `max active projects enforced`() {
        val root = Files.createTempDirectory("psyke-pm-limit")
        try {
            val config = testConfig(root).copy(maxActiveProjects = 2)
            val manager = ProjectManager(
                config = config,
                store = ProjectStore(root),
            )
            manager.start(testScope())

            val id1 = manager.createProject("Task 1")
            val id2 = manager.createProject("Task 2")
            val id3 = manager.createProject("Task 3")

            assertTrue(id1.isNotBlank())
            assertTrue(id2.isNotBlank())
            assertEquals("", id3)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `project persists and reloads across manager restarts`() {
        val root = Files.createTempDirectory("psyke-pm-restart")
        try {
            val store = ProjectStore(root)
            val manager1 = ProjectManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicProjectPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createProject("Persistent task")
            manager1.stop()

            val manager2 = ProjectManager(
                config = testConfig(root),
                store = store,
            )
            manager2.start(testScope())

            val reloaded = manager2.projectStatus(id)
            assertNotNull(reloaded)
            assertTrue(reloaded.project.instruction.contains("Persistent task"))
            assertEquals(ProjectStatus.ACTIVE, reloaded.project.status)

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromSignal returns null when project is missing`() {
        val root = Files.createTempDirectory("psyke-pm-missing")
        try {
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
            )
            manager.start(testScope())
            assertNull(manager.nextWorkFromSignal(ProjectSignal.WorkReady("missing", "s1", "test")))
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `suspended project resume timer is restored on restart and emits work-ready`() {
        val root = Files.createTempDirectory("psyke-pm-restore-suspend")
        try {
            val store = ProjectStore(root)
            val manager1 = ProjectManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicProjectPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createProject("Resume me later")
            val resumeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                ProjectEvent.Suspended(id, "paused", resumeAt)
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager2 = ProjectManager(
                config = testConfig(root),
                store = store,
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.projectStatus(id)?.project?.status == ProjectStatus.ACTIVE &&
                    signals.any { it is ProjectSignal.WorkReady && it.projectId == id }
            }

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `blocked timer wait is restored on restart and project becomes ACTIVE when timer fires`() {
        val root = Files.createTempDirectory("psyke-pm-restore-wait")
        try {
            val store = ProjectStore(root)
            val manager1 = ProjectManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicProjectPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createProject("Wait for timer")
            val wakeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                ProjectEvent.StepBlocked(
                    projectId = id,
                    stepId = "step-1",
                    waitCondition = WaitCondition(
                        type = WaitConditionType.TIMER,
                        params = mapOf("wake_at" to wakeAt.toString()),
                        registeredAt = Instant.now(),
                        timeoutAt = wakeAt,
                    )
                )
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager2 = ProjectManager(
                config = testConfig(root),
                store = store,
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.projectStatus(id)?.project?.status == ProjectStatus.ACTIVE &&
                    manager2.projectStatus(id)?.project?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it is ProjectSignal.WorkReady && it.projectId == id }
            }

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun waitUntil(timeoutMs: Long = 2_500, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        assertTrue(predicate())
    }
}
