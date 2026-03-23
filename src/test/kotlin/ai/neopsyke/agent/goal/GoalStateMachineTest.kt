package ai.neopsyke.agent.goal

import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectStateMachineTest {
    private val workspacePath = Paths.get("/tmp/test-goals/proj-1")
    private val now = Instant.parse("2026-03-17T12:00:00Z")

    private fun createdEvent(goalId: String = "proj-1") = GoalEvent.Created(
        goalId = goalId,
        title = "Test Goal",
        instruction = "Do something useful",
        priority = GoalPriority.MEDIUM,
        completionCriteria = "All steps done",
        timestamp = now,
    )

    private fun initialState(goalId: String = "proj-1"): GoalState =
        GoalStateMachine.initialState(createdEvent(goalId), workspacePath)

    private fun simplePlan(vararg stepSpecs: Pair<String, Set<String>>): GoalPlan {
        val steps = stepSpecs.map { (id, requires) ->
            PlanStep(
                id = id,
                description = "Step $id",
                status = StepStatus.PENDING,
                acceptanceCriteria = "Verify $id",
                requires = requires,
            )
        }
        return GoalPlan(steps = steps, generatedAt = now)
    }

    @Test
    fun `initial state is PLANNING with empty plan`() {
        val state = initialState()
        assertEquals("proj-1", state.id)
        assertEquals(GoalStatus.PLANNING, state.goal.status)
        assertTrue(state.goal.plan.steps.isEmpty())
        assertEquals(1, state.eventCount)
    }

    @Test
    fun `PlanGenerated transitions to ACTIVE and emits work-ready command`() {
        val state = initialState()
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.PlanGenerated(goalId = "proj-1", plan = plan, timestamp = now)
        )

        assertEquals(GoalStatus.ACTIVE, newState.goal.status)
        assertEquals(StepStatus.READY, newState.goal.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.goal.plan.steps[1].status)
        assertTrue(commands.any { it is GoalCommand.EmitWorkReady })
        assertTrue(commands.any { it is GoalCommand.PersistGoal })
    }

    @Test
    fun `PlanGenerated for recurring goal defers initial work-ready until cron wake`() {
        val state = initialState().copy(
            goal = initialState().goal.copy(cronExpression = "*/5 * * * *")
        )
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.PlanGenerated(goalId = "proj-1", plan = plan, timestamp = now)
        )

        assertEquals(GoalStatus.ACTIVE, newState.goal.status)
        assertEquals(StepStatus.READY, newState.goal.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.goal.plan.steps[1].status)
        assertTrue(commands.none { it is GoalCommand.EmitWorkReady })
        assertTrue(commands.any { it is GoalCommand.PersistGoal })
    }

    @Test
    fun `nextRunnableStep prefers IN_PROGRESS over READY`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.ACTIVE,
                plan = GoalPlan(
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
    fun `StepAcceptancePassed marks final step DONE and goal COMPLETED`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.ACTIVE,
                plan = GoalPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1", attempts = 1),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.StepAcceptancePassed("proj-1", "s1", now)
        )

        assertEquals(StepStatus.DONE, newState.goal.plan.steps.first().status)
        assertEquals(GoalStatus.COMPLETED, newState.goal.status)
        assertTrue(commands.any { it is GoalCommand.PersistGoal })
    }

    @Test
    fun `CronCycleStarted resets recurring completed goal and emits work-ready`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.COMPLETED,
                cronExpression = "*/5 * * * *",
                plan = GoalPlan(
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

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.CronCycleStarted("proj-1", now)
        )

        assertEquals(GoalStatus.ACTIVE, newState.goal.status)
        assertTrue(newState.producedKeys.isEmpty())
        assertEquals(StepStatus.READY, newState.goal.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.goal.plan.steps[1].status)
        assertEquals(0, newState.goal.plan.steps[0].attempts)
        assertEquals("", newState.goal.plan.steps[0].notes)
        assertTrue(commands.any { it is GoalCommand.EmitWorkReady })
        assertTrue(commands.any { it is GoalCommand.PersistGoal })
    }

    @Test
    fun `StepAcceptanceFailed with retries left returns step to READY and emits work-ready`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.ACTIVE,
                plan = GoalPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1", attempts = 1, maxAttempts = 3),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.StepAcceptanceFailed("proj-1", "s1", "not verified", now)
        )

        assertEquals(StepStatus.READY, newState.goal.plan.steps.first().status)
        assertTrue(commands.any { it is GoalCommand.EmitWorkReady })
    }

    @Test
    fun `StepBlocked transitions step and goal to BLOCKED and emits explicit timer registration`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.ACTIVE,
                plan = GoalPlan(
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

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.StepBlocked("proj-1", "s1", condition, now)
        )

        assertEquals(StepStatus.BLOCKED, newState.goal.plan.steps.first().status)
        assertEquals(GoalStatus.BLOCKED, newState.goal.status)
        assertTrue(commands.any { it is GoalCommand.RegisterWaitCondition })
        assertTrue(commands.any { it is GoalCommand.ScheduleWakeTimer })
    }

    @Test
    fun `StepUnblocked returns goal to ACTIVE and emits work-ready`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.BLOCKED,
                plan = GoalPlan(
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

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.StepUnblocked("proj-1", "s1", now)
        )

        assertEquals(StepStatus.READY, newState.goal.plan.steps.first().status)
        assertEquals(GoalStatus.ACTIVE, newState.goal.status)
        assertTrue(commands.any { it is GoalCommand.EmitWorkReady })
    }

    @Test
    fun `WaitConditionSatisfied restores READY step appends async resolution note and emits detailed wake reason`() {
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.BLOCKED,
                plan = GoalPlan(
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

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.WaitConditionSatisfied(
                goalId = "proj-1",
                stepId = "s1",
                conditionType = "async_operation",
                resolutionSummary = "download complete",
                resolutionStatus = "succeeded",
                timestamp = now,
            )
        )

        val updatedStep = newState.goal.plan.steps.first()
        assertEquals(StepStatus.READY, updatedStep.status)
        assertEquals(GoalStatus.ACTIVE, newState.goal.status)
        assertTrue(updatedStep.notes.contains("prior note"))
        assertTrue(updatedStep.notes.contains("async_status=succeeded"))
        assertTrue(updatedStep.notes.contains("async_summary=download complete"))
        val workReady = assertIs<GoalCommand.EmitWorkReady>(
            commands.first { it is GoalCommand.EmitWorkReady }
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
        val state = GoalState(
            goal = initialState().goal.copy(
                status = GoalStatus.BLOCKED,
                plan = GoalPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.BLOCKED, "verify s1", waitCondition = condition),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.WaitConditionTimedOut("proj-1", "s1", now)
        )

        assertEquals(StepStatus.READY, newState.goal.plan.steps.first().status)
        val workReady = commands.filterIsInstance<GoalCommand.EmitWorkReady>().single()
        assertEquals("s1", workReady.cue.stepId)
    }

    @Test
    fun `Suspended schedules explicit wake timer`() {
        val state = GoalState(
            goal = initialState().goal.copy(status = GoalStatus.ACTIVE)
        )
        val resumeAt = Instant.parse("2026-03-18T08:00:00Z")

        val (newState, commands) = GoalStateMachine.transition(
            state,
            GoalEvent.Suspended("proj-1", "waiting for market open", resumeAt, now)
        )

        assertEquals(GoalStatus.SUSPENDED, newState.goal.status)
        val timer = assertIs<GoalCommand.ScheduleWakeTimer>(
            commands.first { it is GoalCommand.ScheduleWakeTimer }
        )
        assertEquals(resumeAt, timer.wakeAt)
        assertEquals("goal_suspended_resume", timer.reason)
    }

    @Test
    fun `event count increments with each transition`() {
        var state = initialState()
        assertEquals(1, state.eventCount)

        val plan = simplePlan("s1" to emptySet())
        state = GoalStateMachine.transition(
            state,
            GoalEvent.PlanGenerated("proj-1", plan, now)
        ).first
        assertEquals(2, state.eventCount)

        state = GoalStateMachine.transition(
            state,
            GoalEvent.StepStarted("proj-1", "s1", now)
        ).first
        assertEquals(3, state.eventCount)
    }
}
