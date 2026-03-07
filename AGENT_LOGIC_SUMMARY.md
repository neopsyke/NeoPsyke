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
  - LLM clients (planner, action-verifier, superego, meta-reasoner, long-term-memory advisor)
    - Each cognitive role can use an independent provider/api key/base URL/model from `llm-runtime.yaml`.
    - `web_search` runtime remains independently configurable.
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
- Interactive startup runs LLM provider health probes per configured cognitive role endpoint:
  - probes use normalized URL joining (`base_url` + `/models`) so trailing slashes do not produce `//models`
  - for Google `v1beta/openai` routes, an `HTTP 404` probe on `/openai/models` falls back to native `/v1beta/models` before reporting status
- Instrumentation and metrics are wired before loop start and receive lifecycle events throughout.
- Interactive startup wires a pre-call LLM token budget gate (`LlmTokenBudgetGate`) across all cognitive-role clients and web search:
  - optional hard caps are configurable via `PlannerConfig` / `agent-runtime.yaml` (`max_run_total_tokens`, `max_run_tokens_per_provider`, `max_run_tokens_per_role`)
  - limits are enforced before outbound model calls using conservative prompt/completion estimates
  - default `0` keeps each cap disabled

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
    - external evidence hints derived from prior successful/failed evidence actions for the same root input
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
    - Enqueue a new "find safe alternative" thought with denied-action context, including structured `reason_code`.
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
  - One strict-JSON retry when initial planner output is non-parseable.
  - Normalizes `action_payload` from either JSON string or structured JSON (object/array) into a string payload.
  - Decision types:
    - `thought`
    - `action`
    - `plan` (decomposed into multiple thought steps)
    - `noop`
  - Action proposal validation against runtime available actions.
  - Secondary action verifier pass (`approve|repair|reject`) with:
    - one strict-JSON retry on parse failure
    - parse-failure circuit breaker (scoped by `root_input + action_type`) that bypasses verifier for one decision after repeated malformed verifier outputs
  - Retry policy and safe fallback to `Noop` on model/parse failures.

## Policy Gate (Superego)
- File: `src/main/kotlin/psyke/agent/superego/Superego.kt`
- Reviews each non-fallback action with layered checks:
  - deterministic hard-deny checks first (`SuperegoDeterministicConscience`)
  - LLM semantic review second (only if deterministic checks pass)
- Returns `GateDecision(allow, reason, reasonCode)` from strict JSON.
- LLM deny responses can include optional `reason_code`; deterministic denials emit policy-prefixed `reason_code`s.
- If initial LLM output is non-parseable, Superego performs one strict-JSON retry before default deny fallback.
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
- `web_search` provider routing is independent from cognitive-role routing:
  - configured directly via `web_search.provider` in `llm-runtime.yaml`.
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
- LLM callers use retry loops with bounded attempts (max 3).
- A shared pre-call token budget gate can short-circuit outbound LLM calls when projected usage would exceed configured run caps (global, per-provider, or per-role).
- Required JSON fields are validated after deserialization.
- Chat clients treat blank assistant message content as transport/protocol failure so retries/fallbacks trigger upstream.
- Prompt-injection mitigation is implemented as deterministic, model-agnostic guards outside Superego:
  - untrusted external content sanitization (`PromptInjectionDefense`)
  - untrusted-data framing before follow-up planner thoughts
  - long-term recall wrapped as untrusted data block before planner context
- On failures:
  - planner -> noop fallback
  - superego -> deny fallback
  - meta-reasoner -> continue fallback
  - long-term advisor -> parse-fallback/skip save
- Repeated denied-action loops are blocked by payload/type comparison, except when denial is classified as technical/transient. Classification prefers structured `reason_code` (for example `TECH_*`) and falls back to text heuristics only if code is missing.
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

## Episodic Memory (Logbook)
- File: `src/main/kotlin/psyke/agent/memory/episodic/SqliteLogbook.kt`
- SQLite + FTS5 append-only log of timestamped interaction summaries and keywords.
- Storage: separate DB file (default `.psyke/logbook.db`), WAL mode, synchronized access.
- Schema: `entries` table (id, ts, ts_epoch_ms, event_type, summary, keywords, action_type, run_id, metadata) with FTS5 virtual table `entries_fts` auto-synced via triggers.
- Event types recorded: `INPUT_RECEIVED`, `PLANNER_DECISION`, `ACTION_EXECUTED`, `ACTION_DENIED`, `ANSWER_DELIVERED`, `MEMORY_IMPRINT`.
- Integration through `MemoryCoordinator`:
  - `remember()` auto-journals `INPUT_RECEIVED` for user turns.
  - `maybeAssessLongTermMemory()` auto-journals `MEMORY_IMPRINT` on successful saves.
  - `journal()` public method called from Ego for planner decisions, action outcomes, denials, and answers.
- Summarization: deterministic keyword extraction (tokenize, remove stopwords, deduplicate, cap at `maxKeywordsPerEntry`). Optional LLM-based summarizer (`LlmLogbookSummarizer`, opt-in via `PSYKE_LOGBOOK_USE_LLM_SUMMARIZER=true`) with automatic fallback to deterministic on failure.
- Episodic recall: triggered by temporal intent detection (regex patterns on the latest user turn). Detected intent maps to a time window and optional FTS keyword, producing a compact timeline injected into `PlannerContext.episodicRecall`.
- Temporal-to-vector bridge: episodic summaries from temporal queries also serve as cues for `Hippocampus.recall()`, enriching long-term memory retrieval with temporal context.
- Graceful degradation: logbook is optional (`null`-safe); creation failure logs warning and runs without episodic memory.
- Configuration: `LogbookConfig` (enabled, maxSummaryChars, maxKeywordsPerEntry, retentionDays, dbPath, episodicRecallMaxChars, episodicRecallMaxResults, useLlmSummarizer) with env var overrides (`PSYKE_LOGBOOK_*`).

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
- Episodic memory (logbook) event types, journal call sites, or storage schema.
- Prompt-injection defense patterns or untrusted-content handling paths.
- Supported action types or runtime availability logic.
- Critical instrumentation events that materially change control flow visibility.
