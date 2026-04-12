# Durable Work Runtime Vision

**Status:** Proposal (vision + product architecture)
**Date:** 2026-04-11
**Branch:** current working tree

## Purpose

This specification defines the long-term direction for NeoPsyke's durable work
capability.

It is not an implementation plan. It defines:

- the intended product behavior
- the architectural boundaries
- what the current Goal runtime already provides
- what should change to align with the vision
- explicit phase-1 and phase-2 runtime contracts
- phased acceptance criteria so each release can ship independently

The current Goal runtime should be treated as the nucleus of the final design,
not as a failed side feature to discard.

## Design Focus

This document gives detailed design guidance for phase 1 and phase 2.

Phase 3 remains part of the long-term vision, but it is intentionally not the
center of gravity for the current design. Phase-1 and phase-2 choices should
avoid forcing a future rewrite, but they should not overfit to hypothetical
workflow-generalization needs.

## Vision

NeoPsyke's main agent should work like a mind.

The Ego remains the reasoning center for the current activation: dialogue
context, memory recall, grounding, local planning, and action choice. Durable
work lives outside the Ego as structured long-lived commitments that can wake
the Ego when needed, much like alarms, calendars, reminders, obligations, and
life objectives do for a person.

All durable work is still reasoning work. NeoPsyke should not grow a zoo of
special-purpose automation engines with isolated reasoning paths. Instead:

- the durable runtime owns persistence, triggers, runtime state, progress, and
  wakes
- the Ego owns reasoning and action selection for the current activation
- the handoff between them is typed, explicit, and narrow

## Durable Work Shapes

NeoPsyke should converge toward one durable-work substrate that can express
multiple shapes of long-lived work.

### 1. Recurrent Tasks

Timer-driven recurring work.

Examples:

- weather alerts
- check social media periodically and prepare draft answers
- produce recurring summaries or reminders

### 2. Long-Term Goals

Persistent open-ended objectives that may wake from Id impulses or scheduled
review cycles and may require memory over long time spans.

Examples:

- find a better apartment and alert the user when suitable options appear
- help the user maintain healthier habits over time

These require durable state such as:

- seen items
- prior recommendations
- user preferences
- previously reported findings

### 3. Durable Workflows

Durable multi-step work with blockers, dependencies, and artifacts.

Examples:

- classify an incoming email, research the sender, decide importance, prepare a
  summary, include it in a report, or alert the user
- monitor repositories, triage issues, summarize PR requests, prioritize work,
  and surface important changes

This remains part of the target end state, but detailed workflow-generalization
concerns are out of scope for the current phase-1 and phase-2 design.

## Core Principles

### One durable-work substrate

The system should converge toward one durable-work runtime rather than separate
planner families for reminders, goals, and workflows.

### One reasoning path

The Ego should reason about durable work through the same general action
planning machinery it uses elsewhere, with only narrow trigger-specific context
shaping where necessary.

This explicitly includes plan generation. Plans for durable work must be
produced by the Ego, not by isolated LLM calls in the motor layer or runtime.
The Ego has access to ambient context, long-term memory, episodic recall,
available actions, and deliberation state. Plan generation without these
inputs produces plans that cannot be executed reliably.

### Runtime outside the mind

Scheduling, event listening, wake ownership, persistence, replay, progress
tracking, and execution state belong outside the Ego.

### Typed activation

The durable runtime should wake the Ego with a typed work activation, not with
natural-language heuristics and not with hidden side channels.

### Durable ledger of progress

The system must preserve what happened, what changed, what remains blocked, and
what artifacts were produced. Broad goals must be allowed to evolve and replan,
but changes must still leave an auditable trail.

### Safe side-effect handling

Durability is not real unless retries, restarts, and recovery have explicit
side-effect semantics.

### Progressive autonomy

The system should be released in phases. Each phase must be independently
valuable, testable, and reliable before the next phase adds more autonomy or
more trigger types.

## What The Current Goal Runtime Already Covers

The current Goal runtime already covers an important portion of this vision.

### Durable persistence

Goals already have:

- an append-only event log
- snapshots
- a workspace directory
- working context
- scratch notes
- artifacts

This is the right foundation for durable work continuity.

### Sequential durable plans

Goals already support:

- multi-step plans
- step dependencies
- produced keys
- retries
- pause/resume
- priority changes
- completion and failure

This already resembles a simple durable-work runtime for sequential work.

### Triggered wakeups

The current design already supports:

- cron/timer wakeups
- blocked/waiting steps
- async wait handles
- wake-based handoff into Ego

This aligns well with the intended "alarm/calendar/objective wakes the mind"
model.

### Runtime outside Ego

The current GoalManager already lives outside the Ego and emits work for the
Ego to process. That boundary is strategically correct and should be preserved.

### Goal operations

The current system already supports lifecycle operations such as:

- create
- list
- status
- pause/resume
- update
- revise plan
- reprioritize
- complete/delete

This gives NeoPsyke a usable operator surface for user-visible durable work.

## Current Design Limits

The current Goal runtime is best described as durable sequential goals with
timers and simple blocking.

That is already useful, but it does not yet fully cover the intended scope.

### What is not yet broad enough

- Plan generation for durable work happens in the motor layer
  (`LlmWorkPlanBuilder` inside `DurableWorkRuntime.createWorkItem()`), not in
  the Ego. This LLM call has no access to ambient context, memory, available
  actions, or deliberation state. Plans are generated blind and cannot be
  reviewed by the user before the work item is committed.
- There is no plan refinement or validation pass. Plans generated by the LLM
  are accepted as-is, even when they contain unexecutable or redundant steps.
  This applies to both durable work plans and Ego inline plans.
- The approval system has no mechanism to surface rich structured context (such
  as plan steps) alongside the approval prompt. The user sees only a summary
  and reason, not the plan that will govern execution.
- Generic external-event triggers are modeled but not yet realized as a
  first-class runtime capability.
- Durable per-item structured state is weak for monitoring scenarios such as
  apartment hunting, repeated search, deduplication, or report windows.
- Runtime replanning exists only partially. Durable plan mutation is not yet a
  first-class closed loop.
- Failure handling is mostly local to retries and terminal failure states. The
  broader durable-work model needs explicit stall detection, escalation, and
  user-visible health semantics.
- Resource contention, work ownership, and concurrent wake coalescing are not
  yet explicit runtime contracts.
- User observability is still closer to "goal status" than to a full durable
  operator view.
- Multi-activation testing and replay are supported by adjacent infrastructure,
  but they are not yet specified as required durable-work validation semantics.
- Id impulses are present at the Ego level, but they are not yet a strong
  first-class trigger source for reviewing durable work.

### What should not be overcomplicated

The system does not need, at this stage:

- separate planner families per durable-work category
- a general-purpose distributed workflow engine
- arbitrary graph execution
- full branching/subflow semantics from day one
- a free-form state blob with no schema ownership
- premature taxonomy proliferation in the user-facing model

## Target Alignment

The current Goal runtime should evolve into a broader durable-work runtime
while keeping its core strengths:

- event-sourced persistence
- workspace and artifact continuity
- typed work activation into the Ego
- explicit lifecycle and progress state

The main design shift is conceptual:

- today's concept: "persistent goals"
- target concept: "durable work"

In the aligned design, recurrent tasks, long-term goals, and later workflows
are different shapes of the same durable-work substrate.

The main differences between them should be expressed through:

- trigger policy
- plan richness
- durable state
- delivery policy
- autonomy policy

They should not be expressed through entirely separate reasoning subsystems.

## Terminology

The word Goal remains valuable, but it is too narrow as the umbrella term for
the full feature set. It fits long-term objectives well, but it
under-describes recurring monitoring and durable orchestration.

Recommended terminology:

- **Internal umbrella:** `Durable Work`
- **Runtime/service name:** `DurableWorkRuntime`
- **Gateway/interface name:** `DurableWorkGateway`
- **Single durable unit:** `WorkItem`
- **User-facing plain language:** keep `goal` available where it feels natural

Recommendation:

- Use Durable Work as the architectural umbrella.
- Treat Goal as one important user-facing flavor and as the historical nucleus
  of the system.
- Prefer a clean internal rename during phase 1 rather than carrying two sets
  of internal names indefinitely.

## Proposed Structural Changes

### 1. Generalize the current Goal runtime into a DurableWorkRuntime

The existing runtime should be evolved rather than replaced.

Target capability set for one work item:

- objective
- priority
- trigger policy
- plan
- runtime status
- durable state
- workspace and artifacts
- delivery policy
- history ledger

### 2. Keep the Ego as a shared reasoning engine

Durable work should continue to be executed by waking the Ego with a typed
activation. The runtime may shape context differently depending on trigger
source, but it should not create separate planners unless the reasoning
contract is materially different.

### 3. Strengthen durable state

The runtime should evolve from mostly document-style persistence toward a
combination of:

- event log
- plan state
- typed durable-state namespaces for monitoring and delivery state
- artifacts and summaries

This is necessary for recurring monitors, search-and-dedupe scenarios, and
report aggregation.

### 4. Strengthen trigger policy

The runtime should converge toward a common trigger model over:

- timer or cron
- Id review wakes
- manual or user wakes
- tool-result wakes
- external events later

Phase 1 and phase 2 do not need full external-event generalization.

### 5. Move plan generation into the Ego and add plan refinement

Plan generation for durable work must move from the motor layer into the Ego.
The Ego planner should produce plan steps as part of the durable work `CREATE`
and `REVISE_PLAN` decisions, with full access to available actions, memory, and
context. The runtime receives pre-built plans and stores or applies them; it
does not make LLM calls for plan generation or runtime-side replanning.

This is a clean ownership boundary:

- the Ego owns plan generation and plan revision
- the runtime owns persistence, revision history, wake semantics, and plan
  application

The durable-work plan contract must preserve the full runtime step model rather
than collapse to prose-only steps. At minimum this includes:

- step description
- acceptance criteria
- grounding requirement
- dependency inputs (`requires`)
- produced keys (`produces`)
- retry budget (`max_attempts`)

A shared `PlanRefiner` component should validate and improve all plans before
they are committed, whether they originate from durable work creation or from
Ego inline plan decomposition. The refiner should be a single bounded
LLM-powered repair/refinement step, not a stack of pre-processing layers. It checks
achievability (steps map to available actions), non-redundancy (no steps for
runtime facts), correct data flow between steps, minimal sufficiency, and
preservation of the durable-work step contract. The refiner can revise the plan
in the same pass, not just accept or reject it.

The refiner should be designed for endurance:

- repair malformed-but-recoverable plans instead of rejecting them
- preserve intent over literal wording
- prefer conservative edits over aggressive rewrites
- keep semantic judgment inside the model rather than deterministic text logic

The refiner must not apply one universal terminal rule to every plan. It should
receive plan-kind and terminal-policy signals so durable-work plans are not
forced into the same "end with direct user delivery" rubric as inline
conversational plans.

Outside the refiner, the system should keep only minimal mechanical boundary
checks required for execution/storage safety. It should not add a separate
deterministic semantic normalizer, because that would reintroduce brittle
natural-language interpretation outside the model.

Plan steps should be visible to the user at approval time through a
generalized approval context mechanism, not a durable-work-specific rendering
path. The user should be able to review the plan before the work item is
created, and request changes through the existing `DENY_AND_REISSUE` approval
flow without any ad-hoc plan-revision protocol. Because plan-edit replies are
often more specific than the approval summary, the approval interpreter should
also receive the rendered approval context as classification input. This should
strengthen model-based classification, not add a new layer of deterministic
semantic marker parsing for plan-edit text.

See `docs/EGO_PLAN_REFINEMENT_DESIGN.md` for the detailed implementation
design.

### 6. Make replanning an explicit durable operation

The system should allow plan revision and work reshaping without losing the
durable ledger of what was previously attempted, learned, blocked, or produced.

### 7. Preserve phase independence

Each phase must produce a stable subset that can be implemented, tested, and
trusted before broader trigger power or richer workflow semantics are added.

## Phase-1 And Phase-2 Runtime Contracts

The following contracts are part of the intended design for phase 1 and phase
2. They exist to keep the runtime durable without pushing unnecessary
complexity into the Ego.

### Runtime ownership and activation

- Each work item has exactly one runtime owner at a time.
- Phase 1 assumes a single-process runtime owner model, not a distributed lease
  system. Lease shapes should still avoid blocking a later distributed
  implementation, but distributed coordination is out of scope for phase 1 and
  phase 2.
- A work item may have many historical activations, but only one active lease,
  one current plan revision, and one authoritative durable-state snapshot.
- If a new wake arrives while the work item is already leased, the runtime
  should coalesce the wake into pending wake reasons rather than enqueue a
  second parallel activation for the same work item.
- The runtime, not the Ego, owns leases, wake coalescing, wait registrations,
  and resume readiness.
- The Ego should receive a typed activation that identifies the work item, the
  activation reason, the current plan revision, and the runtime-owned context it
  needs to reason.

### Side effects and idempotency

- Every externally meaningful action should be associated with a stable
  `effect_intent_id`.
- `effect_intent_id` should be derived from:
  `work_item_id + plan_revision + step_id + logical_effect_key`.
- Before a side effect is dispatched, the runtime should durably record that the
  effect intent exists.
- After dispatch, the runtime should durably record whether the effect was
  confirmed, abandoned, or left uncertain.
- Observe-only actions may be retried freely.
- Internal mutating actions may be retried only when they reuse the same
  `effect_intent_id`.
- External mutating actions may run autonomously only when the integration can
  enforce idempotency through a native idempotency key or a reliable dedupe
  lookup.
- If an external mutating action cannot guarantee idempotency, the runtime
  should prefer staged output, user review, or explicit human approval over
  autonomous replay.

### Durable state and schema evolution

- Durable state must not be an unowned free-form blob.
- Each durable-state namespace should declare an owner, a schema version, and a
  bounded purpose.
- Phase 1 should separate at least these conceptual namespaces:
  `runtime`, `plan`, `delivery`, and `artifacts`.
- Phase 2 may add monitoring-oriented namespaces such as `monitor` without
  forcing a redesign of the phase-1 core.
- Both the work-item snapshot and each durable-state namespace should carry a
  `schema_version`.
- Snapshot upgrades may transform projections, but the event log remains the
  authoritative history.

### Replanning and history

- Replanning is append-only.
- A new plan replaces the current future, but it does not rewrite prior history.
- Completed steps, prior failures, and prior outputs remain immutable history.
- Each plan revision should carry a monotonic `plan_revision`.
- A plan revision should be able to declare which prior revision it supersedes.
- Replanning should preserve continuity of artifacts, durable state, and user
  visibility into what changed and why.

### Crash recovery and replay

- Recovery must be based on an append-only event log plus persisted artifacts.
- Snapshots are projections for fast loading, not the source of truth.
- Each activation should leave a durable journal of at least:
  activation started, context materialized, step selected, effect intent
  recorded, effect confirmed or unresolved, activation finished, and next wake
  scheduled.
- On restart, a leased work item with no terminal closure and an expired
  heartbeat should enter a recovery path rather than silently resuming as if
  nothing happened.
- Recovery may resume reasoning from the last safe activation boundary, but it
  must reconcile side effects before replaying mutating work.
- Reasoning may be replayed for diagnosis; side effects must not be replayed
  blindly.

### Failure escalation and cleanup

- Step-local retry limits remain necessary, but they are not sufficient.
- The runtime should distinguish `FAILED`, `STALLED`, and `NEEDS_ATTENTION`.
- `FAILED` means the current attempt reached a terminal failure outcome under
  known rules.
- `STALLED` means the work item has stopped making progress for too long or has
  repeated an equivalent failure pattern across activations.
- `NEEDS_ATTENTION` means the runtime now requires user visibility or operator
  intervention.
- The runtime should track rolling-window failure counts per work item.
- A sensible default for escalation is:
  3 failed activations in 24 hours or 5 failed activations in 7 days.
- Timed-out waits should transition to an explicit blocked-timeout or
  stalled-wait state rather than disappearing silently.
- Timeout-based cleanup may clear stale leases and stale wait registrations, but
  it should not auto-delete durable work.
- User notification should be triggered when a high-priority item has stalled
  past its expected delivery window or when the system can no longer keep a
  user-visible promise quietly.
- A reasonable default notification SLA is:
  immediate surfacing for high-priority missed commitments, and next scheduled
  digest surfacing for lower-priority stalled work unless the user configured a
  stricter policy.

### Resource contention and scheduling

- The Ego remains a shared reasoning engine and therefore a constrained
  resource.
- Phase 1 and phase 2 should assume one activation is actively reasoning at a
  time, even if many work items are ready.
- The durable runtime may hold many ready items, but it should enforce bounded
  queues, bounded wait-monitor load, and configurable global limits for ready
  work and registered waits.
- Interactive user turns outrank background durable work.
- Within durable work, ready resumptions and overdue obligations should outrank
  routine recurring checks.
- Routine recurring checks should outrank low-urgency review wakes.
- Noisy monitors should be demoted by policy rather than relying on the planner
  to rediscover that they are noisy each time.
- Id may wake or request review of existing durable work in phase 2, but it
  should not create new work items autonomously by default.

### User observability and delivery policy

- The user needs a durable operator view, not only a raw status endpoint.
- User-facing health should distinguish at least:
  healthy, blocked, stalled, failed, and needs-attention.
- At minimum, a work item should expose:
  current status, next wake, last successful activation, last meaningful change,
  current blocker, failure count, delivery policy, and latest artifact summary.
- The system should support user-facing explanations such as:
  why blocked, why quiet, why notified, and why skipped.
- Delivery policy should be explicit runtime state, not planner folklore.
- Phase 1 delivery policy can remain simple, but it should already model the
  difference between immediate notify, digest later, only-on-change, and manual
  review.
- Phase 1 digest delivery may use one coarse runtime-level flush cadence rather
  than per-item digest scheduling. Rich digest assembly and aggregation remain a
  phase-2 concern.

### Testing across activations

- Durable work must be tested across multiple activations, not only within a
  single Ego wake.
- Multi-activation deterministic testing is a required acceptance mechanism for
  phase 1 and phase 2.
- Freud replay and session-recording infrastructure should be treated as part of
  the durable-work validation story.
- Important deterministic cases include:
  repeated timer wakes, dedupe across cycles, restart recovery, duplicate wake
  coalescing, timeout recovery, and replanning with preserved history.
- Crash-injection testing around effect-intent recording and effect confirmation
  boundaries is required for any runtime that claims durability.

### Event log growth

- Append-only event history is the source of truth in phase 1 and phase 2.
- Snapshotting is expected in phase 1; event-log compaction is not required in
  phase 1.
- Event-log compaction, archival, or segmenting should be treated as a phase-2
  operational concern and must not weaken replayability or auditability.

### Risk-management stance

- Constrain the runtime shell, not the reasoning core.
- Keep strict contracts for ownership, leases, state, side effects, replay, and
  delivery policy.
- Keep the reasoning layer flexible inside those contracts.
- Prefer typed activation profiles over new planner families when durable-work
  shapes need context-specific prompting.
- Do not solve monitor noise, repeated reports, or failure escalation only in
  prompts when the runtime can encode the policy explicitly.

## External Patterns Worth Learning From

The final design should remain NeoPsyke-specific, but several outside patterns
are relevant.

### OpenClaw

Useful pattern:

- shared agent runtime
- different trigger and runtime shells
- separate detached-work ledger
- separate durable flow orchestration

Lesson:

- avoid planner proliferation
- separate execution ownership, tracking, and orchestration from the core
  reasoning path

### Durable execution systems

Useful pattern:

- durable execution
- replay and checkpoint thinking
- idempotent side effects
- explicit human interrupts

Lesson:

- durable work needs explicit runtime ownership and safe side-effect semantics

### Actor and event-driven runtimes

Useful pattern:

- one owner for a durable work item's runtime state
- explicit messages and events
- conflict handling through ownership or revision checks

Lesson:

- avoid ambiguous multi-writer runtime state

### Workflow engines

Useful pattern:

- explicit blockers
- explicit state transitions
- durable artifacts and wait states

Lesson:

- simple sequential durable work can go far before general graph execution is
  necessary

### Memory-oriented agent work

Useful pattern:

- preserve summaries and artifacts across sessions
- distinguish immediate working context from long-term memory

Lesson:

- durable work should not replace NeoPsyke's memory system, but it should
  integrate with it cleanly

## Release Phases

### Phase 1: Stable Durable Sequential Work

### Objective

Evolve the current Goal runtime into a stable durable-work nucleus for:

- recurring timer-based tasks
- user-created persistent goals
- simple sequential multi-step work

### Scope

Included:

- create, update, list, status, pause, resume, reprioritize, revise, and delete
- timer and cron triggers
- sequential plans with dependencies
- retries and blocking via timer or async-operation waits
- durable context, scratch, and artifacts
- typed handoff into the Ego
- stable recurring execution for simple monitors and reminders
- explicit leases, wake coalescing, and activation journaling
- explicit delivery policy and user-visible health state
- explicit crash recovery and restart restoration
- single-process leases with runtime-owned wake coalescing
- coarse runtime-level digest flushing if digest delivery is enabled
- Ego-level plan generation: the Ego planner produces plan steps as part of
  the durable work `CREATE` and `REVISE_PLAN` decisions, with full access to
  available actions, memory, episodic context, and runtime facts. The runtime
  receives pre-built plans and does not make LLM calls for plan generation or
  runtime-side replanning.
- plan refinement: a shared `PlanRefiner` validates and improves all plans
  (durable work and Ego inline) before they are committed. It is a single
  bounded LLM-powered repair/refinement step that can fix malformed-but-
  recoverable plans and return one canonical final plan. Refinement is
  best-effort and falls back to the original planner plan on failure when
  meaning is still preserved and final mechanical execution/storage safety
  checks pass.
- full step-contract preservation: durable-work plans keep dependencies,
  produced keys, grounding requirements, and retry budgets when planning moves
  into Ego.
- generalized approval context: approvals carry structured context entries
  (label + content) that any action type can populate. For durable work
  creation, the plan steps are surfaced to the user at approval time.
- plan feedback via existing approval flows: the user can request plan changes
  through `DENY_AND_REISSUE`, which forwards their feedback as normal input
  for the Ego to re-plan. No ad-hoc plan-revision protocol.
- approval interpretation strengthened for plan edits: the approval interpreter
  receives approval-context content so replies like "merge steps 2 and 3" or
  "approve, but use web search first" can be treated as reissue requests.

Excluded:

- general external-event triggers
- rich workflow branching
- complex monitoring state beyond minimal dedupe and progress support
- autonomous Id creation or reshaping of durable work
- distributed lease coordination
- rich digest aggregation and cross-item digest assembly

### Acceptance Criteria

- A recurring timer-based work item can run repeatedly without losing durable
  state between activations.
- A simple monitor such as a weather alert can be created by the user, run over
  time, and produce reliable user-visible results.
- A sequential multi-step work item can complete with durable artifacts and a
  clear progress record.
- Pause, resume, reprioritize, revise, and delete behave reliably.
- Restoration after restart preserves progress, waits, future timer behavior,
  and lease recovery semantics.
- Duplicate wakes for the same work item are coalesced rather than processed in
  parallel.
- Side effects follow explicit idempotency and replay rules.
- The user can inspect why a work item is active, blocked, stalled, or quiet.
- The runtime and Ego boundary remains explicit: the runtime owns state and
  wakes; the Ego owns reasoning per activation.
- Plan generation happens in the Ego with full context. The runtime does not
  make LLM calls for plan generation. Plans for durable work are produced as
  part of the Ego planner's `CREATE` and `REVISE_PLAN` decisions.
- All plans (durable work and Ego inline) pass through a shared refinement step
  that repairs malformed-but-recoverable structure, validates achievability
  against available actions, removes redundant steps, verifies data flow, and
  preserves the durable-work step contract. Refinement is best-effort and falls
  back to the original planner plan on failure when meaning is still preserved
  and final mechanical execution/storage safety checks pass.
- The user sees the plan steps at approval time before the work item is created.
  Approval context is a generic mechanism available to any action type, not a
  durable-work-specific rendering path.
- The user can request plan changes by denying the approval with feedback. The
  feedback flows through the existing `DENY_AND_REISSUE` path: the Ego
  re-plans with the user's corrections visible in episodic context and approval
  context available to the interpreter.
- Once planning ownership moves into Ego, the runtime no longer silently
  re-generates plans. Missing plans on `CREATE` or `REVISE_PLAN` fail closed by
  default; any deterministic fallback exists only as an explicit recovery mode.
- The feature can be considered production-stable for reminders, recurring
  checks, and simple sequential durable work before phase 2 begins.

### Phase 2: Durable Monitoring And Long-Term Objectives

### Objective

Extend the durable-work nucleus to support open-ended monitoring and broader
long-term objectives that require remembered operational state over time.

### Scope

Included:

- durable typed state for seen items, cursors, dedupe keys, report windows, and
  other monitoring state
- long-term recurring search and monitor patterns
- stronger delivery and report policies
- phase-1 compatible schema evolution for monitoring state
- Id review wakes as a valid trigger source for reviewing or advancing existing
  durable work
- cleaner support for "keep looking and tell me when something important
  changes"
- richer digest assembly, report windows, and compaction or archival policy as
  needed for long-lived monitoring workloads

Excluded:

- broad external-event ingestion as a general workflow substrate
- full workflow orchestration with approvals and branching
- autonomous Id creation of new work items by default

### Acceptance Criteria

- A long-term search task such as apartment hunting can avoid reporting the
  same item repeatedly across many cycles.
- A monitoring task can maintain durable operational state without encoding
  everything as free-form text.
- A broad objective can be periodically reviewed and advanced over time without
  being reduced to a one-shot reminder.
- Delivery policy can suppress noisy repeated summaries and surface only
  meaningful deltas.
- Id review wakes can review or advance existing durable work without bypassing
  the shared durable-work model.
- Phase-1 plan generation, refinement, approval context, and user feedback
  mechanisms remain stable. Phase-2 does not introduce separate plan generation
  paths, runtime-side hidden replanning, or ad-hoc plan-revision protocols.
- Phase-1 schemas, leases, side-effect semantics, recovery rules, and user
  observability remain valid without requiring a redesign of the phase-1 core.
- Phase 1 behavior remains stable and does not regress while the richer state
  model is added.

### Phase 3: Durable Workflow Runtime

Phase 3 remains the long-term direction for richer event-driven workflows, but
its detailed design is intentionally deferred. The phase-1 and phase-2
contracts above should keep that path open without forcing a workflow-heavy
architecture into the current implementation.

## Non-Goals

This specification does not require:

- one universal planner prompt for every trigger forever
- elimination of all trigger-specific context shaping
- a complete implementation design in this document
- backward compatibility with old internal naming if a cleaner cut is better
- a commitment to the word Goal as the permanent architectural umbrella
- a general-purpose workflow DSL in phase 1 or phase 2

## Success Condition

This vision is fulfilled when NeoPsyke can treat recurring tasks, long-term
objectives, and later richer durable workflows as one family of durable work
that:

- persists outside the Ego
- wakes the Ego when needed
- preserves its own ledger of state, progress, and artifacts
- supports replanning without losing history
- handles retries, restarts, and side effects safely
- remains reliable when delivered incrementally in independent phases

At that point, the current Goal runtime will have evolved into the intended
DurableWorkRuntime rather than being replaced by a proliferation of special
automation subsystems.
