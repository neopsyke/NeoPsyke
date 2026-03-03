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
    - Primary reasoning provider is shared across those clients.
    - `web_search` runtime can be configured with an independent provider/api key/base URL/model.
  - `Superego`
  - `MotorCortex` (answer + web search + MCP tools)
  - `LlmEgoPlanner`
  - `LlmMetaReasoner`
  - `LlmLongTermMemoryAdvisor`
  - `Hippocampus` (MCP memory adapter or noop)
  - `Ego` orchestrator
- Interactive startup now performs an MCP memory health probe before enabling memory:
  - if probe passes, memory is exposed as available and `McpHippocampus` is wired
  - if probe fails, memory is downgraded to noop for the run and reported unavailable
  - MCP memory server process now stays alive after `connect` until transport close so startup health checks can complete instead of racing a premature process exit
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
    - Optionally queue forced terminal answer under high pressure (scoped to current root input when available).
    - Optionally run long-term memory assessment (interval trigger, plus explicit remember-intent fast path).
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
  - Duplicate fallback answer enqueues for the same root input are suppressed if one is already pending.
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
    - For `answer`, optionally force a post-terminal-answer long-term memory assessment.
    - For evidence actions (`web_search`, `mcp_time`, `mcp_fetch`), enqueue follow-up thought.
    - Optionally run immediate post-allowed-action long-term memory assessment.
  - For `answer`, response latency is emitted and per-input evidence cache is cleared.
  - After `answer`, pending thoughts/actions for the same root input are pruned from queues
    (`input_resolution_cleanup`) so stale plan/follow-up work cannot continue cycling.

## Planner Logic
- File: `src/main/kotlin/psyke/agent/ego/LlmEgoPlanner.kt`
- Responsibilities:
  - Prompt assembly with budget allocation.
  - Strict JSON parse + minimal repair for invalid escapes.
  - Normalizes `action_payload` from either JSON string or structured JSON (object/array) into a string payload.
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
- Reviews each non-fallback action with layered checks:
  - deterministic hard-deny checks first (`SuperegoDeterministicConscience`)
  - LLM semantic review second (only if deterministic checks pass)
- Returns `GateDecision(allow, reason)` from strict JSON.
- Default behavior on model/parse failure is deny (safe fallback).
- Deterministic deny is authoritative (LLM cannot override a hard deny).

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
  - MCP stdio connect uses bounded startup retry (2 attempts) to absorb transient transport-close failures.
- Long-term consolidation:
  - `LlmLongTermMemoryAdvisor` decides `save|skip` with confidence/tags/summary.
  - `MemoryCoordinator` enforces:
    - interval/cooldown gates
    - explicit remember-intent fast path (one forced assessment per input when user asks to remember)
    - optional forced assessments (post-allowed-action and post-terminal-answer)
    - confidence threshold
    - recall-echo suppression (skip imprints whose summary substantially matches current recall payload)
      - thresholds are configurable via `MemoryConfig` / `EGO_LONG_TERM_MEMORY_RECALL_ECHO_*`
    - duplicate fingerprint suppression
    - temporary disable after repeated parse-fallback streaks
    - every blocked persistence emits `long_term_memory_persistence_skipped` with exact `reason_code` + `reason_detail`
  - `McpHippocampus` requests `write_mode=dedupe_if_similar` when calling memory write tools.

## Action Execution Surface
- File: `src/main/kotlin/psyke/agent/cortex/motor/MotorCortex.kt`
- Supported action types:
  - `answer`
  - `web_search`
  - `mcp_time`
  - `mcp_fetch`
- `web_search` provider routing is independent from primary reasoning provider:
  - default follows primary provider unless `web_search.provider`/`LLM_WEBSEARCH_PROVIDER` overrides it.
  - startup initialization failures (missing key, bad base URL, provider/session errors) degrade web search to an unavailable engine instead of crashing the app.
- Action availability is runtime health-dependent and fed back into planner context.
- `mcp_fetch` errors are classified as retryable vs non-retryable; non-retryable failures
  feed into the per-input circuit breaker in `DeliberationEngine`.

## Queueing Model
- File: `src/main/kotlin/psyke/agent/ego/AttentionScheduler.kt`
- Three bounded priority queues:
  - inputs (`InputPriority`)
  - thoughts (`Urgency`)
  - actions (`Urgency`)
- Supports root-input scoped queue operations used by convergence logic:
  - detect pending fallback explanation actions per input
  - detect pending plan-context thoughts per input
  - clear pending thoughts/actions for a resolved input after terminal answer
- Saturation leads to drop + instrumentation warning/event.

## Safety and Fallback Patterns
- LLM callers use retry loops with bounded attempts.
- Required JSON fields are validated after deserialization.
- Prompt-injection mitigation is implemented as deterministic, model-agnostic guards outside Superego:
  - untrusted external content sanitization (`PromptInjectionDefense`)
  - untrusted-data framing before follow-up planner thoughts
  - long-term recall wrapped as untrusted data block before planner context
- On failures:
  - planner -> noop fallback
  - superego -> deny fallback
  - meta-reasoner -> continue fallback
  - long-term advisor -> parse-fallback/skip save
- Repeated denied-action loops are detected and blocked by payload/type comparison.
- Multi-layer duplicate plan suppression (evaluated cheapest-first):
  1. **Plan budget**: hard cap (`maxPlansPerInput`, default 2) on plans emitted per root input.
  2. **Pressure gate**: suppress new plans when `decisionPressure >= planEmissionPressureThreshold` (default 0.55).
  3. **Exact hash dedup**: normalized goal+steps hash prevents identical plans from being re-emitted.
  4. **Pending plan detection**: if plan-context thoughts are already queued, suppress and enqueue a convergence thought instead.
  5. **Convergence thought dedupe**: at most one convergence thought per root input to prevent churn.
- `mcp_fetch` circuit breaker: after `FETCH_CIRCUIT_BREAKER_THRESHOLD` (default 3) non-retryable failures
  (403, 404, 401, install errors, etc.) for a root input, `MCP_FETCH` is removed from available actions,
  forcing the planner to use alternatives like `web_search`.
- Fallback answer synthesis aggregates up to 6 successful evidence signals from the deliberation session
  instead of relying only on the latest planner signal.

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
- Superego deterministic rules/validators or deterministic-vs-LLM precedence.
- Deliberation pressure formula, thresholds, or forced-terminal criteria.
- Memory recall/consolidation triggers, thresholds, or disable semantics.
- Prompt-injection defense patterns or untrusted-content handling paths.
- Supported action types or runtime availability logic.
- Critical instrumentation events that materially change control flow visibility.
