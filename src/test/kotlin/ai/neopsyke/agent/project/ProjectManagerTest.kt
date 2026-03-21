package ai.neopsyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.actions.async.AsyncActionHandle
import ai.neopsyke.agent.actions.async.AsyncActionWait
import ai.neopsyke.agent.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.actions.async.AsyncOperationEventStatus
import ai.neopsyke.agent.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.actions.async.AsyncResumeMode
import ai.neopsyke.agent.cortex.sensory.ProjectSignal
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.support.RecordingInstrumentation
import java.nio.file.Files
import java.time.Instant
import java.time.ZonedDateTime
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.PROJECT),
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
    fun `async action outcome blocks step and poll provider restores it to READY`() {
        val root = Files.createTempDirectory("psyke-pm-async-poll")
        try {
            val provider = StubAsyncOperationProvider()
            provider.enqueue(
                operationId = "op-1",
                statuses = listOf(
                    AsyncOperationStatus.Pending("still downloading", nextPollAfterMs = 25),
                    AsyncOperationStatus.Succeeded("download complete"),
                )
            )
            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = ProjectStore(root),
                planner = DeterministicProjectPlanner(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager.start(testScope())
            val projectId = manager.createProject("Wait for async completion")
            val work = manager.nextWorkFromSignal(assertIs<ProjectSignal.WorkReady>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-1"),
                observedEvidence = false,
            )
            manager.finalizeProjectCycle(work.rootInputId)

            waitUntil {
                val state = manager.projectStatus(projectId)
                state?.project?.status == ProjectStatus.ACTIVE &&
                    state.project.plan.steps.firstOrNull()?.status == StepStatus.READY
            }

            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertTrue(state.project.plan.steps.first().notes.contains("async_status=succeeded"))
            assertTrue(signals.any { it is ProjectSignal.WorkReady && it.projectId == projectId })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `project WAITING outcome without async handles is rejected as contract violation`() {
        val root = Files.createTempDirectory("psyke-pm-invalid-wait")
        try {
            val instrumentation = RecordingInstrumentation()
            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager = ProjectManager(
                config = testConfig(root),
                store = ProjectStore(root),
                planner = DeterministicProjectPlanner(),
                instrumentation = instrumentation,
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager.start(testScope())
            val projectId = manager.createProject("Reject invalid waiting outcome")
            val work = manager.nextWorkFromSignal(assertIs<ProjectSignal.WorkReady>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = ActionOutcome(
                    statusSummary = "Async operation allegedly started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                ),
                observedEvidence = false,
            )
            manager.finalizeProjectCycle(work.rootInputId)

            val state = manager.projectStatus(projectId)
            assertNotNull(state)
            assertEquals(ProjectStatus.ACTIVE, state.project.status)
            assertEquals(StepStatus.READY, state.project.plan.steps.first().status)
            assertNull(state.project.plan.steps.first().waitCondition)
            assertEquals(1, state.project.plan.steps.first().attempts)
            assertTrue(
                signals.any {
                    it is ProjectSignal.WorkReady &&
                        it.projectId == projectId &&
                        it.stepId == "step-1"
                }
            )
            assertTrue(
                instrumentation.events.any {
                    it.type == "warning" &&
                        (it.data["message"] as? String)?.contains("WAITING without async handles") == true
                }
            )

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `async poll wait is restored on restart and unblocks step`() {
        val root = Files.createTempDirectory("psyke-pm-async-poll-restore")
        try {
            val store = ProjectStore(root)
            val provider = StubAsyncOperationProvider()
            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Pending("queued")))
            val manager1 = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicProjectPlanner(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val projectId = manager1.createProject("Restore async poll wait")
            val work = manager1.nextWorkFromSignal(ProjectSignal.WorkReady(projectId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-restore"),
                observedEvidence = false,
            )
            manager1.finalizeProjectCycle(work.rootInputId)
            manager1.stop()

            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Succeeded("restored completion")))
            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager2 = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager2.start(testScope())

            waitUntil {
                val state = manager2.projectStatus(projectId)
                state?.project?.status == ProjectStatus.ACTIVE &&
                    state.project.plan.steps.firstOrNull()?.status == StepStatus.READY
            }

            assertTrue(signals.any { it is ProjectSignal.WorkReady && it.projectId == projectId })
            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `async event wait is restored on restart and external event unblocks step`() {
        val root = Files.createTempDirectory("psyke-pm-async-event-restore")
        try {
            val store = ProjectStore(root)
            val provider = StubAsyncOperationProvider()
            val manager1 = ProjectManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicProjectPlanner(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val projectId = manager1.createProject("Restore async event wait")
            val work = manager1.nextWorkFromSignal(ProjectSignal.WorkReady(projectId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "evt-1", resumeMode = AsyncResumeMode.EVENT, correlationKey = "corr-1"),
                observedEvidence = false,
            )
            manager1.finalizeProjectCycle(work.rootInputId)
            manager1.stop()

            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager2 = ProjectManager(
                config = testConfig(root),
                store = store,
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager2.start(testScope())
            val matched = manager2.notifyAsyncOperationEvent(
                AsyncOperationEvent(
                    providerType = "test_async",
                    providerId = "provider-1",
                    correlationKey = "corr-1",
                    status = AsyncOperationEventStatus.SUCCEEDED,
                    summary = "event completed",
                )
            )

            assertEquals(1, matched)
            waitUntil {
                val state = manager2.projectStatus(projectId)
                state?.project?.status == ProjectStatus.ACTIVE &&
                    state.project.plan.steps.firstOrNull()?.status == StepStatus.READY
            }

            val state = manager2.projectStatus(projectId)
            assertNotNull(state)
            assertTrue(state.project.plan.steps.first().notes.contains("event completed"))
            assertTrue(signals.any { it is ProjectSignal.WorkReady && it.projectId == projectId })

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `finalized project cycle writes workspace context scratch and artifact`() {
        val root = Files.createTempDirectory("psyke-pm-workspace")
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
            val id = manager.createProject("Document workspace artifacts")
            val work = manager.nextWorkFromSignal(assertIs<ProjectSignal.WorkReady>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = PendingAction(
                    id = 2L,
                    urgency = Urgency.MEDIUM,
                    type = ActionType.REFLECT,
                    payload = """{"note":"done"}""",
                    summary = "done",
                    rootInputId = work.rootInputId,
                    conversationContext = ConversationContext.default(),
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.PROJECT),
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeProjectCycle(work.rootInputId)

            val workspace = root.resolve(id).resolve(ProjectStore.WORKSPACE_DIR)
            val context = workspace.resolve("context.md")
            val scratch = workspace.resolve("scratch.md")
            val artifacts = workspace.resolve("artifacts").resolve(work.stepId)

            assertTrue(Files.exists(context))
            assertTrue(Files.readString(context).contains("Latest Cycle"))
            assertTrue(Files.readString(context).contains("completed"))

            assertTrue(Files.exists(scratch))
            assertTrue(Files.readString(scratch).contains("root_input_id: ${work.rootInputId}"))
            assertTrue(Files.readString(scratch).contains("result: completed"))

            val artifactFiles = Files.list(artifacts).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
            assertEquals(1, artifactFiles.size)
            val artifactContent = Files.readString(artifacts.resolve(artifactFiles.single()))
            assertTrue(artifactContent.contains("# Project Cycle"))
            assertTrue(artifactContent.contains("step_id: ${work.stepId}"))
            assertTrue(artifactContent.contains("completed"))

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
    fun `startup prunes expired completed projects but keeps recent and active ones`() {
        val root = Files.createTempDirectory("psyke-pm-prune")
        try {
            val store = ProjectStore(root)
            val oldCompletion = Instant.now().minusSeconds(3 * 24 * 60 * 60)
            val recentCompletion = Instant.now()

            seedStoredProject(
                store = store,
                projectId = "old-completed",
                status = ProjectStatus.COMPLETED,
                stepStatus = StepStatus.DONE,
                completedAt = oldCompletion,
                lastWorkedAt = oldCompletion,
            )
            seedStoredProject(
                store = store,
                projectId = "recent-completed",
                status = ProjectStatus.COMPLETED,
                stepStatus = StepStatus.DONE,
                completedAt = recentCompletion,
                lastWorkedAt = recentCompletion,
            )
            seedStoredProject(
                store = store,
                projectId = "active-project",
                status = ProjectStatus.ACTIVE,
                stepStatus = StepStatus.READY,
                completedAt = null,
                lastWorkedAt = recentCompletion,
            )

            val manager = ProjectManager(
                config = testConfig(root).copy(completedProjectRetentionDays = 1),
                store = store,
            )
            manager.start(testScope())

            assertFalse(Files.exists(root.resolve("old-completed")))
            assertNotNull(manager.projectStatus("recent-completed"))
            assertNotNull(manager.projectStatus("active-project"))

            manager.stop()
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

    @Test
    fun `condition check wait is restored on restart and retry timeout requeues the step`() {
        val root = Files.createTempDirectory("psyke-pm-restore-condition-check")
        try {
            val store = ProjectStore(root)
            val manager1 = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicProjectPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createProject("Wait for condition check")
            manager1.applyEventExternal(
                id,
                ProjectEvent.StepBlocked(
                    projectId = id,
                    stepId = "step-1",
                    waitCondition = WaitCondition(
                        type = WaitConditionType.CONDITION_CHECK,
                        params = mapOf("check" to "mailbox_empty"),
                        registeredAt = Instant.now(),
                        timeoutAt = Instant.now().plusMillis(150),
                        onTimeout = TimeoutAction.RETRY,
                    )
                )
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager2 = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.projectStatus(id)?.project?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    manager2.projectStatus(id)?.project?.plan?.steps?.firstOrNull()?.waitCondition == null &&
                    signals.any { it is ProjectSignal.WorkReady && it.projectId == id && it.stepId == "step-1" }
            }

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `external event wait is restored on restart and retry timeout requeues the step`() {
        val root = Files.createTempDirectory("psyke-pm-restore-external-event")
        try {
            val store = ProjectStore(root)
            val manager1 = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicProjectPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createProject("Wait for external event")
            manager1.applyEventExternal(
                id,
                ProjectEvent.StepBlocked(
                    projectId = id,
                    stepId = "step-1",
                    waitCondition = WaitCondition(
                        type = WaitConditionType.EXTERNAL_EVENT,
                        params = mapOf("event_key" to "calendar_update"),
                        registeredAt = Instant.now(),
                        timeoutAt = Instant.now().plusMillis(150),
                        onTimeout = TimeoutAction.RETRY,
                    )
                )
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<ProjectSignal>()
            val manager2 = ProjectManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                signalEmitter = { signal -> if (signal is ProjectSignal) signals += signal },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.projectStatus(id)?.project?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    manager2.projectStatus(id)?.project?.plan?.steps?.firstOrNull()?.waitCondition == null &&
                    signals.any { it is ProjectSignal.WorkReady && it.projectId == id && it.stepId == "step-1" }
            }

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cron schedule is restored on restart`() {
        val root = Files.createTempDirectory("psyke-pm-restore-cron")
        try {
            val store = ProjectStore(root)
            val manager1 = ProjectManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicProjectPlanner(),
            )
            manager1.start(testScope())
            val future = ZonedDateTime.now().plusMinutes(2)
            val cronExpression = "${future.minute} ${future.hour} * * *"
            val id = manager1.createProject(
                instruction = "Wake me on a cron schedule",
                cronExpression = cronExpression,
            )
            manager1.stop()

            val manager2 = ProjectManager(
                config = testConfig(root),
                store = store,
            )
            manager2.start(testScope())

            val restoredSchedules = timerSchedulerCronSchedules(manager2)
            assertEquals(cronExpression, restoredSchedules[id])

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

    private fun projectAction(rootInputId: String) = PendingAction(
        id = 99L,
        urgency = Urgency.MEDIUM,
        type = ActionType.REFLECT,
        payload = """{"summary":"async start","keywords":["async"]}""",
        summary = "start async operation",
        rootInputId = rootInputId,
        conversationContext = ConversationContext.default(),
        origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.PROJECT),
    )

    private fun asyncWaitingOutcome(
        operationId: String,
        resumeMode: AsyncResumeMode = AsyncResumeMode.POLL,
        correlationKey: String? = null,
    ) = ActionOutcome(
        statusSummary = "Async operation started.",
        executionStatus = ActionExecutionStatus.WAITING,
        asyncWait = AsyncActionWait(
            handles = listOf(
                AsyncActionHandle(
                    providerType = "test_async",
                    providerId = "provider-1",
                    operationId = operationId,
                    resumeMode = resumeMode,
                    pollAfterMs = 25,
                    timeoutAt = Instant.now().plusSeconds(5),
                    correlationKey = correlationKey,
                )
            ),
            summary = "Waiting for async test provider.",
        ),
    )

    private fun seedStoredProject(
        store: ProjectStore,
        projectId: String,
        status: ProjectStatus,
        stepStatus: StepStatus,
        completedAt: Instant?,
        lastWorkedAt: Instant?,
    ) {
        val workspace = store.createWorkspace(projectId)
        val createdAt = completedAt ?: lastWorkedAt ?: Instant.now()
        val state = ProjectState(
            project = Project(
                id = projectId,
                title = projectId,
                instruction = "instruction for $projectId",
                status = status,
                priority = ProjectPriority.MEDIUM,
                plan = ProjectPlan(
                    steps = listOf(
                        PlanStep(
                            id = "step-1",
                            description = "Step 1",
                            status = stepStatus,
                            acceptanceCriteria = "done",
                            completedAt = completedAt,
                        )
                    ),
                    generatedAt = createdAt,
                ),
                completionCriteria = "done",
                createdAt = createdAt,
                lastWorkedAt = lastWorkedAt,
                workspacePath = workspace,
            ),
            eventCount = 1,
        )
        store.saveProjectState(projectId, state)
        store.eventLog(projectId).append(
            ProjectEvent.Created(
                projectId = projectId,
                title = projectId,
                instruction = state.project.instruction,
                priority = ProjectPriority.MEDIUM,
                completionCriteria = "done",
                timestamp = createdAt,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun timerSchedulerCronSchedules(manager: ProjectManager): Map<String, String> {
        val timerField = ProjectManager::class.java.getDeclaredField("timerScheduler")
        timerField.isAccessible = true
        val timer = timerField.get(manager) ?: error("TimerScheduler was not initialized")
        val cronField = timer.javaClass.getDeclaredField("cronSchedules")
        cronField.isAccessible = true
        return (cronField.get(timer) as ConcurrentHashMap<String, String>).toMap()
    }

    private class StubAsyncOperationProvider : AsyncOperationProvider {
        override val providerType: String = "test_async"
        private val statusesByOperation = ConcurrentHashMap<String, ArrayDeque<AsyncOperationStatus>>()

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
