package ai.neopsyke.agent.durablework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.plugin.builtin.DurableWorkOperationActionPlugin
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

class DurableWorkOperationActionPluginTest {

    @Test
    fun `plugin is dispatchable only when goals are enabled`() {
        val disabled = DurableWorkOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(durableWork = DurableWorkConfig(enabled = false)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
            )
        )
        val enabled = DurableWorkOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(durableWork = DurableWorkConfig(enabled = true)),
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
        var capturedRequest: DurableWorkOperationRequest? = null
        val gateway = object : DurableWorkGateway by NoopDurableWorkGateway {
            override fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult {
                capturedRequest = request
                return DurableWorkOperationResult(true, "created", "goal-1")
            }
        }
        val plugin = DurableWorkOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(durableWork = DurableWorkConfig(enabled = true)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                durableWorkGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.DURABLE_WORK_OPERATION,
                payload = """{"command":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH","completion_criteria":"Inbox is triaged","cron_expression":"*/5 * * * *"}""",
                summary = "create goal",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.SUCCESS, outcome.executionStatus)
        assertEquals(DurableWorkOperation.CREATE, capturedRequest?.operation)
        assertEquals("Inbox", capturedRequest?.title)
        assertEquals(WorkItemPriority.HIGH, capturedRequest?.priority)
        assertEquals("Inbox is triaged", capturedRequest?.completionCriteria)
        assertEquals("*/5 * * * *", capturedRequest?.cronExpression)
    }

    @Test
    fun `plugin executes typed delete_all command from planner`() = runBlocking {
        var capturedRequest: DurableWorkOperationRequest? = null
        val gateway = object : DurableWorkGateway by NoopDurableWorkGateway {
            override fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult {
                capturedRequest = request
                return DurableWorkOperationResult(true, "deleted")
            }
        }
        val plugin = DurableWorkOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(durableWork = DurableWorkConfig(enabled = true)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                durableWorkGateway = gateway,
            )
        )

        // The new planner emits canonical "command" field; no operation normalization needed
        val outcome = plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.DURABLE_WORK_OPERATION,
                payload = """{"command":"delete_all"}""",
                summary = "delete goals",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

        assertEquals(ActionExecutionStatus.SUCCESS, outcome.executionStatus)
        assertEquals(DurableWorkOperation.DELETE_ALL, capturedRequest?.operation)
    }

    @Test
    fun `plugin rejects delete command without goal reference`() = runBlocking {
        var capturedRequest: DurableWorkOperationRequest? = null
        val gateway = object : DurableWorkGateway by NoopDurableWorkGateway {
            override fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult {
                capturedRequest = request
                return DurableWorkOperationResult(false, "Goal delete requires workItemId.")
            }
        }
        val plugin = DurableWorkOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(durableWork = DurableWorkConfig(enabled = true)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                durableWorkGateway = gateway,
            )
        )

        val outcome = plugin.execute(
            PendingAction(
                id = 2L,
                urgency = Urgency.MEDIUM,
                type = ActionType.DURABLE_WORK_OPERATION,
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
            val manager = DurableWorkRuntime(
                config = DurableWorkConfig(enabled = true, workspaceRoot = root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
            )
            manager.start(testScope())
            val plugin = projectPlugin(manager, root)

            val create = execute(plugin, """{"command":"create","title":"Inbox","instruction":"Keep inbox triaged","priority":"HIGH"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)

            val workItemId = manager.allWorkItems().single().workItemId
            val created = manager.workItemStatus(workItemId)
            assertNotNull(created)
            assertEquals(WorkItemPriority.HIGH, created.workItem.priority)

            val status = execute(plugin, """{"command":"status","work_item_reference":{"type":"by_internal_id","id":"$workItemId"}}""")
            assertEquals(ActionExecutionStatus.SUCCESS, status.executionStatus)
            assertTrue(status.statusSummary.contains("status=ACTIVE"))
            assertTrue(status.statusSummary.contains("next_step=Keep inbox triaged"))

            val list = execute(plugin, """{"command":"list"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, list.executionStatus)
            assertTrue(list.statusSummary.contains(workItemId))
            assertTrue(list.statusSummary.contains("Inbox"))

            val pause = execute(plugin, """{"command":"pause","work_item_reference":{"type":"by_internal_id","id":"$workItemId"},"reason":"waiting"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, pause.executionStatus)
            assertEquals(WorkItemStatus.SUSPENDED, manager.workItemStatus(workItemId)?.workItem?.status)

            val resume = execute(plugin, """{"command":"resume","work_item_reference":{"type":"by_internal_id","id":"$workItemId"}}""")
            assertEquals(ActionExecutionStatus.SUCCESS, resume.executionStatus)
            assertEquals(WorkItemStatus.ACTIVE, manager.workItemStatus(workItemId)?.workItem?.status)

            val reprioritize = execute(plugin, """{"command":"reprioritize","work_item_reference":{"type":"by_internal_id","id":"$workItemId"},"priority":"CRITICAL"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, reprioritize.executionStatus)
            assertEquals(WorkItemPriority.CRITICAL, manager.workItemStatus(workItemId)?.workItem?.priority)

            val complete = execute(plugin, """{"command":"complete","work_item_reference":{"type":"by_internal_id","id":"$workItemId"}}""")
            assertEquals(ActionExecutionStatus.SUCCESS, complete.executionStatus)
            assertEquals(WorkItemStatus.COMPLETED, manager.workItemStatus(workItemId)?.workItem?.status)

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
            val planner = object : WorkPlanBuilder {
                override fun generatePlan(workItem: WorkItem): WorkItemPlan {
                    planVersion += 1
                    return WorkItemPlan(
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
            val manager = DurableWorkRuntime(
                config = DurableWorkConfig(enabled = true, workspaceRoot = root),
                store = WorkItemStore(root),
                planner = planner,
            )
            manager.start(testScope())
            val plugin = projectPlugin(manager, root)

            val create = execute(plugin, """{"command":"create","title":"Revise Me","instruction":"Original instruction"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, create.executionStatus)
            val workItemId = manager.allWorkItems().single().workItemId
            assertEquals("Initial plan", manager.workItemStatus(workItemId)?.workItem?.plan?.steps?.single()?.description)

            val revise = execute(plugin, """{"command":"revise_plan","work_item_reference":{"type":"by_internal_id","id":"$workItemId"},"reason":"new context"}""")
            assertEquals(ActionExecutionStatus.SUCCESS, revise.executionStatus)
            assertEquals("Revised plan", manager.workItemStatus(workItemId)?.workItem?.plan?.steps?.single()?.description)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun execute(plugin: DurableWorkOperationActionPlugin, payload: String) =
        plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.DURABLE_WORK_OPERATION,
                payload = payload,
                summary = "goal operation",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0)
        )

    private fun projectPlugin(gateway: DurableWorkGateway, root: java.nio.file.Path) =
        DurableWorkOperationActionPlugin(
            ActionPluginFactoryContext(
                config = AgentConfig(durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root)),
                webSearchActionHandler = null,
                fetchTool = null,
                output = {},
                reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
                durableWorkGateway = gateway,
            )
        )

    @Test
    fun `create command with contactChannel passes through to request`() = runBlocking {
        var capturedRequest: DurableWorkOperationRequest? = null
        val gateway = object : DurableWorkGateway by NoopDurableWorkGateway {
            override fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult {
                capturedRequest = request
                return DurableWorkOperationResult(true, "created", "goal-1")
            }
        }
        val plugin = pluginWithGateway(gateway)

        plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.DURABLE_WORK_OPERATION,
                payload = """{"command":"create","title":"Weather","instruction":"Check weather","contact_channel":"telegram"}""",
                summary = "create goal",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("telegram", capturedRequest?.contactChannel)
    }

    @Test
    fun `update command with contactChannel passes through to request`() = runBlocking {
        var capturedRequest: DurableWorkOperationRequest? = null
        val gateway = object : DurableWorkGateway by NoopDurableWorkGateway {
            override fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult {
                capturedRequest = request
                return DurableWorkOperationResult(true, "updated", "goal-1")
            }
        }
        val plugin = pluginWithGateway(gateway)

        plugin.execute(
            PendingAction(
                id = 1L,
                urgency = Urgency.MEDIUM,
                type = ActionType.DURABLE_WORK_OPERATION,
                payload = """{"command":"update","work_item_id":"goal-1","contact_channel":"webapp"}""",
                summary = "update goal",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("webapp", capturedRequest?.contactChannel)
    }

    private fun pluginWithGateway(gateway: DurableWorkGateway) = DurableWorkOperationActionPlugin(
        ActionPluginFactoryContext(
            config = AgentConfig(durableWork = DurableWorkConfig(enabled = true)),
            webSearchActionHandler = null,
            fetchTool = null,
            output = {},
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
            durableWorkGateway = gateway,
        )
    )

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
