package ai.neopsyke.agent.goal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.builtin.GoalOperationActionPlugin
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

class GoalOperationActionPluginTest {

    @Test
    fun `plugin is dispatchable only when goals are enabled`() {
        val disabled = GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = false)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
            )
        )
        val enabled = GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true)),
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
    fun `plugin routes create operation through goals gateway`() = runBlocking {
        var capturedRequest: GoalOperationRequest? = null
        val gateway = object : GoalsGateway by NoopGoalsGateway {
            override fun executeOperation(request: GoalOperationRequest): GoalOperationResult {
                capturedRequest = request
                return GoalOperationResult(true, "created", "goal-1")
            }
        }
        val plugin = GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH","completion_criteria":"Inbox is triaged","cron_expression":"*/5 * * * *"}""",
                summary = "create goal",
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.SUCCESS, outcome.executionStatus)
        assertEquals(GoalOperation.CREATE, capturedRequest?.operation)
        assertEquals("Inbox", capturedRequest?.title)
        assertEquals(GoalPriority.HIGH, capturedRequest?.priority)
        assertEquals("Inbox is triaged", capturedRequest?.completionCriteria)
        assertEquals("*/5 * * * *", capturedRequest?.cronExpression)
    }

    @Test
    fun `plugin normalizes delete all intent from revise payload`() = runBlocking {
        var capturedRequest: GoalOperationRequest? = null
        val gateway = object : GoalsGateway by NoopGoalsGateway {
            override fun executeOperation(request: GoalOperationRequest): GoalOperationResult {
                capturedRequest = request
                return GoalOperationResult(true, "deleted")
            }
        }
        val plugin = GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"revise","instruction":"Delete all existing goals"}""",
                summary = "delete goals",
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.SUCCESS, outcome.executionStatus)
        assertEquals(GoalOperation.DELETE_ALL, capturedRequest?.operation)
    }

    @Test
    fun `plugin keeps ambiguous delete payload as single delete instead of delete all`() = runBlocking {
        var capturedRequest: GoalOperationRequest? = null
        val gateway = object : GoalsGateway by NoopGoalsGateway {
            override fun executeOperation(request: GoalOperationRequest): GoalOperationResult {
                capturedRequest = request
                return GoalOperationResult(false, "Goal delete requires goalId.")
            }
        }
        val plugin = GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 2L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"operation":"delete"}""",
                summary = "delete goal ambiguously",
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.FAILED, outcome.executionStatus)
        assertEquals(GoalOperation.DELETE, capturedRequest?.operation)
        assertEquals(null, capturedRequest?.goalId)
    }

    @Test
    fun `plugin executes goal lifecycle operations through manager gateway`() = runBlocking {
        val root = Files.createTempDirectory("psyke-goal-op-lifecycle")
        try {
            val manager = GoalManager(
                config = GoalConfig(enabled = true, workspaceRoot = root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
            )
            manager.start(testScope())
            val plugin = projectPlugin(manager, root)

            val create = execute(plugin, """{"operation":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)

            val goalId = manager.allGoals().single().goalId
            val created = manager.goalStatus(goalId)
            assertNotNull(created)
            assertEquals(GoalPriority.HIGH, created.goal.priority)

            val status = execute(plugin, """{"operation":"status","goalId":"$goalId"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, status.executionStatus)
            assertTrue(status.statusSummary.contains("status=ACTIVE"))
            assertTrue(status.statusSummary.contains("next_step=Keep inbox triaged"))

            val list = execute(plugin, """{"operation":"list"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, list.executionStatus)
            assertTrue(list.statusSummary.contains(goalId))
            assertTrue(list.statusSummary.contains("Inbox"))

            val pause = execute(plugin, """{"operation":"pause","goalId":"$goalId","reason":"waiting"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, pause.executionStatus)
            assertEquals(GoalStatus.SUSPENDED, manager.goalStatus(goalId)?.goal?.status)

            val resume = execute(plugin, """{"operation":"resume","goalId":"$goalId"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, resume.executionStatus)
            assertEquals(GoalStatus.ACTIVE, manager.goalStatus(goalId)?.goal?.status)

            val reprioritize = execute(plugin, """{"operation":"reprioritize","goalId":"$goalId","priority":"CRITICAL"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, reprioritize.executionStatus)
            assertEquals(GoalPriority.CRITICAL, manager.goalStatus(goalId)?.goal?.priority)

            val complete = execute(plugin, """{"operation":"complete","goalId":"$goalId"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, complete.executionStatus)
            assertEquals(GoalStatus.COMPLETED, manager.goalStatus(goalId)?.goal?.status)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `plugin revise_plan operation updates stored plan`() = runBlocking {
        val root = Files.createTempDirectory("psyke-goal-op-revise")
        try {
            var planVersion = 0
            val planner = object : GoalPlanner {
                override fun generatePlan(goal: Goal): GoalPlan {
                    planVersion += 1
                    return GoalPlan(
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
                config = GoalConfig(enabled = true, workspaceRoot = root),
                store = GoalStore(root),
                planner = planner,
            )
            manager.start(testScope())
            val plugin = projectPlugin(manager, root)

            val create = execute(plugin, """{"operation":"create","title":"Revise Me","instruction":"Original instruction"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)
            val goalId = manager.allGoals().single().goalId
            assertEquals("Initial plan", manager.goalStatus(goalId)?.goal?.plan?.steps?.single()?.description)

            val revise = execute(plugin, """{"operation":"revise_plan","goalId":"$goalId","reason":"new context"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, revise.executionStatus)
            assertEquals("Revised plan", manager.goalStatus(goalId)?.goal?.plan?.steps?.single()?.description)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun execute(plugin: GoalOperationActionPlugin, payload: String) =
        plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = payload,
                summary = "goal operation",
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

    private fun projectPlugin(gateway: GoalsGateway, root: java.nio.file.Path) =
        GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true, workspaceRoot = root)),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
