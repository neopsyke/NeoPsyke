package ai.neopsyke.agent.assignments

import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectStateMachineTest {
    private val workspacePath = Paths.get("/tmp/test-assignments/proj-1")
    private val now = Instant.parse("2026-03-17T12:00:00Z")

    private fun createdEvent(workItemId: String = "proj-1") = WorkItemEvent.Created(
        workItemId = workItemId,
        title = "Test Assignment",
        instruction = "Do something useful",
        priority = WorkItemPriority.MEDIUM,
        completionCriteria = "All steps done",
        timestamp = now,
    )

    private fun initialState(workItemId: String = "proj-1"): WorkItemState =
        WorkItemStateMachine.initialState(createdEvent(workItemId), workspacePath)

    private fun simplePlan(vararg stepSpecs: Pair<String, Set<String>>): WorkItemPlan {
        val steps = stepSpecs.map { (id, requires) ->
            PlanStep(
                id = id,
                description = "Step $id",
                status = StepStatus.PENDING,
                acceptanceCriteria = "Verify $id",
                requires = requires,
            )
        }
        return WorkItemPlan(steps = steps, generatedAt = now)
    }

    @Test
    fun `initial state is PLANNING with empty plan`() {
        val state = initialState()
        assertEquals("proj-1", state.id)
        assertEquals(WorkItemStatus.PLANNING, state.workItem.status)
        assertTrue(state.workItem.plan.steps.isEmpty())
        assertEquals(1, state.eventCount)
    }

    @Test
    fun `PlanGenerated transitions to ACTIVE and emits work-ready command`() {
        val state = initialState()
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.PlanGenerated(workItemId = "proj-1", plan = plan, timestamp = now)
        )

        assertEquals(WorkItemStatus.ACTIVE, newState.workItem.status)
        assertEquals(StepStatus.READY, newState.workItem.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.workItem.plan.steps[1].status)
        assertTrue(commands.any { it is WorkItemCommand.EmitWorkReady })
        assertTrue(commands.any { it is WorkItemCommand.PersistWorkItem })
    }

    @Test
    fun `PlanGenerated for recurring assignment defers initial work-ready until cron wake`() {
        val state = initialState().copy(
            workItem = initialState().workItem.copy(cronExpression = "*/5 * * * *")
        )
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.PlanGenerated(workItemId = "proj-1", plan = plan, timestamp = now)
        )

        assertEquals(WorkItemStatus.ACTIVE, newState.workItem.status)
        assertEquals(StepStatus.READY, newState.workItem.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.workItem.plan.steps[1].status)
        assertTrue(commands.none { it is WorkItemCommand.EmitWorkReady })
        assertTrue(commands.any { it is WorkItemCommand.PersistWorkItem })
    }

    @Test
    fun `nextRunnableStep prefers IN_PROGRESS over READY`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.ACTIVE,
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.READY, "verify s1"),
                        PlanStep("s2", "Step 2", StepStatus.IN_PROGRESS, "verify s2"),
                    ),
                    generatedAt = now,
                )
            )
        )

        assertEquals("s2", state.nextRunnableStep()?.id)
    }

    @Test
    fun `StepAcceptancePassed marks final step DONE and assignment COMPLETED`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.ACTIVE,
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1", attempts = 1),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.StepAcceptancePassed("proj-1", "s1", now)
        )

        assertEquals(StepStatus.DONE, newState.workItem.plan.steps.first().status)
        assertEquals(WorkItemStatus.COMPLETED, newState.workItem.status)
        assertTrue(commands.any { it is WorkItemCommand.PersistWorkItem })
    }

    @Test
    fun `CronCycleStarted resets recurring completed assignment and emits work-ready`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.COMPLETED,
                cronExpression = "*/5 * * * *",
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep(
                            id = "s1",
                            description = "Step 1",
                            status = StepStatus.DONE,
                            acceptanceCriteria = "verify s1",
                            attempts = 2,
                            completedAt = now,
                            notes = "previous run completed"
                        ),
                        PlanStep(
                            id = "s2",
                            description = "Step 2",
                            status = StepStatus.SKIPPED,
                            acceptanceCriteria = "verify s2",
                            requires = setOf("s1")
                        ),
                    ),
                    generatedAt = now,
                )
            ),
            producedKeys = setOf("artifact-key")
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.CronCycleStarted("proj-1", now)
        )

        assertEquals(WorkItemStatus.ACTIVE, newState.workItem.status)
        assertTrue(newState.producedKeys.isEmpty())
        assertEquals(StepStatus.READY, newState.workItem.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.workItem.plan.steps[1].status)
        assertEquals(0, newState.workItem.plan.steps[0].attempts)
        assertEquals("", newState.workItem.plan.steps[0].notes)
        assertTrue(commands.any { it is WorkItemCommand.EmitWorkReady })
        assertTrue(commands.any { it is WorkItemCommand.PersistWorkItem })
    }

    @Test
    fun `StepAcceptanceFailed with retries left returns step to READY and emits work-ready`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.ACTIVE,
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1", attempts = 1, maxAttempts = 3),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.StepAcceptanceFailed("proj-1", "s1", "not verified", now)
        )

        assertEquals(StepStatus.READY, newState.workItem.plan.steps.first().status)
        assertTrue(commands.any { it is WorkItemCommand.EmitWorkReady })
    }

    @Test
    fun `StepBlocked transitions step and assignment to BLOCKED and emits explicit timer registration`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.ACTIVE,
                plan = WorkItemPlan(
                    steps = listOf(PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1")),
                    generatedAt = now,
                )
            )
        )
        val condition = WaitCondition(
            type = WaitConditionType.TIMER,
            params = mapOf("wake_at" to "2026-03-18T12:00:00Z"),
            registeredAt = now,
            timeoutAt = Instant.parse("2026-03-18T12:00:00Z"),
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.StepBlocked("proj-1", "s1", condition, now)
        )

        assertEquals(StepStatus.BLOCKED, newState.workItem.plan.steps.first().status)
        assertEquals(WorkItemStatus.BLOCKED, newState.workItem.status)
        assertTrue(commands.any { it is WorkItemCommand.RegisterWaitCondition })
        assertTrue(commands.any { it is WorkItemCommand.ScheduleWakeTimer })
    }

    @Test
    fun `StepUnblocked returns assignment to ACTIVE and emits work-ready`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.BLOCKED,
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep(
                            "s1",
                            "Step 1",
                            StepStatus.BLOCKED,
                            "verify s1",
                            waitCondition = WaitCondition(WaitConditionType.TIMER, emptyMap(), now),
                        )
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.StepUnblocked("proj-1", "s1", now)
        )

        assertEquals(StepStatus.READY, newState.workItem.plan.steps.first().status)
        assertEquals(WorkItemStatus.ACTIVE, newState.workItem.status)
        assertTrue(commands.any { it is WorkItemCommand.EmitWorkReady })
    }

    @Test
    fun `WaitConditionSatisfied restores READY step appends async resolution note and emits detailed wake reason`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.BLOCKED,
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep(
                            "s1",
                            "Step 1",
                            StepStatus.BLOCKED,
                            "verify s1",
                            waitCondition = WaitCondition(
                                type = WaitConditionType.ASYNC_OPERATION,
                                params = emptyMap(),
                                registeredAt = now,
                            ),
                            notes = "prior note",
                        )
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.WaitConditionSatisfied(
                workItemId = "proj-1",
                stepId = "s1",
                conditionType = "async_operation",
                resolutionSummary = "download complete",
                resolutionStatus = "succeeded",
                timestamp = now,
            )
        )

        val updatedStep = newState.workItem.plan.steps.first()
        assertEquals(StepStatus.READY, updatedStep.status)
        assertEquals(WorkItemStatus.ACTIVE, newState.workItem.status)
        assertTrue(updatedStep.notes.contains("prior note"))
        assertTrue(updatedStep.notes.contains("async_status=succeeded"))
        assertTrue(updatedStep.notes.contains("async_summary=download complete"))
        val workReady = assertIs<WorkItemCommand.EmitWorkReady>(
            commands.first { it is WorkItemCommand.EmitWorkReady }
        )
        assertEquals("wait_condition_satisfied: succeeded: download complete", workReady.cue.reason)
    }

    @Test
    fun `WaitConditionTimedOut with RETRY emits work-ready for the same step`() {
        val condition = WaitCondition(
            type = WaitConditionType.CONDITION_CHECK,
            params = emptyMap(),
            registeredAt = now,
            onTimeout = TimeoutAction.RETRY,
        )
        val state = WorkItemState(
            workItem = initialState().workItem.copy(
                status = WorkItemStatus.BLOCKED,
                plan = WorkItemPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.BLOCKED, "verify s1", waitCondition = condition),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.WaitConditionTimedOut("proj-1", "s1", now)
        )

        assertEquals(StepStatus.READY, newState.workItem.plan.steps.first().status)
        val workReady = commands.filterIsInstance<WorkItemCommand.EmitWorkReady>().single()
        assertEquals("s1", workReady.cue.stepId)
    }

    @Test
    fun `Suspended schedules explicit wake timer`() {
        val state = WorkItemState(
            workItem = initialState().workItem.copy(status = WorkItemStatus.ACTIVE)
        )
        val resumeAt = Instant.parse("2026-03-18T08:00:00Z")

        val (newState, commands) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.Suspended("proj-1", "waiting for market open", resumeAt, now)
        )

        assertEquals(WorkItemStatus.SUSPENDED, newState.workItem.status)
        val timer = assertIs<WorkItemCommand.ScheduleWakeTimer>(
            commands.first { it is WorkItemCommand.ScheduleWakeTimer }
        )
        assertEquals(resumeAt, timer.wakeAt)
        assertEquals("work_item_suspended_resume", timer.reason)
    }

    @Test
    fun `event count increments with each transition`() {
        var state = initialState()
        assertEquals(1, state.eventCount)

        val plan = simplePlan("s1" to emptySet())
        state = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.PlanGenerated("proj-1", plan, now)
        ).first
        assertEquals(2, state.eventCount)

        state = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.StepStarted("proj-1", "s1", now)
        ).first
        assertEquals(3, state.eventCount)
    }

    @Test
    fun `Created event sets contactChannel on assignment`() {
        val event = WorkItemEvent.Created(
            workItemId = "proj-1",
            title = "Notify via Telegram",
            instruction = "Send weather update",
            priority = WorkItemPriority.MEDIUM,
            completionCriteria = "Message sent",
            contactChannel = "telegram",
            timestamp = now,
        )
        val state = WorkItemStateMachine.initialState(event, workspacePath)
        assertEquals("telegram", state.workItem.contactChannel)
    }

    @Test
    fun `Created event without contactChannel leaves it null`() {
        val state = initialState()
        assertEquals(null, state.workItem.contactChannel)
    }

    @Test
    fun `Updated event sets contactChannel`() {
        val state = initialState()
        val (updated, _) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.Updated(workItemId = "proj-1", contactChannel = "dashboard", timestamp = now),
        )
        assertEquals("dashboard", updated.workItem.contactChannel)
    }

    @Test
    fun `Updated event without contactChannel preserves existing value`() {
        val event = WorkItemEvent.Created(
            workItemId = "proj-1",
            title = "Test",
            instruction = "Test",
            priority = WorkItemPriority.MEDIUM,
            completionCriteria = "Done",
            contactChannel = "telegram",
            timestamp = now,
        )
        val state = WorkItemStateMachine.initialState(event, workspacePath)
        val (updated, _) = WorkItemStateMachine.transition(
            state,
            WorkItemEvent.Updated(workItemId = "proj-1", instruction = "New instruction", timestamp = now),
        )
        assertEquals("telegram", updated.workItem.contactChannel)
    }
}
