package ai.neopsyke.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.actions.async.AsyncActionHandle
import ai.neopsyke.agent.actions.async.AsyncActionWait
import ai.neopsyke.agent.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.actions.async.AsyncResumeMode
import ai.neopsyke.agent.actions.builtin.ContactUserActionPlugin
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.actions.websearch.WebSearchResult
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.project.DeterministicProjectPlanner
import ai.neopsyke.agent.project.PlanStep
import ai.neopsyke.agent.project.Project
import ai.neopsyke.agent.project.ProjectConfig
import ai.neopsyke.agent.project.GoalManager
import ai.neopsyke.agent.project.ProjectPriority
import ai.neopsyke.agent.project.ProjectState
import ai.neopsyke.agent.project.ProjectStatus
import ai.neopsyke.agent.project.ProjectStepVerification
import ai.neopsyke.agent.project.ProjectStepVerdict
import ai.neopsyke.agent.project.ProjectStepVerifier
import ai.neopsyke.agent.project.ProjectStore
import ai.neopsyke.agent.project.TimeoutAction
import ai.neopsyke.agent.project.WaitCondition
import ai.neopsyke.agent.project.WaitConditionType
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
    fun `work-ready signal executes through ego and completes project`() = runBlocking {
        val root = Files.createTempDirectory("psyke-ego-project-complete")
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
            projects = ProjectConfig(enabled = true, workspaceRoot = root, actionsPerCycle = 2),
        )
        val manager = GoalManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            instrumentation = instrumentation,
            cueEmitter = source::offer,
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
            source.offer(RuntimeControlSignal.ExitRequested("test"))
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
        val manager = GoalManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
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
            source.offer(RuntimeControlSignal.ExitRequested("test"))
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
        val manager = GoalManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
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
                action: ai.neopsyke.agent.model.PendingAction,
                outcome: ai.neopsyke.agent.model.ActionOutcome,
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
        val manager = GoalManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            verifier = verifier,
            instrumentation = instrumentation,
            cueEmitter = source::offer,
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
            source.offer(RuntimeControlSignal.ExitRequested("test"))
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
                action: ai.neopsyke.agent.model.PendingAction,
                outcome: ai.neopsyke.agent.model.ActionOutcome,
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
        val manager = GoalManager(
            config = config.projects,
            store = ProjectStore(root),
            planner = DeterministicProjectPlanner(),
            verifier = verifier,
            instrumentation = instrumentation,
            cueEmitter = source::offer,
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
            source.offer(RuntimeControlSignal.ExitRequested("test"))
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
        manager: GoalManager,
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

        fun offer(cue: GoalRuntimeCue) {
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
