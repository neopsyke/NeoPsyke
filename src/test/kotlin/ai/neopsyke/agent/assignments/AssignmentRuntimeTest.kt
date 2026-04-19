package ai.neopsyke.agent.assignments

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
import ai.neopsyke.agent.cortex.sensory.AssignmentCue
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.ego.planner.model.AssignmentPlanStepPayload
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

class AssignmentRuntimeTest {

    private fun testConfig(root: java.nio.file.Path) = AssignmentConfig(
        enabled = true,
        workspaceRoot = root,
        maxActiveWorkItems = 5,
        timerResolutionMs = 100,
        conditionCheckIntervalMs = 100,
        allowRuntimePlanFallback = true,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `createWorkItem generates plan persists state and emits work-ready signal`() {
        val root = Files.createTempDirectory("psyke-pm-create")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Monitor stock prices daily",
                title = "Stock Monitor",
                priority = WorkItemPriority.HIGH,
            )

            assertTrue(id.isNotBlank())
            val workReady = assertIs<AssignmentCue>(signals.last())
            assertEquals(id, workReady.workItemId)
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.ACTIVE, state.workItem.status)
            assertTrue(Files.exists(root.resolve(id).resolve(WorkItemStore.ASSIGNMENT_FILE)))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem fails closed when pre-built plan is missing and fallback disabled`() {
        val root = Files.createTempDirectory("psyke-pm-create-fail-closed")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem("Missing plan steps")

            assertEquals("", id)
            assertTrue(manager.allWorkItems().isEmpty())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `revise_plan fails closed when plan steps are missing and fallback disabled`() {
        val root = Files.createTempDirectory("psyke-pm-revise-fail-closed")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())
            val id = manager.createWorkItem(
                instruction = "Task with explicit plan",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "step-1",
                        description = "Do one thing",
                        acceptanceCriteria = "done",
                        produces = setOf("done_signal"),
                    )
                ),
            )
            assertTrue(id.isNotBlank())

            val result = manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.REVISE_PLAN,
                    workItemId = id,
                    reason = "Update approach",
                    planSteps = null,
                )
            )

            assertFalse(result.success)
            assertTrue(result.message.contains("requires pre-built plan steps"))
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem rejects malformed dependency graph in pre-built plan`() {
        val root = Files.createTempDirectory("psyke-pm-plan-validate")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Malformed plan",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "step-1",
                        description = "Use missing key",
                        acceptanceCriteria = "n/a",
                        requires = setOf("missing_key"),
                    )
                ),
            )

            assertEquals("", id)
            assertTrue(manager.allWorkItems().isEmpty())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `responsibility review remains reviewable and emits manual review wake`() {
        val root = Files.createTempDirectory("psyke-pm-responsibility-review")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Keep tracking better Berlin apartments over time",
                title = "Apartment search",
                operatorSummary = "Own apartment hunting and surface materially better options.",
                kind = WorkItemKind.RESPONSIBILITY,
            )

            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemKind.RESPONSIBILITY, state.workItem.kind)
            assertTrue(state.workItem.reviewPolicy.enabled)
            assertNotNull(state.workItem.nextReviewAt)

            val reviewable = manager.reviewableResponsibilities(limit = 8)
            assertEquals(listOf(id), reviewable.map { it.workItemId })

            val result = manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.REVIEW,
                    workItemId = id,
                    reason = "manual audit",
                )
            )

            assertTrue(result.success)
            val cue = signals.last()
            assertEquals(id, cue.workItemId)
            assertEquals(WakeReasonType.MANUAL_REVIEW, cue.wakeReasonType)
            assertEquals("manual audit", cue.wakeReasonDetail)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `retired responsibility leaves the reviewable slate`() {
        val root = Files.createTempDirectory("psyke-pm-responsibility-retire")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Own weekly project triage",
                title = "Project triage",
                kind = WorkItemKind.RESPONSIBILITY,
            )
            assertEquals(listOf(id), manager.reviewableResponsibilities(limit = 8).map { it.workItemId })

            val result = manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.RETIRE,
                    workItemId = id,
                    reason = "superseded",
                )
            )

            assertTrue(result.success)
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.RETIRED, state.workItem.status)
            assertTrue(manager.reviewableResponsibilities(limit = 8).isEmpty())

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `responsibility remains ongoing after a review cycle and id review rearms it`() {
        val root = Files.createTempDirectory("psyke-pm-responsibility-rearm")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Own the apartment search over time",
                title = "Apartment responsibility",
                kind = WorkItemKind.RESPONSIBILITY,
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "scan",
                        description = "Scan the current apartment market",
                        acceptanceCriteria = "Current listings reviewed",
                    )
                ),
            )

            manager.applyEventExternal(id, WorkItemEvent.StepStarted(id, "scan"))
            manager.applyEventExternal(id, WorkItemEvent.StepAcceptancePassed(id, "scan"))

            val afterCycle = manager.workItemStatus(id)
            assertNotNull(afterCycle)
            assertEquals(WorkItemStatus.ACTIVE, afterCycle.workItem.status)
            assertNull(afterCycle.nextRunnableStep())
            assertEquals(listOf(id), manager.reviewableResponsibilities(limit = 8).map { it.workItemId })

            val result = manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.REVIEW,
                    workItemId = id,
                    reason = "be useful",
                    reviewSource = ReviewRequestSource.ID,
                )
            )

            assertTrue(result.success)
            val cue = signals.last()
            assertEquals(WakeReasonType.ID_REVIEW, cue.wakeReasonType)
            assertEquals("scan", cue.stepId)
            val rearmed = manager.workItemStatus(id)
            assertNotNull(rearmed)
            assertEquals(WorkItemStatus.ACTIVE, rearmed.workItem.status)
            assertEquals(listOf(StepStatus.READY), rearmed.workItem.plan.steps.map { it.status })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paused responsibility does not emit overdue review wake`() {
        val root = Files.createTempDirectory("psyke-pm-responsibility-pause-review")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(
                    monitoring = MonitoringConfig(overdueResponsibilityReviewIntervalMs = 50)
                ),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Keep an eye on repository health over time",
                title = "Repository watch",
                kind = WorkItemKind.RESPONSIBILITY,
            )
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            val reviewAt = assertNotNull(state.workItem.nextReviewAt)

            manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.PAUSE,
                    workItemId = id,
                    reason = "paused for testing",
                )
            )

            val onTimerWake = AssignmentRuntime::class.java.getDeclaredMethod(
                "onTimerWake", String::class.java, Long::class.javaPrimitiveType
            )
            onTimerWake.isAccessible = true
            onTimerWake.invoke(manager, id, reviewAt.toEpochMilli())

            assertTrue(signals.none { it.workItemId == id && it.reason == "review_due" })
            assertTrue(manager.reviewableResponsibilities(limit = 8).isEmpty())

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delivery decision events preserve digest queue and suppression state`() {
        val root = Files.createTempDirectory("psyke-pm-delivery-state")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem("Track notable changes")
            manager.applyEventExternal(id, WorkItemEvent.ReportWindowOpened(id, "digest-window-1"))
            manager.applyEventExternal(
                id,
                WorkItemEvent.DeliveryDecisionRecorded(
                    workItemId = id,
                    decision = DeliveryDecision.QUEUE_FOR_DIGEST,
                    fingerprint = "fp-1",
                    summary = "delta one",
                )
            )
            manager.applyEventExternal(
                id,
                WorkItemEvent.DeliverySuppressed(
                    workItemId = id,
                    reason = DeliverySuppressionReason.NO_MEANINGFUL_CHANGE,
                    summary = "quiet cycle",
                )
            )

            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(DeliveryDecision.QUEUE_FOR_DIGEST, state.durableState.delivery.lastDecision)
            assertEquals(DeliverySuppressionReason.NO_MEANINGFUL_CHANGE, state.durableState.delivery.lastSuppressionReason)
            assertEquals("digest-window-1", state.durableState.delivery.activeDigestWindow?.windowKey)
            assertEquals(listOf("delta one"), state.durableState.delivery.activeDigestWindow?.itemKeys)
            assertEquals(listOf("delta one"), state.durableState.delivery.pendingEntries.map { it.summary })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue creates assignment session and returns work unit`() {
        val root = Files.createTempDirectory("psyke-pm-work")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Persistent task")

            val work = manager.nextWorkFromCue(AssignmentCue(id, "step-1", "test"))
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
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Persistent task")

            val first = manager.nextWorkFromCue(AssignmentCue(id, "step-1", "test"))
            assertNotNull(first)
            assertEquals("work:$id:step-1", first.rootInputId)

            // Second wake while leased should be coalesced (returns null)
            val second = manager.nextWorkFromCue(AssignmentCue(id, "step-1", "resume"))
            assertNull(second, "Duplicate wake should be coalesced while item is leased")

            // Verify the pending wake reason was captured
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertTrue(state.workItem.pendingWakeReasons.any { it.detail == "resume" })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `assignment-origin action outcome completes final step and assignment`() {
        val root = Files.createTempDirectory("psyke-pm-complete")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                verifier = DeterministicWorkStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Ship release checklist")
            val work = manager.nextWorkFromCue(assertIs<AssignmentCue>(signals.last()))
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
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.ASSIGNMENT),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeAssignmentCycle(work.rootInputId)

            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(WorkItemStatus.COMPLETED, state.workItem.status)

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
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val workItemId = manager.createWorkItem("Wait for async completion")
            val work = manager.nextWorkFromCue(assertIs<AssignmentCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-1"),
                observedEvidence = false,
            )
            manager.finalizeAssignmentCycle(work.rootInputId)

            waitUntil {
                val state = manager.workItemStatus(workItemId)
                state != null &&
                    state.workItem.status == WorkItemStatus.ACTIVE &&
                    state.workItem.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.workItemId == workItemId }
            }

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertTrue(state.workItem.plan.steps.first().notes.contains("async_status=succeeded"))
            assertTrue(signals.any { it.workItemId == workItemId })

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `assignment WAITING outcome without async handles is rejected as contract violation`() {
        val root = Files.createTempDirectory("psyke-pm-invalid-wait")
        try {
            val instrumentation = RecordingInstrumentation()
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                instrumentation = instrumentation,
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val workItemId = manager.createWorkItem("Reject invalid waiting outcome")
            val work = manager.nextWorkFromCue(assertIs<AssignmentCue>(signals.last()))
            assertNotNull(work)

            manager.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = ActionOutcome(
                    statusSummary = "Async operation allegedly started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                ),
                observedEvidence = false,
            )
            manager.finalizeAssignmentCycle(work.rootInputId)

            val state = manager.workItemStatus(workItemId)
            assertNotNull(state)
            assertEquals(WorkItemStatus.ACTIVE, state.workItem.status)
            assertEquals(StepStatus.READY, state.workItem.plan.steps.first().status)
            assertNull(state.workItem.plan.steps.first().waitCondition)
            assertEquals(1, state.workItem.plan.steps.first().attempts)
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
    fun `delete all operation removes all assignments and workspaces`() {
        val root = Files.createTempDirectory("psyke-pm-delete-all")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val firstId = manager.createWorkItem("Delete me first")
            val secondId = manager.createWorkItem("Delete me second")
            assertTrue(Files.exists(root.resolve(firstId)))
            assertTrue(Files.exists(root.resolve(secondId)))

            val result = manager.executeOperation(AssignmentOperationRequest(operation = AssignmentOperation.DELETE_ALL))

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
            val manager1 = AssignmentRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Restore async poll wait")
            val work = manager1.nextWorkFromCue(AssignmentCue(workItemId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "op-restore"),
                observedEvidence = false,
            )
            manager1.finalizeAssignmentCycle(work.rootInputId)
            waitUntil {
                val state = manager1.workItemStatus(workItemId)
                state != null &&
                    state.workItem.status == WorkItemStatus.BLOCKED &&
                    state.workItem.plan.steps.firstOrNull()?.status == StepStatus.BLOCKED
            }
            manager1.stop()

            provider.enqueue(operationId = "op-restore", statuses = listOf(AsyncOperationStatus.Succeeded("restored completion")))
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
                cueEmitter = { cue -> signals += cue },
            )
            manager2.start(testScope())

            waitUntil {
                val state = manager2.workItemStatus(workItemId)
                state != null &&
                    state.workItem.status == WorkItemStatus.ACTIVE &&
                    state.workItem.plan.steps.firstOrNull()?.status == StepStatus.READY &&
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
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Restore ready step cue")
            manager1.stop()

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
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
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
                asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Restore async event wait")
            val work = manager1.nextWorkFromCue(AssignmentCue(workItemId, "step-1", "test"))
            assertNotNull(work)
            manager1.onActionExecuted(
                action = projectAction(work.rootInputId),
                outcome = asyncWaitingOutcome(operationId = "evt-1", resumeMode = AsyncResumeMode.EVENT, correlationKey = "corr-1"),
                observedEvidence = false,
            )
            manager1.finalizeAssignmentCycle(work.rootInputId)
            manager1.stop()

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
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
                state != null &&
                    state.workItem.status == WorkItemStatus.ACTIVE &&
                    state.workItem.plan.steps.firstOrNull()?.status == StepStatus.READY &&
                    signals.any { it.workItemId == workItemId }
            }

            val state = manager2.workItemStatus(workItemId)
            assertNotNull(state)
            assertTrue(state.workItem.plan.steps.first().notes.contains("event completed"))
            assertTrue(signals.any { it.workItemId == workItemId })

            manager2.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `finalized assignment cycle writes workspace context scratch and artifact`() {
        val root = Files.createTempDirectory("psyke-pm-workspace")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
                verifier = DeterministicWorkStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Document workspace artifacts")
            val work = manager.nextWorkFromCue(assertIs<AssignmentCue>(signals.last()))
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
                    origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.ASSIGNMENT),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                outcome = ActionOutcome(
                    statusSummary = "completed",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                ),
                observedEvidence = true,
            )
            manager.finalizeAssignmentCycle(work.rootInputId)

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
    fun `pendingWorkSummary returns empty when no assignments exist`() {
        val root = Files.createTempDirectory("psyke-pm-summary")
        try {
            val manager = AssignmentRuntime(
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
    fun `max active assignments enforced`() {
        val root = Files.createTempDirectory("psyke-pm-limit")
        try {
            val config = testConfig(root).copy(maxActiveWorkItems = 2)
            val manager = AssignmentRuntime(
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
    fun `assignment persists and reloads across manager restarts`() {
        val root = Files.createTempDirectory("psyke-pm-restart")
        try {
            val store = WorkItemStore(root)
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Persistent task")
            manager1.stop()

            val manager2 = AssignmentRuntime(
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
    fun `startup prunes expired completed assignments but keeps recent and active ones`() {
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
                workItemId = "active-assignment",
                status = WorkItemStatus.ACTIVE,
                stepStatus = StepStatus.READY,
                completedAt = null,
                lastWorkedAt = recentCompletion,
            )

            val manager = AssignmentRuntime(
                config = testConfig(root).copy(completedWorkItemRetentionDays = 1),
                store = store,
            )
            manager.start(testScope())

            assertFalse(Files.exists(root.resolve("old-completed")))
            assertNotNull(manager.workItemStatus("recent-completed"))
            assertNotNull(manager.workItemStatus("active-assignment"))

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextWorkFromCue returns null when assignment is missing`() {
        val root = Files.createTempDirectory("psyke-pm-missing")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
            )
            manager.start(testScope())
            assertNull(manager.nextWorkFromCue(AssignmentCue("missing", "s1", "test")))
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `suspended assignment resume timer is restored on restart and emits work-ready`() {
        val root = Files.createTempDirectory("psyke-pm-restore-suspend")
        try {
            val store = WorkItemStore(root)
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager1.start(testScope())
            val id = manager1.createWorkItem("Resume me later")
            val resumeAt = Instant.now().plusMillis(200)
            manager1.applyEventExternal(
                id,
                WorkItemEvent.Suspended(id, "paused", resumeAt)
            )
            manager1.stop()

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
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
    fun `blocked timer wait is restored on restart and assignment becomes ACTIVE when timer fires`() {
        val root = Files.createTempDirectory("psyke-pm-restore-wait")
        try {
            val store = WorkItemStore(root)
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
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

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
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
            val manager1 = AssignmentRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
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

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
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
            val manager1 = AssignmentRuntime(
                config = testConfig(root).copy(conditionCheckIntervalMs = 25),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
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

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
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
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager1.start(testScope())
            val future = ZonedDateTime.now().plusMinutes(2)
            val cronExpression = "${future.minute} ${future.hour} * * *"
            val id = manager1.createWorkItem(
                instruction = "Wake me on a cron schedule",
                cronExpression = cronExpression,
            )
            manager1.stop()

            val manager2 = AssignmentRuntime(
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
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val result = manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.CREATE,
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
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
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
            assertEquals(WorkItemStatus.ACTIVE, state.workItem.status)
            assertEquals(cronExpression, state.workItem.cronExpression)
            assertEquals(StepStatus.READY, state.workItem.plan.steps.first().status)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cron timer wake on ACTIVE assignment with READY step emits work-ready cue`() {
        val root = Files.createTempDirectory("psyke-pm-cron-wake-active")
        try {
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
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
            assertEquals(WorkItemStatus.ACTIVE, state.workItem.status)
            assertEquals(StepStatus.READY, state.workItem.plan.steps.first().status)

            // No work-ready cue should have been emitted at creation for a cron assignment
            assertTrue(signals.none { it.workItemId == id })

            // Simulate the cron timer firing by invoking onTimerWake reflectively
            val onTimerWake = AssignmentRuntime::class.java.getDeclaredMethod(
                "onTimerWake", String::class.java, Long::class.javaPrimitiveType
            )
            onTimerWake.isAccessible = true
            onTimerWake.invoke(manager, id, System.currentTimeMillis())

            // Now a work-ready cue should have been emitted
            val cue = signals.firstOrNull { it.workItemId == id }
            assertNotNull(cue, "Expected work-ready cue after cron timer wake on ACTIVE assignment")
            assertEquals("cron_wake_active", cue.reason)
            assertEquals(state.workItem.plan.steps.first().id, cue.stepId)

            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `assignment mutating effect intent is recorded confirmed and duplicate dispatch is blocked`() {
        val root = Files.createTempDirectory("psyke-pm-effect-intent")
        try {
            val store = WorkItemStore(root)
            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
                verifier = DeterministicWorkStepVerifier(),
                cueEmitter = { cue -> signals += cue },
            )
            manager.start(testScope())
            val id = manager.createWorkItem("Send a durable notification")
            val work = manager.nextWorkFromCue(assertIs<AssignmentCue>(signals.last()))
            assertNotNull(work)

            val action = PendingAction(
                id = 501L,
                urgency = Urgency.HIGH,
                type = ActionType.CONTACT_USER,
                payload = """{"message":"Hello"}""",
                summary = "send durable notification",
                rootInputId = work.rootInputId,
                conversationContext = ConversationContext.default(),
                origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.ASSIGNMENT),
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
            assertEquals("assignment_effect_duplicate", secondGate.reasonCode)

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
            val manager1 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager1.start(testScope())
            val workItemId = manager1.createWorkItem("Recover unfinished activation")
            val work = manager1.nextWorkFromCue(AssignmentCue(workItemId, "step-1", "test"))
            assertNotNull(work)
            manager1.stop()

            val signals = CopyOnWriteArrayList<AssignmentCue>()
            val manager2 = AssignmentRuntime(
                config = testConfig(root),
                store = store,
                planner = DeterministicAssignmentPlanBuilder(),
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
            assertEquals(WorkItemHealth.NEEDS_ATTENTION, state.workItem.health)

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
        origin = ai.neopsyke.agent.model.ActionOrigin(source = OriginSource.ASSIGNMENT),
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
                instruction = state.workItem.instruction,
                priority = WorkItemPriority.MEDIUM,
                completionCriteria = "done",
                timestamp = createdAt,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun timerSchedulerCronSchedules(manager: AssignmentRuntime): Map<String, String> {
        val timerField = AssignmentRuntime::class.java.getDeclaredField("timerScheduler")
        timerField.isAccessible = true
        val timer = timerField.get(manager) ?: error("TimerScheduler was not initialized")
        val cronField = timer.javaClass.getDeclaredField("cronSchedules")
        cronField.isAccessible = true
        return (cronField.get(timer) as ConcurrentHashMap<String, String>).toMap()
    }

    // ── Plan validation boundary checks ──

    @Test
    fun `createWorkItem rejects plan with dependency cycle`() {
        val root = Files.createTempDirectory("psyke-pm-cycle")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Cyclic plan",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "a",
                        description = "Step A",
                        requires = setOf("b-out"),
                        produces = setOf("a-out"),
                    ),
                    AssignmentPlanStepPayload(
                        id = "b",
                        description = "Step B",
                        requires = setOf("a-out"),
                        produces = setOf("b-out"),
                    ),
                ),
            )

            assertEquals("", id, "Cyclic dependency graph should be rejected")
            assertTrue(manager.allWorkItems().isEmpty())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem rejects plan with invalid grounding requirement`() {
        val root = Files.createTempDirectory("psyke-pm-grounding")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Bad grounding",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "step-1",
                        description = "Step with invalid grounding",
                        groundingRequirement = "maybe",
                    ),
                ),
            )

            assertEquals("", id, "Invalid grounding_requirement should be rejected")
            assertTrue(manager.allWorkItems().isEmpty())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem coerces maxAttempts bounds`() {
        val root = Files.createTempDirectory("psyke-pm-attempts")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Coerced attempts",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "low",
                        description = "Step with zero attempts",
                        maxAttempts = 0,
                        produces = setOf("low-out"),
                    ),
                    AssignmentPlanStepPayload(
                        id = "high",
                        description = "Step with excessive attempts",
                        maxAttempts = 999,
                        requires = setOf("low-out"),
                    ),
                ),
            )

            assertTrue(id.isNotBlank(), "Plan with coercible maxAttempts should be accepted")
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            val steps = state.workItem.plan.steps
            assertEquals(1, steps[0].maxAttempts, "maxAttempts=0 should be coerced to 1")
            assertEquals(10, steps[1].maxAttempts, "maxAttempts=999 should be coerced to MAX_STEP_ATTEMPTS(10)")
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem normalizes duplicate step ids`() {
        val root = Files.createTempDirectory("psyke-pm-dup-ids")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Duplicate ids",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "fetch",
                        description = "First fetch",
                        produces = setOf("data-1"),
                    ),
                    AssignmentPlanStepPayload(
                        id = "fetch",
                        description = "Second fetch",
                        requires = setOf("data-1"),
                    ),
                ),
            )

            assertTrue(id.isNotBlank(), "Duplicate step ids should be normalized, not rejected")
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            val ids = state.workItem.plan.steps.map { it.id }
            assertEquals(2, ids.toSet().size, "Normalized step ids must be unique: $ids")
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `revise_plan happy path applies pre-built plan`() {
        val root = Files.createTempDirectory("psyke-pm-revise-happy")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Task to revise",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "old-step",
                        description = "Old approach",
                    ),
                ),
            )
            assertTrue(id.isNotBlank())

            val result = manager.executeOperation(
                AssignmentOperationRequest(
                    operation = AssignmentOperation.REVISE_PLAN,
                    workItemId = id,
                    reason = "Better approach found",
                    planSteps = listOf(
                        AssignmentPlanStepPayload(
                            id = "new-step-1",
                            description = "Search for data",
                            produces = setOf("data"),
                        ),
                        AssignmentPlanStepPayload(
                            id = "new-step-2",
                            description = "Deliver to user",
                            requires = setOf("data"),
                        ),
                    ),
                )
            )

            assertTrue(result.success, "Revise with valid plan should succeed: ${result.message}")
            val state = manager.workItemStatus(id)
            assertNotNull(state)
            assertEquals(2, state.workItem.plan.steps.size, "Plan should have 2 new steps")
            assertTrue(state.workItem.plan.steps.any { it.description == "Search for data" })
            assertTrue(state.workItem.plan.steps.any { it.description == "Deliver to user" })
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createWorkItem rejects plan with blank description`() {
        val root = Files.createTempDirectory("psyke-pm-blank-desc")
        try {
            val manager = AssignmentRuntime(
                config = testConfig(root).copy(allowRuntimePlanFallback = false),
                store = WorkItemStore(root),
                planner = DeterministicAssignmentPlanBuilder(),
            )
            manager.start(testScope())

            val id = manager.createWorkItem(
                instruction = "Blank step description",
                planSteps = listOf(
                    AssignmentPlanStepPayload(
                        id = "step-1",
                        description = "   ",
                    ),
                ),
            )

            assertEquals("", id, "Blank description should be rejected")
            assertTrue(manager.allWorkItems().isEmpty())
            manager.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
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
