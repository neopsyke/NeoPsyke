package psyke.agent.project

import psyke.agent.cortex.sensory.ProjectSignal
import java.nio.file.Path
import java.time.Instant

/**
 * Immutable snapshot of a project's full state, reconstructable by replaying events.
 */
data class ProjectState(
    val project: Project,
    val producedKeys: Set<String> = emptySet(),
    val eventCount: Int = 0,
) {
    val id: String get() = project.id

    fun readySteps(): List<PlanStep> =
        project.plan.steps.filter { it.status == StepStatus.READY }

    fun nextRunnableStep(): PlanStep? =
        project.plan.steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: readySteps().firstOrNull()

    fun isTerminal(): Boolean =
        project.status == ProjectStatus.COMPLETED || project.status == ProjectStatus.FAILED
}

/**
 * Pure state machine: no side effects, no I/O.
 * Returns the new state and a list of commands the ProjectManager should dispatch.
 */
object ProjectStateMachine {

    fun transition(
        state: ProjectState,
        event: ProjectEvent,
    ): Pair<ProjectState, List<ProjectCommand>> {
        require(event.projectId == state.id) {
            "Event projectId '${event.projectId}' does not match state id '${state.id}'"
        }
        val commands = mutableListOf<ProjectCommand>()
        val newState = when (event) {
            is ProjectEvent.Created -> handleCreated(state, event, commands)
            is ProjectEvent.PlanGenerated -> handlePlanGenerated(state, event, commands)
            is ProjectEvent.PlanRevised -> handlePlanRevised(state, event, commands)
            is ProjectEvent.StepStarted -> handleStepStarted(state, event)
            is ProjectEvent.StepActionExecuted -> handleStepActionExecuted(state, event)
            is ProjectEvent.StepAcceptancePassed -> handleStepAcceptancePassed(state, event, commands)
            is ProjectEvent.StepAcceptanceFailed -> handleStepAcceptanceFailed(state, event, commands)
            is ProjectEvent.StepBlocked -> handleStepBlocked(state, event, commands)
            is ProjectEvent.StepUnblocked -> handleStepUnblocked(state, event, commands)
            is ProjectEvent.StepSkipped -> handleStepSkipped(state, event, commands)
            is ProjectEvent.WaitConditionRegistered -> handleWaitConditionRegistered(state, event, commands)
            is ProjectEvent.WaitConditionSatisfied -> handleWaitConditionSatisfied(state, event, commands)
            is ProjectEvent.WaitConditionTimedOut -> handleWaitConditionTimedOut(state, event, commands)
            is ProjectEvent.Suspended -> handleSuspended(state, event, commands)
            is ProjectEvent.Resumed -> handleResumed(state, event, commands)
            is ProjectEvent.Completed -> handleCompleted(state, event, commands)
            is ProjectEvent.PriorityChanged -> handlePriorityChanged(state, event, commands)
            is ProjectEvent.Failed -> handleFailed(state, event, commands)
            is ProjectEvent.ContextUpdated -> state
            is ProjectEvent.WorkCycleCompleted -> handleWorkCycleCompleted(state, event, commands)
        }
        val finalState = newState.copy(eventCount = newState.eventCount + 1)
        return finalState to commands
    }

    /**
     * Create an initial [ProjectState] from a [ProjectEvent.Created] event.
     */
    fun initialState(event: ProjectEvent.Created, workspacePath: Path): ProjectState =
        ProjectState(
            project = Project(
                id = event.projectId,
                title = event.title,
                instruction = event.instruction,
                status = ProjectStatus.PLANNING,
                priority = event.priority,
                plan = ProjectPlan.empty(),
                completionCriteria = event.completionCriteria,
                createdAt = event.timestamp,
                workspacePath = workspacePath,
            ),
            eventCount = 1,
        )

    // ── Event handlers ───────────────────────────────────────────────────

    private fun handleCreated(
        state: ProjectState,
        event: ProjectEvent.Created,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        commands += ProjectCommand.PersistProject(event.projectId)
        return state.copy(
            project = state.project.copy(
                title = event.title,
                instruction = event.instruction,
                status = ProjectStatus.PLANNING,
                priority = event.priority,
                completionCriteria = event.completionCriteria,
            )
        )
    }

    private fun handlePlanGenerated(
        state: ProjectState,
        event: ProjectEvent.PlanGenerated,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val stepsWithReady = promoteReadySteps(event.plan.steps, state.producedKeys)
        val newPlan = event.plan.copy(steps = stepsWithReady)
        val newProject = state.project.copy(plan = newPlan, status = ProjectStatus.ACTIVE)
        commands += ProjectCommand.PersistProject(state.id)
        emitWorkReadyIfRunnable(state.id, stepsWithReady, "plan_generated", commands)
        return state.copy(project = newProject)
    }

    private fun handlePlanRevised(
        state: ProjectState,
        event: ProjectEvent.PlanRevised,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val stepsWithReady = promoteReadySteps(event.plan.steps, state.producedKeys)
        val newPlan = event.plan.copy(steps = stepsWithReady, revisedAt = event.timestamp)
        val newProject = state.project.copy(plan = newPlan, status = ProjectStatus.ACTIVE)
        commands += ProjectCommand.PersistProject(state.id)
        emitWorkReadyIfRunnable(state.id, stepsWithReady, "plan_revised", commands)
        return state.copy(project = newProject)
    }

    private fun handleStepStarted(
        state: ProjectState,
        event: ProjectEvent.StepStarted,
    ): ProjectState = updateStep(state, event.stepId) { step ->
        step.copy(status = StepStatus.IN_PROGRESS, lastAttemptAt = event.timestamp)
    }.let { it.copy(project = it.project.copy(lastWorkedAt = event.timestamp)) }

    private fun handleStepActionExecuted(
        state: ProjectState,
        event: ProjectEvent.StepActionExecuted,
    ): ProjectState = updateStep(state, event.stepId) { step ->
        step.copy(attempts = step.attempts + 1, notes = event.actionResult)
    }

    private fun handleStepAcceptancePassed(
        state: ProjectState,
        event: ProjectEvent.StepAcceptancePassed,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val withDone = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.DONE, completedAt = event.timestamp)
        }
        val completedStep = withDone.project.plan.steps.first { it.id == event.stepId }
        val newProducedKeys = withDone.producedKeys + completedStep.produces
        val stepsPromoted = promoteReadySteps(withDone.project.plan.steps, newProducedKeys)
        val completed = isProjectComplete(withDone.copy(project = withDone.project.copy(plan = withDone.project.plan.copy(steps = stepsPromoted))))
        val updatedProject = withDone.project.copy(
            plan = withDone.project.plan.copy(steps = stepsPromoted),
            status = if (completed) ProjectStatus.COMPLETED else withDone.project.status,
        )
        val newState = withDone.copy(project = updatedProject, producedKeys = newProducedKeys)

        if (completed) {
            commands += ProjectCommand.PersistProject(state.id)
            return newState
        }

        emitWorkReadyIfRunnable(state.id, stepsPromoted, "step_completed", commands)
        commands += ProjectCommand.PersistProject(state.id)
        return newState
    }

    private fun handleStepAcceptanceFailed(
        state: ProjectState,
        event: ProjectEvent.StepAcceptanceFailed,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val step = state.project.plan.steps.first { it.id == event.stepId }
        return if (step.attempts >= step.maxAttempts) {
            val failed = updateStep(state, event.stepId) { it.copy(status = StepStatus.FAILED) }
            val withSkipped = skipDependentSteps(failed, event.stepId, "dependency '${event.stepId}' failed")
            commands += ProjectCommand.NotifyUser(state.id, "Step '${step.description}' failed: ${event.reason}")
            val terminal = if (isProjectFailed(withSkipped)) {
                withSkipped.copy(project = withSkipped.project.copy(status = ProjectStatus.FAILED))
            } else {
                withSkipped
            }
            commands += ProjectCommand.PersistProject(state.id)
            terminal
        } else {
            val retryState = updateStep(state, event.stepId) { it.copy(status = StepStatus.READY) }
            commands += ProjectCommand.EmitWorkReady(
                ProjectSignal.WorkReady(state.id, event.stepId, "step_retry")
            )
            commands += ProjectCommand.PersistProject(state.id)
            retryState
        }
    }

    private fun handleStepBlocked(
        state: ProjectState,
        event: ProjectEvent.StepBlocked,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.BLOCKED, waitCondition = event.waitCondition)
        }
        commands += ProjectCommand.RegisterWaitCondition(state.id, event.stepId, event.waitCondition)
        if (event.waitCondition.type == WaitConditionType.TIMER && event.waitCondition.timeoutAt != null) {
            commands += ProjectCommand.ScheduleWakeTimer(
                projectId = state.id,
                stepId = event.stepId,
                wakeAt = event.waitCondition.timeoutAt,
                reason = "step_blocked_timer"
            )
        }
        val projectStatus = if (allRemainingStepsBlocked(newState)) {
            ProjectStatus.BLOCKED
        } else {
            newState.project.status
        }
        commands += ProjectCommand.PersistProject(state.id)
        return newState.copy(project = newState.project.copy(status = projectStatus))
    }

    private fun handleStepUnblocked(
        state: ProjectState,
        event: ProjectEvent.StepUnblocked,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.READY, waitCondition = null)
        }
        val projectStatus = if (newState.project.status == ProjectStatus.BLOCKED) {
            ProjectStatus.ACTIVE
        } else {
            newState.project.status
        }
        commands += ProjectCommand.ClearWaitCondition(state.id, event.stepId)
        commands += ProjectCommand.EmitWorkReady(
            ProjectSignal.WorkReady(state.id, event.stepId, "step_unblocked")
        )
        commands += ProjectCommand.PersistProject(state.id)
        return newState.copy(project = newState.project.copy(status = projectStatus))
    }

    private fun handleStepSkipped(
        state: ProjectState,
        event: ProjectEvent.StepSkipped,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val newState = updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.SKIPPED)
        }
        val withCascade = skipDependentSteps(newState, event.stepId, "dependency '${event.stepId}' skipped")
        commands += ProjectCommand.PersistProject(state.id)
        return withCascade
    }

    private fun handleWaitConditionRegistered(
        state: ProjectState,
        event: ProjectEvent.WaitConditionRegistered,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        if (event.condition.type == WaitConditionType.TIMER) {
            val wakeAt = event.condition.params["wake_at"]?.let { Instant.parse(it) }
            if (wakeAt != null) {
                commands += ProjectCommand.ScheduleWakeTimer(
                    projectId = state.id,
                    stepId = event.stepId,
                    wakeAt = wakeAt,
                    reason = "wait_condition_registered"
                )
            }
        }
        commands += ProjectCommand.RegisterWaitCondition(state.id, event.stepId, event.condition)
        commands += ProjectCommand.PersistProject(state.id)
        return updateStep(state, event.stepId) { step ->
            step.copy(waitCondition = event.condition, status = StepStatus.BLOCKED)
        }
    }

    private fun handleWaitConditionSatisfied(
        state: ProjectState,
        event: ProjectEvent.WaitConditionSatisfied,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        commands += ProjectCommand.ClearWaitCondition(state.id, event.stepId)
        commands += ProjectCommand.EmitWorkReady(
            ProjectSignal.WorkReady(state.id, event.stepId, "wait_condition_satisfied")
        )
        commands += ProjectCommand.PersistProject(state.id)
        return updateStep(state, event.stepId) { step ->
            step.copy(status = StepStatus.READY, waitCondition = null)
        }
    }

    private fun handleWaitConditionTimedOut(
        state: ProjectState,
        event: ProjectEvent.WaitConditionTimedOut,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val step = state.project.plan.steps.first { it.id == event.stepId }
        val action = step.waitCondition?.onTimeout ?: TimeoutAction.FAIL
        return when (action) {
            TimeoutAction.FAIL -> {
                val failed = updateStep(state, event.stepId) { it.copy(status = StepStatus.FAILED, waitCondition = null) }
                commands += ProjectCommand.NotifyUser(state.id, "Step '${step.description}' timed out")
                commands += ProjectCommand.ClearWaitCondition(state.id, event.stepId)
                commands += ProjectCommand.PersistProject(state.id)
                skipDependentSteps(failed, event.stepId, "dependency '${event.stepId}' timed out")
            }
            TimeoutAction.RETRY -> {
                commands += ProjectCommand.ClearWaitCondition(state.id, event.stepId)
                commands += ProjectCommand.EmitWorkReady(
                    ProjectSignal.WorkReady(state.id, event.stepId, "wait_condition_timeout_retry")
                )
                commands += ProjectCommand.PersistProject(state.id)
                updateStep(state, event.stepId) { it.copy(status = StepStatus.READY, waitCondition = null) }
            }
            TimeoutAction.ESCALATE -> {
                commands += ProjectCommand.NotifyUser(state.id, "Step '${step.description}' timed out — escalating to user")
                commands += ProjectCommand.PersistProject(state.id)
                state
            }
        }
    }

    private fun handleSuspended(
        state: ProjectState,
        event: ProjectEvent.Suspended,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        if (event.resumeAt != null) {
            commands += ProjectCommand.ScheduleWakeTimer(
                projectId = state.id,
                wakeAt = event.resumeAt,
                reason = "project_suspended_resume"
            )
        }
        commands += ProjectCommand.PersistProject(state.id)
        return state.copy(
            project = state.project.copy(
                status = ProjectStatus.SUSPENDED,
                suspendedUntil = event.resumeAt,
            )
        )
    }

    private fun handleResumed(
        state: ProjectState,
        event: ProjectEvent.Resumed,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        val resumed = state.copy(
            project = state.project.copy(
                status = ProjectStatus.ACTIVE,
                suspendedUntil = null,
            )
        )
        resumed.nextRunnableStep()?.let { step ->
            commands += ProjectCommand.EmitWorkReady(
                ProjectSignal.WorkReady(state.id, step.id, "project_resumed")
            )
        }
        commands += ProjectCommand.PersistProject(state.id)
        return resumed
    }

    private fun handleCompleted(
        state: ProjectState,
        event: ProjectEvent.Completed,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        commands += ProjectCommand.PersistProject(state.id)
        return state.copy(
            project = state.project.copy(status = ProjectStatus.COMPLETED)
        )
    }

    private fun handleFailed(
        state: ProjectState,
        event: ProjectEvent.Failed,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        commands += ProjectCommand.PersistProject(state.id)
        return state.copy(
            project = state.project.copy(status = ProjectStatus.FAILED)
        )
    }

    private fun handlePriorityChanged(
        state: ProjectState,
        event: ProjectEvent.PriorityChanged,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        commands += ProjectCommand.PersistProject(state.id)
        return state.copy(
            project = state.project.copy(priority = event.priority)
        )
    }

    private fun handleWorkCycleCompleted(
        state: ProjectState,
        event: ProjectEvent.WorkCycleCompleted,
        commands: MutableList<ProjectCommand>,
    ): ProjectState {
        commands += ProjectCommand.PersistProject(state.id)
        return state.copy(
            project = state.project.copy(lastWorkedAt = event.timestamp)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun updateStep(
        state: ProjectState,
        stepId: String,
        transform: (PlanStep) -> PlanStep,
    ): ProjectState {
        val newSteps = state.project.plan.steps.map { step ->
            if (step.id == stepId) transform(step) else step
        }
        return state.copy(
            project = state.project.copy(
                plan = state.project.plan.copy(steps = newSteps)
            )
        )
    }

    private fun promoteReadySteps(
        steps: List<PlanStep>,
        producedKeys: Set<String>,
    ): List<PlanStep> {
        val doneIds = steps.filter { it.status == StepStatus.DONE }.map { it.id }.toSet()
        return steps.map { step ->
            if (step.status == StepStatus.PENDING && step.requires.all { req ->
                    req in doneIds || req in producedKeys
                }) {
                step.copy(status = StepStatus.READY)
            } else {
                step
            }
        }
    }

    private fun skipDependentSteps(
        state: ProjectState,
        failedStepId: String,
        reason: String,
    ): ProjectState {
        val failedProduces = state.project.plan.steps
            .first { it.id == failedStepId }
            .produces
        val stepsToSkip = state.project.plan.steps.filter { step ->
            step.status in setOf(StepStatus.PENDING, StepStatus.READY) &&
                (failedStepId in step.requires || step.requires.any { it in failedProduces })
        }
        if (stepsToSkip.isEmpty()) return state
        var current = state
        for (step in stepsToSkip) {
            current = updateStep(current, step.id) { it.copy(status = StepStatus.SKIPPED) }
        }
        return current
    }

    private fun isProjectComplete(state: ProjectState): Boolean =
        state.project.plan.steps.all { it.status in setOf(StepStatus.DONE, StepStatus.SKIPPED) }

    private fun isProjectFailed(state: ProjectState): Boolean {
        val remaining = state.project.plan.steps.filter {
            it.status !in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED)
        }
        return remaining.isEmpty() && state.project.plan.steps.any { it.status == StepStatus.FAILED }
    }

    private fun allRemainingStepsBlocked(state: ProjectState): Boolean =
        state.project.plan.steps
            .filter { it.status !in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED) }
            .all { it.status == StepStatus.BLOCKED }

    private fun emitWorkReadyIfRunnable(
        projectId: String,
        steps: List<PlanStep>,
        reason: String,
        commands: MutableList<ProjectCommand>,
    ) {
        val step = steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: steps.firstOrNull { it.status == StepStatus.READY }
            ?: return
        commands += ProjectCommand.EmitWorkReady(
            ProjectSignal.WorkReady(projectId, step.id, reason)
        )
    }
}
