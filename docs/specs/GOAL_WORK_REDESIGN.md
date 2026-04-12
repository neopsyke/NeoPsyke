# Goal Work Execution Redesign

**Status:** Proposal (research + architecture)
**Date:** 2026-04-09
**Branch:** refactor/typed-planner-redesign

## Problem Statement

When recurring goals fire (cron wake), the current cue-based design produces
duplicate responses, duplicate step processing, and wasted LLM calls. The
observed run `20260409T180618Z-35135` showed:

- Step1 picked 3 times, step2 picked 2+ times
- 4 duplicate/redundant `contact_user` responses for one weather reminder
- 3 separate terminal answers produced for the same goal cycle

Goal creation, editing, and deletion work correctly. The problem is exclusively
in the **goal work execution path** -- the flow from cron/timer fire through
step processing to response delivery.

## Root Causes

### 1. No exclusivity guard in `nextWorkFromCue`

`GoalManager.kt:85-107` -- When a `GoalRuntimeCue` arrives, `nextWorkFromCue()`
calls `nextRunnableStep()` which returns the first `IN_PROGRESS` step. It then
unconditionally overwrites the existing `GoalRunSession` at line 95 and returns
a new work unit. There is no check that a session for this step is already
in-flight.

```kotlin
// GoalStateMachine.kt:20-22
fun nextRunnableStep(): PlanStep? =
    goal.plan.steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
        ?: readySteps().firstOrNull()
```

An `IN_PROGRESS` step is considered "runnable," so every cue triggers a new
work cycle for it.

### 2. Multiple cue emission paths fire concurrently

Multiple independent paths emit `GoalRuntimeCue` for the same goal/step:

| Source | File | Reason |
|--------|------|--------|
| `handleStepAcceptancePassed` | `GoalStateMachine.kt:195` | `"step_completed"` |
| `handleStepAcceptanceFailed` | `GoalStateMachine.kt:219` | `"step_retry"` |
| `handlePlanGenerated` | `GoalStateMachine.kt:119` | `"plan_generated"` |
| `handlePlanRevised` | `GoalStateMachine.kt:134` | `"plan_revised"` |
| `handleStepUnblocked` | `GoalStateMachine.kt:267` | `"step_unblocked"` |
| `handleWaitConditionSatisfied` | `GoalStateMachine.kt:317` | `"wait_condition_satisfied"` |
| `handleWaitConditionTimedOut` | `GoalStateMachine.kt:352` | `"wait_condition_timeout_retry"` |
| `handleResumed` | `GoalStateMachine.kt:399` | `"goal_resumed"` |
| `handleCronCycleStarted` | `GoalStateMachine.kt:437` | `"cron_cycle_started"` |
| `finalizeGoalCycle` | `GoalManager.kt:260-270` | `session.requeueReason` (e.g. `"step_continue"`) |
| `emitRestoredWorkReady` | `GoalManager.kt:633-642` | `"goal_restored_work_ready"` |
| `onTimerWake` (ACTIVE cron) | `GoalManager.kt:698-704` | `"cron_wake_active"` -- direct call, outside state machine |

All go through `cueEmitter -> SensoryCortex.offerGoalRuntimeCue()`, which
buffers into a 1024-capacity channel with zero deduplication.

### 3. Expensive CONTINUE roundtrip

The `CONTINUE` verdict path goes:
`session.requeueReason` -> `finalizeGoalCycle` -> `cueEmitter` ->
channel -> `nextSignal` -> `ingest` -> `nextWorkFromCue` ->
`enqueueGoalWork` -> `runLoop`

This is a full signal cycle for what should be an inline retry.

### 4. `clearGoalWork` is dead code

`AttentionScheduler.clearGoalWork()` (lines 198-200) exists but is never called.
`cleanupAfterProjectAdvance()` in Ego does not clear stale queued goal work.

### 5. No action-level guard on step completion

`ContactUserActionPlugin.execute()` delivers unconditionally. If an old
in-flight work cycle's planner enqueues a `contact_user` and the step has since
been marked DONE by a newer cycle, the old action still executes.

### 6. Timer emits cues outside the state machine

`onTimerWake` for ACTIVE cron goals calls `cueEmitter` directly
(`GoalManager.kt:698-704`), bypassing the event-sourced command dispatch that
all other state transitions use. This creates a dual-path confusion.

## Current Architecture

```
TimerScheduler ──(cron fire)──> GoalManager.onTimerWake()
                                    │
GoalStateMachine ──(commands)──>    ├──> cueEmitter(GoalRuntimeCue)
                                    │         │
GoalManager.finalizeGoalCycle() ────┘         │
                                              ▼
                                SensoryCortex.offerGoalRuntimeCue()
                                              │
                                   syntheticSignals Channel(1024)
                                              │
                                              ▼
                                  Ego.runInteractive() loop
                                    sensoryCortex.nextSignal()
                                              │
                                              ▼
                                  StimulusIngressCoordinator.ingest()
                                              │
                                              ▼
                                  GoalManager.nextWorkFromCue(cue)
                                    creates GoalRunSession (overwrites!)
                                    returns GoalRunActivation
                                              │
                                              ▼
                                  AttentionScheduler.enqueueGoalWork()
                                              │
                                              ▼
                                        Ego.runLoop()
                                    processGoalWork()
                                              │
                                              ▼
                                  cleanupAfterProjectAdvance()
                                    GoalManager.finalizeGoalCycle()
                                    (may emit another cue -> cycle repeats)
```

Key concurrency observations:
- Single-threaded Ego loop reads, multi-threaded writes to synthetic channel
- `cueEmitter` can be called from: TimerScheduler coroutine, WaitConditionMonitor
  coroutine, Ego loop thread (via finalizeGoalCycle or dispatchCommands)
- The channel is concurrent-safe but provides no deduplication
- Synthetic channel has drop-oldest semantics under pressure (1024 capacity)

## Proposed Architecture: Owned Lifecycle Model

Replace "react to cues" with "own the execution." `GoalManager` becomes the
execution authority that grants exclusive work permits. Cues become lightweight
wake signals that say "check if there's work," not "do this work."

```
GoalWakeChannel          GoalManager                    Ego
(notification bus)       (execution authority)           (worker)

 wake(goalId) ------>    acquireWork(goalId) --------->  runLoop()
                         returns null if locked
                         returns GoalRunActivation       processGoalWork()
                         if acquired exclusively
                                                         releaseWork(goalId)
                                    <--------------------
                         returns WorkOutcome:
                           .DONE
                           .CONTINUE -> re-acquire
                           .STEP_ADVANCED -> wake again
```

### A. GoalWakeChannel

Replaces direct `cueEmitter -> syntheticSignals` path. Deduplicated: only one
pending wake per goal at any time.

```kotlin
class GoalWakeChannel {
    private val pendingWakes = ConcurrentHashMap<String, GoalWakeSignal>()
    private val channel = Channel<GoalWakeSignal>(capacity = 256)

    fun wake(goalId: String, reason: String): Boolean {
        val signal = GoalWakeSignal(goalId, reason)
        val prev = pendingWakes.putIfAbsent(goalId, signal)
        if (prev != null) return false  // Already pending, absorbed
        channel.trySend(signal)
        return true
    }

    fun consume(goalId: String) {
        pendingWakes.remove(goalId)
    }
}

data class GoalWakeSignal(val goalId: String, val reason: String)
```

All cue emitters (state machine, timer, finalizeGoalCycle, restored-work) call
`wakeChannel.wake(goalId, reason)`. Step ID is not in the signal --
`GoalManager.acquireWork` determines which step to run.

### B. GoalManager: Exclusive Acquisition

```kotlin
// Active executions: at most one per goal
private val activeExecutions = ConcurrentHashMap<String, GoalExecution>()

fun acquireWork(goalId: String): GoalRunActivation? {
    val state = states[goalId] ?: return null
    val step = state.nextRunnableStep() ?: return null
    val rootInputId = buildGoalRootInputId(state.id, step.id)

    // CAS: only one caller wins per goal
    val execution = GoalExecution(goalId, step.id, rootInputId, Instant.now())
    if (activeExecutions.putIfAbsent(goalId, execution) != null) {
        logger.debug { "Goal work already active: goal=$goalId, skipping" }
        return null
    }

    // Transition READY -> IN_PROGRESS if needed
    if (step.status == StepStatus.READY) {
        applyEvent(state.id, GoalEvent.StepStarted(state.id, step.id))
    }
    return GoalContextLoader.buildWorkUnit(...)
}

fun releaseWork(goalId: String): WorkOutcome {
    val execution = activeExecutions.remove(goalId) ?: return WorkOutcome.NOTHING
    // Apply WorkCycleCompleted event, write artifacts...
    val state = states[goalId] ?: return WorkOutcome.DONE
    return when {
        execution.requeueReason == "step_continue" &&
            state.nextRunnableStep()?.id == execution.stepId -> WorkOutcome.CONTINUE
        state.nextRunnableStep() != null -> WorkOutcome.STEP_ADVANCED
        else -> WorkOutcome.DONE
    }
}

enum class WorkOutcome { DONE, CONTINUE, STEP_ADVANCED, NOTHING }
```

`putIfAbsent` is the key: atomic check-and-set. If another execution is active
for this goal, the caller gets `null` and moves on.

### C. Ego Integration: Inline Continuation

```kotlin
// In Ego, after runLoop completes for goal work:
when (val outcome = goalsGateway.releaseWork(goalId)) {
    WorkOutcome.CONTINUE -> {
        // Re-acquire inline, no channel roundtrip
        goalsGateway.acquireWork(goalId)?.let { work ->
            scheduler.enqueueGoalWork(work, shapeOpportunity(work))
            runLoop()
        }
    }
    WorkOutcome.STEP_ADVANCED -> {
        // Step completed, next step available -- wake for deferred pickup
        wakeChannel.wake(goalId, "step_advanced")
    }
    WorkOutcome.DONE -> { /* Goal complete or no more steps */ }
    WorkOutcome.NOTHING -> { /* Execution was already released */ }
}
```

CONTINUE avoids the full channel roundtrip. STEP_ADVANCED goes through the wake
channel so normal signal processing order is respected (user inputs may have
arrived while the goal was executing).

### D. TimerScheduler Unification

`onTimerWake` for ACTIVE cron goals currently calls `cueEmitter` directly.
Instead:

```kotlin
// In onTimerWake, for ACTIVE cron goals:
wakeChannel.wake(goalId, "cron_wake_active")
```

For COMPLETED/FAILED cron goals that need a cycle reset, the existing
`applyEvent(CronCycleStarted)` path is kept -- it emits `EmitWorkReady`
commands that `dispatchCommands` converts into `wakeChannel.wake(...)` calls.

## What This Eliminates

| Current Problem | How It's Solved |
|-----------------|-----------------|
| Same step picked multiple times | `putIfAbsent` exclusive lock per goal |
| Session overwrite | No sessions -- execution tracking replaces them |
| Multiple cue paths racing | All paths call `wake()`, which is deduplicated |
| CONTINUE channel roundtrip | Inline re-acquire in Ego |
| `clearGoalWork` dead code | Not needed -- scheduler only has work from `acquireWork` |
| Timer emitting outside state machine | Unified through `wake()` |
| No action-level guard | Moot -- only one execution per goal, no stale actions |

## Migration Path

1. Introduce `GoalWakeChannel` and `GoalExecution` alongside existing code
2. Replace `cueEmitter` lambda with `wakeChannel::wake` in `GoalManager` constructor
3. Replace `nextWorkFromCue` with `acquireWork` (can keep the method name on the interface initially)
4. Replace `finalizeGoalCycle` with `releaseWork`
5. Update Ego's signal handler to call `acquireWork` when it receives a wake signal
6. Add inline continuation for `CONTINUE` outcome
7. Remove `GoalRunSession`, `GoalRuntimeCue`, `requeueReason` field, and `clearGoalWork`

Goal creation, editing, and deletion don't touch this path -- they go through
`executeOperation` which is a separate flow. They'd just call
`wakeChannel.wake()` when a newly created/edited goal has runnable work.

## Files Affected

| File | Change |
|------|--------|
| `agent/goal/GoalManager.kt` | Replace `nextWorkFromCue`/`finalizeGoalCycle`/`GoalRunSession` with `acquireWork`/`releaseWork`/`GoalExecution` |
| `agent/goal/GoalsGateway.kt` | Update interface methods |
| `agent/goal/GoalStateMachine.kt` | `EmitWorkReady` commands carry goalId only (no stepId) |
| `agent/goal/TimerScheduler.kt` | Callback becomes `wakeChannel::wake` instead of `cueEmitter` |
| `agent/cortex/sensory/SensoryCortex.kt` | Add `GoalWakeChannel` integration (or separate class) |
| `agent/ego/Ego.kt` | Handle wake signals, inline CONTINUE, call `releaseWork` |
| `agent/ego/StimulusIngressCoordinator.kt` | Replace `enqueueGoalWork` with `acquireWork`-based path |
| `agent/ego/AttentionScheduler.kt` | Remove `clearGoalWork` dead code |
| `AppModeRunners.kt` | Wire `GoalWakeChannel` |

## Evidence (Run 20260409T180618Z-35135)

### Goal step pickup duplication

From events JSONL:
- Line 433 (18:13:03): Goal work picked step1 (cycle 1)
- Line 511 (18:13:08): Goal work picked step1 (cycle 2, after step_retry)
- Line 620 (18:13:15): Goal work picked step1 (cycle 3, after step_retry)
- Line 938 (18:13:41): Goal work picked step2 (cycle 1)
- Line 1154 (18:14:00): Goal work picked step2 (cycle 2)

### Duplicate contact_user responses

- Line 925 (18:13:40): Weather + confirmation (step1, legitimate)
- Line 1067 (18:13:53): "reached diminishing returns" (step2, forced terminal)
- Line 1141 (18:13:59): Same weather message (step1 lingering)
- Line 1230 (18:14:03): Same weather message (step1 lingering)

### Terminal answer count

3 terminal answers at steps 21, 29, and 33 within 23 seconds.
