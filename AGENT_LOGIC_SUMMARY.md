# Agent Logic Summary (Living Document)

This file is a human-readable map of Psyke's main agent runtime logic.
It is intentionally high-level and should stay aligned with the code.

## Scope
- Interactive runtime path only (`runInteractiveMode`), not eval harness internals.
- Source of truth is code under `src/main/kotlin/psyke/**`.

## Runtime Wiring
- Entry:
  - `src/main/kotlin/psyke/Application.kt`
  - `src/main/kotlin/psyke/AppModeRunners.kt#runInteractiveMode`
- `runInteractiveMode` composes:
  - LLM clients (planner, superego, meta-reasoner, long-term-memory advisor)
  - `Superego`
  - `MotorCortex` (answer + web search + MCP tools)
  - `LlmEgoPlanner`
  - `LlmMetaReasoner`
  - `LlmLongTermMemoryAdvisor`
  - `Hippocampus` (MCP memory adapter or noop)
  - `Ego` orchestrator
- Instrumentation and metrics are wired before loop start and receive lifecycle events throughout.

## Main Loop (Ego)
- File: `src/main/kotlin/psyke/agent/ego/Ego.kt`
- `runInteractive()`:
  - Pulls signals from `SensoryCortex`.
  - Enqueues new user input in `AttentionScheduler`.
  - Runs `runLoop()` while there is pending work.
- `runLoop()` (bounded by `config.planner.maxLoopStepsPerInput`):
  - Scheduler priority:
    - Inputs first
    - Then highest-urgency between pending action and thought
  - Per task:
    - Advance deliberation step.
    - Dispatch one of:
      - `processInput`
      - `processThought`
      - `processAction`
    - Catch task errors, emit warning, continue loop.
    - Optionally queue forced terminal answer under high pressure.
    - Optionally run long-term memory assessment.
  - If step limit is reached with pending work:
    - Try to execute one fallback explanation action.
  - If queues drain:
    - Reset deliberation state.
    - Reset per-input memory coordinator state.

## Input Path
- `SensoryCortex` sanitizes and clamps input to configured limits.
- `processInput`:
  - Appends user turn to dialogue deque.
  - Stores turn in short-term `MemoryStore`.
  - Builds `PlannerContext`:
    - recent dialogue
    - queue snapshot
    - short-term memory summary
    - long-term memory recall (if available)
    - deliberation state and meta-guidance
    - currently available action types from `MotorCortex`
  - Runs planner (`LlmEgoPlanner`) and applies deliberation pressure override if needed.
  - Applies decision by enqueueing thought/action/plan/noop-thought.

## Thought Path
- `processThought`:
  - Drops thought if `passes >= maxThoughtPasses`.
  - If dropped and fallback explanation is allowed, enqueue fallback answer action.
  - Otherwise mirrors input path:
    - build context
    - optional meta assessment/guidance
    - planner decision
    - decision application

## Action Path
- `processAction`:
  - Fallback explanation actions bypass policy gate.
  - Normal actions go through `Superego.review`.
  - If denied:
    - Record denial metrics/evidence.
    - Enqueue a new "find safe alternative" thought with denied-action context.
  - If allowed:
    - Execute via `MotorCortex.execute`.
    - Record outcome + deliberation evidence.
    - Store assistant output in dialogue and short-term memory when applicable.
    - For evidence actions (`web_search`, `mcp_time`, `mcp_fetch`), enqueue follow-up thought.
    - Optionally run immediate long-term memory assessment.
  - For `answer`, response latency is emitted and per-input evidence cache is cleared.

## Planner Logic
- File: `src/main/kotlin/psyke/agent/ego/LlmEgoPlanner.kt`
- Responsibilities:
  - Prompt assembly with budget allocation.
  - Strict JSON parse + minimal repair for invalid escapes.
  - Decision types:
    - `thought`
    - `action`
    - `plan` (decomposed into multiple thought steps)
    - `noop`
  - Action proposal validation against runtime available actions.
  - Secondary action verifier pass (`approve|repair|reject`).
  - Retry policy and safe fallback to `Noop` on model/parse failures.

## Policy Gate (Superego)
- File: `src/main/kotlin/psyke/agent/superego/Superego.kt`
- Reviews each non-fallback action against directives.
- Returns `GateDecision(allow, reason)` from strict JSON.
- Default behavior on model/parse failure is deny (safe fallback).

## Deliberation and Convergence
- Files:
  - `src/main/kotlin/psyke/agent/ego/DeliberationProgressMonitor.kt`
  - `src/main/kotlin/psyke/agent/ego/DeliberationEngine.kt`
  - `src/main/kotlin/psyke/agent/ego/MetaReasoner.kt`
- Tracks pressure signals:
  - stale streak
  - repeats
  - denials
  - noop streak
  - model error streak
  - steps since new evidence
  - progress score
- Pressure drives:
  - meta-reasoner assessment cadence
  - guidance text for planner
  - optional override toward finalization
  - forced terminal answer enqueue under persistent circular pressure

## Memory System
- Short-term:
  - File: `src/main/kotlin/psyke/agent/memory/shortterm/MemoryStore.kt`
  - Stores recent turns + rolled summary under char budgets.
  - Produces prompt-clamped summary text.
- Long-term recall:
  - Through `MemoryCoordinator` + `Hippocampus.recall`.
  - Input-trigger recalls are cue-based; thought-trigger recalls require explicit planner query.
- Long-term consolidation:
  - `LlmLongTermMemoryAdvisor` decides `save|skip` with confidence/tags/summary.
  - `MemoryCoordinator` enforces:
    - interval/cooldown gates
    - confidence threshold
    - duplicate fingerprint suppression
    - temporary disable after repeated parse-fallback streaks

## Action Execution Surface
- File: `src/main/kotlin/psyke/agent/cortex/motor/MotorCortex.kt`
- Supported action types:
  - `answer`
  - `web_search`
  - `mcp_time`
  - `mcp_fetch`
- Action availability is runtime health-dependent and fed back into planner context.

## Queueing Model
- File: `src/main/kotlin/psyke/agent/ego/AttentionScheduler.kt`
- Three bounded priority queues:
  - inputs (`InputPriority`)
  - thoughts (`Urgency`)
  - actions (`Urgency`)
- Saturation leads to drop + instrumentation warning/event.

## Safety and Fallback Patterns
- LLM callers use retry loops with bounded attempts.
- Required JSON fields are validated after deserialization.
- On failures:
  - planner -> noop fallback
  - superego -> deny fallback
  - meta-reasoner -> continue fallback
  - long-term advisor -> parse-fallback/skip save
- Repeated denied-action loops are detected and blocked by payload/type comparison.

## Key Complexity Drivers
- Multiple LLM actors with distinct prompts and fallback semantics.
- Multi-queue scheduling with bounded loop budget.
- Pressure-based convergence (meta guidance + forced terminal mode).
- Dual memory systems (short-term compression + long-term recall/consolidation).
- Runtime-dependent action availability.

## Quick "What to Update" Checklist
Update this file whenever any of these change:
- Loop task ordering, step-limit behavior, or fallback execution policy.
- Planner decision schema or verifier verdict handling.
- Superego directives contract or default deny/allow fallback behavior.
- Deliberation pressure formula, thresholds, or forced-terminal criteria.
- Memory recall/consolidation triggers, thresholds, or disable semantics.
- Supported action types or runtime availability logic.
- Critical instrumentation events that materially change control flow visibility.
