package ai.neopsyke.agent.durablework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionHandle
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEventStatus
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncResumeMode
import ai.neopsyke.agent.cortex.sensory.DurableWorkCue
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.model.GroundingMetadata
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

class DurableWorkRuntimeTest {

    private fun testConfig(root: java.nio.file.Path) = DurableWorkConfig(
        enabled = true,
        workspaceRoot = root,
        maxActiveWorkItems = 5,
        timerResolutionMs = 100,
        conditionCheckIntervalMs = 100,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `createWorkItem generates plan persists state and emits work-ready signal`() {
        val root = Files.createTempDirectory("psyke-pm-create")
        try {
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Monitor stock prices daily",
                title = "Stock Monitor",
                priority = WorkItemPriority.HIGH,
            )

            assertTrue(id.isNotBlank())
            val workReady = assertIs<DurableWorkCue>(signals.last())
            assertEquals(id, workReady.workItemId)
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.ACTIVE, state!!.workItem.status)
            assertTrue(Files.exists(root.resolve(id).resolve(WorkItemStore.GOAL_FILE)))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue creates goal session and returns work unit`() {
        val root = Files.createTempDirectory("psyke-pm-work")
        try {
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Persistent task")

            val work = manager.nextWorkFromCue(DurableWorkCue(id, "step-1", "test"))
            assertNotNull(work)
            assertEquals(id, work.workItemId)
            assertTrue(work.rootInputId.startsWith("work:$id"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue acquires lease and coalesces duplicate wakes`() {
        val root = Files.createTempDirectory("psyke-pm-stable-root")
        try {
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Persistent task")

            val first = manager.nextWorkFromCue(DurableWorkCue(id, "step-1", "test"))
            assertNotNull(first)
            assertEquals("work:$id:step-1", first.rootInputId)

            // Second wake while leased should be coalesced (returns null)
            val second = manager.nextWorkFromCue(DurableWorkCue(id, "step-1", "resume"))
            assertNull(second, "Duplicate wake should be coalesced while item is leased")

            // Verify the pending wake reason was captured
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertTrue(state.workItem.pendingWakeReasons.contains("resume"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `goal-origin action outcome completes final step and goal`() {
        val root = Files.createTempDirectory("psyke-pm-complete")
        try {
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                verifier = DeterministicWorkStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Ship release checklist")
            val work = manager.nextWorkFromCue(assertIs<DurableWorkCue>(signals.last()))
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
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.DURABLE_WORK),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeDurableWorkCycle(work.rootInputId)

            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state!!.workItem.status)

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
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val workItemId = manager.createWorkItem("Wait for async completion")
            val work = manager.nextWorkFromCue(assertIs<DurableWorkCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-1"),
                observedEvidence = false,
            )
            manager.finalizeDurableWorkCycle(work.rootInputId)

            waitUntil {
                val state = manager.workItemStatus(workItemId)
                state!!.workItem?.status == WorkItemStatus.ACTIVE &&
                    state!!.workItem.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.workItemId == workItemId }
            }

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertTrue(state!!.workItem.plan.steps.first().notes.contains("async_status=succeeded"))
            assertTrue(signals.any { it.workItemId == workItemId })

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
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                instrumentation = instrumentation,
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val workItemId = manager.createWorkItem("Reject invalid waiting outcome")
            val work = manager.nextWorkFromCue(assertIs<DurableWorkCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = ActionOutcome(
                    statusSummary = "Async operation allegedly started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                ),
                observedEvidence = false,
            )
            manager.finalizeDurableWorkCycle(work.rootInputId)

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.ACTIVE, state!!.workItem.status)
            assertEquals(StepStatus.READY, state!!.workItem.plan.steps.first().status)
            assertNull(state!!.workItem.plan.steps.first().waitCondition)
            assertEquals(1, state!!.workItem.plan.steps.first().attempts)
            assertTrue(
                signals.any {
                                            it.workItemId == workItemId &&
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
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
            )
            manager.start(testScope())

            val firstId = manager.createWorkItem("Delete me first")
            val secondId = manager.createWorkItem("Delete me second")
            assertTrue(Files.exists(root.resolve(firstId)))
            assertTrue(Files.exists(root.resolve(secondId)))

            val result = manager.executeOperation(DurableWorkOperationRequest(operation = DurableWorkOperation.DELETE_ALL))

            assertTrue(result.success)
            assertEquals("Deleted 2 work items.", result.message)
            assertTrue(manager.allWorkItems().isEmpty())
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
            val store = WorkItemStore(root)
            val provider = StubAsyncOperationProvider()
            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Pending("queued")))
            val manager1 = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Restore async poll wait")
            val work = manager1.nextWorkFromCue(DurableWorkCue(workItemId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-restore"),
                observedEvidence = false,
            )
            manager1.finalizeDurableWorkCycle(work.rootInputId)
            waitUntil {
                val state = manager1.workItemStatus(workItemId)
                state!!.workItem?.status == WorkItemStatus.BLOCKED &&
                    state!!.workItem.plan.steps.firstOrNull()?.status == StepStatus.BLOCKED
            }
            manager1.stop()

            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Succeeded("restored completion")))
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                val state = manager2.workItemStatus(workItemId)
                state!!.workItem?.status == WorkItemStatus.ACTIVE &&
                    state!!.workItem.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.workItemId == workItemId }
            }

            assertTrue(signals.any { it.workItemId == workItemId })
            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restored READY step emits work-ready cue on restart`() {
        val root = Files.createTempDirectory("psyke-pm-restore-ready")
        try {
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Restore ready step cue")
            manager1.stop()

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                signals.any {
                    it.workItemId == workItemId &&
                        it.stepId == "step-1" &&
                        it.reason == "work_item_restored_ready"
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
            val store = WorkItemStore(root)
            val provider = StubAsyncOperationProvider()
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Restore async event wait")
            val work = manager1.nextWorkFromCue(DurableWorkCue(workItemId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "evt-1", resumeMode = AsyncResumeMode.EVENT, correlationKey = "corr-1"),
                observedEvidence = false,
            )
            manager1.finalizeDurableWorkCycle(work.rootInputId)
            manager1.stop()

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
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
                val state = manager2.workItemStatus(workItemId)
                state!!.workItem?.status == WorkItemStatus.ACTIVE &&
                    state!!.workItem.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.workItemId == workItemId }
            }

            val state = manager2.workItemStatus(workItemId)
            assertNotNull(state)
            assertTrue(state!!.workItem.plan.steps.first().notes.contains("event completed"))
            assertTrue(signals.any { it.workItemId == workItemId })

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `finalized goal cycle writes workspace context scratch and artifact`() {
        val root = Files.createTempDirectory("psyke-pm-workspace")
        try {
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                verifier = DeterministicWorkStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Document workspace artifacts")
            val work = manager.nextWorkFromCue(assertIs<DurableWorkCue>(signals.last()))
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
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.DURABLE_WORK),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeDurableWorkCycle(work.rootInputId)

            val workspace = root.resolve(id).resolve(WorkItemStore.WORKSPACE_DIR)
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
            assertTrue(artifactContent.contains("# Work Item Cycle"))
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
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
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
            val config = testConfig(root).copy(maxActiveWorkItems = 2)
            val manager = DurableWorkRuntime(
                config = config,
                store = WorkItemStore(root),
            )
            manager.start(testScope())

            val id1 = manager.createWorkItem("Task 1")
            val id2 = manager.createWorkItem("Task 2")
            val id3 = manager.createWorkItem("Task 3")

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
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Persistent task")
            manager1.stop()

            val manager2 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
            )
            manager2.start(testScope())

            val reloaded = manager2.workItemStatus(id)
            assertNotNull(reloaded)
            assertTrue(reloaded.workItem.instruction.contains("Persistent task"))
            assertEquals(WorkItemStatus.ACTIVE, reloaded.workItem.status)

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup prunes expired completed goals but keeps recent and active ones`() {
        val root = Files.createTempDirectory("psyke-pm-prune")
        try {
            val store = WorkItemStore(root)
            val oldCompletion = Instant.now().minusSeconds(3 * 24 * 60 * 60)
            val recentCompletion = Instant.now()

            seedStoredProject(
                store = store,
                workItemId = "old-completed",
                status = WorkItemStatus.COMPLETED,
                stepStatus = StepStatus.DONE,
                completedAt = oldCompletion,
                lastWorkedAt = oldCompletion,
            )
            seedStoredProject(
                store = store,
                workItemId = "recent-completed",
                status = WorkItemStatus.COMPLETED,
                stepStatus = StepStatus.DONE,
                completedAt = recentCompletion,
                lastWorkedAt = recentCompletion,
            )
            seedStoredProject(
                store = store,
                workItemId = "active-goal",
                status = WorkItemStatus.ACTIVE,
                stepStatus = StepStatus.READY,
                completedAt = null,
                lastWorkedAt = recentCompletion,
            )

            val manager = DurableWorkRuntime(
                config = testConfig(root).copy(completedWorkItemRetentionDays = 1),
                store = store,
            )
            manager.start(testScope())

            assertFalse(Files.exists(root.resolve("old-completed")))
            assertNotNull(manager.workItemStatus("recent-completed"))
            assertNotNull(manager.workItemStatus("active-goal"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue returns null when goal is missing`() {
        val root = Files.createTempDirectory("psyke-pm-missing")
        try {
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
            )
            manager.start(testScope())
            assertNull(manager.nextWorkFromCue(DurableWorkCue("missing", "s1", "test")))
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `suspended goal resume timer is restored on restart and emits work-ready`() {
        val root = Files.createTempDirectory("psyke-pm-restore-suspend")
        try {
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Resume me later")
            val resumeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                WorkItemEvent.Suspended(id, "paused", resumeAt)
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.workItemStatus(id)?.workItem?.status == WorkItemStatus.ACTIVE &&
                    signals.any { it.workItemId == id }
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
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Wait for timer")
            val wakeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                WorkItemEvent.StepBlocked(
                    workItemId = id,
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

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.workItemStatus(id)?.workItem?.status == WorkItemStatus.ACTIVE &&
                    manager2.workItemStatus(id)?.workItem?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.workItemId == id }
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
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Wait for condition check")
            manager1.applyEventExternal(
                id,
                WorkItemEvent.StepBlocked(
                    workItemId = id,
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

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.workItemStatus(id)?.workItem?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    manager2.workItemStatus(id)?.workItem?.plan?.steps?.firstOrNull()?.waitCondition == null &&
                    signals.any { it.workItemId == id && it.stepId == "step-1" }
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
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Wait for external event")
            manager1.applyEventExternal(
                id,
                WorkItemEvent.StepBlocked(
                    workItemId = id,
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

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                manager2.workItemStatus(id)?.workItem?.plan?.steps?.firstOrNull()?.status == StepStatus.READY &&
                    manager2.workItemStatus(id)?.workItem?.plan?.steps?.firstOrNull()?.waitCondition == null &&
                    signals.any { it.workItemId == id && it.stepId == "step-1" }
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
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val future = ZonedDateTime.now().plusMinutes(2)
            val cronExpression = "${future.minute} ${future.hour} * * *"
            val id = manager1.createWorkItem(
                instruction = "Wake me on a cron schedule",
                cronExpression = cronExpression,
            )
            manager1.stop()

            val manager2 = DurableWorkRuntime(
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
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
            )
            manager.start(testScope())

            val result = manager.executeOperation(
                DurableWorkOperationRequest(
                    operation = DurableWorkOperation.CREATE,
                    title = "Bad schedule",
                    instruction = "Run on a bad schedule",
                    cronExpression = "bad cron",
                )
            )

            assertFalse(result.success)
            assertTrue(result.message.contains("valid 5-field cron_expression", ignoreCase = true))
            assertTrue(manager.allWorkItems().isEmpty())

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem with cron schedule does not emit immediate work-ready signal`() {
        val root = Files.createTempDirectory("psyke-pm-cron-idle-create")
        try {
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val future = ZonedDateTime.now().plusHours(2).withSecond(0).withNano(0)
            val cronExpression = "${future.minute} ${future.hour} * * *"

            val id = manager.createWorkItem(
                instruction = "Check the weather and remind me on schedule",
                title = "Weather reminder",
                cronExpression = cronExpression,
            )

            assertTrue(id.isNotBlank())
            assertTrue(signals.none { it.workItemId == id })
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.ACTIVE, state!!.workItem.status)
            assertEquals(cronExpression, state!!.workItem.cronExpression)
            assertEquals(StepStatus.READY, state!!.workItem.plan.steps.first().status)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cron timer wake on ACTIVE goal with READY step emits work-ready cue`() {
        val root = Files.createTempDirectory("psyke-pm-cron-wake-active")
        try {
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicWorkPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            // Use a far-future cron so it doesn't fire on its own during the test
            val future = ZonedDateTime.now().plusHours(2).withSecond(0).withNano(0)
            val cronExpression = "${future.minute} ${future.hour} * * *"

            val id = manager.createWorkItem(
                instruction = "Send weather report on cron schedule",
                title = "Weather cron test",
                cronExpression = cronExpression,
            )
            assertTrue(id.isNotBlank())
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.ACTIVE, state!!.workItem.status)
            assertEquals(StepStatus.READY, state!!.workItem.plan.steps.first().status)

            // No work-ready cue should have been emitted at creation for a cron goal
            assertTrue(signals.none { it.workItemId == id })

            // Simulate the cron timer firing by invoking onTimerWake reflectively
            val onTimerWake = DurableWorkRuntime::class.java.getDeclaredMethod(
                "onTimerWake", String::class.java, Long::class.javaPrimitiveType
            )
            onTimerWake.isAccessible = true
            onTimerWake.invoke(manager, id, System.currentTimeMillis())

            // Now a work-ready cue should have been emitted
            val cue = signals.firstOrNull { it.workItemId == id }
            assertNotNull(cue, "Expected work-ready cue after cron timer wake on ACTIVE goal")
            assertEquals("cron_wake_active", cue.reason)
            assertEquals(state!!.workItem.plan.steps.first().id, cue.stepId)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `durable work mutating effect intent is recorded confirmed and duplicate dispatch is blocked`() {
        val root = Files.createTempDirectory("psyke-pm-effect-intent")
        try {
            val store = WorkItemStore(root)
            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
                verifier = DeterministicWorkStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Send a durable notification")
            val work = manager.nextWorkFromCue(assertIs<DurableWorkCue>(signals.last()))
            assertNotNull(work)

            val action = PendingAction(
                id = 501L,
                urgency = Urgency.HIGH,
                type = ActionType.CONTACT_USER,
                payload = """{"message":"Hello"}""",
                summary = "send durable notification",
                rootInputId = work.rootInputId,
                conversationContext = ConversationContext.default(),
                origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.DURABLE_WORK),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            )

            val firstGate = manager.beforeActionExecution(action)
            assertTrue(firstGate.allow)
            manager.onActionExecuted(
                action = action,
                outcome = ActionOutcome(
                    statusSummary = "Delivered notification",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )

            val secondGate = manager.beforeActionExecution(action)
            assertFalse(secondGate.allow)
            assertEquals("durable_work_effect_duplicate", secondGate.reasonCode)

            val events = store.workItemEventLog(id).readAll()
            assertTrue(events.any { it is WorkItemEvent.EffectIntentRecorded })
            assertTrue(events.any { it is WorkItemEvent.EffectConfirmed })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `activation journal records boundaries and recovery expires lease after restart`() {
        val root = Files.createTempDirectory("psyke-pm-activation-journal")
        try {
            val store = WorkItemStore(root)
            val manager1 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Recover unfinished activation")
            val work = manager1.nextWorkFromCue(DurableWorkCue(workItemId, "step-1", "test"))
            assertNotNull(work)
            manager1.stop()

            val signals = CopyOnWriteArrayList<DurableWorkCue>()
            val manager2 = DurableWorkRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicWorkPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                signals.any { it.workItemId == workItemId && it.reason == "activation_recovered" }
            }

            val events = store.workItemEventLog(workItemId).readAll()
            assertTrue(events.any { it is WorkItemEvent.ActivationStarted })
            assertTrue(events.any { it is WorkItemEvent.ActivationRecovered })
            assertTrue(events.any { it is WorkItemEvent.LeaseExpired })

            val journal = ActivationJournal(root.resolve("activation-journal.jsonl")).readAll()
            assertTrue(journal.any { it.workItemId == workItemId && it.boundary == ActivationBoundary.STARTED })
            assertTrue(journal.any { it.workItemId == workItemId && it.boundary == ActivationBoundary.STEP_SELECTED })
            assertTrue(journal.any { it.workItemId == workItemId && it.boundary == ActivationBoundary.CONTEXT_MATERIALIZED })
            assertTrue(journal.any { it.workItemId == workItemId && it.boundary == ActivationBoundary.RECOVERED })

            val state = manager2.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemHealth.NEEDS_ATTENTION, state!!.workItem.health)

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
        type = ActionType.REFLECT_INTERNAL,
        payload = """{"summary":"async start","keywords":["async"]}""",
        summary = "start async operation",
        rootInputId = rootInputId,
        conversationContext = ConversationContext.default(),
        origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.DURABLE_WORK),
    groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
        store: WorkItemStore,
        workItemId: String,
        status: WorkItemStatus,
        stepStatus: StepStatus,
        completedAt: Instant?,
        lastWorkedAt: Instant?,
    ) {
        val workspace = store.createWorkspace(workItemId)
        val createdAt = completedAt ?: lastWorkedAt ?: Instant.now()
        val state = WorkItemState(
            workItem = WorkItem(
                id = workItemId,
                title = workItemId,
                instruction = "instruction for $workItemId",
                status = status,
                priority = WorkItemPriority.MEDIUM,
                plan = WorkItemPlan(
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
        store.saveWorkItemState(workItemId, state)
        store.workItemEventLog(workItemId).append(
            WorkItemEvent.Created(
                workItemId = workItemId,
                title = workItemId,
                instruction = state!!.workItem.instruction,
                priority = WorkItemPriority.MEDIUM,
                completionCriteria = "done",
                timestamp = createdAt,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun timerSchedulerCronSchedules(manager: DurableWorkRuntime): Map<String, String> {
        val timerField = DurableWorkRuntime::class.java.getDeclaredField("timerScheduler")
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
