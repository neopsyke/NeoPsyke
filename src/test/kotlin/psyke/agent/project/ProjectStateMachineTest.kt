package psyke.agent.project

import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectStateMachineTest {
    private val workspacePath = Paths.get("/tmp/test-projects/proj-1")
    private val now = Instant.parse("2026-03-17T12:00:00Z")

    private fun createdEvent(projectId: String = "proj-1") = ProjectEvent.Created(
        projectId = projectId,
        title = "Test Project",
        instruction = "Do something useful",
        priority = ProjectPriority.MEDIUM,
        completionCriteria = "All steps done",
        timestamp = now,
    )

    private fun initialState(projectId: String = "proj-1"): ProjectState =
        ProjectStateMachine.initialState(createdEvent(projectId), workspacePath)

    private fun simplePlan(vararg stepSpecs: Pair<String, Set<String>>): ProjectPlan {
        val steps = stepSpecs.map { (id, requires) ->
            PlanStep(
                id = id,
                description = "Step $id",
                status = StepStatus.PENDING,
                acceptanceCriteria = "Verify $id",
                requires = requires,
            )
        }
        return ProjectPlan(steps = steps, generatedAt = now)
    }

    @Test
    fun `initial state is PLANNING with empty plan`() {
        val state = initialState()
        assertEquals("proj-1", state.id)
        assertEquals(ProjectStatus.PLANNING, state.project.status)
        assertTrue(state.project.plan.steps.isEmpty())
        assertEquals(1, state.eventCount)
    }

    @Test
    fun `PlanGenerated transitions to ACTIVE and emits work-ready command`() {
        val state = initialState()
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.PlanGenerated(projectId = "proj-1", plan = plan, timestamp = now)
        )

        assertEquals(ProjectStatus.ACTIVE, newState.project.status)
        assertEquals(StepStatus.READY, newState.project.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.project.plan.steps[1].status)
        assertTrue(commands.any { it is ProjectCommand.EmitWorkReady })
        assertTrue(commands.any { it is ProjectCommand.PersistProject })
    }

    @Test
    fun `nextRunnableStep prefers IN_PROGRESS over READY`() {
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.ACTIVE,
                plan = ProjectPlan(
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
    fun `StepAcceptancePassed marks final step DONE and project COMPLETED`() {
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.ACTIVE,
                plan = ProjectPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1", attempts = 1),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.StepAcceptancePassed("proj-1", "s1", now)
        )

        assertEquals(StepStatus.DONE, newState.project.plan.steps.first().status)
        assertEquals(ProjectStatus.COMPLETED, newState.project.status)
        assertTrue(commands.any { it is ProjectCommand.PersistProject })
    }

    @Test
    fun `StepAcceptanceFailed with retries left returns step to READY and emits work-ready`() {
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.ACTIVE,
                plan = ProjectPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1", attempts = 1, maxAttempts = 3),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.StepAcceptanceFailed("proj-1", "s1", "not verified", now)
        )

        assertEquals(StepStatus.READY, newState.project.plan.steps.first().status)
        assertTrue(commands.any { it is ProjectCommand.EmitWorkReady })
    }

    @Test
    fun `StepBlocked transitions step and project to BLOCKED and emits explicit timer registration`() {
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.ACTIVE,
                plan = ProjectPlan(
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

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.StepBlocked("proj-1", "s1", condition, now)
        )

        assertEquals(StepStatus.BLOCKED, newState.project.plan.steps.first().status)
        assertEquals(ProjectStatus.BLOCKED, newState.project.status)
        assertTrue(commands.any { it is ProjectCommand.RegisterWaitCondition })
        assertTrue(commands.any { it is ProjectCommand.ScheduleWakeTimer })
    }

    @Test
    fun `StepUnblocked returns project to ACTIVE and emits work-ready`() {
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.BLOCKED,
                plan = ProjectPlan(
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

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.StepUnblocked("proj-1", "s1", now)
        )

        assertEquals(StepStatus.READY, newState.project.plan.steps.first().status)
        assertEquals(ProjectStatus.ACTIVE, newState.project.status)
        assertTrue(commands.any { it is ProjectCommand.EmitWorkReady })
    }

    @Test
    fun `WaitConditionTimedOut with RETRY emits work-ready for the same step`() {
        val condition = WaitCondition(
            type = WaitConditionType.CONDITION_CHECK,
            params = emptyMap(),
            registeredAt = now,
            onTimeout = TimeoutAction.RETRY,
        )
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.BLOCKED,
                plan = ProjectPlan(
                    steps = listOf(
                        PlanStep("s1", "Step 1", StepStatus.BLOCKED, "verify s1", waitCondition = condition),
                    ),
                    generatedAt = now,
                )
            )
        )

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.WaitConditionTimedOut("proj-1", "s1", now)
        )

        assertEquals(StepStatus.READY, newState.project.plan.steps.first().status)
        val workReady = commands.filterIsInstance<ProjectCommand.EmitWorkReady>().single()
        assertEquals("s1", workReady.signal.stepId)
    }

    @Test
    fun `Suspended schedules explicit wake timer`() {
        val state = ProjectState(
            project = initialState().project.copy(status = ProjectStatus.ACTIVE)
        )
        val resumeAt = Instant.parse("2026-03-18T08:00:00Z")

        val (newState, commands) = ProjectStateMachine.transition(
            state,
            ProjectEvent.Suspended("proj-1", "waiting for market open", resumeAt, now)
        )

        assertEquals(ProjectStatus.SUSPENDED, newState.project.status)
        val timer = assertIs<ProjectCommand.ScheduleWakeTimer>(
            commands.first { it is ProjectCommand.ScheduleWakeTimer }
        )
        assertEquals(resumeAt, timer.wakeAt)
        assertEquals("project_suspended_resume", timer.reason)
    }

    @Test
    fun `event count increments with each transition`() {
        var state = initialState()
        assertEquals(1, state.eventCount)

        val plan = simplePlan("s1" to emptySet())
        state = ProjectStateMachine.transition(
            state,
            ProjectEvent.PlanGenerated("proj-1", plan, now)
        ).first
        assertEquals(2, state.eventCount)

        state = ProjectStateMachine.transition(
            state,
            ProjectEvent.StepStarted("proj-1", "s1", now)
        ).first
        assertEquals(3, state.eventCount)
    }
}
