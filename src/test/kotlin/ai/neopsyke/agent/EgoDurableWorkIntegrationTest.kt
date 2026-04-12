package ai.neopsyke.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.cortex.motor.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionHandle
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncResumeMode
import ai.neopsyke.agent.cortex.motor.actions.plugin.builtin.ContactUserActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.DurableWorkCue
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.durablework.DeterministicWorkPlanBuilder
import ai.neopsyke.agent.durablework.PlanStep
import ai.neopsyke.agent.durablework.WorkItem
import ai.neopsyke.agent.durablework.DurableWorkConfig
import ai.neopsyke.agent.durablework.DurableWorkRuntime
import ai.neopsyke.agent.durablework.WorkItemPriority
import ai.neopsyke.agent.durablework.WorkItemStatus
import ai.neopsyke.agent.durablework.WorkStepVerification
import ai.neopsyke.agent.durablework.WorkStepVerdict
import ai.neopsyke.agent.durablework.WorkStepVerifier
import ai.neopsyke.agent.durablework.WorkItemStore
import ai.neopsyke.agent.durablework.TimeoutAction
import ai.neopsyke.agent.durablework.WaitCondition
import ai.neopsyke.agent.durablework.WaitConditionType
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.buildTestEgo
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class EgoProjectIntegrationTest {

    @Test
    fun `work-ready signal executes through ego and completes goal`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-goal-complete")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxContinuationPasses = 2),
            durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2, allowRuntimePlanFallback = true),
        )
        val manager = DurableWorkRuntime(
            config = config.durableWork,
            store = WorkItemStore(root),
            planner = DeterministicWorkPlanBuilder(),
            instrumentation = instrumentation,
            cueEmitter = source::offer,
        )
        val scope = testScope()
        manager.start(scope)
        val agent = buildTestEgo(
            planner = projectOnlyPlanner("goal done"),
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = buildMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
            durableWorkGateway = manager,
        )

        val loop = launch { agent.runInteractive() }
        try {
            val workItemId = manager.createWorkItem("Ship the report", "Report Goal", WorkItemPriority.HIGH)
            waitForStatus(manager, workItemId, WorkItemStatus.COMPLETED)
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state.workItem.status)
            assertEquals(listOf("ego> goal done"), outputs)
            assertTrue(
                instrumentation.events.any {
                    it.type == "durable_work_step_completed" && it.data["success"] == true
                }
            )
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `async goal action waits through goals runtime and resumes to completion`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-goal-async")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxContinuationPasses = 2),
            durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2, conditionCheckIntervalMs = 25, allowRuntimePlanFallback = true),
        )
        val provider = StubAsyncOperationProvider().apply {
            enqueue(
                operationId = "async-op-1",
                statuses = listOf(
                    AsyncOperationStatus.Pending("download queued", nextPollAfterMs = 25),
                    AsyncOperationStatus.Succeeded("download finished"),
                )
            )
        }
        val manager = DurableWorkRuntime(
            config = config.durableWork,
            store = WorkItemStore(root),
            planner = DeterministicWorkPlanBuilder(),
            asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            instrumentation = instrumentation,
            cueEmitter = source::offer,
        )
        val scope = testScope()
        manager.start(scope)
        var startedAsync = false
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.DurableWork -> {
                        if (!startedAsync) {
                            startedAsync = true
                            EgoDecision.FormIntention(
                                urgency = Urgency.MEDIUM,
                                intentionKind = IntentionKind.PREPARE,
                                commitModePreference = CommitMode.APPROVAL_BACKED,
                                actionType = ActionType("async_test"),
                                payload = """{"operation_id":"async-op-1"}""",
                                summary = "start async test operation"
                            )
                        } else {
                            EgoDecision.FormIntention(
                                urgency = Urgency.MEDIUM,
                                intentionKind = IntentionKind.PREPARE,
                                commitModePreference = CommitMode.APPROVAL_BACKED,
                                actionType = ActionType.CONTACT_USER,
                                payload = "async goal done",
                                summary = "report completion"
                            )
                        }
                    }

                    else -> EgoDecision.Noop("ignore non-goal work")
                }
        }
        val agent = buildTestEgo(
            planner = planner,
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = buildAsyncMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
            durableWorkGateway = manager,
        )

        val loop = launch { agent.runInteractive() }
        try {
            val workItemId = manager.createWorkItem("Use async action in goal")
            waitForStatus(manager, workItemId, WorkItemStatus.COMPLETED)
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state.workItem.status)
            assertTrue(state.workItem.plan.steps.first().notes.contains("async_status=succeeded"))
            assertEquals(listOf("ego> async goal done"), outputs)
            assertTrue(
                instrumentation.events.any {
                    it.type == "opportunity_enqueued" &&
                        it.data["source"] == "goal_runtime"
                }
            )
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal action denial is routed back into retry and eventually completes`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-goal-retry")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 12, maxContinuationPasses = 2),
            durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2, allowRuntimePlanFallback = true),
        )
        val manager = DurableWorkRuntime(
            config = config.durableWork,
            store = WorkItemStore(root),
            planner = DeterministicWorkPlanBuilder(),
            instrumentation = instrumentation,
            cueEmitter = source::offer,
        )
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"not yet","reason_code":"TEMP_RETRY"}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val actionPayloads = ArrayDeque(listOf("first try", "second try"))
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.DurableWork -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.PREPARE,
                        commitModePreference = CommitMode.APPROVAL_BACKED,
                        actionType = ActionType.CONTACT_USER,
                        payload = actionPayloads.removeFirst(),
                        summary = "goal step"
                    )
                    else -> EgoDecision.Noop("ignore non-goal work")
                }
        }
        val loop = launch {
            buildTestEgo(
                planner = planner,
                superego = Superego(modelClient = superegoLlm, config = config, instrumentation = instrumentation),
                motorCortex = buildMotorCortex(outputs),
                config = config,
                instrumentation = instrumentation,
                sensoryCortex = SensoryCortex(config, source),
                durableWorkGateway = manager,
            ).runInteractive()
        }
        val scope = testScope()
        manager.start(scope)
        try {
            val workItemId = manager.createWorkItem("Retry until approved")
            waitForStatus(manager, workItemId, WorkItemStatus.COMPLETED)
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()

            assertEquals("ego> second try", outputs.last())
            assertTrue(outputs.any { it.contains("blocked by policy", ignoreCase = true) })
            assertTrue(actionPayloads.isEmpty())
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal action budget yields and resumes same in-progress step`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-goal-budget")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 12, maxContinuationPasses = 2),
            durableWork = DurableWorkConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 1, allowRuntimePlanFallback = true),
        )
        var verifierCalls = 0
        val verifier = object : WorkStepVerifier {
            override fun evaluate(
                workItem: WorkItem,
                step: PlanStep,
                action: ai.neopsyke.agent.model.PendingAction,
                outcome: ai.neopsyke.agent.model.ActionOutcome,
                observedEvidence: Boolean,
            ): WorkStepVerification {
                verifierCalls += 1
                return if (verifierCalls == 1) {
                    WorkStepVerification(WorkStepVerdict.CONTINUE, "keep going")
                } else {
                    WorkStepVerification(WorkStepVerdict.PASS, "done")
                }
            }
        }
        val manager = DurableWorkRuntime(
            config = config.durableWork,
            store = WorkItemStore(root),
            planner = DeterministicWorkPlanBuilder(),
            verifier = verifier,
            instrumentation = instrumentation,
            cueEmitter = source::offer,
        )
        val actionPayloads = ArrayDeque(listOf("cycle one", "cycle two"))
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.DurableWork -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.PREPARE,
                        commitModePreference = CommitMode.APPROVAL_BACKED,
                        actionType = ActionType.CONTACT_USER,
                        payload = actionPayloads.removeFirst(),
                        summary = "goal step"
                    )
                    else -> EgoDecision.Noop("ignore non-goal work")
                }
        }
        val loop = launch {
            buildTestEgo(
                planner = planner,
                superego = allowAllSuperego(config, instrumentation),
                motorCortex = buildMotorCortex(outputs),
                config = config,
                instrumentation = instrumentation,
                sensoryCortex = SensoryCortex(config, source),
                durableWorkGateway = manager,
            ).runInteractive()
        }
        val scope = testScope()
        manager.start(scope)
        try {
            val workItemId = manager.createWorkItem("Use multiple cycles")
            waitForStatus(manager, workItemId, WorkItemStatus.COMPLETED)
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()

            assertEquals(listOf("ego> cycle one", "ego> cycle two"), outputs)
            assertEquals(2, verifierCalls)
            assertTrue(actionPayloads.isEmpty())
            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state.workItem.status)
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal blocked by timer resumes through ego and completes`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-goal-timer-resume")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 12, maxContinuationPasses = 2),
            durableWork = DurableWorkConfig(
                enabled = true,
                workspaceRoot = root,
                actionsPerCycle = 1,
                timerResolutionMs = 50,
                allowRuntimePlanFallback = true,
            ),
        )
        var verifierCalls = 0
        val verifier = object : WorkStepVerifier {
            override fun evaluate(
                workItem: WorkItem,
                step: PlanStep,
                action: ai.neopsyke.agent.model.PendingAction,
                outcome: ai.neopsyke.agent.model.ActionOutcome,
                observedEvidence: Boolean,
            ): WorkStepVerification {
                verifierCalls += 1
                return if (verifierCalls == 1) {
                    WorkStepVerification(
                        verdict = WorkStepVerdict.BLOCK,
                        reason = "waiting on timer",
                        waitCondition = WaitCondition(
                            type = WaitConditionType.TIMER,
                            params = emptyMap(),
                            registeredAt = Instant.now(),
                            timeoutAt = Instant.now().plusMillis(200),
                            onTimeout = TimeoutAction.RETRY,
                        ),
                    )
                } else {
                    WorkStepVerification(WorkStepVerdict.PASS, "timer satisfied")
                }
            }
        }
        val manager = DurableWorkRuntime(
            config = config.durableWork,
            store = WorkItemStore(root),
            planner = DeterministicWorkPlanBuilder(),
            verifier = verifier,
            instrumentation = instrumentation,
            cueEmitter = source::offer,
        )
        val actionPayloads = ArrayDeque(listOf("wait for timer", "timer resumed"))
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.DurableWork -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.PREPARE,
                        commitModePreference = CommitMode.APPROVAL_BACKED,
                        actionType = ActionType.CONTACT_USER,
                        payload = actionPayloads.removeFirst(),
                        summary = "goal step"
                    )
                    else -> EgoDecision.Noop("ignore non-goal work")
                }
        }
        val loop = launch {
            buildTestEgo(
                planner = planner,
                superego = allowAllSuperego(config, instrumentation),
                motorCortex = buildMotorCortex(outputs),
                config = config,
                instrumentation = instrumentation,
                sensoryCortex = SensoryCortex(config, source),
                durableWorkGateway = manager,
            ).runInteractive()
        }
        val scope = testScope()
        manager.start(scope)
        try {
            val workItemId = manager.createWorkItem("Pause until timer wakes the step")
            waitForStatus(manager, workItemId, WorkItemStatus.COMPLETED)
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()

            assertEquals(listOf("ego> wait for timer", "ego> timer resumed"), outputs)
            assertEquals(2, verifierCalls)
            assertTrue(actionPayloads.isEmpty())
            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state.workItem.status)
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private fun projectOnlyPlanner(response: String): Ego.Planner =
        object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.DurableWork -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.PREPARE,
                        commitModePreference = CommitMode.APPROVAL_BACKED,
                        actionType = ActionType.CONTACT_USER,
                        payload = response,
                        summary = "complete goal"
                    )
                    else -> EgoDecision.Noop("ignore non-goal work")
                }
        }

    private fun allowAllSuperego(
        config: AgentConfig,
        instrumentation: RecordingInstrumentation,
    ): Superego {
        val client = StubChatModelClient().apply {
            repeat(8) { enqueueRawResponse("""{"allow":true}""") }
        }
        return Superego(
            modelClient = client,
            config = config,
            instrumentation = instrumentation,
        )
    }

    private fun buildMotorCortex(outputs: MutableList<String>): MotorCortex =
        MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult(summary = "unused", snippets = emptyList())
                }
            ),
            output = { outputs += it },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

    private fun buildAsyncMotorCortex(outputs: MutableList<String>): MotorCortex {
        val asyncPlugin = object : AgentActionPlugin {
            override val descriptor: ActionDescriptor = ActionDescriptor(
                actionType = ActionType("async_test"),
                dispatchable = true,
                plannerDescription = "async_test: start a test async operation and return a durable handle.",
                payloadGuidance = """JSON: {"operation_id":"async-op-1"}""",
                requiresFollowUpThought = false,
            )

            override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
                ActionOutcome(
                    statusSummary = "Async operation started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                    asyncWait = AsyncActionWait(
                        handles = listOf(
                            AsyncActionHandle(
                                providerType = "test_async",
                                providerId = "provider-1",
                                operationId = "async-op-1",
                                resumeMode = AsyncResumeMode.POLL,
                                pollAfterMs = 25,
                                timeoutAt = Instant.now().plusSeconds(5),
                            )
                        ),
                        summary = "Waiting for async test provider.",
                    ),
                )
        }
        val contactPlugin = ContactUserActionPlugin(
            conversationOutput = RoutedConversationOutputGateway(
                fallbackOutput = { text -> outputs += text }
            )
        )
        val registry = ActionRegistry.fromPlugins(listOf(contactPlugin, asyncPlugin))
        return MotorCortex(registry)
    }

    private suspend fun waitForStatus(
        manager: DurableWorkRuntime,
        workItemId: String,
        status: WorkItemStatus,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.workItemStatus(workItemId)?.workItem?.status == status) {
                return
            }
            delay(10)
        }
        val state = manager.workItemStatus(workItemId)
        fail(
            "Timed out waiting for status=$status, actual=${state?.workItem?.status}, " +
                "steps=${state?.workItem?.plan?.steps?.map { "${it.id}:${it.status}" }}"
        )
    }

    private class QueueSignalSource : SignalSource {
        private val signals = Channel<Signal>(Channel.UNLIMITED)

        fun offer(signal: Signal) {
            signals.trySend(signal).getOrThrow()
        }

        fun offer(cue: DurableWorkCue) {
            signals.trySend(CognitiveSignal.StimulusReceived(cue.toStimulus())).getOrThrow()
        }

        override suspend fun nextSignal(): Signal = signals.receive()
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class StubAsyncOperationProvider : AsyncOperationProvider {
        override val providerType: String = "test_async"
        private val statusesByOperation = mutableMapOf<String, ArrayDeque<AsyncOperationStatus>>()

        fun enqueue(operationId: String, statuses: List<AsyncOperationStatus>) {
            statusesByOperation[operationId] = ArrayDeque(statuses)
        }

        override suspend fun poll(handle: AsyncActionHandle): AsyncOperationStatus {
            val queue = statusesByOperation[handle.operationId]
            if (queue.isNullOrEmpty()) {
                return AsyncOperationStatus.Pending("still pending", nextPollAfterMs = 25)
            }
            return if (queue.size > 1) {
                queue.removeFirst()
            } else {
                queue.first()
            }
        }
    }
}
