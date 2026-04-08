package ai.neopsyke.agent.goal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.plugin.builtin.GoalOperationActionPlugin
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.model.GroundingMetadata
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
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
            )
        )
        val enabled = GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
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
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"command":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH","completion_criteria":"Inbox is triaged","cron_expression":"*/5 * * * *"}""",
                summary = "create goal",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
    fun `plugin executes typed delete_all command from planner`() = runBlocking {
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
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

        // The new planner emits canonical "command" field; no operation normalization needed
        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"command":"delete_all"}""",
                summary = "delete goals",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.SUCCESS, outcome.executionStatus)
        assertEquals(GoalOperation.DELETE_ALL, capturedRequest?.operation)
    }

    @Test
    fun `plugin rejects delete command without goal reference`() = runBlocking {
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
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 2L,
                urgency = Urgency.MEDIUM,
                type = ActionType.GOAL_OPERATION,
                payload = """{"command":"delete"}""",
                summary = "delete goal ambiguously",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.FAILED, outcome.executionStatus)
        assertEquals(null, capturedRequest)
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

            val create = execute(plugin, """{"command":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)

            val goalId = manager.allGoals().single().goalId
            val created = manager.goalStatus(goalId)
            assertNotNull(created)
            assertEquals(GoalPriority.HIGH, created.goal.priority)

            val status = execute(plugin, """{"command":"status","goal_reference":{"type":"by_internal_id","id":"$goalId"}}""")
            assertEquals(ActionExecutionStatus.SUCCESS, status.executionStatus)
            assertTrue(status.statusSummary.contains("status=ACTIVE"))
            assertTrue(status.statusSummary.contains("next_step=Keep inbox triaged"))

            val list = execute(plugin, """{"command":"list"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, list.executionStatus)
            assertTrue(list.statusSummary.contains(goalId))
            assertTrue(list.statusSummary.contains("Inbox"))

            val pause = execute(plugin, """{"command":"pause","goal_reference":{"type":"by_internal_id","id":"$goalId"},"reason":"waiting"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, pause.executionStatus)
            assertEquals(GoalStatus.SUSPENDED, manager.goalStatus(goalId)?.goal?.status)

            val resume = execute(plugin, """{"command":"resume","goal_reference":{"type":"by_internal_id","id":"$goalId"}}""")
            assertEquals(ActionExecutionStatus.SUCCESS, resume.executionStatus)
            assertEquals(GoalStatus.ACTIVE, manager.goalStatus(goalId)?.goal?.status)

            val reprioritize = execute(plugin, """{"command":"reprioritize","goal_reference":{"type":"by_internal_id","id":"$goalId"},"priority":"CRITICAL"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, reprioritize.executionStatus)
            assertEquals(GoalPriority.CRITICAL, manager.goalStatus(goalId)?.goal?.priority)

            val complete = execute(plugin, """{"command":"complete","goal_reference":{"type":"by_internal_id","id":"$goalId"}}""")
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

            val create = execute(plugin, """{"command":"create","title":"Revise Me","instruction":"Original instruction"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)
            val goalId = manager.allGoals().single().goalId
            assertEquals("Initial plan", manager.goalStatus(goalId)?.goal?.plan?.steps?.single()?.description)

            val revise = execute(plugin, """{"command":"revise_plan","goal_reference":{"type":"by_internal_id","id":"$goalId"},"reason":"new context"}""")
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
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

    private fun projectPlugin(gateway: GoalsGateway, root: java.nio.file.Path) =
        GoalOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(goals = GoalConfig(enabled = true, workspaceRoot = root)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                goalsGateway = gateway,
            )
        )

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
