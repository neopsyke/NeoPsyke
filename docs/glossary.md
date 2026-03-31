# Glossary

A grouped reference for the concepts and terms used throughout NeoPsyke.
Each section covers terms that appear together in the same area of the agent.

---

## Structural Modules

- **Id** — Autonomous drive module that generates internal motivation. Runs an independent pulse loop, grows needs over time, and fires impulses into the Ego when a need becomes urgent enough. Named after Freud's concept of unconscious drives. `ai.neopsyke.agent.id.Id`
- **Ego** — Central planning and orchestration loop. Receives inputs, impulses, and goal work; decides what to do next (think, act, or defer); and coordinates memory, planner, and superego. The conscious executive of the agent. `ai.neopsyke.agent.ego.Ego`
- **Superego** — Governance layer that reviews every proposed action before execution. Applies deterministic policy checks and, when needed, an LLM-backed ethical review. Can allow, deny, or escalate. `ai.neopsyke.agent.superego.Superego`
- **SensoryCortex** — Input processing boundary. Receives external stimuli (chat messages, Telegram updates, Id cues, goal cues), sanitizes them, resolves interlocutor identity, attaches security context, and emits them as typed cognitive signals for the Ego. `ai.neopsyke.agent.cortex.sensory.SensoryCortex`
- **MotorCortex** — Action execution boundary. Maintains a registry of action plugins (discovered at startup via `ServiceLoader`) and dispatches approved actions to the correct handler. The agent's interface to the outside world. `ai.neopsyke.agent.cortex.motor.MotorCortex`

---

## Input & Perception

- **Stimulus** — Any raw external event arriving at the agent before processing: a user message, a Telegram update, an Id impulse cue, or a goal-runtime cue. A stimulus is data at rest — content that has not yet entered the runtime's typed event flow. Contrast with **Signal**, which is the runtime transport.
- **Signal** — A typed event flowing through the runtime's sensory channel. Signals are the transport mechanism; they carry stimuli (or control commands) into the Ego. There are two disjoint planes:
  - **CognitiveSignal** — wraps a `StimulusEnvelope` so the Ego can perceive it (`StimulusReceived`), or indicates absence (`NoStimulus`). This is data in flight on the cognitive plane.
  - **RuntimeControlSignal** — lifecycle and control events (`ExitRequested`, `ShutdownRequested`, `SourceClosed`, `ConfigReloaded`). Not perceived by the Ego — controls the runtime itself.
  In summary: a **stimulus** is *what* arrived; a **signal** is *how* it travels through the runtime. Both are defined in `SensoryCortex.kt`.
- **StimulusEnvelope** — The typed wrapper around a stimulus. Carries the content plus metadata: family (`LINGUISTIC`, `OBSERVATION`, `FEEDBACK`, `CUE`), trust level, provenance, and conversation context. `CognitiveArchitectureModels.kt`
- **Percept** — The internal representation of a stimulus after appraisal. A stimulus of family `LINGUISTIC` becomes a percept of family `REQUEST`; a `CUE` from the Id becomes `DRIVE_ACTIVATION`. The percept is what the Ego actually reasons about.
- **AsyncSignalSource** — The coroutine-backed signal bus that feeds stimuli into the Ego loop. Dashboard chat messages and terminal commands are enqueued here. `AsyncSignalSource.kt`
- **Interlocutor** — The identity of whoever the agent is talking to in a given session. Carries an `id` (name, hash, or JWT subject) and optional display `label`. The sentinel `Interlocutor.UNKNOWN` is used before identity resolution.
- **ConversationContext** — End-to-end metadata that travels with every input, thought, and action through the pipeline. Contains `sessionId`, `interlocutor`, and `security` (principal, channel, trust). Required to be non-blank everywhere.

---

## Drives & Motivation (Id)

- **Need** — A named internal drive (e.g. `user-interaction`, `learning`). Modeled as a value that grows continuously over time and, once urgent enough, triggers an impulse. Configured in `id-runtime.yaml`.
- **NeedState** — Mutable runtime state for a single need: current `value` (raw intensity 0–1), computed `tension`, cooldown countdown, in-flight flag, and backoff state. `NeedState.kt`
- **Pulse** — One tick of the Id's clock. On every pulse the Id increments each need's value by its `growthRate`, decrements timers, and evaluates whether any need should fire. Interval set by `pulseIntervalMs`.
- **Impulse** — A signal fired from the Id into the Ego when a need's tension crosses the trigger threshold. Carries the need id, tension, a prompt hint, and a `rootImpulseId` for lifecycle tracking. Queued as `PendingImpulse`.
- **Tension** — The curve-transformed value of a need, mapping raw intensity to effective tension via a `ResponseCurve`. A continuous `[0,1]` value that represents how "tense" or demanding a need feels. Not related to the `Urgency` enum used for queue prioritization. `NeedState.tension`
- **ResponseCurve** — A function mapping a need's raw value `[0,1]` to its effective tension `[0,1]`. Four types: `linear` (baseline), `power` (concave-up, good for core drives), `sigmoid` (tipping-point, good for social drives), `logarithmic` (immediately felt but never desperate, good for epistemic drives).
- **GrowthRate** — How much a need's raw value increases per pulse. A small number (e.g. `0.005`) means the need builds slowly.
- **ActivityDecay** — Passive reduction of a need's value when the agent performs relevant work, even without completing a full impulse cycle. Triggered by Ego callbacks, not the instrumentation bus. Configured per event type in `activityDecay`.
- **Cooldown** — Number of pulses a need must wait after firing an impulse before it can fire again. Prevents rapid re-triggering. Set by `cooldownPulses`.
- **Backoff** — Exponential delay applied after repeated superego denials. After `maxConsecutiveDenials` consecutive denials, the need enters backoff with duration doubling per escalation tier (capped at `MAX_BACKOFF_ESCALATION`).
- **ConvergenceMode** — How an impulse should be resolved. `CONTACT_USER` allows the planner to address the user; `INTERNALIZE` restricts it to internal research/reflection (user contact only if `allowEscalation` is true).
- **InFlight** — A flag on `NeedState` indicating that an impulse from this need is currently being processed by the Ego. While true, no new impulse is fired from this need.

---

## Planning & Deliberation (Ego)

- **Planner** — The LLM-backed decision function inside the Ego. Given a `PlannerContext`, returns an `EgoDecision`: propose an action, enqueue a thought, produce a no-op, or enqueue a multi-step plan. `LlmEgoPlanner`
- **PlannerContext** — The full context bundle passed to the planner on each step: recent dialogue, queue snapshot, short-term and long-term memory, lessons, scratchpad, evidence hints, deliberation state, meta-guidance, security summaries, available actions, and optionally the Id state snapshot.
- **PlanningBranch** — Which reasoning path the planner uses. `GENERAL` is the default; `GOAL_CREATION` is a special branch for multi-step goal proposals.
- **EgoDecision** — The planner's output. A sealed type with four variants: `ProposeAction` (do something), `EnqueueThought` (think more), `Noop` (decline with reason), `EnqueuePlan` (propose a multi-step goal).
- **DeliberationState** — Accumulated counters that track how the current reasoning episode is going. Injected into the planner context so it can adapt behavior under pressure. `CognitionModels.kt`
- **DecisionPressure** — A `[0,1]` score that rises with each deliberation step. When high, it signals the planner to stop deliberating and commit to an answer. Also used to trigger forced terminal answers.
- **StaleStreak** — Consecutive steps where the planner repeated the same type of decision without progress. Indicates the agent may be stuck.
- **ProgressScore** — A quality/progress metric accumulated across deliberation steps. Tracks whether the agent is making forward progress toward resolving the current input.
- **NoopStreak** — Consecutive steps where the planner returned `Noop`. A long streak suggests the agent has no useful next step.
- **ModelErrorStreak** — Consecutive LLM parsing or generation failures. Triggers fallback strategies when it grows.
- **MetaReasoner** — A second-level LLM reasoning step that assesses whether deliberation should continue, be constrained, or be finalized. Returns a `MetaReasonerVerdict`. Has a circuit-breaker and an optional fallback provider.
- **MetaReasonerVerdict** — The meta-reasoner's assessment: `CONTINUE`, `CONTINUE_WITH_CONSTRAINTS`, `FINALIZE_NOW`, or `REQUEST_TOOL_THEN_FINALIZE`.
- **AttentionScheduler** — Priority queue that decides what the Ego works on next. Opportunity work is scheduled first; after that the scheduler chooses between queued intentions and queued actions by urgency. `AttentionScheduler.kt`
- **LoopTask** — The unit of work the Ego executes in one step: `AttendOpportunity`, `ProcessIntention`, or `PerformAction`. Pulled from the `AttentionScheduler`.
- **ScheduledOpportunity** — A queued attention item pairing an `Opportunity` contract with its originating trigger (`Input`, `Impulse`, `Feedback`, or `GoalWork`). `QueueModels.kt`
- **OpportunityTrigger** — Sealed type recording which runtime source produced a scheduled opportunity: `Input`, `Impulse`, `Feedback`, or `GoalWork`. `QueueModels.kt`
- **AmbientContext** — Best-effort cached snapshot of active goals, recent scratchpad themes, useful recent actions, and open loops. Advisory only — biases recall and prompting toward relevant topics without blocking on state queries.
- **EgoTrigger** — A sealed type representing what caused the Ego to run the current step. Five variants: `IncomingInput`, `DeferredIntention`, `ActionFeedback`, `IncomingImpulse`, and `GoalWork`. `CognitionModels.kt`
- **ActionOrigin** — Provenance marker on pending thoughts and actions, tracking which source initiated the work (`USER`, `ID`, `SYSTEM`, `GOAL`) and optionally the `needId` and `rootImpulseId` for Id-originated work. `QueueModels.kt`
- **OriginSource** — Enum identifying the source of work: `USER`, `ID`, `SYSTEM`, `GOAL`. Part of `ActionOrigin`.
- **DecisionVerifier** — Pre-superego gate that validates whether a proposed action is grounded and ready to commit. Assesses task intent (personal memory, external observation, etc.), volatility, evidence requirements, and dispatch eligibility. `DecisionVerifier.kt`
- **DecisionDispatcher** — Routes planner decisions into the appropriate queues. Handles intention enqueueing, deferred continuation regeneration, plan suppression with dedup gates, and recovery from suppressed plans. `DecisionDispatcher.kt`
- **FallbackHandler** — Manages fallback behavior when actions are denied or fail. Enqueues denial thoughts, staged-action follow-ups, and fallback explanations when the planner exhausts options. `FallbackHandler.kt`
- **DeliberationEngine** — Manages deliberation state, external evidence tracking, action cooldown (retry budget), thread security context, and meta-reasoning pressure. Separate from `DeliberationProgressMonitor`. `DeliberationEngine.kt`
- **ScratchpadFinalizer** — Optional LLM-backed pass that rewrites the final action payload using gathered evidence, scratchpad state, and dialogue history. Returns confidence and grounding assessment. `ScratchpadFinalizer.kt`
- **ActionReviewPipeline** — Orchestrates the full path from proposed action to execution: scratchpad finalization, decision verifier check, superego review, action-control authorization, and execution. `ActionReviewPipeline.kt`
- **ImpulseLifecycleTracker** — Tracks the lifecycle of Id impulses through the Ego, recording action outcomes and evaluating need satisfaction when all impulse-driven work completes. `ImpulseLifecycleTracker.kt`

---

## Governance & Review (Superego)

- **GateDecision** — The superego's verdict on a proposed action: `allow` (boolean), `reason` (explanation), and optional `reasonCode`. The final allow/deny output of the review pipeline.
- **DeterministicConscience** — The fast, rule-based first stage of superego judgement. Applies static policy checks (action shape, origin, Id-origin allowlist) and delegates to the plugin's deterministic review. Runs before any LLM call and is authoritative. Emits `superego_deterministic_judgement` events. `SuperegoDeterministicConscience`
- **ActionDeterministicReview** — The plugin-level validity check on an action payload ("is this well-formed and safe?"). Each `AgentActionPlugin` can override `deterministicReview()` to apply action-specific rules. The superego maps these plugin verdicts into its own `SuperegoDeterministicDecision`, framing them in ethical/moral terms. The two types are intentionally separate: the plugin validates, the superego judges. `ActionPluginContracts.kt`
- **SuperegoPolicy** — Static directive sets that define what the superego should enforce. Includes `GENERAL_DIRECTIVES` (always applied) and `ID_ORIGIN_DIRECTIVES` (extra scrutiny for drive-initiated actions). Resolved per action via `forAction()`.
- **TwoStageReview** — An optional mode where a cheaper primary superego model handles routine reviews and an escalation model handles uncertain cases. Configured via `SuperegoConfig.twoStageReviewEnabled`.
- **AuthorizationDecision** — The full output of the action-control authorization step. Contains `progress` (how far the action is allowed to advance), `commitMode`, and reason/code.
- **AuthorizationProgress** — How far an action is allowed to proceed: `DENY`, `ALLOW_STAGE`, or `ALLOW_COMMIT`. Determines which lifecycle stage the action reaches.
- **CommitMode** — How an action was authorized to execute: `NOT_APPLICABLE` (no commit needed), `APPROVAL_BACKED` (human approved), `POLICY_AUTONOMOUS` (policy allowed direct commit), `ADMIN_OVERRIDE` (administrative bypass).

---

## Action Lifecycle

- **ActionType** — Extensible identifier for what an action does (e.g. `contact_user`, `web_search`, `reflect_evidence`). Whether it is dispatchable depends on its `ActionDescriptor`, not the type. Format: `^[a-z][a-z0-9_]{1,63}$`.
- **PendingAction** — An action proposed by the planner, sitting in the action queue awaiting review and execution. Carries urgency, payload, summary, root input tracking, and origin metadata. `QueueModels.kt`
- **PreparedAction** — An action that has passed initial preparation and carries full provenance and security context. Created from a `PendingAction` after the planner's proposal is accepted.
- **StagedAction** — A persistent record in the action-control store. Tracks the action through its full lifecycle via `StagedActionStatus`. May require human authorization before execution.
- **StagedActionStatus** — The state machine for a staged action: `READY` → `WAITING_AUTHORIZATION` → `AUTHORIZED` → `EXECUTING` → `COMPLETED` | `FAILED` | `CANCELLED` | `WAITING_EXTERNAL`.
- **CommitAuthorization** — A record granting permission to execute a staged action. Tracks who authorized it, via which commit mode, and optionally when it expires.
- **ActionReceipt** — The execution outcome record for a completed or failed action. Contains `executionStatus`, `statusSummary`, `effects`, and an optional `asyncWait` handle.
- **ActionOutcome** — The rich result returned by an action handler after execution. Contains the status summary, effects produced, any external content artifacts, and async-wait metadata. Used internally by the Ego to decide next steps.
- **ActionEffect** — A tag describing what an action accomplished: `TASK_PROGRESS`, `EVIDENCE_GATHERED`, `DURABLE_MEMORY_SAVED`, or `USER_MESSAGE_DELIVERED`. Used for Id satisfaction checks and progress tracking.
- **ActionEffectClass** — Classifies the impact scope of an action type: `OBSERVE` (read-only), `COMMIT_PRIVATE` (modifies user-private state), `COMMIT_PUBLIC` (externally visible), `COMMIT_STATEFUL` (durable system changes), `CONTROL_PLANE` (system configuration).
- **ActionLedgerEntry** — An immutable history record for action control. Each entry has a `kind` (staged, authorized, executed, denied, etc.) and an `importance` level (signal, background, trace).
- **ActionControlService** — Orchestrates the full authorization, staging, and execution lifecycle. Routes superego decisions, manages the SQLite store, and coordinates the autonomous worker. `DefaultActionControlService`
- **AutonomousWorker** — A background poller that picks up staged actions with status `READY` and executes them without waiting for the main Ego loop. `ActionControlAutonomousWorker`
- **DirectCommit** — An action that is allowed to skip staging and execute immediately because its policy says so. The superego still reviews it, but it does not wait for human approval.
- **AgentActionPlugin** — Interface for action handlers. Must provide `execute()`, may override `deterministicReview()`, `repairPlannerPayload()`, and `healthCheck()`. Discovered at startup via `ServiceLoader`. `ActionPluginContracts.kt`
- **ActionDescriptor** — Full configuration for an action type: planner guidance, payload schema, capabilities, effect class, superego directives, connector binding, and trust constraints. `ActionPluginContracts.kt`
- **ActionPlanningDefinition** — Per-action guidance injected into the `PlannerContext`: description, payload guidance, schema examples, effect class, and trust constraints. Derived from `ActionDescriptor`. `CognitionModels.kt`
- **ActionCapability** — Enum declaring behavioral traits of action plugins: `PRODUCES_USER_OUTPUT`, `GATHERS_EVIDENCE`. Allows cross-cutting systems (evidence tracking, workspace synthesis) to query plugin traits without hard-coding action types. `ActionPluginContracts.kt`
- **ActionExecutionContext** — Runtime context passed to action handlers during execution: conversation context, request ID, dry-run flag, and authorization record. `ActionPluginContracts.kt`

---

## Memory

- **MemoryStore** — Session-scoped short-term context buffer. Holds recent dialogue turns, action summaries, and recalled facts up to a character limit. Cleared between inputs. The agent's working scratchpad during a single interaction.
- **Hippocampus** — Long-term memory facade. Supports `recall` (semantic vector search), typed `imprint` (write), and future `consolidate` (summarize). Backed by pgvector over MCP. Named after the brain region for long-term memory formation.
- **Logbook** — Episodic memory store. Records timestamped events (inputs received, actions executed, denials, memory writes) with keywords and action types. Supports time-range and keyword queries. Used to give the agent a sense of its own history.
- **ScratchpadStore** — Ephemeral per-request workspace. Created when a new input arrives (keyed by `rootInputId`), used during deliberation, finalized by `ScratchpadFinalizer`, and cleared when queues drain. Visible in the dashboard.
- **LongTermMemoryAdvisor** — LLM-backed module that decides whether to persist new information to long-term memory after a conversation. Evaluates whether durable user facts or preferences were revealed.
- **RecallIntent** — The purpose of a memory recall request: `GENERAL`, `EPISODIC`, `LESSON`, `EVIDENCE`, `USER_PREFERENCE`, `FACT`, `GOAL`, or `SELF_REFLECTION`. Guides retrieval strategy.
- **MemoryKind** — Classification of what is being stored: `NARRATIVE`, `FACT`, `RELATION`, `EPISODE`, `LESSON`, `PREFERENCE`, `GOAL`, or `CONSTRAINT`.
- **Lesson** — A piece of learned knowledge stored in long-term memory and recalled alongside facts. Lessons are retrieved by the planner to avoid repeating mistakes or to apply prior insights.
- **Consolidation** — The process of summarizing and compacting long-term memories. Not yet fully implemented; the `consolidate` path exists on the `Hippocampus` interface.

---

## Queue & Scheduling

- **PendingInput** — A user message or external event waiting in the input queue. Carries content, `InputPriority`, `rootInputId`, and `ConversationContext`.
- **PendingThought** — A reconstructed deferred-continuation payload used while processing a queued `IntentionKind.DEFER`. It carries urgency, pass count, denial context, and recall hints, but it is no longer a primary scheduler category by itself.
- **PendingImpulse** — An Id impulse waiting in the impulse queue. Carries the triggering `needId`, tension, prompt hint, and `rootImpulseId`.
- **QueueSnapshot** — A read-only view of queue depths: `pendingInputCount`, `pendingThoughtCount`, `pendingActionCount`, `pendingIntentionCount`, and `pendingImpulseCount`. Injected into the planner context.
- **InputPriority** — Priority level for incoming inputs: `LOW`, `MEDIUM`, `HIGH`. Determines ordering in the scheduler. `Enums.kt`
- **Urgency (queue sense)** — The `Urgency` enum (`LOW`, `MEDIUM`, `HIGH`) used to prioritize pending thoughts and actions in the attention scheduler. Distinct from the Id's continuous tension value. `Enums.kt`
- **RootInputId** — A UUID string that uniquely identifies the originating request and follows it through every downstream thought, action, and lifecycle event. Generated by `RootInputIds.next()`. Used for tracing and scratchpad scoping.

---

## Security & Trust

- **PrincipalRole** — The trust level of whoever is interacting with the agent: `OWNER`, `APPROVED_AUTOMATION`, `EXTERNAL_PARTICIPANT`, `SYSTEM_INTERNAL`, `ADMIN_CONTROL`, or `UNAUTHENTICATED_EXTERNAL`. Determines what actions are permissible.
- **InstructionTrust** — Whether the source of an input may instruct the agent: `TRUSTED_INSTRUCTION` (owner, admin) or `UNTRUSTED_INSTRUCTION` (external participants, retrieved content). Propagated end-to-end.
- **DataTrust** — Trustworthiness classification of content: `TRUSTED_DATA` (system-generated), `EXTERNAL_DATA` (raw external content), or `SANITIZED_EXTERNAL_DATA` (external content passed through the sanitization pipeline).
- **Provenance** — Full metadata about the origin and trust of a piece of content. Contains `instructionTrust`, `dataTrust`, `source` descriptor (provider, content kind, object type), and optional sanitization record. Attached to actions, artifacts, and stimuli.
- **ChannelSurface** — The interaction modality: `DIRECT` (private chat), `GROUP`, `SHARED_WORKSPACE`, `AUTOMATION`, or `ADMIN`. Part of `ChannelRef`.
- **TransportClass** — The delivery mechanism: `CHAT`, `WEBHOOK`, `API`, or `INTERNAL`. Part of `ChannelRef`.
- **ConversationSecurityContext** — The security metadata attached to every conversation: principal identity and role, channel metadata, instruction trust, and policy scope. Carried end-to-end on `ConversationContext.security`.
- **ExternalContentPipeline** — The single ingestion path for all external content artifacts (web fetches, search results, etc.). Ensures consistent sanitization, trust tagging, and provenance tracking before external data enters the agent's reasoning.

---

## Goals

- **Goal** — A user-defined or agent-proposed multi-step objective. Has a status, priority, plan (list of steps), completion criteria, and optional cron schedule for recurring execution.
- **GoalStatus** — Lifecycle state: `CREATED`, `PLANNING`, `ACTIVE`, `BLOCKED`, `SUSPENDED`, `COMPLETED`, `FAILED`.
- **GoalPriority** — Importance level: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. Influences scheduling when competing with other work.
- **GoalPlan** — An ordered list of `PlanStep` entries with generation and revision timestamps. The roadmap for achieving a goal.
- **PlanStep** — A single step within a goal plan. Has a status, acceptance criteria, dependency edges (`requires`, `produces`), retry limits, and an optional `WaitCondition`.
- **StepStatus** — Lifecycle state of a plan step: `PENDING`, `READY`, `IN_PROGRESS`, `BLOCKED`, `DONE`, `FAILED`, `SKIPPED`.
- **WaitCondition** — A condition that must be met before a step can proceed. Types: `TIMER`, `EXTERNAL_EVENT`, `CONDITION_CHECK`, `CRON`, `ASYNC_OPERATION`. Has a timeout and escalation behavior.
- **GoalRunActivation** — A signal from the goal runtime to the Ego indicating that a goal step is ready for execution. Enters the Ego as an `EgoTrigger.GoalWork`.

---

## Dashboard & Observability

- **DashboardServer** — The embedded HTTP server (default `127.0.0.1:8787`) that serves the web UI and exposes REST + SSE APIs. Uses Java's `HttpServer` with virtual threads.
- **SSE stream** — Server-Sent Events connections used for real-time updates. Separate streams exist for chat events, thinking/inner-voice events, observability events, action-control events, and Id thinking.
- **InnerVoice** — The filtered stream of the agent's internal reasoning steps, exposed to the dashboard. The `InnerVoiceSink` selectively surfaces deliberation events: single-step answers produce no events; multi-step deliberation surfaces curated events.
- **InnerVoiceEvent** — A single inner-voice event with a type (`DELIBERATION`, `INTENTION`, `PLAN`, `PLAN_STEP`, `RECONSIDERATION`, `RECALL`, `OBSERVATION`, `REFLECTION`), message, session id, and timestamp. Streamed to the conversations page. `InnerVoiceModels.kt`
- **ChatRuntimeBridge** — The connector between dashboard chat sessions and the agent runtime. Validates and records user messages, enqueues them to `AsyncSignalSource`, and returns submission status.
- **DashboardStateStore** — Thread-safe state manager for the dashboard. Aggregates agent events into chat sessions, queue views, deliberation state, and scratchpad snapshots. Powers both REST snapshots and SSE broadcasts.
- **Scratchpad (dashboard view)** — The dashboard's live view of the agent's ephemeral working notes. Shows scratchpad index and per-request content. Distinct from `ScratchpadStore` which is the runtime data structure.

---

## LLM & Providers

- **CognitiveRole** — A named function the agent uses an LLM for: `planner`, `action_verifier`, `superego_primary`, `superego_escalation`, `meta_reasoner`, `meta_reasoner_fallback`, `memory_advisor`. Each can use a different provider and model, configured in `llm-runtime.yaml`.
- **ChatModelClient** — The interface for making LLM completion calls. Implementations exist per provider: `OpenAiChatClient`, `AnthropicChatClient`, `GroqChatClient`, `MistralChatClient`, `GeminiChatClient`, `OllamaChatClient`.
- **ProviderStatus** — The result of a health check against an LLM provider endpoint. States: `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`. Probed at startup per cognitive role.
- **PromptBudget** — The token budget system that controls how much context the planner can include. Sections are prioritized and trimmed (optional sections first, then required context) to fit within `maxLlmPromptTokens`. Logged as `prompt_budget.allocation` events.
- **TokenBudgetGate** — A pre-call guard that enforces run-level token caps (`max_run_total_tokens`, `max_run_tokens_per_provider`, `max_run_tokens_per_role`) across all cognitive-role LLM calls.
- **StructuredOutputMode** — The negotiated mode for getting structured JSON from an LLM: `strict json_schema` → `relaxed json_schema` → `prompt-only JSON`. Degrades automatically if a provider does not support strict schemas. Can stay sticky (degraded) for the lifetime of a run.

---

## Instrumentation

- **AgentEvent** — A structured telemetry event emitted by the agent runtime. Carries a `type` (e.g. `loop_status`, `planner_decision`, `action_executed`), timestamp, and type-specific data map. Produced by `AgentEvents` factory methods.
- **InstrumentationBus** — The central event dispatch system. Buffers events in a channel and routes them to registered sinks. Supports critical (synchronous) and regular (asynchronous) delivery. Drops oldest events on overflow.
- **InstrumentationSink** — A consumer of agent events. Implementations: `StructuredLogSink` (log file), `MetricsEventSink` (metrics aggregation), `DashboardStateStore` (UI state), event sidecar (JSONL file).
- **PhaseTimings** — Per-task timing breakdown recording how long each phase took: input processing, planner context assembly, LLM call, superego review, action execution, etc. Emitted as `phase_timings` events.
- **MetricsSnapshot** — Periodic aggregate of LLM call counts, token usage, latencies, and error rates across all providers and cognitive roles. Emitted as `metrics_snapshot` events and served by the metrics dashboard page.

---

## Evaluation (Freud)

- **Scenario** — A scripted test case for the agent, defined in `freud/scenarios/`. Specifies input stimuli, expected behavior, and success criteria. Used to evaluate reasoning quality, safety, and memory.
- **FeatureLoop** — The Freud pipeline runner implemented by `./freud/bin/freud run <feature-id>`. It executes the configured deterministic and optional live steps, writes indexed artifacts, and reports pass/fail status.
- **LiveEval** — Real-time evaluation mode where the agent runs against live scenarios with instrumented metrics. Includes `MemoryLiveEval` (memory system) and reasoning self-evaluation.
- **ReasoningEvalMode** — The evaluation strategy: `LOGIC` (pure logic test) or `MODEL` (model-based reasoning assessment).
