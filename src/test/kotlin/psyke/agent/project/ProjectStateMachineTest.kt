package psyke.agent.project

import psyke.agent.cortex.sensory.ProjectSignal
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

    // ── Creation ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is CREATED with empty plan`() {
        val state = initialState()
        assertEquals("proj-1", state.id)
        assertEquals(ProjectStatus.CREATED, state.project.status)
        assertTrue(state.project.plan.steps.isEmpty())
        assertEquals(1, state.eventCount)
    }

    @Test
    fun `Created event transitions to PLANNING and requests plan`() {
        val state = initialState()
        val (newState, commands) = ProjectStateMachine.transition(state, createdEvent())
        assertEquals(ProjectStatus.PLANNING, newState.project.status)
        assertTrue(commands.any { it is ProjectCommand.RequestPlan })
    }

    // ── Plan generation ──────────────────────────────────────────────────

    @Test
    fun `PlanGenerated transitions to ACTIVE and promotes first step to READY`() {
        val state = initialState().let {
            ProjectStateMachine.transition(it, createdEvent()).first
        }
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))
        val event = ProjectEvent.PlanGenerated(projectId = "proj-1", plan = plan, timestamp = now)

        val (newState, commands) = ProjectStateMachine.transition(state, event)

        assertEquals(ProjectStatus.ACTIVE, newState.project.status)
        assertEquals(StepStatus.READY, newState.project.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, newState.project.plan.steps[1].status)
        assertTrue(commands.any { it is ProjectCommand.RequestStepExecution })
        assertTrue(commands.any { it is ProjectCommand.PersistState })
    }

    // ── Step lifecycle ───────────────────────────────────────────────────

    @Test
    fun `StepStarted transitions step to IN_PROGRESS`() {
        val state = activeProjectWith("s1" to emptySet())
        val event = ProjectEvent.StepStarted(projectId = "proj-1", stepId = "s1", timestamp = now)

        val (newState, _) = ProjectStateMachine.transition(state, event)
        val step = newState.project.plan.steps.first { it.id == "s1" }
        assertEquals(StepStatus.IN_PROGRESS, step.status)
    }

    @Test
    fun `StepActionExecuted increments attempts and stores notes`() {
        val state = activeProjectWith("s1" to emptySet()).let { s ->
            ProjectStateMachine.transition(
                s, ProjectEvent.StepStarted("proj-1", "s1", now)
            ).first
        }
        val event = ProjectEvent.StepActionExecuted("proj-1", "s1", "API returned 200", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)
        val step = newState.project.plan.steps.first { it.id == "s1" }
        assertEquals(1, step.attempts)
        assertEquals("API returned 200", step.notes)
    }

    @Test
    fun `StepAcceptancePassed marks step DONE and produces keys`() {
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1",
                    produces = setOf("result-a"), attempts = 1),
                PlanStep("s2", "Step 2", StepStatus.PENDING, "verify s2",
                    requires = setOf("result-a")),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.ACTIVE, plan = plan
            )
        )
        val event = ProjectEvent.StepAcceptancePassed("proj-1", "s1", now)

        val (newState, commands) = ProjectStateMachine.transition(state, event)

        val s1 = newState.project.plan.steps.first { it.id == "s1" }
        val s2 = newState.project.plan.steps.first { it.id == "s2" }
        assertEquals(StepStatus.DONE, s1.status)
        assertEquals(StepStatus.READY, s2.status)
        assertTrue("result-a" in newState.producedKeys)
        assertTrue(commands.any { it is ProjectCommand.RequestStepExecution })
    }

    @Test
    fun `StepAcceptanceFailed with retries left returns step to READY`() {
        val state = activeProjectWith("s1" to emptySet()).let { s ->
            val started = ProjectStateMachine.transition(
                s, ProjectEvent.StepStarted("proj-1", "s1", now)
            ).first
            ProjectStateMachine.transition(
                started, ProjectEvent.StepActionExecuted("proj-1", "s1", "attempt 1", now)
            ).first
        }
        val event = ProjectEvent.StepAcceptanceFailed("proj-1", "s1", "not verified", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)
        val step = newState.project.plan.steps.first { it.id == "s1" }
        assertEquals(StepStatus.READY, step.status)
    }

    @Test
    fun `StepAcceptanceFailed at max attempts marks step FAILED and skips dependents`() {
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1",
                    attempts = 3, maxAttempts = 3),
                PlanStep("s2", "Step 2", StepStatus.PENDING, "verify s2",
                    requires = setOf("s1")),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.ACTIVE, plan = plan
            )
        )
        val event = ProjectEvent.StepAcceptanceFailed("proj-1", "s1", "permanently failed", now)

        val (newState, commands) = ProjectStateMachine.transition(state, event)

        assertEquals(StepStatus.FAILED, newState.project.plan.steps[0].status)
        assertEquals(StepStatus.SKIPPED, newState.project.plan.steps[1].status)
        assertTrue(commands.any { it is ProjectCommand.NotifyUser })
    }

    // ── Blocking ─────────────────────────────────────────────────────────

    @Test
    fun `StepBlocked transitions step and project to BLOCKED when all remaining blocked`() {
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.IN_PROGRESS, "verify s1"),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = initialState().project.copy(status = ProjectStatus.ACTIVE, plan = plan)
        )
        val condition = WaitCondition(
            type = WaitConditionType.TIMER,
            params = mapOf("wake_at" to "2026-03-18T12:00:00Z"),
            registeredAt = now,
            timeoutAt = Instant.parse("2026-03-18T12:00:00Z"),
        )
        val event = ProjectEvent.StepBlocked("proj-1", "s1", condition, now)

        val (newState, commands) = ProjectStateMachine.transition(state, event)

        assertEquals(StepStatus.BLOCKED, newState.project.plan.steps[0].status)
        assertEquals(ProjectStatus.BLOCKED, newState.project.status)
        assertTrue(commands.any { it is ProjectCommand.ScheduleTimer })
    }

    @Test
    fun `StepUnblocked transitions step to READY and project back to ACTIVE`() {
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.BLOCKED, "verify s1",
                    waitCondition = WaitCondition(WaitConditionType.TIMER, emptyMap(), now)),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = initialState().project.copy(status = ProjectStatus.BLOCKED, plan = plan)
        )
        val event = ProjectEvent.StepUnblocked("proj-1", "s1", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)

        assertEquals(StepStatus.READY, newState.project.plan.steps[0].status)
        assertEquals(ProjectStatus.ACTIVE, newState.project.status)
    }

    // ── Wait condition timeout ───────────────────────────────────────────

    @Test
    fun `WaitConditionTimedOut with FAIL marks step failed`() {
        val condition = WaitCondition(
            type = WaitConditionType.TIMER,
            params = emptyMap(),
            registeredAt = now,
            onTimeout = TimeoutAction.FAIL,
        )
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.BLOCKED, "verify s1",
                    waitCondition = condition),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = initialState().project.copy(status = ProjectStatus.BLOCKED, plan = plan)
        )
        val event = ProjectEvent.WaitConditionTimedOut("proj-1", "s1", now)

        val (newState, commands) = ProjectStateMachine.transition(state, event)

        assertEquals(StepStatus.FAILED, newState.project.plan.steps[0].status)
        assertTrue(commands.any { it is ProjectCommand.NotifyUser })
    }

    @Test
    fun `WaitConditionTimedOut with RETRY puts step back to READY`() {
        val condition = WaitCondition(
            type = WaitConditionType.TIMER,
            params = emptyMap(),
            registeredAt = now,
            onTimeout = TimeoutAction.RETRY,
        )
        val plan = ProjectPlan(
            steps = listOf(
                PlanStep("s1", "Step 1", StepStatus.BLOCKED, "verify s1",
                    waitCondition = condition),
            ),
            generatedAt = now,
        )
        val state = ProjectState(
            project = initialState().project.copy(status = ProjectStatus.BLOCKED, plan = plan)
        )
        val event = ProjectEvent.WaitConditionTimedOut("proj-1", "s1", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)
        assertEquals(StepStatus.READY, newState.project.plan.steps[0].status)
    }

    // ── Suspension / resumption ──────────────────────────────────────────

    @Test
    fun `Suspended transitions project to SUSPENDED and schedules timer if resumeAt given`() {
        val state = activeProjectWith("s1" to emptySet())
        val resumeAt = Instant.parse("2026-03-18T08:00:00Z")
        val event = ProjectEvent.Suspended("proj-1", "waiting for market open", resumeAt, now)

        val (newState, commands) = ProjectStateMachine.transition(state, event)

        assertEquals(ProjectStatus.SUSPENDED, newState.project.status)
        assertEquals(resumeAt, newState.project.suspendedUntil)
        assertTrue(commands.any { it is ProjectCommand.ScheduleTimer })
    }

    @Test
    fun `Resumed transitions project back to ACTIVE`() {
        val state = ProjectState(
            project = initialState().project.copy(
                status = ProjectStatus.SUSPENDED,
                suspendedUntil = Instant.parse("2026-03-18T08:00:00Z"),
            )
        )
        val event = ProjectEvent.Resumed("proj-1", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)

        assertEquals(ProjectStatus.ACTIVE, newState.project.status)
        assertEquals(null, newState.project.suspendedUntil)
    }

    // ── Completion ───────────────────────────────────────────────────────

    @Test
    fun `Completed event marks project COMPLETED`() {
        val state = activeProjectWith()
        val event = ProjectEvent.Completed("proj-1", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)
        assertEquals(ProjectStatus.COMPLETED, newState.project.status)
    }

    @Test
    fun `Failed event marks project FAILED`() {
        val state = activeProjectWith()
        val event = ProjectEvent.Failed("proj-1", "unrecoverable error", now)

        val (newState, _) = ProjectStateMachine.transition(state, event)
        assertEquals(ProjectStatus.FAILED, newState.project.status)
    }

    // ── Happy path: multi-step project ───────────────────────────────────

    @Test
    fun `happy path - create plan execute complete`() {
        var state = initialState()

        // Create
        val (s1, _) = ProjectStateMachine.transition(state, createdEvent())
        state = s1
        assertEquals(ProjectStatus.PLANNING, state.project.status)

        // Generate plan
        val plan = simplePlan("s1" to emptySet(), "s2" to setOf("s1"))
        val (s2, _) = ProjectStateMachine.transition(
            state, ProjectEvent.PlanGenerated("proj-1", plan, now)
        )
        state = s2
        assertEquals(ProjectStatus.ACTIVE, state.project.status)

        // Start s1
        val (s3, _) = ProjectStateMachine.transition(
            state, ProjectEvent.StepStarted("proj-1", "s1", now)
        )
        state = s3

        // Execute s1
        val (s4, _) = ProjectStateMachine.transition(
            state, ProjectEvent.StepActionExecuted("proj-1", "s1", "done", now)
        )
        state = s4

        // Accept s1 -> s2 becomes READY
        val (s5, _) = ProjectStateMachine.transition(
            state, ProjectEvent.StepAcceptancePassed("proj-1", "s1", now)
        )
        state = s5
        assertEquals(StepStatus.DONE, state.project.plan.steps[0].status)
        assertEquals(StepStatus.READY, state.project.plan.steps[1].status)

        // Start + execute + accept s2
        state = ProjectStateMachine.transition(
            state, ProjectEvent.StepStarted("proj-1", "s2", now)
        ).first
        state = ProjectStateMachine.transition(
            state, ProjectEvent.StepActionExecuted("proj-1", "s2", "done", now)
        ).first
        val (final, finalCmds) = ProjectStateMachine.transition(
            state, ProjectEvent.StepAcceptancePassed("proj-1", "s2", now)
        )

        assertEquals(StepStatus.DONE, final.project.plan.steps[0].status)
        assertEquals(StepStatus.DONE, final.project.plan.steps[1].status)
        // StepCompleted signal should be emitted for the last step
        assertTrue(finalCmds.any { it is ProjectCommand.EmitSignal })
    }

    @Test
    fun `event count increments with each transition`() {
        var state = initialState()
        assertEquals(1, state.eventCount)

        state = ProjectStateMachine.transition(state, createdEvent()).first
        assertEquals(2, state.eventCount)

        val plan = simplePlan("s1" to emptySet())
        state = ProjectStateMachine.transition(
            state, ProjectEvent.PlanGenerated("proj-1", plan, now)
        ).first
        assertEquals(3, state.eventCount)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun activeProjectWith(
        vararg stepSpecs: Pair<String, Set<String>>,
    ): ProjectState {
        val state = initialState()
        val planned = if (stepSpecs.isEmpty()) {
            state.copy(project = state.project.copy(status = ProjectStatus.ACTIVE))
        } else {
            val plan = simplePlan(*stepSpecs)
            val event = ProjectEvent.PlanGenerated("proj-1", plan, now)
            val (s, _) = ProjectStateMachine.transition(
                ProjectStateMachine.transition(state, createdEvent()).first,
                event,
            )
            s
        }
        return planned
    }
}
