package psyke.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.ActionRegistry
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.NoopReflectionMemoryRecorder
import psyke.agent.actions.async.AsyncActionHandle
import psyke.agent.actions.async.AsyncActionWait
import psyke.agent.actions.async.AsyncOperationProvider
import psyke.agent.actions.async.AsyncOperationRegistry
import psyke.agent.actions.async.AsyncOperationStatus
import psyke.agent.actions.async.AsyncResumeMode
import psyke.agent.actions.builtin.ContactUserActionPlugin
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.config.AgentConfig
import psyke.agent.config.PlannerConfig
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.cortex.sensory.ProjectSignal
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.cortex.sensory.SensorySignal
import psyke.agent.cortex.sensory.Signal
import psyke.agent.cortex.sensory.SignalSource
import psyke.agent.ego.Ego
import psyke.agent.model.ActionType
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionExecutionStatus
import psyke.agent.model.ConversationContext
import psyke.agent.model.EgoDecision
import psyke.agent.model.EgoTrigger
import psyke.agent.model.PlannerContext
import psyke.agent.model.PendingAction
import psyke.agent.model.Urgency
import psyke.agent.project.DeterministicProjectPlanner
import psyke.agent.project.PlanStep
import psyke.agent.project.Project
import psyke.agent.project.ProjectConfig
import psyke.agent.project.ProjectManager
import psyke.agent.project.ProjectPriority
import psyke.agent.project.ProjectState
import psyke.agent.project.ProjectStatus
import psyke.agent.project.ProjectStepVerification
import psyke.agent.project.ProjectStepVerdict
import psyke.agent.project.ProjectStepVerifier
import psyke.agent.project.ProjectStore
import psyke.agent.project.TimeoutAction
import psyke.agent.project.WaitCondition
import psyke.agent.project.WaitConditionType
import psyke.agent.superego.Superego
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import psyke.support.buildTestEgo
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class EgoProjectIntegrationTest {

    @Test
    fun `work-ready signal executes through ego and completes project`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-project-complete")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
            projects = ProjectConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2),
        )
        val manager = ProjectManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            instrumentation = instrumentation,
            signalEmitter = source::offer,
        )
        val scope = testScope()
        manager.start(scope)
        val agent = buildTestEgo(
            planner = projectOnlyPlanner("project done"),
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = buildMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
            projectsGateway = manager,
        )

        val loop = launch { agent.runInteractive() }
        try {
            val projectId = manager.createProject("Ship the report", "Report Project", ProjectPriority.HIGH)
            waitForStatus(manager, projectId, ProjectStatus.COMPLETED)
            source.offer(SensorySignal.ExitRequested("test"))
            loop.join()

            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertEquals(ProjectStatus.COMPLETED, state.project.status)
            assertEquals(listOf("ego> project done"), outputs)
            assertTrue(
                instrumentation.events.any {
                    it.type == "project_step_completed" && it.data["success"] == true
                }
            )
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `async project action waits through projects runtime and resumes to completion`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-project-async")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
            projects = ProjectConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2, conditionCheckIntervalMs = 25),
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
        val manager = ProjectManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            instrumentation = instrumentation,
            signalEmitter = source::offer,
        )
        val scope = testScope()
        manager.start(scope)
        var startedAsync = false
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.ProjectWork -> {
                        if (!startedAsync) {
                            startedAsync = true
                            EgoDecision.ProposeAction(
                                urgency = Urgency.MEDIUM,
                                actionType = ActionType("async_test"),
                                payload = """{"operation_id":"async-op-1"}""",
                                summary = "start async test operation"
                            )
                        } else {
                            EgoDecision.ProposeAction(
                                urgency = Urgency.MEDIUM,
                                actionType = ActionType.CONTACT_USER,
                                payload = "async project done",
                                summary = "report completion"
                            )
                        }
                    }

                    else -> EgoDecision.Noop("ignore non-project work")
                }
        }
        val agent = buildTestEgo(
            planner = planner,
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = buildAsyncMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
            projectsGateway = manager,
        )

        val loop = launch { agent.runInteractive() }
        try {
            val projectId = manager.createProject("Use async action in project")
            waitForStatus(manager, projectId, ProjectStatus.COMPLETED)
            source.offer(SensorySignal.ExitRequested("test"))
            loop.join()

            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertEquals(ProjectStatus.COMPLETED, state.project.status)
            assertTrue(state.project.plan.steps.first().notes.contains("async_status=succeeded"))
            assertEquals(listOf("ego> async project done"), outputs)
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `project action denial is routed back into retry and eventually completes`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-project-retry")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 12, maxThoughtPasses = 2),
            projects = ProjectConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2),
        )
        val manager = ProjectManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            instrumentation = instrumentation,
            signalEmitter = source::offer,
        )
        val superegoLlm = StubChatModelClient().apply {
            enqueueRawResponse("""{"allow":false,"reason":"not yet","reason_code":"TEMP_RETRY"}""")
            enqueueRawResponse("""{"allow":true}""")
        }
        val actionPayloads = ArrayDeque(listOf("first try", "second try"))
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.ProjectWork -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.CONTACT_USER,
                        payload = actionPayloads.removeFirst(),
                        summary = "project step"
                    )
                    else -> EgoDecision.Noop("ignore non-project work")
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
                projectsGateway = manager,
            ).runInteractive()
        }
        val scope = testScope()
        manager.start(scope)
        try {
            val projectId = manager.createProject("Retry until approved")
            waitForStatus(manager, projectId, ProjectStatus.COMPLETED)
            source.offer(SensorySignal.ExitRequested("test"))
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
    fun `project action budget yields and resumes same in-progress step`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-project-budget")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 12, maxThoughtPasses = 2),
            projects = ProjectConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 1),
        )
        var verifierCalls = 0
        val verifier = object : ProjectStepVerifier {
            override fun evaluate(
                project: Project,
                step: PlanStep,
                action: psyke.agent.model.PendingAction,
                outcome: psyke.agent.model.ActionOutcome,
                observedEvidence: Boolean,
            ): ProjectStepVerification {
                verifierCalls += 1
                return if (verifierCalls == 1) {
                    ProjectStepVerification(ProjectStepVerdict.CONTINUE, "keep going")
                } else {
                    ProjectStepVerification(ProjectStepVerdict.PASS, "done")
                }
            }
        }
        val manager = ProjectManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            verifier = verifier,
            instrumentation = instrumentation,
            signalEmitter = source::offer,
        )
        val actionPayloads = ArrayDeque(listOf("cycle one", "cycle two"))
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.ProjectWork -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.CONTACT_USER,
                        payload = actionPayloads.removeFirst(),
                        summary = "project step"
                    )
                    else -> EgoDecision.Noop("ignore non-project work")
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
                projectsGateway = manager,
            ).runInteractive()
        }
        val scope = testScope()
        manager.start(scope)
        try {
            val projectId = manager.createProject("Use multiple cycles")
            waitForStatus(manager, projectId, ProjectStatus.COMPLETED)
            source.offer(SensorySignal.ExitRequested("test"))
            loop.join()

            assertEquals(listOf("ego> cycle one", "ego> cycle two"), outputs)
            assertEquals(2, verifierCalls)
            assertTrue(actionPayloads.isEmpty())
            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertEquals(ProjectStatus.COMPLETED, state.project.status)
        } finally {
            manager.stop()
            loop.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `project blocked by timer resumes through ego and completes`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-project-timer-resume")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 12, maxThoughtPasses = 2),
            projects = ProjectConfig(
                enabled = true,
                workspaceRoot = root,
                actionsPerCycle = 1,
                timerResolutionMs = 50,
            ),
        )
        var verifierCalls = 0
        val verifier = object : ProjectStepVerifier {
            override fun evaluate(
                project: Project,
                step: PlanStep,
                action: psyke.agent.model.PendingAction,
                outcome: psyke.agent.model.ActionOutcome,
                observedEvidence: Boolean,
            ): ProjectStepVerification {
                verifierCalls += 1
                return if (verifierCalls == 1) {
                    ProjectStepVerification(
                        verdict = ProjectStepVerdict.BLOCK,
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
                    ProjectStepVerification(ProjectStepVerdict.PASS, "timer satisfied")
                }
            }
        }
        val manager = ProjectManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            verifier = verifier,
            instrumentation = instrumentation,
            signalEmitter = source::offer,
        )
        val actionPayloads = ArrayDeque(listOf("wait for timer", "timer resumed"))
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.ProjectWork -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.CONTACT_USER,
                        payload = actionPayloads.removeFirst(),
                        summary = "project step"
                    )
                    else -> EgoDecision.Noop("ignore non-project work")
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
                projectsGateway = manager,
            ).runInteractive()
        }
        val scope = testScope()
        manager.start(scope)
        try {
            val projectId = manager.createProject("Pause until timer wakes the step")
            waitForStatus(manager, projectId, ProjectStatus.COMPLETED)
            source.offer(SensorySignal.ExitRequested("test"))
            loop.join()

            assertEquals(listOf("ego> wait for timer", "ego> timer resumed"), outputs)
            assertEquals(2, verifierCalls)
            assertTrue(actionPayloads.isEmpty())
            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertEquals(ProjectStatus.COMPLETED, state.project.status)
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
                    is EgoTrigger.ProjectWork -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.CONTACT_USER,
                        payload = response,
                        summary = "complete project"
                    )
                    else -> EgoDecision.Noop("ignore non-project work")
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
        val contactPlugin = ContactUserActionPlugin(output = { outputs += it })
        val registry = ActionRegistry.fromPlugins(listOf(contactPlugin, asyncPlugin))
        return MotorCortex(registry)
    }

    private suspend fun waitForStatus(
        manager: ProjectManager,
        projectId: String,
        status: ProjectStatus,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.projectStatus(projectId)?.project?.status == status) {
                return
            }
            delay(10)
        }
        val state = manager.projectStatus(projectId)
        fail(
            "Timed out waiting for status=$status, actual=${state?.project?.status}, " +
                "steps=${state?.project?.plan?.steps?.map { "${it.id}:${it.status}" }}"
        )
    }

    private class QueueSignalSource : SignalSource {
        private val signals = Channel<Signal>(Channel.UNLIMITED)

        fun offer(signal: Signal) {
            signals.trySend(signal).getOrThrow()
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
