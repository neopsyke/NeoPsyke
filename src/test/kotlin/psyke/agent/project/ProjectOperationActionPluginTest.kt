package psyke.agent.project

import kotlinx.coroutines.runBlocking
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.builtin.ProjectOperationActionPlugin
import psyke.agent.config.AgentConfig
import psyke.agent.model.ActionExecutionStatus
import psyke.agent.model.ActionType
import psyke.agent.model.PendingAction
import psyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                reflectionMemoryRecorder = psyke.agent.actions.NoopReflectionMemoryRecorder,
            )
        )
        val enabled = ProjectOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(projects = ProjectConfig(enabled = true)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = psyke.agent.actions.NoopReflectionMemoryRecorder,
            )
        )

        assertFalse(disabled.descriptor.dispatchable)
        assertTrue(enabled.descriptor.dispatchable)
    }

    @Test
    fun `plugin routes create operation through projects gateway`() = runBlocking {
        var capturedRequest: ProjectOperationRequest? = null
        val gateway = object : ProjectsGateway by NoopProjectsGateway {
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
                reflectionMemoryRecorder = psyke.agent.actions.NoopReflectionMemoryRecorder,
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
}
