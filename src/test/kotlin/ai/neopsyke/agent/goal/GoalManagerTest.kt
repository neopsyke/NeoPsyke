package ai.neopsyke.agent.goal

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
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
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

class GoalManagerTest {

    private fun testConfig(root: java.nio.file.Path) = GoalConfig(
        enabled = true,
        workspaceRoot = root,
        maxActiveGoals = 5,
        timerResolutionMs = 100,
        conditionCheckIntervalMs = 100,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `createGoal generates plan persists state and emits work-ready signal`() {
        val root = Files.createTempDirectory("psyke-pm-create")
        try {
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())

            val id = manager.createGoal(
                instruction = "Monitor stock prices daily",
                title = "Stock Monitor",
                priority = GoalPriority.HIGH,
            )

            assertTrue(id.isNotBlank())
            val workReady = assertIs<GoalRuntimeCue>(signals.last())
            assertEquals(id, workReady.goalId)
            val state = manager.goalStatus(id)
            assertNotNull(state)
            assertEquals(GoalStatus.ACTIVE, state.goal.status)
            assertTrue(Files.exists(root.resolve(id).resolve(GoalStore.GOAL_FILE)))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue creates goal session and returns work unit`() {
        val root = Files.createTempDirectory("psyke-pm-work")
        try {
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
            )
            manager.start(testScope())
            val id = manager.createGoal("Persistent task")

            val work = manager.nextWorkFromCue(GoalRuntimeCue(id, "step-1", "test"))
            assertNotNull(work)
            assertEquals(id, work.goalId)
            assertTrue(work.rootInputId.startsWith("goal:$id"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal-origin action outcome completes final step and goal`() {
        val root = Files.createTempDirectory("psyke-pm-complete")
        try {
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
                verifier = DeterministicGoalStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createGoal("Ship release checklist")
            val work = manager.nextWorkFromCue(assertIs<GoalRuntimeCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = PendingAction(
                    id = 1L,
                    urgency = Urgency.MEDIUM,
                    type = ActionType.REFLECT_INTERNAL,
                    payload = """{"note":"done"}""",
                    summary = "done",
                    rootInputId = work.rootInputId,
                    conversationContext = ConversationContext.default(),
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.GOAL),
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeGoalCycle(work.rootInputId)

            val state = manager.goalStatus(id)
            assertNotNull(state)
            assertEquals(GoalStatus.COMPLETED, state.goal.status)

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
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val goalId = manager.createGoal("Wait for async completion")
            val work = manager.nextWorkFromCue(assertIs<GoalRuntimeCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-1"),
                observedEvidence = false,
            )
            manager.finalizeGoalCycle(work.rootInputId)

            waitUntil {
                val state = manager.goalStatus(goalId)
                state?.goal?.status == GoalStatus.ACTIVE &&
                    state.goal.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.goalId == goalId }
            }

            val state = manager.goalStatus(goalId)
            assertNotNull(state)
            assertTrue(state.goal.plan.steps.first().notes.contains("async_status=succeeded"))
            assertTrue(signals.any { it.goalId == goalId })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal WAITING outcome without async handles is rejected as contract violation`() {
        val root = Files.createTempDirectory("psyke-pm-invalid-wait")
        try {
            val instrumentation = RecordingInstrumentation()
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
                instrumentation = instrumentation,
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val goalId = manager.createGoal("Reject invalid waiting outcome")
            val work = manager.nextWorkFromCue(assertIs<GoalRuntimeCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = ActionOutcome(
                    statusSummary = "Async operation allegedly started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                ),
                observedEvidence = false,
            )
            manager.finalizeGoalCycle(work.rootInputId)

            val state = manager.goalStatus(goalId)
            assertNotNull(state)
            assertEquals(GoalStatus.ACTIVE, state.goal.status)
            assertEquals(StepStatus.READY, state.goal.plan.steps.first().status)
            assertNull(state.goal.plan.steps.first().waitCondition)
            assertEquals(1, state.goal.plan.steps.first().attempts)
            assertTrue(
                signals.any {
                                            it.goalId == goalId &&
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
    fun `delete all operation removes all goals and workspaces`() {
        val root = Files.createTempDirectory("psyke-pm-delete-all")
        try {
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
            )
            manager.start(testScope())

            val firstId = manager.createGoal("Delete me first")
            val secondId = manager.createGoal("Delete me second")
            assertTrue(Files.exists(root.resolve(firstId)))
            assertTrue(Files.exists(root.resolve(secondId)))

            val result = manager.executeOperation(GoalOperationRequest(operation = GoalOperation.DELETE_ALL))

            assertTrue(result.success)
            assertEquals("Deleted 2 goals.", result.message)
            assertTrue(manager.allGoals().isEmpty())
            assertFalse(Files.exists(root.resolve(firstId)))
            assertFalse(Files.exists(root.resolve(secondId)))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `async poll wait is restored on restart and unblocks step`() {
        val root = Files.createTempDirectory("psyke-pm-async-poll-restore")
        try {
            val store = GoalStore(root)
            val provider = StubAsyncOperationProvider()
            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Pending("queued")))
            val manager1 = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicGoalPlanner(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val goalId = manager1.createGoal("Restore async poll wait")
            val work = manager1.nextWorkFromCue(GoalRuntimeCue(goalId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-restore"),
                observedEvidence = false,
            )
            manager1.finalizeGoalCycle(work.rootInputId)
            waitUntil {
                val state = manager1.goalStatus(goalId)
                state?.goal?.status == GoalStatus.BLOCKED &&
                    state.goal.plan.steps.firstOrNull()?.status == StepStatus.BLOCKED
            }
            manager1.stop()

            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Succeeded("restored completion")))
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                val state = manager2.goalStatus(goalId)
                state?.goal?.status == GoalStatus.ACTIVE &&
                    state.goal.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.goalId == goalId }
            }

            assertTrue(signals.any { it.goalId == goalId })
            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restored READY step emits work-ready cue on restart`() {
        val root = Files.createTempDirectory("psyke-pm-restore-ready")
        try {
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val goalId = manager1.createGoal("Restore ready step cue")
            manager1.stop()

            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                signals.any {
                    it.goalId == goalId &&
                        it.stepId == "step-1" &&
                        it.reason == "goal_restored_work_ready"
                }
            }

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `async event wait is restored on restart and external event unblocks step`() {
        val root = Files.createTempDirectory("psyke-pm-async-event-restore")
        try {
            val store = GoalStore(root)
            val provider = StubAsyncOperationProvider()
            val manager1 = GoalManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicGoalPlanner(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val goalId = manager1.createGoal("Restore async event wait")
            val work = manager1.nextWorkFromCue(GoalRuntimeCue(goalId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "evt-1", resumeMode = AsyncResumeMode.EVENT, correlationKey = "corr-1"),
                observedEvidence = false,
            )
            manager1.finalizeGoalCycle(work.rootInputId)
            manager1.stop()

            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root),
                store = store,
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
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
                val state = manager2.goalStatus(goalId)
                state?.goal?.status == GoalStatus.ACTIVE &&
                    state.goal.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.goalId == goalId }
            }

            val state = manager2.goalStatus(goalId)
            assertNotNull(state)
            assertTrue(state.goal.plan.steps.first().notes.contains("event completed"))
            assertTrue(signals.any { it.goalId == goalId })

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `finalized goal cycle writes workspace context scratch and artifact`() {
        val root = Files.createTempDirectory("psyke-pm-workspace")
        try {
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
                verifier = DeterministicGoalStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createGoal("Document workspace artifacts")
            val work = manager.nextWorkFromCue(assertIs<GoalRuntimeCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = PendingAction(
                    id = 2L,
                    urgency = Urgency.MEDIUM,
                    type = ActionType.REFLECT_INTERNAL,
                    payload = """{"note":"done"}""",
                    summary = "done",
                    rootInputId = work.rootInputId,
                    conversationContext = ConversationContext.default(),
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.GOAL),
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeGoalCycle(work.rootInputId)

            val workspace = root.resolve(id).resolve(GoalStore.WORKSPACE_DIR)
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
            assertTrue(artifactContent.contains("# Goal Cycle"))
            assertTrue(artifactContent.contains("step_id: ${work.stepId}"))
            assertTrue(artifactContent.contains("completed"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pendingWorkSummary returns empty when no goals exist`() {
        val root = Files.createTempDirectory("psyke-pm-summary")
        try {
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
            )
            manager.start(testScope())
            assertEquals("", manager.pendingWorkSummary())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `max active goals enforced`() {
        val root = Files.createTempDirectory("psyke-pm-limit")
        try {
            val config = testConfig(root).copy(maxActiveGoals = 2)
            val manager = GoalManager(
                config = config,
                store = GoalStore(root),
            )
            manager.start(testScope())

            val id1 = manager.createGoal("Task 1")
            val id2 = manager.createGoal("Task 2")
            val id3 = manager.createGoal("Task 3")

            assertTrue(id1.isNotBlank())
            assertTrue(id2.isNotBlank())
            assertEquals("", id3)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal persists and reloads across manager restarts`() {
        val root = Files.createTempDirectory("psyke-pm-restart")
        try {
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createGoal("Persistent task")
            manager1.stop()

            val manager2 = GoalManager(
                config = testConfig(root),
                store = store,
            )
            manager2.start(testScope())

            val reloaded = manager2.goalStatus(id)
            assertNotNull(reloaded)
            assertTrue(reloaded.goal.instruction.contains("Persistent task"))
            assertEquals(GoalStatus.ACTIVE, reloaded.goal.status)

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup prunes expired completed goals but keeps recent and active ones`() {
        val root = Files.createTempDirectory("psyke-pm-prune")
        try {
            val store = GoalStore(root)
            val oldCompletion = Instant.now().minusSeconds(3 * 24 * 60 * 60)
            val recentCompletion = Instant.now()

            seedStoredProject(
                store = store,
                goalId = "old-completed",
                status = GoalStatus.COMPLETED,
                stepStatus = StepStatus.DONE,
                completedAt = oldCompletion,
                lastWorkedAt = oldCompletion,
            )
            seedStoredProject(
                store = store,
                goalId = "recent-completed",
                status = GoalStatus.COMPLETED,
                stepStatus = StepStatus.DONE,
                completedAt = recentCompletion,
                lastWorkedAt = recentCompletion,
            )
            seedStoredProject(
                store = store,
                goalId = "active-goal",
                status = GoalStatus.ACTIVE,
                stepStatus = StepStatus.READY,
                completedAt = null,
                lastWorkedAt = recentCompletion,
            )

            val manager = GoalManager(
                config = testConfig(root).copy(completedGoalRetentionDays = 1),
                store = store,
            )
            manager.start(testScope())

            assertFalse(Files.exists(root.resolve("old-completed")))
            assertNotNull(manager.goalStatus("recent-completed"))
            assertNotNull(manager.goalStatus("active-goal"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue returns null when goal is missing`() {
        val root = Files.createTempDirectory("psyke-pm-missing")
        try {
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
            )
            manager.start(testScope())
            assertNull(manager.nextWorkFromCue(GoalRuntimeCue("missing", "s1", "test")))
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `suspended goal resume timer is restored on restart and emits work-ready`() {
        val root = Files.createTempDirectory("psyke-pm-restore-suspend")
        try {
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createGoal("Resume me later")
            val resumeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                GoalEvent.Suspended(id, "paused", resumeAt)
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.goalStatus(id)?.goal?.status == GoalStatus.ACTIVE &&
                    signals.any { it.goalId == id }
            }

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `blocked timer wait is restored on restart and goal becomes ACTIVE when timer fires`() {
        val root = Files.createTempDirectory("psyke-pm-restore-wait")
        try {
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createGoal("Wait for timer")
            val wakeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                GoalEvent.StepBlocked(
                    goalId = id,
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

            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.goalStatus(id)?.goal?.status == GoalStatus.ACTIVE &&
                    manager2.goalStatus(id)?.goal?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.goalId == id }
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
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createGoal("Wait for condition check")
            manager1.applyEventExternal(
                id,
                GoalEvent.StepBlocked(
                    goalId = id,
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

            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.goalStatus(id)?.goal?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    manager2.goalStatus(id)?.goal?.plan?.steps?.firstOrNull()?.waitCondition == null &&
                    signals.any { it.goalId == id && it.stepId == "step-1" }
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
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val id = manager1.createGoal("Wait for external event")
            manager1.applyEventExternal(
                id,
                GoalEvent.StepBlocked(
                    goalId = id,
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

            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager2 = GoalManager(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.goalStatus(id)?.goal?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    manager2.goalStatus(id)?.goal?.plan?.steps?.firstOrNull()?.waitCondition == null &&
                    signals.any { it.goalId == id && it.stepId == "step-1" }
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
            val store = GoalStore(root)
            val manager1 = GoalManager(
                config = testConfig(root),
                store = store,
                planner = DeterministicGoalPlanner(),
            )
            manager1.start(testScope())
            val future = ZonedDateTime.now().plusMinutes(2)
            val cronExpression = "${future.minute} ${future.hour} * * *"
            val id = manager1.createGoal(
                instruction = "Wake me on a cron schedule",
                cronExpression = cronExpression,
            )
            manager1.stop()

            val manager2 = GoalManager(
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

    @Test
    fun `executeOperation rejects invalid cron expression on create`() {
        val root = Files.createTempDirectory("psyke-pm-invalid-cron")
        try {
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
            )
            manager.start(testScope())

            val result = manager.executeOperation(
                GoalOperationRequest(
                    operation = GoalOperation.CREATE,
                    title = "Bad schedule",
                    instruction = "Run on a bad schedule",
                    cronExpression = "bad cron",
                )
            )

            assertFalse(result.success)
            assertTrue(result.message.contains("valid 5-field cron_expression", ignoreCase = true))
            assertTrue(manager.allGoals().isEmpty())

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createGoal with cron schedule does not emit immediate work-ready signal`() {
        val root = Files.createTempDirectory("psyke-pm-cron-idle-create")
        try {
            val signals = CopyOnWriteArrayList<GoalRuntimeCue>()
            val manager = GoalManager(
                config = testConfig(root),
                store = GoalStore(root),
                planner = DeterministicGoalPlanner(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val future = ZonedDateTime.now().plusHours(2).withSecond(0).withNano(0)
            val cronExpression = "${future.minute} ${future.hour} * * *"

            val id = manager.createGoal(
                instruction = "Check the weather and remind me on schedule",
                title = "Weather reminder",
                cronExpression = cronExpression,
            )

            assertTrue(id.isNotBlank())
            assertTrue(signals.none { it.goalId == id })
            val state = manager.goalStatus(id)
            assertNotNull(state)
            assertEquals(GoalStatus.ACTIVE, state.goal.status)
            assertEquals(cronExpression, state.goal.cronExpression)
            assertEquals(StepStatus.READY, state.goal.plan.steps.first().status)

            manager.stop()
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
        type = ActionType.REFLECT_INTERNAL,
        payload = """{"summary":"async start","keywords":["async"]}""",
        summary = "start async operation",
        rootInputId = rootInputId,
        conversationContext = ConversationContext.default(),
        origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.GOAL),
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
        store: GoalStore,
        goalId: String,
        status: GoalStatus,
        stepStatus: StepStatus,
        completedAt: Instant?,
        lastWorkedAt: Instant?,
    ) {
        val workspace = store.createWorkspace(goalId)
        val createdAt = completedAt ?: lastWorkedAt ?: Instant.now()
        val state = GoalState(
            goal = Goal(
                id = goalId,
                title = goalId,
                instruction = "instruction for $goalId",
                status = status,
                priority = GoalPriority.MEDIUM,
                plan = GoalPlan(
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
        store.saveGoalState(goalId, state)
        store.goalEventLog(goalId).append(
            GoalEvent.Created(
                goalId = goalId,
                title = goalId,
                instruction = state.goal.instruction,
                priority = GoalPriority.MEDIUM,
                completionCriteria = "done",
                timestamp = createdAt,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun timerSchedulerCronSchedules(manager: GoalManager): Map<String, String> {
        val timerField = GoalManager::class.java.getDeclaredField("timerScheduler")
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
