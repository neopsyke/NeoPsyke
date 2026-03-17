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
    - `meta_reasoner_fallback` is optional and used when repeated primary meta-reasoner technical failures occur (empty-content transport failures and schema-validation failures).
    - Optional `model_catalog` in `llm-runtime.yaml` provides per-provider model ROI metadata (`tier`, `token_weight`, optional cost fields).
    - Superego and memory-advisor read `token_weight` for their configured models and apply it to dynamic completion-budget scaling.
    - When `SuperegoConfig.twoStageReviewEnabled` is on, runtime resolves a cheaper primary superego model from `model_catalog` (same provider) and keeps the configured superego model as escalation stage.
    - Supported cognitive-role providers are `groq`, `mistral`, `google`, and `openai`.
    - OpenAI moderation utility (`omni-moderation-latest`) exists as a standalone callable path and is not auto-wired into cognitive-role chat calls.
    - Planner runtime wiring now inserts an LLM-layer structured-output adapter ahead of the planner client:
      - provider/model/call-site compatibility policy lives in the LLM layer, not `LlmEgoPlanner`
      - structured-output compatibility failures can degrade request mode (`strict json_schema` -> relaxed json_schema -> prompt-only JSON) before the planner sees a terminal model failure
      - degraded mode can stay sticky for the lifetime of the planner client instance (run-scoped)
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
  - `Id` (autonomous internal drive module; optional, loaded from `id-runtime.yaml`)
  - `ProjectsGateway` (optional project runtime boundary; also serves ambient active-project queries)
  - `Ego` orchestrator
- Interactive startup now performs an MCP memory health probe before enabling memory:
  - if probe passes, memory is exposed as available and `McpHippocampus` is wired
  - if probe fails, memory is downgraded to noop for the run and reported unavailable
  - MCP memory server process now stays alive after `connect` until transport close so startup health checks can complete instead of racing a premature process exit
- Interactive startup runs LLM provider health probes per configured cognitive role endpoint:
  - probes use normalized URL joining (`base_url` + `/models`) so trailing slashes do not produce `//models`
  - for Google `v1beta/openai` routes, an `HTTP 404` probe on `/openai/models` falls back to native `/v1beta/models` before reporting status
  - transient unavailable probe results (for example, timeout) are retried once before startup decides the endpoint state
  - `meta_reasoner_fallback` is treated as optional during startup: if it remains unavailable after retry, startup logs a warning and disables that fallback for the run instead of aborting
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
  - Handles `ProjectSignal.WorkReady` by asking `ProjectsGateway.nextWorkFromSignal(...)` for a `ProjectWorkUnit`, enqueueing project work, and clearing transient Ego state for that project root after the cycle.
  - Interactive wiring uses `AsyncSensoryInputSource` with stdin enabled in control-only mode:
    - terminal `exit` emits `ExitRequested(source="stdin")` and stops the loop
    - non-command stdin text is ignored as chat input and never enqueued to the scheduler
  - Default chat answers from web sessions are delivered via dashboard chat events, not terminal stdout.
  - Interactive startup requires dashboard mode enabled; without dashboard input path the loop does not start.
- `runLoop()` (bounded by `config.planner.maxLoopStepsPerInput`):
  - Scheduler priority:
    - Inputs first
    - Then project work already enqueued from `ProjectSignal.WorkReady`
    - Then Id impulses (when queued)
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
    - Any active Id impulse lifecycles are force-denied to avoid stale pending Id state.
  - If queues drain:
    - Finalize any idle Id impulse lifecycles (accepted or denied).
    - Reset deliberation state.
    - Reset per-input memory coordinator state.
    - Clear active task workspaces and pending workspace gates, while preserving per-session workspace digests.

## Id Module and Impulse Lifecycle
- Files:
  - `src/main/kotlin/psyke/agent/id/Id.kt`
  - `src/main/kotlin/psyke/agent/id/NeedState.kt`
  - `src/main/kotlin/psyke/config/IdRuntimeConfig.kt`
  - `id-runtime.yaml`
- Id pulse loop:
  - Grows each configured need and decrements cooldown/backoff/in-flight timers.
  - Emits `id_pulse` telemetry with need snapshots.
  - Enforces a global pending-impulse gate: while one impulse lifecycle is pending, no new impulse is emitted.
  - Requires Ego idle (`hasPendingWork == false`) before firing a new impulse.
  - Selects one winner need (max urgency above threshold), enqueues exactly one impulse, and marks it in-flight.
- Ego impulse lifecycle tracking:
  - Each impulse root (`root_impulse_id`) gets a lifecycle record.
  - Id-origin is propagated on every downstream thought/action enqueue path (follow-up, denial recovery, suppression recovery, fallback).
  - Id convergence constraints are re-applied on every Id-origin thought (including follow-up and plan-step thoughts), not only on the initial impulse planner pass.
    - `internalize` + `allowEscalation=false` removes `contact_user` from planner dispatchable actions and planner action definitions.
  - Id-driven planning now assembles a shared ambient context before planner/retrieval work:
    - optional active projects from `ProjectRegistry`
    - recent workspace themes from task-workspace digests
    - recent useful actions/updates from episodic logbook history
    - unresolved/open loops from active task workspaces
    - recently explored exact learning topics from successful `reflect` saves
  - The ambient context is advisory only:
    - it biases recall/prompting toward user-relevant topics
    - it does not hard-require project alignment
    - all Id-driven needs see the same full block set and may use any part of it
  - Exact-repeat pressure remains learning-specific:
    - `recent_exact_learning_topics` is visible to all needs
    - only learning retrieval adds freshness guidance to avoid exact topic repeats
    - deeper follow-up questions on related topics remain allowed
  - Lifecycle result is aggregated across parallel branches:
    - accepted: at least one Id-origin action executed
    - denied: all branches finished without any executed Id-origin action
  - Final callback to Id is emitted only when no pending scheduler work remains for that root.
- Denial dynamics:
  - `IdConfig.maxConsecutiveDenials` is now authoritative for backoff thresholding.
  - Backoff escalation remains exponential and capped (`MAX_BACKOFF_ESCALATION`).

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
    - ambient context for Id-driven work (optional relevance signals only)
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
  - For Id-origin thoughts, planner context now rebuilds Id convergence state and applies the same convergence action filters used during impulse processing.
  - Otherwise mirrors input path:
    - build context
    - optional meta assessment/guidance
    - planner decision
    - decision application

## Action Path
- `processAction`:
- For `answer_draft` actions, records an internal draft section in task workspace and does not emit a user-visible assistant turn.
- For terminal `answer` actions, runs task-workspace final-pass processing before action execution:
    - records candidate answer draft into workspace
    - builds final compilation from workspace sections/evidence
    - skips final-pass only when both `evidenceCount == 0` and `answerDraftCount < max(2, activationMinPlanSteps)`
    - applies workspace-confidence gate (`finalPassMinWorkspaceConfidence`)
    - runs `TaskWorkspaceFinalizer` rewrite when enabled
    - applies model-confidence gate (`finalPassMinModelConfidence`)
    - keeps original payload on any gate/finalizer failure path
  - Emits lightweight workspace-head telemetry (`task_workspace_head`) on workspace mutations.
  - When `TaskWorkspaceConfig.debugCaptureEnabled` is on, emits full debug snapshots (`task_workspace_debug_snapshot`) for dashboard-only inspection.
  - Fallback explanation actions bypass policy gate.
  - Normal actions pass through deterministic `TaskVerifier` first (task-truth/sufficiency gate), then `Superego.review`.
    - Deterministic checks classify task intent + volatility for terminal answers.
    - External evidence is required only for volatile/unknown factual intents; transformation/personal-memory/subjective/static-reasoning intents bypass evidence requirement.
    - When volatile evidence is required but evidence actions are unavailable, verifier uses a graceful allow path (`TASK_EVIDENCE_UNAVAILABLE_GRACEFUL`) to avoid dead-loop retries.
    - Forced-terminal system answers (decision-pressure safety path) are exempt from TaskVerifier evidence requirement.
  - If denied:
    - Record denial metrics/evidence.
    - Enqueue a new "find safe alternative" thought with denied-action context, including structured `reason_code`.
    - Attempt reflection-lesson persistence into long-term memory (filtered; technical/system failures are skipped).
    - Notify `ActionLifecycleObserver` subscribers so project-origin actions can translate denials back into project-step state.
  - If allowed:
    - Execute via `MotorCortex.execute`.
    - Record outcome + deliberation evidence.
    - Notify `ActionLifecycleObserver` subscribers after execution so project-origin actions can update step acceptance/block/retry state.
    - Record non-answer/non-answer_draft action outcomes into the task workspace (when enabled).
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
  - Prompt assembly with contract-based budget allocation (`required_core` > `required_context` > `optional`).
  - Overhead-aware floor reservation per section, tiered degradation, and single-message fallback under extreme prompt pressure.
  - Emits `prompt_budget_allocation` telemetry for planner and action-verifier prompt builds (cost estimates, degradation path, fallback/floor-violation signals).
  - For Id-driven work, planner prompt may include an ambient context block containing optional project/workspace/activity/open-loop/topic relevance signals.
  - Planner calls request schema-enforced structured output, but provider/model compatibility handling now lives below the planner in the LLM layer:
    - planner requests one structured-output contract (`response_format=json_schema`) plus call-site metadata
    - LLM adapter owns compatibility retries/degradation and may retry as relaxed schema or prompt-only JSON before surfacing a terminal failure
    - planner no longer branches on provider/model error details
  - Action verifier still uses schema-enforced `response_format=json_schema` with planner-local relaxed-schema retry.
  - Strict JSON parse + minimal repair for invalid escapes.
  - On planner parse failure: one truncation retry with increased completion budget when output appears truncated, then one strict-JSON retry.
  - Normalizes `action_payload` from either JSON string or structured JSON (object/array) into a string payload.

## Projects Runtime
- Files:
  - `src/main/kotlin/psyke/agent/project/ProjectsGateway.kt`
  - `src/main/kotlin/psyke/agent/project/ProjectManager.kt`
  - `src/main/kotlin/psyke/agent/project/ProjectStateMachine.kt`
  - `src/main/kotlin/psyke/agent/project/ProjectPlanner.kt`
  - `src/main/kotlin/psyke/agent/project/ProjectStepVerifier.kt`
- Feature flag:
  - When `config.projects.enabled=false`, `NoopProjectsGateway` is injected and project actions/signals are inert.
- Boundary:
  - Ego uses the gateway only for:
    - `pendingWorkSummary()` during Id-driven impulses
    - `nextWorkFromSignal(ProjectSignal.WorkReady)` when project work is ready
    - project-origin action lifecycle callbacks plus `finalizeProjectCycle(rootInputId)`
- Runtime responsibilities:
  - Create/revise plans through `ProjectPlanner`
  - Observe project-origin action outcomes through the generic action lifecycle observer hook
  - Apply verifier decisions (`PASS`, `RETRY`, `BLOCK`, `CONTINUE`, `FAIL`) back into the event-sourced state machine
  - Restore timers, suspended resumes, and blocked waits on startup
  - Persist `events.jsonl`, `project.json`, `snapshot.json`, `workspace/context.md`, `workspace/scratch.md`, and per-step artifacts
- Ego-facing signal contract:
  - The runtime emits only `ProjectSignal.WorkReady(projectId, stepId, reason)`
  - Timer wakes, wait-condition satisfaction, new-project planning, and resume reconciliation stay inside the project subsystem and are translated into `WorkReady` when runnable work exists.
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
    - strict-schema-first call with relaxed-schema fallback on provider schema-validation failure
    - one truncation retry with increased completion budget on likely-truncated output
    - one strict-JSON retry on parse failure
    - parse-failure circuit breaker (scoped by `root_input + action_type`) that bypasses verifier for one decision after repeated malformed verifier outputs
    - reject propagation into noop-thoughts: verifier `reject` preserves denied action type/payload metadata so follow-up planning can see exactly which candidate was blocked
    - repeated-answer disagreement override: if a follow-up thought repeats the same `answer` payload after a prior non-technical verifier reject and the verifier rejects it again, planner keeps the original answer and the dispatcher lets that answer through instead of re-blocking it as an ordinary repeated denied action
    - structured follow-up lineage guard: follow-up thoughts carry origin action metadata (`originActionType`, `originActionObservedEvidence`), and verifier `repair` back to the same evidence action is ignored when the candidate is `answer`, prior evidence succeeded, and user did not explicitly request refresh/retry
    - no-op repair collapse: if verifier returns `repair` but action type/payload/summary are materially unchanged, planner treats it as `approve` instead of recording a repair
    - answer-action tuning: verifier instructions explicitly approve directly entailed exact-match answers when there is no contradictory evidence, and verifier sampling temperature is fixed at `0.0`
  - `answer_draft` action proposals are allowed only inside active plan-context thoughts; out-of-context proposals are coerced to `noop`.
  - Retry policy and safe fallback to `Noop` on model/parse failures.
  - Follow-up evidence thoughts now explicitly ask for the next planner decision as one raw JSON object and forbid tool/function wrappers.
  - Planner and action-verifier prompts now include "reflection lessons" context to avoid repeated failed strategies.

## Task Verifier Gate
- File: `src/main/kotlin/psyke/agent/ego/TaskVerifier.kt`
- Deterministic pre-policy gate for task-level correctness/sufficiency.
- Uses intent classification (`volatile_fact`, `stable_fact`, `transformation`, `personal_memory`, `subjective_advice`, `static_reasoning`, `unknown`) plus volatility scoring.
- Returns `TaskVerifierDecision(allow, reason, reasonCode, assessment)` and emits enriched `task_verifier_review` telemetry.
- Decision outcomes:
  - `TASK_EVIDENCE_REQUIRED`: volatile/unknown intent without successful evidence and evidence actions are available.
  - `TECH_EXTERNAL_EVIDENCE_FAILURE`: volatile/unknown intent with failed evidence attempts and no success.
  - `TASK_EVIDENCE_UNAVAILABLE_GRACEFUL`: volatile/unknown intent but runtime evidence actions are unavailable/undispatchable (allow path).
- Gate runs before Superego and reuses the same denied-action recovery loop in `Ego`.

## Policy Gate (Superego)
- File: `src/main/kotlin/psyke/agent/superego/Superego.kt`
- Reviews each non-fallback action with layered checks:
  - deterministic hard-deny checks first (`SuperegoDeterministicConscience`)
  - LLM semantic review second (only if deterministic checks pass)
- Id-origin deterministic policy is enforced inside Superego (not plugins):
  - Direct `answer` from Id origin is hard-denied by default.
  - Id-origin actions are allowlisted for internal/evidence-gathering types (`web_search`, `website_fetch`, `mcp_time`, `answer_draft`).
  - Non-allowlisted Id-origin actions are denied before LLM review.
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
- Superego prompt assembly uses the same contract allocator and emits `prompt_budget_allocation` telemetry (`call_site=superego_prompt`).
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
- Meta-reasoner calls now request schema-enforced structured output (`response_format=json_schema`) with adaptive safeguards:
  - local parse clamp on `reason` at 180 chars
  - schema-validation fallback retry using relaxed schema (removes `reason.maxLength`) before giving up
  - empty-content retry with one adaptive completion-budget increase (bounded by config)
- Meta-reasoner emits `prompt_budget_allocation` telemetry (`call_site=meta_reasoner_prompt`) with completion-budget metadata.
- Meta-reasoner primary endpoint can fail over to optional `meta_reasoner_fallback` after repeated technical failures (empty-content or schema-validation).

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
  - Saved summaries are a first-person memory contract from the agent's perspective (for example, `I learned ...` / `I should remember ...`); common third-person outputs are normalized before persistence as a guardrail.
  - MCP-backed durable-memory writes stamp the fact/reference subject as `me` so persisted memories are attributed to the agent rather than the user if a fact-style backend path is used.
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
  - Enabled by default via `MemoryConfig.taskWorkspace.enabled=true`.
  - Activation remains plan-gated with `MemoryConfig.taskWorkspace.activationMinPlanSteps=2`.
  - Scoped to root input; independent from short-term and long-term memory pipelines.
  - Stores compact sections/evidence for the active request only.
  - `answer_draft` sections are stored separately and counted for final-pass gating.
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
  - `answer_draft` (internal chunked synthesis, non-user-visible)
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
- For meta-reasoner and superego, circuit-breaker streaks now include repeated empty-content transport failures (and meta-reasoner also tracks repeated schema-validation failures) in addition to parse failures.
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
- Event types recorded: `INPUT_RECEIVED`, `PLANNER_DECISION`, `ACTION_EXECUTED`, `ACTION_DENIED`, `CONTACT_DELIVERED`, `MEMORY_IMPRINT`, `SELF_INITIATED`.
- Integration through `MemoryCoordinator`:
  - `remember()` auto-journals `INPUT_RECEIVED` for user turns.
  - `maybeAssessLongTermMemory()` auto-journals `MEMORY_IMPRINT` on successful saves.
  - `journal()` public method called from Ego for planner decisions, action outcomes, denials, and answers.
  - `recordReflection()` owns `REFLECT` persistence, adding first-person normalization plus session/interlocutor/run and Id-origin provenance before writing logbook and long-term memory.
  - `REFLECT` only reports `DURABLE_MEMORY_SAVED` on durable long-term memory persistence success; journal-only fallback does not satisfy the originating learn need.

## Id Need Satisfaction
- Id-originated roots now resolve against structured action effects instead of the old "some action executed" heuristic.
- Built-in action outcomes expose machine-readable execution status plus effects such as `TASK_PROGRESS`, `EVIDENCE_GATHERED`, `DURABLE_MEMORY_SAVED`, and `USER_MESSAGE_DELIVERED`.
- `ImpulseLifecycleTracker` aggregates successful effects across the full root impulse tree and only finalizes success when the need's configured `satisfactionEffectsAnyOf` intersects those observed effects.
- Default need contract: `TASK_PROGRESS`; runtime config now overrides the built-in needs explicitly:
  - `be-useful` → `TASK_PROGRESS` or `USER_MESSAGE_DELIVERED`
  - `user-interaction` → `USER_MESSAGE_DELIVERED`
  - `learn-something` → `DURABLE_MEMORY_SAVED`
- Generic `action_executed` / `contact_delivered` activity decay remains ambient for non-Id work only; Id-originated need satisfaction comes from root-level effect evaluation.
- Narrative perspective is normalized by event type before logbook persistence:
  - `INPUT_RECEIVED` stays canonical third-person timeline form as `User: ...`
  - planner/action/answer events keep neutral timeline narration
  - `MEMORY_IMPRINT` and `SELF_INITIATED` preserve or normalize to first-person agent wording
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
