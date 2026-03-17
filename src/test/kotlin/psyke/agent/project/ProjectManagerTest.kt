package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import psyke.agent.cortex.sensory.Signal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun testScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `createProject returns projectId and emits signal`() {
        val root = Files.createTempDirectory("psyke-pm-test")
        try {
            val signals = mutableListOf<Signal>()
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
                signalEmitter = signals::add,
            )
            manager.start(testScope())

            val id = manager.createProject(
                instruction = "Monitor stock prices daily",
                title = "Stock Monitor",
                priority = ProjectPriority.HIGH,
            )

            assertTrue(id.isNotBlank())
            assertTrue(signals.isNotEmpty())
            val state = manager.projectStatus(id)
            assertNotNull(state)
            assertEquals(ProjectPriority.HIGH, state.project.priority)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `allProjects returns tier1 summaries`() {
        val root = Files.createTempDirectory("psyke-pm-allprojects")
        try {
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
            )
            manager.start(testScope())

            manager.createProject("Task A", "Project A")
            manager.createProject("Task B", "Project B")

            val all = manager.allProjects()
            assertEquals(2, all.size)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pendingWorkSummary returns empty when no active projects`() {
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
    fun `pickWork returns null when no active projects`() {
        val root = Files.createTempDirectory("psyke-pm-pickwork")
        try {
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
            )
            manager.start(testScope())

            assertNull(manager.pickWork())

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
            assertEquals("", id3) // should be rejected

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
            )
            manager1.start(testScope())
            val id = manager1.createProject("Persistent task")
            manager1.stop()

            // New manager, same store
            val manager2 = ProjectManager(
                config = testConfig(root),
                store = store,
            )
            manager2.start(testScope())

            val reloaded = manager2.projectStatus(id)
            assertNotNull(reloaded)
            assertTrue(reloaded.project.instruction.contains("Persistent task"))

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
