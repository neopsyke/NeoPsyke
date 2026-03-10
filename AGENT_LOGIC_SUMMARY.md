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
    - `meta_reasoner_fallback` is optional and only used when repeated empty-content transport failures occur on the primary meta-reasoner endpoint.
    - Optional `model_catalog` in `llm-runtime.yaml` provides per-provider model ROI metadata (`tier`, `token_weight`, optional cost fields).
    - Superego and memory-advisor read `token_weight` for their configured models and apply it to dynamic completion-budget scaling.
    - When `SuperegoConfig.twoStageReviewEnabled` is on, runtime resolves a cheaper primary superego model from `model_catalog` (same provider) and keeps the configured superego model as escalation stage.
    - Supported cognitive-role providers are `groq`, `mistral`, `google`, and `openai`.
    - OpenAI moderation utility (`omni-moderation-latest`) exists as a standalone callable path and is not auto-wired into cognitive-role chat calls.
    - `web_search` runtime remains independently configurable.
  - `Superego`
  - `ActionRegistry` (startup plugin discovery via `ServiceLoader<AgentActionPluginFactory>`)
  - `MotorCortex` (plugin-dispatched action execution)
  - `LlmEgoPlanner`
  - `LlmMetaReasoner`
  - `LlmLongTermMemoryAdvisor`
  - `Hippocampus` (MCP memory adapter or noop)
  - `TaskWorkspaceStore` (ephemeral per-request notebook/workspace)
  - `TaskWorkspaceFinalizer` (noop or `LlmTaskWorkspaceFinalizer`)
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
  - Interactive wiring uses `AsyncSensoryInputSource` with stdin enabled in control-only mode:
    - terminal `exit` emits `ExitRequested(source="stdin")` and stops the loop
    - non-command stdin text is ignored as chat input and never enqueued to the scheduler
  - Default chat answers from web sessions are delivered via dashboard chat events, not terminal stdout.
  - Interactive startup requires dashboard mode enabled; without dashboard input path the loop does not start.
- `runLoop()` (bounded by `config.planner.maxLoopStepsPerInput`):
  - Scheduler priority:
    - Inputs first
    - Then highest-urgency between pending action and thought
  - Per task:
    - Activate session context for the task (`sessionId` + interlocutor) before deliberation/memory updates.
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
    - Clear active task workspaces and pending workspace gates, while preserving per-session workspace digests.

## Input Path
- `SensoryCortex` sanitizes and clamps input to configured limits.
- `ConversationContext` is mandatory end-to-end and requires a non-blank `sessionId`.
- For incoming inputs with `ConversationContext.interlocutor=UNKNOWN`, `SensoryCortex` resolves interlocutor via `InterlocutorResolver`.
- Session id derivation from `source` (for example `chat:<sessionId>`) only applies when incoming context uses the default session id.
- `PendingInput` carries:
  - `source` metadata (for example `chat:<sessionId>`) so runtime telemetry can map root requests to conversation sessions.
  - `rootInputId` (UUID string identity for request-scoped orchestration)
  - `receivedAtMs` (request timing anchor, not an identity key)
- `processInput`:
  - Appends user turn to dialogue deque.
  - Stores turn in short-term `MemoryStore`.
  - Creates/refreshes a task-scoped ephemeral workspace keyed by `rootInputId`; workspace telemetry also carries `root_input_received_at_ms` for latency/timing views.
  - Builds `PlannerContext`:
    - recent dialogue
    - queue snapshot
    - short-term memory summary
    - long-term memory recall (if available)
    - reflection-lesson recall (if available)
    - task workspace summary (index + compact section summaries, if enabled)
    - external evidence hints derived from prior successful/failed evidence actions for the same root input
    - deliberation state and meta-guidance
    - currently available action types from `MotorCortex`
    - dispatchable action set + per-action planner definitions (description/payload guidance/example)
  - Runs planner (`LlmEgoPlanner`) and applies deliberation pressure override if needed.
  - Applies decision by enqueueing thought/action/plan/noop-thought.

## Thought Path
- `processThought`:
  - Drops thought if `passes >= maxThoughtPasses`.
  - If dropped and fallback explanation is allowed, enqueue fallback answer action.
  - Duplicate fallback answer enqueues are suppressed per `(root input, sessionId)` scope so one session cannot block fallback for another.
  - Otherwise mirrors input path:
    - build context
    - optional meta assessment/guidance
    - planner decision
    - decision application

## Action Path
- `processAction`:
- For terminal `answer` actions, runs task-workspace final-pass processing before action execution:
    - records candidate answer draft into workspace
    - builds final compilation from workspace sections/evidence
    - applies workspace-confidence gate (`finalPassMinWorkspaceConfidence`)
    - runs `TaskWorkspaceFinalizer` rewrite when enabled
    - applies model-confidence gate (`finalPassMinModelConfidence`)
    - keeps original payload on any gate/finalizer failure path
  - Emits lightweight workspace-head telemetry (`task_workspace_head`) on workspace mutations.
  - When `TaskWorkspaceConfig.debugCaptureEnabled` is on, emits full debug snapshots (`task_workspace_debug_snapshot`) for dashboard-only inspection.
  - Fallback explanation actions bypass policy gate.
  - Normal actions pass through deterministic `TaskVerifier` first (task-truth/sufficiency gate), then `Superego.review`.
    - Current deterministic checks focus on verification-sensitive final answers (for example latest/current/price/news/schedule/weather/law/rates/version) and require at least one successful evidence action before allowing terminal answer.
    - Forced-terminal system answers (decision-pressure safety path) are exempt from TaskVerifier evidence requirement.
  - If denied:
    - Record denial metrics/evidence.
    - Enqueue a new "find safe alternative" thought with denied-action context, including structured `reason_code`.
    - Attempt reflection-lesson persistence into long-term memory (filtered; technical/system failures are skipped).
  - If allowed:
    - Execute via `MotorCortex.execute`.
    - Record outcome + deliberation evidence.
    - Record non-answer action outcomes into the task workspace (when enabled).
    - Store assistant output in dialogue and short-term memory when applicable.
    - For `answer`, optionally force a post-terminal-answer long-term memory assessment.
    - Follow-up thought behavior is action-descriptor-driven (`requiresFollowUpThought` + `followUpPrefix`).
    - Optionally run immediate post-allowed-action long-term memory assessment.
- For `answer`, response latency is emitted and per-input evidence cache is cleared.
- After `answer`, pending thoughts/actions for the same `(root input, sessionId)` scope are pruned from queues
    (`input_resolution_cleanup`) so stale plan/follow-up work cannot continue cycling or leak across sessions.
- After `answer`, task workspace digest is captured into the session digest ring before workspace destruction.
- After `answer`, the task workspace for that root input is destroyed (`task_workspace_destroyed`).

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
  - Redundancy handling is planner-side and cost-oriented:
    - planner prompt treats repeated external calls as low-value unless refresh/retry is explicitly requested
    - action verifier can reject low-value repeated external calls when evidence hints already contain usable signal
    - `Ego` emits `external_action_redundancy_signal` telemetry (soft signal, not policy deny) with repeated signature hit count and evidence state
  - Secondary action verifier pass (`approve|repair|reject`) with:
    - one strict-JSON retry on parse failure
    - parse-failure circuit breaker (scoped by `root_input + action_type`) that bypasses verifier for one decision after repeated malformed verifier outputs
    - structured follow-up lineage guard: follow-up thoughts carry origin action metadata (`originActionType`, `originActionObservedEvidence`), and verifier `repair` back to the same evidence action is ignored when the candidate is `answer`, prior evidence succeeded, and user did not explicitly request refresh/retry
    - no-op repair collapse: if verifier returns `repair` but action type/payload/summary are materially unchanged, planner treats it as `approve` instead of recording a repair
  - Retry policy and safe fallback to `Noop` on model/parse failures.
  - Planner and action-verifier prompts now include "reflection lessons" context to avoid repeated failed strategies.

## Task Verifier Gate
- File: `src/main/kotlin/psyke/agent/ego/TaskVerifier.kt`
- Deterministic pre-policy gate for task-level correctness/sufficiency.
- Returns `TaskVerifierDecision(allow, reason, reasonCode)` and emits `task_verifier_review`.
- Current deny paths:
  - `TASK_EVIDENCE_REQUIRED`: verification-sensitive terminal answer proposed without successful evidence.
  - `TECH_EXTERNAL_EVIDENCE_FAILURE`: verification-sensitive terminal answer proposed after only failed evidence attempts.
- Gate runs before Superego and reuses the same denied-action recovery loop in `Ego`.

## Policy Gate (Superego)
- File: `src/main/kotlin/psyke/agent/superego/Superego.kt`
- Reviews each non-fallback action with layered checks:
  - deterministic hard-deny checks first (`SuperegoDeterministicConscience`)
  - LLM semantic review second (only if deterministic checks pass)
- Superego LLM review is separated into dedicated engines:
  - `SingleStageSuperegoReviewEngine` handles one model (retry, strict-JSON retry, parse validation, safe deny fallback).
  - `TwoStageSuperegoReviewEngine` runs cheap primary review first and escalates only on:
    - technical/parsing fallback
    - low confidence (`twoStageLowConfidenceThreshold`)
    - medium/high `policy_risk` (configurable for medium)
- Redundancy/low-value suppression is no longer a Superego hard-deny directive.
  - It is a planner/cost optimization signal, so Superego remains focused on safety/privacy policy boundaries.
- Superego completion budget is adaptive by prompt size (rough token estimate) and bounded by `SuperegoConfig`:
  - `maxCompletionTokens` is the base floor
  - optional dynamic expansion uses `dynamicPromptToCompletionRatio`
  - hard-capped by `dynamicCompletionHardMaxTokens`
  - expansion is cost-weighted by configured model `token_weight`
- Returns `GateDecision(allow, reason, reasonCode)` from schema-enforced structured output (`response_format=json_schema`), with parser fallback for defensive handling.
- LLM deny responses can include optional `reason_code`; deterministic denials emit policy-prefixed `reason_code`s.
- If initial LLM output is non-parseable, stage engine performs one schema-enforced retry before default deny fallback.
- Empty-content transport failures (`finish_reason=length` + blank content) now increment the stage circuit-breaker streak before safe fallback.
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
- Deliberation runtime state is session-scoped:
  - each session has its own `DeliberationProgressMonitor`, `lastAssessmentStep`, and guidance text
  - forced terminal/evidence/fetch-circuit bookkeeping is scoped by `(rootInputId, sessionId)`
- Meta-reasoner completion budget is adaptive by prompt size (same allocator pattern as superego/memory-advisor) and bounded by `MetaReasonerConfig`:
  - `maxTokens` as base floor
  - optional dynamic expansion with `dynamicPromptToCompletionRatio`
  - hard cap with `dynamicCompletionHardMaxTokens`
  - expansion weighted by configured model `token_weight`
- Meta-reasoner calls now request schema-enforced structured output (`response_format=json_schema`) and keep parse fallback semantics for safety.
- Meta-reasoner primary endpoint can fail over to optional `meta_reasoner_fallback` after repeated empty-content transport failures.

## Memory System
- Short-term:
  - File: `src/main/kotlin/psyke/agent/memory/shortterm/MemoryStore.kt`
  - Stores recent turns + rolled summary under char budgets.
  - Produces prompt-clamped summary text.
- Long-term recall:
  - Through `MemoryCoordinator` + `Hippocampus.recall`.
  - Input-trigger recalls are cue-based; thought-trigger recalls require explicit planner query.
  - Reflection-lesson recall is a separate targeted cue path (`REFLECTION_LESSON retrieval`) injected into planner/action-verifier prompts.
  - MCP stdio connect uses bounded startup retry (2 attempts) to absorb transient transport-close failures.
- Long-term consolidation:
  - `LlmLongTermMemoryAdvisor` decides `save|skip` with confidence/tags/summary.
  - Advisor compresses oversized dialogue and recall blocks before prompting (`ContextBlockCompressor`) and emits `memory_advisor_prompt_compressed` diagnostics.
  - Memory-advisor completion budget is adaptive by prompt size and bounded by `MemoryConfig`:
    - `longTermMemoryMaxTokens` is the base floor
    - optional dynamic expansion uses `longTermMemoryDynamicPromptToCompletionRatio`
    - hard-capped by `longTermMemoryDynamicCompletionHardMaxTokens`
    - expansion is cost-weighted by configured model `token_weight`
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
  - Consolidation state is session-scoped:
    - per-session cooldown step tracking
    - per-session parse-fallback circuit breaker
    - per-session explicit remember-intent trigger flag
    - per-session recent imprint fingerprint ring
  - Session/interlocutor filters are optional in episodic recall:
    - default temporal recall is cross-session
    - session/interlocutor filters are applied only when the user explicitly requests them (for example, “this session”, `session:<id>`, `interlocutor:<id>`)
  - `McpHippocampus` requests `write_mode=dedupe_if_similar` when calling memory write tools.
  - Reflection lessons:
    - Triggered on denied-action/repeated-denied loops.
    - Persisted as `MemoryImprint(source=ego_reflection_lesson)` with tags (`kind:reflection_lesson`, action/reason/session metadata).
    - Deduplicated via recent fingerprint window.
    - Explicitly skipped for technical/system failures (external tool failures, LLM client failures, parse/JSON failures, transport/timeouts, `TECH_*`/`SYSTEM_*` reason codes).
- Task workspace (ephemeral, per request):
  - File: `src/main/kotlin/psyke/agent/memory/workspace/TaskWorkspaceStore.kt`
  - Optional (disabled by default) via `MemoryConfig.taskWorkspace.enabled`.
  - Scoped to root input; independent from short-term and long-term memory pipelines.
  - Stores compact sections/evidence for the active request only.
  - Planner receives only prompt-capped workspace index/summaries, not full workspace content.
  - Provides final-pass compilation input with workspace confidence estimate (sections/evidence/goal weighted signal).
  - Exposes debug head/snapshot views (versioned) for development-time observability.
  - Workspace final-pass rewrite is handled by `TaskWorkspaceFinalizer` (`src/main/kotlin/psyke/agent/ego/TaskWorkspaceFinalizer.kt`) with strict JSON parsing, required-field validation, retry loop, and safe fallback.
  - Workspace is destroyed on input resolution or queue drain cleanup.

- Dashboard workspace observability:
  - Files: `src/main/kotlin/psyke/dashboard/DashboardStateStore.kt`, `src/main/kotlin/psyke/dashboard/DashboardServer.kt`, `src/main/resources/dashboard/conversations.html`, `src/main/resources/dashboard/observability.html`
  - UI routes are split:
    - Conversations page: `/`
    - Observability dashboard: `/dashboard`
  - API namespaces are split:
    - Chat control plane and session-scoped SSE: `/api/chat/*`
    - Observability snapshot/events/workspace: `/api/obs/*`
  - Workspace identity and timing are both exposed in telemetry/event payloads:
    - `root_input_id`: stable request identity key
    - `root_input_received_at_ms`: timing anchor used for latency/timeline correlation
  - Observability SSE lane streams lightweight events only; heavy workspace debug snapshots are captured in a bounded TTL ring and served on-demand via `/api/obs/workspace` and `/api/obs/workspace/{rootId}`.
  - The dashboard drawer fetches snapshot detail on demand to avoid continuous large-payload updates in timeline/event streams.

## Action Execution Surface
- File: `src/main/kotlin/psyke/agent/cortex/motor/MotorCortex.kt`
- Startup discovery:
  - Action plugins are discovered at runtime through `ServiceLoader` factories (`AgentActionPluginFactory`).
  - Each plugin self-describes:
    - action id (`ActionType` id string)
    - dispatchable flag
    - planner description/payload guidance/example
    - deterministic superego directives
    - follow-up-thought behavior
- Built-in discovered action plugins:
  - `answer`
  - `web_search`
  - `mcp_time` (payload timezone is required by current MCP time server contract)
  - `website_fetch`
  - `email_send` (Microsoft Graph adapter; disabled unless env config is present)
- `web_search` provider routing is independent from cognitive-role routing:
  - configured directly via `web_search.provider` in `llm-runtime.yaml`.
  - current web-search runtimes are `mistral`, `groq`, and `google`; configuring `openai` degrades to unavailable with a startup warning.
  - startup initialization failures (missing key, bad base URL, provider/session errors) degrade web search to an unavailable engine instead of crashing the app.
- Action availability is runtime health-dependent and fed back into planner context.
- Planner payload repair is now action-type aware via registry hooks (plugin-specific `repairPlannerPayload`), with legacy default repair retained for bare `website_fetch` URLs.
- Action outcomes can carry a generic `actionErrorCategory` (`none`, `retryable`, `non_retryable`).
  `website_fetch` currently maps its internal error categories into this generic field.

## Queueing Model
- File: `src/main/kotlin/psyke/agent/ego/AttentionScheduler.kt`
- Three bounded priority queues:
  - inputs (`InputPriority`)
  - thoughts (`Urgency`)
  - actions (`Urgency`)
- Supports root-input scoped queue operations used by convergence logic:
  - detect pending fallback explanation actions per `(rootInputId, sessionId)` scope
  - detect pending plan-context or convergence thoughts per `(rootInputId, sessionId)` scope
  - clear pending thoughts/actions for a resolved `(rootInputId, sessionId)` scope after terminal answer
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
- For meta-reasoner and superego, circuit-breaker streaks now include repeated empty-content transport failures in addition to parse failures.
- Repeated denied-action loops are blocked by payload/type comparison, except when denial is classified as technical/transient. Classification prefers structured `reason_code` (for example `TECH_*`) and falls back to text heuristics only if code is missing.
- Reflection-lesson persistence is disabled for technical/system/transient failure classes, so retries/infra noise do not pollute long-term lesson memory.
- Multi-layer duplicate plan suppression (evaluated cheapest-first):
  1. **Plan budget**: hard cap (`maxPlansPerInput`, default 2) on plans emitted per root input.
  2. **Pressure gate**: suppress new plans when `decisionPressure >= planEmissionPressureThreshold` (default 0.55).
  3. **Exact hash dedup**: normalized goal+steps hash prevents identical plans from being re-emitted.
  4. **Pending plan detection**: if plan-context thoughts are already queued, suppress and enqueue a convergence thought instead.
  5. **Convergence thought dedupe**: at most one convergence thought per root input to prevent churn.
- Suppressions from budget/pressure/hash gates now run a recovery step: if no same-scope plan/convergence work remains, enqueue a convergence thought (and fallback explanation if needed) so the input does not end silently without an answer.
- Generic action retry-budget cooldown: for evidence-style actions, repeated non-retryable failures
  (default budget `actionRetryBudgetNonRetryableFailures=3`) trigger a temporary per-input/per-action disable
  for `actionRetryCooldownSteps` loop steps (default `10`). Disabled action types are removed from planner availability
  until cooldown expiry, pushing the planner toward alternative actions.
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
- Task verifier decision rules, reason-code semantics, or placement in action path.
- Superego directives contract or default deny/allow fallback behavior.
- Superego deterministic rules/validators or deterministic-vs-LLM precedence.
- Deliberation pressure formula, thresholds, or forced-terminal criteria.
- Memory recall/consolidation triggers, thresholds, or disable semantics.
- Reflection-lesson recall/imprint triggers, filters, or dedupe behavior.
- Episodic memory (logbook) event types, journal call sites, or storage schema.
- Task workspace lifecycle, scoping, prompt injection, or final-pass compilation behavior.
- Prompt-injection defense patterns or untrusted-content handling paths.
- Supported action types or runtime availability logic.
- Critical instrumentation events that materially change control flow visibility.
