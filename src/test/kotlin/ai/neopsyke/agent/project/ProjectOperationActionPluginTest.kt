package ai.neopsyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.builtin.ProjectOperationActionPlugin
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectOperationActionPluginTest {

    @Test
    fun `plugin is dispatchable only when projects are enabled`() {
        val disabled = ProjectOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(projects = ProjectConfig(enabled = false)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
            )
        )
        val enabled = ProjectOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(projects = ProjectConfig(enabled = true)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
            )
        )

        assertFalse(disabled.descriptor.dispatchable)
        assertTrue(enabled.descriptor.dispatchable)
    }

    @Test
    fun `plugin routes create operation through projects gateway`() = runBlocking {
        var capturedRequest: ProjectOperationRequest? = null
        val gateway = object : GoalsGateway by NoopGoalsGateway {
            override fun executeOperation(request: ProjectOperationRequest): ProjectOperationResult {
                capturedRequest = request
                return ProjectOperationResult(true, "created", "proj-1")
            }
        }
        val plugin = ProjectOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(projects = ProjectConfig(enabled = true)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
                projectsGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.PROJECT_OPERATION,
                payload = """{"operation":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH"}""",
                summary = "create project",
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.SUCCESS, outcome.executionStatus)
        assertEquals(ProjectOperation.CREATE, capturedRequest?.operation)
        assertEquals("Inbox", capturedRequest?.title)
        assertEquals(ProjectPriority.HIGH, capturedRequest?.priority)
    }

    @Test
    fun `plugin executes project lifecycle operations through manager gateway`() = runBlocking {
        val root = Files.createTempDirectory("psyke-project-op-lifecycle")
        try {
            val manager = GoalManager(
                config = ProjectConfig(enabled = true, workspaceRoot = root),
                store = ProjectStore(root),
                planner = DeterministicProjectPlanner(),
            )
            manager.start(testScope())
            val plugin = projectPlugin(manager, root)

            val create = execute(plugin, """{"operation":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)

            val projectId = manager.allProjects().single().projectId
            val created = manager.projectStatus(projectId)
            assertNotNull(created)
            assertEquals(ProjectPriority.HIGH, created.project.priority)

            val status = execute(plugin, """{"operation":"status","projectId":"$projectId"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, status.executionStatus)
            assertTrue(status.statusSummary.contains("status=ACTIVE"))
            assertTrue(status.statusSummary.contains("next_step=Keep inbox triaged"))

            val list = execute(plugin, """{"operation":"list"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, list.executionStatus)
            assertTrue(list.statusSummary.contains(projectId))
            assertTrue(list.statusSummary.contains("Inbox"))

            val pause = execute(plugin, """{"operation":"pause","projectId":"$projectId","reason":"waiting"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, pause.executionStatus)
            assertEquals(ProjectStatus.SUSPENDED, manager.projectStatus(projectId)?.project?.status)

            val resume = execute(plugin, """{"operation":"resume","projectId":"$projectId"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, resume.executionStatus)
            assertEquals(ProjectStatus.ACTIVE, manager.projectStatus(projectId)?.project?.status)

            val reprioritize = execute(plugin, """{"operation":"reprioritize","projectId":"$projectId","priority":"CRITICAL"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, reprioritize.executionStatus)
            assertEquals(ProjectPriority.CRITICAL, manager.projectStatus(projectId)?.project?.priority)

            val complete = execute(plugin, """{"operation":"complete","projectId":"$projectId"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, complete.executionStatus)
            assertEquals(ProjectStatus.COMPLETED, manager.projectStatus(projectId)?.project?.status)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `plugin revise_plan operation updates stored plan`() = runBlocking {
        val root = Files.createTempDirectory("psyke-project-op-revise")
        try {
            var planVersion = 0
            val planner = object : ProjectPlanner {
                override fun generatePlan(project: Project): ProjectPlan {
                    planVersion += 1
                    return ProjectPlan(
                        steps = listOf(
                            PlanStep(
                                id = "step-$planVersion",
                                description = if (planVersion == 1) "Initial plan" else "Revised plan",
                                status = StepStatus.PENDING,
                                acceptanceCriteria = "done"
                            )
                        ),
                        generatedAt = Instant.now(),
                    )
                }
            }
            val manager = GoalManager(
                config = ProjectConfig(enabled = true, workspaceRoot = root),
                store = ProjectStore(root),
                planner = planner,
            )
            manager.start(testScope())
            val plugin = projectPlugin(manager, root)

            val create = execute(plugin, """{"operation":"create","title":"Revise Me","instruction":"Original instruction"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)
            val projectId = manager.allProjects().single().projectId
            assertEquals("Initial plan", manager.projectStatus(projectId)?.project?.plan?.steps?.single()?.description)

            val revise = execute(plugin, """{"operation":"revise_plan","projectId":"$projectId","reason":"new context"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, revise.executionStatus)
            assertEquals("Revised plan", manager.projectStatus(projectId)?.project?.plan?.steps?.single()?.description)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun execute(plugin: ProjectOperationActionPlugin, payload: String) =
        plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.PROJECT_OPERATION,
                payload = payload,
                summary = "project operation",
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

    private fun projectPlugin(gateway: GoalsGateway, root: java.nio.file.Path) =
        ProjectOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(projects = ProjectConfig(enabled = true, workspaceRoot = root)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
                projectsGateway = gateway,
            )
        )

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
