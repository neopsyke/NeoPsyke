# Agent Logic Summary (Living Document)

This file is a human-readable map of NeoPsyke's main agent runtime logic.
It is intentionally high-level and should stay aligned with the code.

## Scope
- Interactive runtime path only (`runInteractiveMode`), not eval harness internals.
- Source of truth is code under `src/main/kotlin/ai/neopsyke/**`.

---

## L0: System Overview

NeoPsyke is an autonomous cognitive agent built around a Freudian-inspired architecture. The system processes stimuli through a deliberation loop that plans, reviews, and executes actions.

**Six major subsystems:**

1. **SensoryCortex** — Receives external stimuli (user messages, Telegram updates, goal/Id wake signals) and internal typed feedback cues, sanitizes/enriches stimulus envelopes, resolves conversation identity/security, and transforms envelope stimuli into typed `Percept` objects.
2. **Ego** — The central deliberation loop. Pulls percepts from SensoryCortex, schedules cognitive work via `AttentionScheduler`, delegates planning to the Planner, routes actions through the `ActionReviewPipeline`, tracks `DecisionPressure`, and manages thread/session lifecycle.
3. **Superego** — Three-layer action review gate: `DeterministicConscience` hard-deny checks, configuration-based `ActionAuthorizationPolicy`, and LLM semantic review (with optional `TwoStageReview` escalation). Every non-fallback action must pass all three layers.
4. **MotorCortex** — Discovers action plugins at startup via `ServiceLoader`, executes authorized actions through `ActionControlService`, and routes output through `ConversationOutputGateway`. Internal action feedback re-enters through SensoryCortex as a typed cognitive signal and is routed by `StimulusIngressCoordinator`.
5. **Id** — Autonomous drive module. Maintains configurable needs that grow over time and emit impulses into the Ego loop when tension exceeds threshold. Impulses are processed like inputs but with convergence constraints.
6. **Memory System** — Four tiers: short-term context buffer (`MemoryStore`), long-term vector recall (`Hippocampus`), episodic journal (`Logbook`), and per-request scratchpad workspace (`ScratchpadStore`).

**Supporting subsystems:**
- **Goals Runtime** (`GoalsGateway` / `GoalManager`) — Persistent multi-step objective manager with event-sourced `GoalStateMachine`, cron scheduling via `TimerScheduler`, and async wait conditions via `WaitConditionMonitor`.
- **DeliberationEngine** — Tracks `DecisionPressure`, coordinates `MetaReasoner` assessments, enforces action retry budgets, and can force terminal answers under sustained pressure.
- **Dashboard & Observability** (`DashboardServer` / `DashboardStateStore`) — Web UI for conversations, observability, and action control with SSE-based live updates.

**Core data flow:**
```
Stimulus → SensoryCortex (sanitize, appraise) → Percept
  → Ego (schedule, plan, review, execute)
    → Planner (LLM decision: defer/intend/plan/noop)
    → Superego (deterministic + policy + LLM review)
    → ActionControlService (stage/authorize/commit)
    → MotorCortex (plugin dispatch)
  → ActionFeedbackCue → SensoryCortex (typed feedback signal) → StimulusIngressCoordinator → Ego (continuation)
```

---

## L0: Runtime Wiring

- Entry: `Application.kt` → `AppModeRunners.kt#runInteractiveMode`
- `runInteractiveMode` assembles all components before starting the Ego loop:
  - `ChatModelClient` instances per `CognitiveRole` (planner, superego_primary, superego_escalation, meta_reasoner, meta_reasoner_fallback, memory_advisor) from `llm-runtime.yaml`
  - Memory system from `memory-runtime.yaml` (off / default managed provider / external provider)
  - Id from `id-runtime.yaml` (optional)
  - Goals runtime (optional, behind `config.goals.enabled`)
  - Telegram ingress (optional, webhook or polling mode)
  - Google Workspace OAuth (optional)
  - `InstrumentationBus`, metrics, `TokenBudgetGate`
  - `DashboardServer` with chat, observability, and action control APIs

### L2: LLM Provider Configuration
- Each cognitive role can use an independent provider/api-key/base-url/model from `llm-runtime.yaml`.
- Supported providers: `anthropic`, `groq`, `google`, `mistral`, `ollama`, `openai`.
- `meta_reasoner_fallback` is optional; used on repeated primary meta-reasoner technical failures.
- Optional `model_catalog` provides per-provider model ROI metadata (`tier`, `token_weight`, cost fields).
- Superego and `LongTermMemoryAdvisor` read `token_weight` for dynamic completion-budget scaling.
- When `SuperegoConfig.twoStageReviewEnabled` is on (`TwoStageReview`), runtime resolves a cheaper primary model from catalog and keeps the configured model for escalation.
- Planner runtime inserts an LLM-layer structured-output adapter (`StructuredOutputMode`): provider/model compatibility is handled in the LLM layer, not the Planner. Degradation path: strict json_schema → relaxed json_schema → prompt-only JSON.
- `web_search` routing is independent from cognitive roles; configured via `web_search.provider` in `llm-runtime.yaml`. Current runtimes: `mistral`, `groq`, `google`.
- Optional pre-call `TokenBudgetGate` enforces configurable hard caps (`max_run_total_tokens`, `max_run_tokens_per_provider`, `max_run_tokens_per_role`) before outbound model calls.

### L2: Memory Startup Gate
- `memory=off` → `NoopHippocampus` (memory unavailable).
- `memory=default` → bootstraps managed `neopsyke-pgvector-memory` artifact, starts provider if needed, waits for `/v1/health`.
- `memory=external` → uses configured external HTTP endpoint (never auto-starts).
- Failures downgrade to noop for the run.
- Managed closeables registered with JVM shutdown hook for Ctrl-C/SIGTERM cleanup.

### L2: LLM Provider Health Gate
- Per-role provider health probe at startup: `GET base_url/models`.
- Normalized URL joining (trailing slash handling).
- Google `v1beta/openai` routes: HTTP 404 falls back to native `/v1beta/models`.
- Transient unavailable results retried once.
- `meta_reasoner_fallback` treated as optional: unavailable after retry → warning + disabled for the run.

---

## L1: SensoryCortex and Input Path

- File: `src/main/kotlin/ai/neopsyke/agent/cortex/sensory/SensoryCortex.kt`
- Receives signals from `SignalSource` (async channel or stdin).
- Two signal planes:
  - `CognitiveSignal` — perception-bearing: `StimulusReceived(envelope, percept)`, `FeedbackReceived(cue)`, `NoStimulus`.
  - `RuntimeControlSignal` — lifecycle: `SourceClosed`, `ExitRequested`, `ShutdownRequested`, `ConfigReloaded`.
- `nextSignal()` is the mandatory stimulus→percept boundary:
  - Prioritizes synthetic signals (internal feedback cues) over external source signals.
  - Enriches stimuli: trims/clamps content, resolves session ID and interlocutor.
  - Appraises via `PerceptualAppraiser` into a `Percept`.
- Typed cognitive stimuli currently arrive as:
  - Linguistic stimuli from dashboard chat sessions.
  - Linguistic stimuli from owner-only Telegram (webhook or polling).
  - Cue stimuli from Id impulse wakeups.
  - Cue stimuli from goal-runtime work-ready signals.
  - Typed internal feedback cues from completed/waiting action outcomes (`FeedbackReceived`).
  - Envelope feedback stimuli for external compatibility paths (`StimulusReceived` with `family=FEEDBACK`).

### L2: Conversation Security Context
- `ConversationContext` is mandatory end-to-end, requires non-blank `sessionId`.
- `ConversationContext.security` normalizes: principal role, channel (provider/surface/transport), instruction trust, policy scope.
- Factory methods: `ownerDirect(...)`, `externalParticipant(...)`, `internalAutomation(...)`.
- Current ingress defaults:
  - Dashboard chat: trusted owner direct-chat.
  - Stdin: control-only (exit command only, no chat stimuli).
  - Telegram: trusted owner after webhook-secret + owner chat/user allowlist checks.
  - Id and goal-runtime cues: trusted internal automation.
- Session replay reconstructs security from recorded signal fields.

### L2: Telegram Ingress
- Two transport modes: `webhook` (Telegram POSTs to configured HTTPS path) and `polling` (NeoPsyke calls `getUpdates`, clears any existing webhook on startup).
- Requires exact `X-Telegram-Bot-Api-Secret-Token` match.
- Can require private/direct chats only plus owner `chat_id` and `user_id`.
- Unauthorized traffic fails closed or silently dropped based on `dropUnauthorizedMessages`.
- Sessions derived via `<sessionIdPrefix>:<chatId>`.
- Verified owner chat routes through approval interceptor before normal sensory enqueue:
  - No live approval pending → forward to normal ingress.
  - Live approval pending → classify as approve/deny/deny-and-reissue/explain/unclear.
  - Refreshed prompts carry explicit short approval ref; stale/mismatched refs rejected.

### L2: Google Workspace Auth
- OAuth start via local NeoPsyke HTTP endpoint → Google authorization URL.
- Callback: verify signed state, consume encrypted PKCE record, exchange code, verify Gmail profile email against configured owner, store encrypted credentials locally.
- Read-only Gmail/Calendar actions unavailable until authorization completes.

### L2: Perceptual Appraisal and Thread Binding
- `PerceptualAppraiser` maps stimulus families to percept families:
  - `LINGUISTIC` → `REQUEST`
  - `OBSERVATION` → `OBSERVATION`
  - `FEEDBACK` → `FEEDBACK`
  - `CUE` → `DRIVE_ACTIVATION` (Id cues) or `STATE_CHANGE` (other cues)
- `StimulusEnvelope` carries: id, family, source, content, receivedAt, conversationContext, trustLevel, provenance, metadata.
- `Percept` carries: id, family, summary, source, occurredAt, conversationContext, cognitiveThreadId, provenance.
- `CognitiveThreadStore` binds percepts to root-scoped cognitive threads, tracking: thread identity, kind/status, latest percept, root-scoped security/trust state, observed-artifact trust degradation.

---

## L1: Ego (Main Loop)

- File: `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`

**`runInteractive()`** — Outer signal loop:
- Pulls signals from `sensoryCortex.nextSignal()`.
- Routes `StimulusReceived` through `StimulusIngressCoordinator.ingest()` → triggers `runLoop()`.
- Handles `ExitRequested`, `ShutdownRequested`, `SourceClosed` to break loop.

**`runLoop()`** — Inner deliberation loop (bounded by `config.planner.maxLoopStepsPerInput`):
- Each iteration: `scheduler.nextTask(isBlocked)` → returns `LoopTask`.
- Three task types:
  - `AttendOpportunity` → `processOpportunity()` (routes by trigger: Input/Impulse/Feedback/GoalWork)
  - `ProcessIntention` → `processIntention()` (DEFER → `processDeferredIntention()`, others → action)
  - `PerformAction` → `processAction()` → `actionPipeline.reviewAndExecute()`
- Per step: activate session context, advance deliberation step, dispatch task, catch errors.
- Pressure monitoring: `deliberation.maybeForceTerminalAnswer()` can enqueue forced `contact_user`.
- Queue drain cleanup: clear orphaned scratchpads, reset per-input state, finalize idle Id impulse lifecycles.

**`StimulusIngressCoordinator`** — Post-sensory routing:
- Appraises goal-runtime cues through `GoalsGateway.nextWorkFromCue(...)`.
- Binds thread/percept state.
- Shapes opportunity contract before enqueue (allowedIntentions, allowedCommitModes, availableActions, dispatchableActions).
- Emits `ScheduledOpportunity` into scheduler.

### L2: Scheduler and Priority Model
- File: `src/main/kotlin/ai/neopsyke/agent/ego/AttentionScheduler.kt`
- Three bounded priority queues: opportunities, intentions, actions.
- `nextTask(isBlocked)` returns work in priority order:
  - Opportunities first (highest priority).
  - Between intentions and actions: higher urgency wins; at equal urgency, non-DEFER intentions outrank deferred continuations.
- Opportunity ranking: `RESPOND`/`INTEGRATE_FEEDBACK` > `EXECUTE` > `RESUME`/`CLARIFY`/`FINALIZE`, then by salience.
- Blocked roots are skipped without being dropped; they become schedulable when approval resolves.
- Queue saturation → drop + instrumentation warning.

### L2: Opportunity Shaping and Policy Pre-filtering
- `CognitivePolicyShaper` operationalizes before planner choice:
  - Policy scope (`default`, `deployment-restricted`, `full-autonomy`).
  - Channel surface (`DIRECT`, `GROUP`, `SHARED_WORKSPACE`, `AUTOMATION`, `ADMIN`).
  - Principal role (owner/internal/admin vs external).
  - Action effect class (observe/private/public/stateful/control-plane).
- Control-plane actions removed from non-admin/non-internal surfaces.
- Restricted scopes lose direct/autonomous commit semantics before planning.
- Planner-visible action availability is prefiltered by instruction trust and thread data trust.

### L2: Session and Thread Lifecycle
- `CognitiveThreadStore` is the live owner of thread state for active roots.
- Thread snapshots carry: latest percept, latest opportunity, latest intention, wait state, terminal summary.
- Async waits update thread to `WAITING` with resume metadata.
- Normal completion marks thread `RESOLVED` before cleanup.
- Terminal thread snapshots preserved (bounded) after per-input ephemera cleared.
- Non-goal threads use same semantics as goal roots.

---

## L1: Id and Impulse Lifecycle

- Files: `src/main/kotlin/ai/neopsyke/agent/id/Id.kt`, `NeedState.kt`
- Config: `id-runtime.yaml` → `IdConfig`
- Optional module; loaded only when enabled.

**Pulse loop** (runs on timer, default 30s interval):
1. Grow each configured need by `growthRate`, decrement cooldown/backoff/in-flight timers.
2. Emit `id_pulse` telemetry.
3. Gate: if one impulse already pending, skip.
4. Gate: if Ego has pending work (`hasPendingWork == false` required), skip.
5. Select winner need: max tension above threshold, create `PendingImpulse`, enqueue, mark in-flight.

**Need state** (`NeedState`):
- `value` grows per pulse via `growthRate` (capped at 1.0).
- `tension` is a curve-transformed view of value (linear/power/sigmoid/logarithmic).
- Eligibility requires: enabled, not in-flight, not on cooldown, not backed off.
- `ConvergenceMode`: `CONTACT_USER` (prompt user interaction) or `INTERNALIZE` (internal research/reflection).

**Ego impulse lifecycle**:
- Each impulse root gets a lifecycle record (`root_impulse_id`).
- Id-origin propagated on every downstream enqueue path.
- Id convergence constraints re-applied on every Id-origin thought: `internalize` + `allowEscalation=false` removes `contact_user` and `reflect_internal` from planner-visible actions.
- Lifecycle result aggregated: accepted (at least one action executed) or denied (all branches failed).
- For satisfying actions, Ego clears remaining same-root work before follow-up.
- Final callback to Id only when no pending scheduler work remains.

### L2: Ambient Context Assembly
- Before planner/retrieval, Id assembles shared ambient context (cached best-effort snapshot):
  - Active goals from `GoalRegistry`.
  - Recent scratchpad themes from digests.
  - Recent useful actions/updates from logbook.
  - Unresolved/open loops from active scratchpads.
  - Recently explored exact learning topics.
- Advisory only: biases recall/prompting, does not hard-require goal alignment.
- Exact-repeat pressure is learning-specific: `recent_exact_learning_topics` visible to all needs, but only learning retrieval adds freshness guidance.

### L2: Denial Dynamics and Backoff
- `consecutiveDenials` tracks per need.
- `IdConfig.maxConsecutiveDenials` is authoritative for backoff thresholding.
- Backoff is exponential: `backoffPulses * 2^(denials / maxConsecutiveDenials)`, capped at `MAX_BACKOFF_ESCALATION`.
- Successful satisfaction: `value *= (1 - satisfactionDecay)`.
- Activity decay: configurable per event type (e.g., `input_received` → 0.1 decay).

---

## L1: Planner (HierarchicalEgoPlanner)

- Files: `src/main/kotlin/ai/neopsyke/agent/ego/planner/`
- Old monolithic `LlmEgoPlanner` has been deleted. A test-only shim exists at `src/test/kotlin/ai/neopsyke/agent/ego/LlmEgoPlanner.kt` for backward-compatible test signatures.

**Decision types** (sealed `EgoDecision`):
- `FormIntention(urgency, intentionKind, commitModePreference, actionType, payload, summary)` — Execute an action.
- `EnqueueThought(urgency, content, longTermMemoryRecallQuery?)` — Defer for later processing.
- `EnqueuePlan(urgency, goal, steps)` — Multi-step plan decomposed into deferred steps.
- `Noop(reason, parseFailureShortCircuit?, deniedActionType?, deniedActionPayload?, denialReasonCode?)` — No action.

### L0: HierarchicalEgoPlanner (Entry Point)
- Single entry point behind `Ego.Planner` (replaced the deleted `LlmEgoPlanner`).
- Typed trigger dispatch: `when (trigger)` on sealed interface variant (no text inspection).
- Delegates to L1 lanes, each returning `EgoDecision` directly.
- Each lane has its own LLM configuration entry point (`PlannerConfig.lanes`).
- Emits `planner_start`, `planner_lane_selected`, and decision telemetry.

### L1: Planner Lanes
| Lane | Trigger | File |
|------|---------|------|
| `InputPlanner` | `IncomingInput` | `lane/InputPlanner.kt` |
| `DeferredStepPlanner` | `DeferredIntention` | `lane/DeferredStepPlanner.kt` |
| `FeedbackPlanner` | `ActionFeedback` | `lane/FeedbackPlanner.kt` |
| `GoalWorkPlanner` | `GoalWork` | `lane/GoalWorkPlanner.kt` |
| `ImpulsePlanner` | `IncomingImpulse` | `lane/ImpulsePlanner.kt` |

Each lane:
- Has its own narrower system prompt focused on its decision family.
- Uses `PlannerRuntime` for model calls (retry, circuit-breaker, schema fallback).
- Parses model output into typed lane decision models before mapping to `EgoDecision`:
  - `StepDecision` (`DeferredStepPlanner`)
  - `FeedbackDecision` (`FeedbackPlanner`)
  - `GoalWorkDecision` (`GoalWorkPlanner`)
  - `ImpulseDecision` (`ImpulsePlanner`)
- Validates constraints: allowed intentions, commit modes, available/dispatchable actions.
- Emits per-lane prompt-budget telemetry.

### L2: InputPlanner Sub-Planners
- `InputIntentRouter`: LLM-based semantic classifier returning typed `InputRoute`.
- `DirectResponsePlanner`: terminal answers from current context.
- `GeneralActionPlanner`: single-action with full constraint validation.
- `TaskDecompositionPlanner`: multi-step plan decomposition.
- `GoalCreationPlanner`: semantic goal creation (no regex heuristics).
- `GoalManagementPlanner`: typed GoalCommand with LLM reference resolution.

Two-call pattern: InputIntentRouter (classify) then sub-planner (decide).
Dispatch from `InputRoute` variant to sub-planner is deterministic on typed LLM output.

### L2: Goal Semantics (Typed)
- Goal creation and management emit typed `GoalCommand` variants.
- Goal references are LLM-resolved (`GoalReference.ByInternalId`, `ByResolvedEntity`, `Ambiguous`, `Unresolved`).
- Planner payloads use a canonical serialized typed boundary (`SerializedGoalCommand`) with nested typed `goal_reference`.
- `GoalOperationActionPlugin` validates and executes typed commands (no text heuristics, no plugin-side goal-id repair).
- Ambiguous/unresolved references trigger clarification or failure, never silent guessing.

### L2: Prompt Budget and Assembly
- `PromptBudgetAllocator` reserves required-core/context floors with message-overhead accounting.
- `SharedPromptSections` provides reusable context sections across lanes.
- Each lane has its own prompt profile; narrower prompts per decision family.
- Emits `prompt_budget_allocation` telemetry per lane.

### L2: Structured Output and Parse Recovery
- Lanes request schema-enforced structured output (`response_format=json_schema`).
- `StructuredOutputHandler`: JSON parse with escape repair fallback.
- On parse failure: truncation retry (increased budget) then strict-JSON retry.
- Per-lane circuit breaker: after N consecutive parse failures, short-circuit to Noop.
- `PlannerRuntime` owns model-call retry, provider-side schema-validation fallback, and circuit-breaker.

### L2: Action Verifier
- **Removed** from the redesigned planner path per spec requirement.
- Planner correctness is achieved through lane design, typed outputs, and existing runtime controls.

### L2: Redundancy Handling
- Planner-side soft cost control: prompt treats repeated external calls as low-value unless refresh/retry explicitly requested.
- `Ego` emits `external_action_redundancy_signal` telemetry (soft signal, not policy deny).

---

## L1: Intention and Deferred Continuation Path

**`processIntention(intention: QueuedIntention)`**:
- `IntentionKind.DEFER` → `processDeferredIntention()`.
- Action-carrying intentions (OBSERVE, PREPARE, STAGE, REQUEST_AUTHORIZATION, COMMIT) → convert to `PendingAction` with intention metadata (intentionId, intentionKind, requestedCommitMode) → `processAction()`.
- Runtime rejects intentions whose kind, action type, or commit mode fall outside the current opportunity contract.

**`processDeferredIntention()`**:
- Drops if `passes >= maxThoughtPasses`; if fallback allowed, enqueues fallback `contact_user` action.
- User/system/goal-origin defer chains default to `allowFallbackExplanation=true`.
- For Id-origin: rebuilds convergence state and action filters; fallback disabled unless earlier path explicitly enables it.
- Otherwise mirrors input path: build context → meta assessment → planner decision → dispatch.

**Planner output is intention-native**:
- `decision=intend` carries explicit `intention_kind` + optional `commit_mode_preference`.
- `decision=defer` yields queued `DEFER` intention.
- Plan steps and recovery/follow-up become deferred intentions, not first-class thought queue items.
- Deferred continuations live inside the intention queue as `IntentionKind.DEFER`.

---

## L1: Action Execution Path

- `processAction(action)` delegates to `ActionReviewPipeline.reviewAndExecute(action)`.
- Pipeline stages: `DecisionVerifier` → Superego → `ActionControlService` → MotorCortex → Feedback.

**Special cases**:
- `resolution_draft`: records active draft-sequence entry, no user-visible turn emitted.
- `contact_user`: runs scratchpad final-pass processing before execution (workspace compilation + finalizer rewrite if enabled).
- Fallback explanation actions bypass the entire review pipeline.

### L2: Grounding Gate
- File: `src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt`
- Typed post-gate enforcing that evidence was gathered when grounding is required.
- Only applies to `contact_user` actions (non-contact actions and fallback explanations always allowed).
- Reads typed `GroundingMetadata` from `PendingAction.groundingMetadata` (set at input classification time).
- Reads typed evidence state from `DeliberationEngine.ExternalEvidenceProgress`.
- Reads evidence action availability/dispatchability.
- Reads typed `isForcedTerminal` marker on the action.
- Decision: grounding not required → allow; evidence gathered → allow; evidence unavailable → graceful allow (`GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`); technical failures → deny (`TECH_GROUNDING_EVIDENCE_FAILURE`); no evidence yet → deny (`GROUNDING_EVIDENCE_REQUIRED`).
- Forced terminal + grounding required + technical failures → allow degraded answer with verification-failure disclaimer.
- Grounding classification happens at input intake via `GroundingClassifier` (deterministic pre-filter on `InputRoute` + LLM fallback for ambiguous routes). Goal work uses per-step typed grounding policy.

### L2: Superego Review
- File: `src/main/kotlin/ai/neopsyke/agent/superego/Superego.kt`

**Three-phase review** (`reviewAuthorization`):

**Phase 1: Deterministic checks** (`DeterministicConscience`):
- Action shape validation: blank summary → deny, summary/payload too long → deny.
- Id-origin policy: Id-origin actions must be in allowlist (`WEB_SEARCH`, `WEBSITE_FETCH`, `RESOLUTION_DRAFT`, `CONTACT_USER`, `REFLECT_INTERNAL`, `REFLECT_EVIDENCE`).
- Plugin deterministic review: delegates to plugin's `deterministicReview()` if available.
- Deterministic deny is authoritative; LLM cannot override.

**Phase 2: Authorization policy** (`SuperegoPolicy.authorize`):
- Delegates to `ActionAuthorizationPolicy` (YAML-backed).
- Evaluates: instruction trust, principal role, argument data trust, per-action YAML overrides.
- Special rules: public-commit deny-until-enabled, recurring-goal stricter approval, goal-delete rules (`delete_all` always stages; single-goal direct commit only from owner-verified direct channel with exact `goal_id`).
- Returns `AuthorizationDecision` with progress: `DENY`, `ALLOW_STAGE`, `ALLOW_COMMIT`.

**Phase 3: LLM semantic review** (conditional):
- Bypassed for Id-origin `reflect_internal` after deterministic payload validation.
- Single-stage review engine: one model, retry loop, strict-JSON schema enforcement, temperature 0.0.
- Response schema: `{ allow, reason, reason_code, confidence, policy_risk }`.
- Parse failure → one schema-enforced retry → default deny fallback.
- Empty-content transport failures increment circuit-breaker streak.
- `TwoStageReview` (when enabled): runs cheap primary first, escalates on:
  - Technical/parsing fallback.
  - Low confidence (below `twoStageLowConfidenceThreshold`).
  - High policy risk (always) or medium (if configured).
- Superego prompt includes: policy directives, candidate action details, action origin context, latest user message, short-term context.
- Completion budget adaptive by prompt size, bounded by config, cost-weighted by model `token_weight`.

### L2: Action Control and Staging
- File: `src/main/kotlin/ai/neopsyke/agent/cortex/motor/actions/control/ActionControlService.kt`
- Handles `ALLOW_STAGE` and `ALLOW_COMMIT` decisions.
- Persists: staged actions, commit authorizations, action receipts, ledger entries (signal/background/trace).
- Centralized per-root-input rate limits across action families.
- Fallback-bypass executions mirrored into durable staged/receipt records.

**Approval Runtime** (for staged actions requiring authorization):
- Creates durable approval request artifact.
- Resolves owner-facing delivery channel (same-channel for conversation-origin, highest-priority live channel otherwise, fail-closed if no eligible channel).
- Sends approval prompt through dashboard chat or Telegram.
- Keeps issuing root blocked until terminal state.
- Approval/denial hash-bound to staged action; hash drift → superseded + replacement prompt.
- Expiry and clarification exhaustion → deny staged action → unblock root.
- Telegram non-conversation routing requires successful startup ACK delivery.

**AutonomousWorker** (`ActionControlAutonomousWorker`):
- Background coroutine polling for `READY` staged actions.
- SQL-driven selection: same-thread serialization (`threadSequence`), same-target serialization (`executionKey`).
- Atomic claim at execution time.

### L2: MotorCortex Execution
- File: `src/main/kotlin/ai/neopsyke/agent/cortex/motor/MotorCortex.kt`
- Final no-bypass authorization check for side-effecting actions.
- Delegates to `ActionRegistry.execute()` which routes to the discovered plugin.
- `contact_user` delivery is channel-aware: Telegram → Bot API, others → dashboard/local.
- Native Google observe actions (`gmail_observe_search`, `gmail_observe_message`, `calendar_observe_events`) use encrypted credentials + on-demand token refresh.
- Actions may return immediate outcome or async wait contract (`WAITING` + operation handles).

### L2: Feedback Re-entry and Continuation
- Non-`contact_user` outcomes emit `ActionFeedbackCue` → re-enter through SensoryCortex.
- `processActionFeedback()`:
  - Binds feedback percept to existing thread.
  - Updates deliberation evidence/progress/cooldown.
  - `WAITING` → suspend thread (no auto-continuation).
  - Continuation decided only after feedback re-enters cognition; executor no longer tags feedback with verdict.
  - Goal-origin `WAITING` without handles → contract violation → retry path.
- For `contact_user`:
  - Clear pending work for same `(rootInputId, sessionId)` scope.
  - Capture session digest, destroy scratchpad.
  - Force post-terminal long-term memory assessment.
  - Emit response latency, clear evidence cache.

---

## L1: Deliberation and Convergence

- Files: `src/main/kotlin/ai/neopsyke/agent/ego/DeliberationEngine.kt`, `DeliberationProgressMonitor.kt`, `MetaReasoner.kt`

**Pressure tracking** (`DeliberationProgressMonitor`):
- `DeliberationState` tracks: stepIndex, `DecisionPressure` [0-1], `StaleStreak`, `ProgressScore`, denialCount, stepsSinceNewEvidence, repeatSignatureHits, `NoopStreak`, `ModelErrorStreak`.
- `DecisionPressure` formula: baseline (0.10) + step/stale/denial/repeat/noop/modelError/evidenceGap pressures - progress relief. Clamped to [0, 1].
- Decision signatures tracked (window of 10) to detect loops.

**Pressure drives**:
- `MetaReasoner` assessment cadence (triggered when min steps reached + interval or pressure due).
- Guidance text for Planner.
- Pressure override: `MetaReasonerVerdict` `FINALIZE_NOW` or `REQUEST_TOOL_THEN_FINALIZE` → force convergence.
- Forced terminal `contact_user` under: circular pressure (high `DecisionPressure` + high `StaleStreak`) OR `ModelErrorStreak` (>= 3 errors + pressure >= 0.72 + steps >= 6).

**Action retry budget** (managed by `DeliberationEngine`):
- Per-input-scope cooldown/circuit-breaker for action types.
- Non-retryable failures increment counter; when budget hit, action type disabled for cooldown period.
- Emits `action_type_circuit_breaker_tripped`.

**State scoping**:
- Deliberation state is session-scoped (per-session monitor, guidance, assessment step).
- Evidence progress and action cooldowns scoped by `(rootInputId, sessionId)`.

### L2: Meta-Reasoner Details
- Schema-enforced structured output (`response_format=json_schema`).
- Local parse clamp on `reason` at 180 chars.
- Schema-validation fallback: retry with relaxed schema (removes `reason.maxLength`).
- Empty-content retry with adaptive completion-budget increase.
- Primary endpoint can fail over to optional `meta_reasoner_fallback` after repeated technical failures.
- Completion budget adaptive by prompt size, bounded by `MetaReasonerConfig`, weighted by model `token_weight`.
- Verdicts: `CONTINUE`, `CONTINUE_WITH_CONSTRAINTS`, `FINALIZE_NOW`, `REQUEST_TOOL_THEN_FINALIZE`.

---

## L1: Memory System

- File: `src/main/kotlin/ai/neopsyke/agent/ego/MemorySystem.kt`

**Four tiers**:
1. **Short-term** (`MemoryStore`): Rolling dialogue window with automatic compaction.
2. **Long-term** (`Hippocampus` + `LongTermMemoryAdvisor`): Vector-backed recall and selective persistence.
3. **Episodic** (`Logbook`): SQLite+FTS5 journal of events and interactions.
4. **Scratchpad** (`ScratchpadStore`): Per-request workspace for active request processing.

### L2: Short-Term Memory
- File: `src/main/kotlin/ai/neopsyke/agent/memory/shortterm/MemoryStore.kt`
- Recent turns in `ArrayDeque` (last ~8 verbatim), older turns folded into `rolledSummary`.
- Per turn capped at 700 chars. Compaction triggers at 85% utilization, targets 65%.
- `summaryForPrompt(maxTokens)` builds compressed memory for planner context.

### L2: Long-Term Recall and Consolidation
- `Hippocampus` interface: `recall`, `imprint`, `consolidate`, `health`. Admin: `stats`, `forget`, `reset`.
- Default: `NoopHippocampus`. Provider-backed when memory startup succeeds.
- `LongTermMemoryAdvisor` decides `save|skip` with confidence/tags/summary.
- Saved summaries use first-person agent perspective ("I learned...", NOT metacognitive wrapping).
- Subject classification: `user` (preferences/facts) or `self` (Id/internal reflections).
- Self-origin normalization: replaces "user" language with agent language; MCP writes stamp subject as "me".
- Oversized dialogue/recall compressed before advisor prompt.
- Completion budget adaptive by prompt size, bounded by `MemoryConfig`, cost-weighted by model `token_weight`.
- `MemorySystem` enforces: interval/cooldown gates, explicit remember-intent fast path, confidence threshold, recall-echo suppression, duplicate fingerprint suppression, temporary disable after repeated parse-fallback streaks.
- Every blocked persistence emits `long_term_memory_persistence_skipped` with reason code/detail.

### L2: Episodic Logbook
- File: `src/main/kotlin/ai/neopsyke/agent/memory/episodic/Logbook.kt`
- SQLite+FTS5 backend. Records events (INPUT_RECEIVED, REFLECTION_SESSION, etc.) with summaries and keywords.
- Entries carry active channel/principal/policy-scope metadata.
- Event-type narrative normalization: user timeline vs agent first-person memory/reflection.
- Session/interlocutor filters optional in recall (default cross-session; filtered only when user explicitly requests).
- Temporal intent maps to episodic recall + vector cues.
- Ambient-context-facing signals: recent useful actions/updates seeded from logbook then maintained incrementally.

### L2: Reflection Lessons
- `ReflectionLesson` entries triggered on denied-action/repeated-denied loops.
- Persisted as `MemoryImprint(source=ego_reflection_lesson)` with tags (`kind:reflection_lesson`, action/reason/session metadata).
- Deduplicated via recent fingerprint window.
- Explicitly skipped for technical/system failures (`TECH_*`/`SYSTEM_*` reason codes).
- Injected into planner lane prompts as "reflection lessons" context.

### L2: Scratchpad (Thread Workspace)
- File: `src/main/kotlin/ai/neopsyke/agent/memory/scratchpad/ScratchpadStore.kt`
- Enabled by default (`MemoryConfig.scratchpad.enabled=true`). Plan-gated activation (`activationMinPlanSteps=2`).
- Per-thread workspace with sections/evidence (persists for thread, survives wait/resume).
- Transient answer drafts grouped in active draft sequence per thread, excluded from planner prompt summaries, reset when cognition leaves answer-drafting work.
- `promptSummary(rootInputId, maxTokens)` for planner context (index + compact section summaries).
- `buildFinalCompilation(...)` for terminal answer: goal + sections + evidence + drafts + candidate.
- Confidence estimate: `(sections * 0.45) + (evidence * 0.45) + (goal * 0.10)`.
- `ScratchpadFinalizer` (`LlmScratchpadFinalizer`): LLM rewrite of final answer using workspace evidence. Workspace-confidence gate first, then model-confidence gate. Original payload kept on any gate/finalizer failure.
- Session digests captured before scratchpad destruction for ambient context.
- Dashboard: workspace telemetry carries `root_input_id` + `root_input_received_at_ms`. Full snapshots served on-demand via `/api/obs/workspace/{rootId}`.

---

## L1: Goals Runtime

- Files: `src/main/kotlin/ai/neopsyke/agent/goal/GoalsGateway.kt`, `GoalManager.kt`, `GoalStateMachine.kt`, `GoalPlanner.kt`, `GoalStepVerifier.kt`
- Feature flag: `config.goals.enabled=false` → `NoopGoalsGateway`.

**Boundary** — Ego uses gateway only for:
- `pendingWorkSummary()` during Id-driven impulses.
- `nextWorkFromCue(GoalRuntimeCue)` when goal work is ready.
- Goal-origin action lifecycle callbacks + `finalizeGoalCycle(rootInputId)`.

**Event-sourced state machine** (`GoalStateMachine`):
- Pure function: `transition(state, event) → (newState, commands)`.
- `GoalStatus`: `CREATED`, `PLANNING`, `ACTIVE`, `BLOCKED`, `SUSPENDED`, `COMPLETED`, `FAILED`.
- Events: Created, PlanGenerated, PlanRevised, StepStarted, StepActionExecuted, StepAcceptancePassed/Failed, StepBlocked/Unblocked, WaitConditionSatisfied/TimedOut, Suspended, Resumed, CronCycleStarted, Completed, Failed, PriorityChanged, Updated.
- Commands (side effects): EmitWorkReady, ScheduleWakeTimer, CancelWakeTimer, RegisterWaitCondition, ClearWaitCondition, PersistGoal, NotifyUser.

**PlanStep**:
- `StepStatus`: PENDING → READY → IN_PROGRESS → DONE/BLOCKED/SKIPPED/FAILED.
- Dependency tracking via `requires` (step IDs) and `produces` (output keys).
- Max attempts with retry tracking.

**Scheduling**:
- `TimerScheduler`: registers cron expressions or absolute timestamps.
- Cron-backed goals do not emit initial work-ready on creation; first execution waits for cron wake.
- Cron cycle restart: when tick arrives after COMPLETED/FAILED, resets plan-step state + clears produced keys.
- `WaitConditionMonitor`: polls async operations, fires satisfaction events.
- Goal step roots stable per `goal:<goalId>:<stepId>` for thread/scratchpad continuity across wait/resume.

**Goal work activations** use trusted internal automation `ConversationContext`.
- Scratchpads created when work is actually processed, not when cue ingested.
- Goal-origin `WAITING` without async handles → contract violation.
- Persist: `goal-events.jsonl`, `goal.json`, `goal-snapshot.json`, workspace artifacts.

---

## L1: Action Execution Surface (Available Actions)

- File: `src/main/kotlin/ai/neopsyke/agent/cortex/motor/MotorCortex.kt`
- Discovery via `ServiceLoader<AgentActionPluginFactory>` + optional connector runtime.
- Each plugin self-describes via `ActionDescriptor`: action id, dispatchable flag, planner description/payload guidance/example, deterministic superego directives, follow-up behavior, effect class, commit capabilities, trust constraints.

**Built-in action plugins**:
- `contact_user` — User-facing output. Effect: COMMIT_PRIVATE. Direct+autonomous commit.
- `resolution_draft` — Internal chunked synthesis (non-user-visible). Effect: OBSERVE.
- `web_search` — External search. Effect: OBSERVE. Capability: GATHERS_EVIDENCE. Requires follow-up.
- `website_fetch` — Fetch URL content. Effect: OBSERVE. Capability: GATHERS_EVIDENCE.
- `reflect_internal` — Internal durable-memory action. Effect: OBSERVE. Direct+autonomous commit.
- `reflect_evidence` — Evidence-bound reflection. Effect: OBSERVE.
- `goal_operation` — Goal lifecycle (create/status/list/pause/resume/delete). Effect: COMMIT_INTERNAL. Create supports optional `cron_expression`.
- `email_send` — Microsoft Graph adapter. Disabled unless env config present.
- `gmail_observe_search`, `gmail_observe_message`, `calendar_observe_events` — Native Google read-only.

**Connector-backed actions**:
- Optional, fail-closed behind `config.connectors.enabled`.
- Loaded from curated catalog + local installed state.
- Require local enablement, allowlisting, capability validation, tool-description pinning.
- Subprocesses launch with explicit minimal env + declared secret handles only.
- Manifests do not grant direct/autonomous commit; runtime treats non-observe connector actions as staged-by-default.

---

## L1: Queueing Model

- File: `src/main/kotlin/ai/neopsyke/agent/ego/AttentionScheduler.kt`
- Three bounded priority queues: opportunities, intentions (by `Urgency`), actions (by `Urgency`).
- `nextTask(isBlocked)` is blocked-root-aware: skips without dropping.
- Root-input scoped operations: detect pending fallback/plan-context work, clear pending work for resolved inputs.
- Duplicate fallback `contact_user` enqueues suppressed per `(rootInputId, sessionId)`.

---

## L1: Dashboard and Observability

- Files: `src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt`, `DashboardServer.kt`
- UI routes: Conversations (`/`), Observability (`/dashboard`), Action Control (`/action-control`).
- API namespaces: Chat (`/api/chat/*`), Observability (`/api/obs/*`), Action Control (`/api/action-control/*`).
- Thread inspection endpoints: `/api/obs/threads`, `/api/obs/threads/{threadId}`.
- Observability SSE streams lightweight events; heavy snapshots served on-demand via `/api/obs/workspace/{rootId}`.
- Action control SSE lane for staged/authorization lifecycle updates (replaces polling).
- Chat submission responses include authoritative stored payload and distinguish `recorded` from `enqueued`.
- Root-input to chat-session routing retained until staged work reaches terminal state.

---

## Safety and Fallback Patterns

- LLM callers use retry loops with bounded attempts (max 3).
- Pre-call `TokenBudgetGate` short-circuits when projected usage exceeds caps.
- Required JSON fields validated after deserialization.
- Blank assistant content treated as transport/protocol failure → retry/fallback triggers.
- Prompt-injection mitigation: deterministic guards outside Superego:
  - `PromptInjectionDefense` sanitizes untrusted external content.
  - `ExternalContentPipeline` ensures consistent sanitization and trust tagging.
  - Untrusted-data framing before follow-up Planner thoughts.
  - Long-term recall wrapped as untrusted data block.
- On failures:
  - Planner → noop fallback.
  - Superego → deny fallback.
  - Meta-reasoner → continue fallback.
  - Memory advisor → skip persistence.
  - Scratchpad finalizer → keep original payload.

---

## Edit Rules
- Keep this file synced with `AGENT_LOGIC_DIAGRAM.md`.
- Source of truth is the code, not this document.
- When behavior changes, update only affected sections.
