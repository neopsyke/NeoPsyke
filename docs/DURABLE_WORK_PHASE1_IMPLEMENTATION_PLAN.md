# Durable Work Phase 1 Implementation Plan

**Status:** Proposed implementation plan
**Date:** 2026-04-11
**Scope:** Phase 1 only
**Primary reference:** `docs/specs/DURABLE_WORK_RUNTIME_VISION.md`

## Purpose

Implement phase 1 by evolving the current Goal runtime into a
`DurableWorkRuntime` without increasing Ego complexity and without painting
phase 2 into a corner.

This plan intentionally optimizes for:

- clean internal naming
- explicit runtime contracts
- minimal changes to the Ego contract
- durability and operator clarity over feature breadth

## Phase-1 Outcome

At the end of phase 1, NeoPsyke should support:

- recurring timer and cron work
- user-created persistent work items
- simple sequential multi-step durable work
- explicit leases and wake coalescing
- explicit activation journaling and restart recovery
- explicit delivery policy and user-visible health state
- explicit side-effect intent recording for durable-work-origin actions

Phase 1 does not need:

- external-event orchestration
- broad workflow branching
- generic graph execution
- autonomous Id creation of new work items
- rich phase-2 monitoring state beyond simple dedupe and progress support
- a general workflow engine or per-kind planner families

## Clean Cuts And Naming Decisions

Phase 1 should perform a clean internal rename from Goal terminology to Durable
Work terminology.

### Internal rename map

- `agent/goal` -> `agent/durablework`
- `GoalsGateway` -> `DurableWorkGateway`
- `NoopGoalsGateway` -> `NoopDurableWorkGateway`
- `GoalManager` -> `DurableWorkRuntime`
- `GoalManagerRegistry` -> `DurableWorkRegistry`
- `GoalStateMachine` -> `WorkItemStateMachine`
- `GoalStore` -> `WorkItemStore`
- `GoalEventLog` -> `WorkItemEventLog`
- `GoalEvents` -> `WorkItemEvents`
- `GoalModels` -> `WorkItemModels`
- `GoalPlanner` -> `WorkPlanBuilder`
- `GoalStepVerifier` -> `WorkStepVerifier`
- `GoalContextLoader` -> `WorkContextLoader`
- `GoalRuntimeCue` -> `DurableWorkCue`
- `GoalRunActivation` -> `DurableWorkActivation`
- `GoalStatus` -> `WorkItemStatus`
- `config.goals` -> `config.durableWork`
- `goal:` root IDs -> `work:` root IDs
- `OpportunityTrigger.GoalWork` -> `OpportunityTrigger.DurableWork`
- `EgoTrigger.GoalWork` -> `EgoTrigger.DurableWork`
- `GoalWorkPlanner` -> `DurableWorkPlanner`
- `GoalWorkDecision` -> `DurableWorkDecision`
- `InputRoute.Goal` -> `InputRoute.DurableWork`
- `agent/ego/planner/input/GoalPlanner.kt` -> `agent/ego/planner/input/DurableWorkPlanner.kt`
- `goal-work` trigger label -> `durable-work`
- `goal_work` call site or timing labels -> `durable_work`
- `OriginSource.GOAL` references that represent durable runtime provenance ->
  `OriginSource.DURABLE_WORK`
- planner prompt references to `goal_operation` -> `durable_work_operation`
- planner prompt fields `goal_reference` / `goal_id` ->
  `work_item_reference` / `work_item_id`

### User-facing naming

- Keep natural-language use of "goal" where it feels natural in chat output.
- Use "work item" as the operator-facing generic term in dashboards and status
  APIs.
- Rename the internal action contract from `goal_operation` to
  `durable_work_operation`.
- If desired, the planner can still describe user-facing examples using the word
  "goal" for long-term objectives.

## Design Constraints

### Keep the Ego simple

- The Ego should continue to consume typed ready work from a gateway.
- The Ego should not own leases, wake coalescing, retries across activations,
  crash recovery, state migration, or user notification policy.
- The Ego may receive slightly richer activation metadata, but the scheduling
  contract should remain essentially the same.

### Keep runtime complexity outside the Ego

- Ownership, queueing, leases, recovery, health, and delivery policy belong in
  `DurableWorkRuntime`.
- If a new policy can be encoded in runtime state or durable-work commands, do
  that before introducing planner or scheduler complexity.
- Prefer a small number of explicit runtime invariants over broad new
  abstractions.

### Preserve existing agent behavior

- Non-durable-work interactive behavior should stay as-is.
- The durable-work refactor should preserve the current cue -> activation ->
  Ego -> action lifecycle shape.

### Prepare for phase 2 without implementing it now

- Introduce namespaced durable state now.
- Introduce delivery policy now.
- Introduce health state now.
- Introduce `planRevision` now.
- Introduce lease and activation journal semantics now.
- Do not implement full monitoring state or Id-driven work creation yet.

## Target Architecture

### 1. Runtime boundary

`DurableWorkRuntime` remains the only owner of durable-work state. The Ego sees
only typed activations and lifecycle callbacks.

Runtime responsibilities:

- create, update, list, status, pause, resume, reprioritize, revise, delete
- maintain event log, snapshot, workspace, and artifacts
- manage leases and wake coalescing
- schedule timers and waits
- restore work after restart
- classify health state
- apply delivery policy
- reconcile effect intents

Ego responsibilities:

- reason about the current activation
- choose actions for the current step
- execute through the normal action pipeline
- return action lifecycle callbacks

### 2. Data model

Replace the current goal model with a `WorkItem` model.

### Required fields

- `id`
- `kind`
  Values for phase 1: `recurring_task`, `long_term_goal`
- `title`
- `objective`
- `priority`
- `status`
- `health`
- `triggerPolicy`
- `deliveryPolicy`
- `planRevision`
- `currentPlan`
- `pendingWakeReasons`
- `activeLease`
- `failureWindow`
- `schemaVersion`
- `workspacePath`

### Namespaced durable state

Add a top-level state envelope with explicit namespaces:

- `runtime`
  Lease, activation journal pointer, wake metadata, heartbeat, retry counters.
- `plan`
  Step statuses, produced keys, current step pointer, superseded revisions.
- `delivery`
  Notify mode, last delivery time, last digest window, last reported summary.
- `artifacts`
  Artifact index, last summary, notable outputs.

Minimal phase-1 support for recurring checks and quiet-on-no-change behavior:

- `monitor`
  Optional, runtime-owned minimal monitor state for recurring checks only:
  last observation hash, last meaningful change timestamp, and bounded dedupe
  keys when a work item needs simple repeated-run suppression.

Phase-2 compatibility:

- include `schemaVersion` per namespace
- keep namespace ownership explicit in code
- keep `monitor` intentionally tiny in phase 1; richer seen-item, cursor, and
  report-window state remains phase 2 work

### `kind` semantics

- `recurring_task` means timer or cron driven work whose main contract is
  repeated execution under an explicit delivery policy.
- `long_term_goal` means persistent user-directed work that may still use timer
  review wakes, but whose main contract is durable progress toward an open-ended
  objective.
- In phase 1, `kind` does not select a different planner family.
- In phase 1, `kind` may shape defaults for trigger policy, delivery policy,
  and operator phrasing.
- In phase 2, `kind` may also shape durable-state expectations, especially for
  monitoring-oriented state, without changing the core runtime substrate.

### 3. State machine

Refactor `GoalStateMachine` into `WorkItemStateMachine`.

### Phase-1 statuses

- `CREATED`
- `PLANNING`
- `ACTIVE`
- `BLOCKED`
- `SUSPENDED`
- `COMPLETED`
- `FAILED`
- `STALLED`
- `NEEDS_ATTENTION`

### Rules

- `FAILED` remains a terminal outcome for the current attempt.
- `STALLED` is non-terminal and indicates recovery or operator visibility is
  needed.
- `NEEDS_ATTENTION` is non-terminal and indicates escalation to the user or
  operator surface.
- Cron-backed work may cycle back into `ACTIVE` after terminal completion or
  failure, but activation history must remain append-only.
- Replanning creates a new `planRevision`; it never rewrites old revisions.

### New event families

- `LeaseAcquired`
- `LeaseHeartbeat`
- `LeaseExpired`
- `WakeCoalesced`
- `ActivationStarted`
- `ActivationFinished`
- `ActivationRecovered`
- `EffectIntentRecorded`
- `EffectConfirmed`
- `EffectAbandoned`
- `EffectUncertain`
- `MarkedStalled`
- `MarkedNeedsAttention`
- `DeliveryDeferred`
- `DeliverySent`

Keep the state machine pure:

- `transition(state, event) -> (newState, commands)`

### 4. Activation and lease lifecycle

Implement a small lease model owned by the runtime.

Phase 1 lease scope is explicitly single-process. The lease model exists to
prevent duplicate activations inside one NeoPsyke runtime and across restart
recovery, not to solve distributed coordination. Lease tokens and revision
fields should still be named cleanly enough that a later distributed lease
implementation would not require another conceptual rewrite.

### Required semantics

- one active lease per work item
- lease acquired before work is offered to the Ego
- lease heartbeat refreshed at activation boundaries
- duplicate wakes while leased become appended wake reasons
- stale lease after restart or timeout moves item into recovery

### Single-writer runtime invariant

Phase 1 should make one additional rule explicit:

- all state changes for one work item are serialized through one runtime-owned
  command path
- timer wakes, wait completions, user operations, delivery updates, recovery,
  and action lifecycle callbacks must not mutate one work item concurrently
- implementation may use a per-item command queue, a per-item lock plus
  revision checks, or an equivalent single-writer mechanism
- event-log append order for one work item is authoritative; later projections
  and snapshots are derived from that order

This is intentionally a narrow invariant, not a distributed actor framework.

### Runtime algorithm

1. Wake arrives.
2. Runtime loads current state.
3. If item is leased, append the wake reason and stop.
4. If item is ready and unleased, acquire lease and emit one `DurableWorkCue`.
5. When activation context is materialized, record that boundary durably.
6. When Ego accepts the activation, record `ActivationStarted` and the selected
   step.
7. On action lifecycle callbacks, update effect-intent and step state.
8. On activation completion, release lease, update health, and schedule next
   wake if needed.

This keeps the scheduler simple because the runtime suppresses duplicate ready
work before it reaches Ego.

### 5. Side-effect handling

Use the existing action lifecycle observer seam instead of pushing effect logic
into the planner or action plugins.

### New runtime component

Add `WorkEffectLedger` under `agent/durablework`.

Responsibilities:

- derive and persist `effectIntentId`
- map lifecycle callbacks to effect status
- answer "has this logical effect already completed?"
- support recovery-time reconciliation

### `effectIntentId` contract

Phase 1 should make the identifier derivation explicit, not implicit:

- `effectIntentId = workItemId + planRevision + stepId + logicalEffectKey`
- `logicalEffectKey` must be stable across retries for the same logical side
  effect
- mutating action families must either provide a stable `logicalEffectKey` or
  declare that they support only staged/manual-review handling for durable work

### Phase-1 policy

- Observe actions may retry freely.
- Internal stateful actions may retry only with the same `effectIntentId`.
- External mutating actions must either:
  use an integration-level idempotency key, or
  degrade to staged/manual-review semantics.

### Integration path

- Keep effect reconciliation in the durable runtime package.
- Reuse the existing root-input and action-lifecycle observer plumbing.
- Avoid changing the general action execution API more than necessary.

### 6. Delivery and user health

Phase 1 should make delivery policy explicit runtime state.

### Delivery policy enum

- `IMMEDIATE`
- `DIGEST`
- `ONLY_ON_CHANGE`
- `MANUAL_REVIEW`

### Health state enum

- `HEALTHY`
- `BLOCKED`
- `STALLED`
- `FAILED`
- `NEEDS_ATTENTION`

### Default rules

- High-priority missed commitments surface immediately.
- Lower-priority stalled work surfaces in the next digest unless configured
  otherwise.
- Repeated equivalent no-change monitor runs should stay quiet under
  `ONLY_ON_CHANGE`.

### Runtime bounds and backpressure

Phase 1 should add a small set of explicit runtime limits:

- `maxReadyItems`
- `maxRegisteredWaits`
- `maxPendingWakeReasonsPerItem`
- `maxPendingDigestEntries`

Required behavior:

- when limits are hit, the runtime records a visible reason and moves lower
  urgency work toward deferred or needs-attention handling instead of silently
  dropping state
- interactive user turns still outrank background durable work
- routine recurring work should be demoted by runtime policy when it becomes
  noisy or backlogged

### Phase-1 digest mechanism

- Phase 1 should implement digest delivery as one coarse runtime-level flush
  cadence, not as per-item digest orchestration.
- Each work item with `DIGEST` delivery accumulates pending delivery entries in
  `state/delivery.json`.
- A single runtime-level digest flush wakes on a configurable interval and
  emits queued summaries.
- Cross-item report assembly, report windows, and richer digest grouping are
  explicitly deferred to phase 2.

### 7. Scheduler and Ego integration

Keep changes here intentionally small.

### `DurableWorkGateway`

The gateway should still expose a "next ready activation from cue" style
contract. Enrich the activation with:

- `workItemId`
- `stepId`
- `activationReason`
- `planRevision`
- `deliveryPolicy`
- `health`
- `wakeSequence`

### `StimulusIngressCoordinator`

- rename goal-specific cue handling to durable-work cue handling
- keep the same typed-ingress flow
- do not add planner branching logic here

### `AttentionScheduler`

Do not redesign it for phase 1.

Needed changes only:

- treat durable-work activations as the same broad class of scheduled
  opportunity they are today
- allow priority mapping from runtime urgency or lateness
- keep fairness policy mostly outside the scheduler by limiting runtime-emitted
  ready work

### `Ego`

Keep Ego edits minimal:

- rename goal-specific concepts to durable-work concepts
- read richer activation metadata
- emit lifecycle callbacks back to the runtime
- do not move retry, recovery, or queue logic into Ego

### 8. Operator surface

Expand the existing goal list and status surface into a work-item operator view.

### Required fields

- status
- health
- priority
- next wake
- last successful activation
- last meaningful change
- current blocker
- failure count in window
- delivery policy
- latest artifact summary

### Required explanation surfaces

- why active
- why blocked
- why stalled
- why quiet
- why notified
- why skipped or deferred

Dashboard and API work should read from runtime projections, not reconstruct
meaning ad hoc from raw event logs on every request.

### 9. Storage layout

Keep the current directory-per-item model, but rename it around work items.

Suggested layout per work item:

- `work-item.json`
- `work-item-snapshot.json`
- `work-item-events.jsonl`
- `activation-journal.jsonl`
- `workspace/`
- `artifacts/`
- `state/runtime.json`
- `state/plan.json`
- `state/delivery.json`
- `state/artifacts.json`
- `state/monitor.json`

Do not over-normalize phase-1 storage. A directory-per-item model is good
enough and keeps restoration simple.

### Event log growth and compaction

- Phase 1 keeps append-only event history plus snapshots.
- Phase 1 should not implement log compaction unless it is required for basic
  stability.
- If a practical limit is needed, use event-log segment rollover, not lossy
  compaction.
- True compaction, archival, and retention policy design are phase-2 concerns.

### 10. Compatibility with phase 2

Phase 1 should include these forward-compatible seams now:

- namespaced durable state
- `planRevision`
- `deliveryPolicy`
- `health`
- `pendingWakeReasons`
- lease semantics
- effect-intent ledger

Phase 1 should avoid these premature abstractions:

- generic workflow graph models
- generic event bus abstractions for every trigger type
- subflow engines
- planner families by work-item kind

## File And Module Plan

### Primary runtime files to rename or replace

- `src/main/kotlin/ai/neopsyke/agent/goal/GoalsGateway.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalManager.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalManagerRegistry.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalStateMachine.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalEvents.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalStore.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalEventLog.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalStepVerifier.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalContextLoader.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/TimerScheduler.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/WaitConditionMonitor.kt`

### High-touch integration files

- `src/main/kotlin/ai/neopsyke/AppModeRunners.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/StimulusIngressCoordinator.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`
- `src/main/kotlin/ai/neopsyke/agent/cortex/sensory/SensoryCortex.kt`
- `src/main/kotlin/ai/neopsyke/agent/cortex/motor/MotorCortex.kt`
- `src/main/kotlin/ai/neopsyke/agent/cortex/motor/actions/plugin/builtin/GoalOperationActionPlugin.kt`
- `src/main/kotlin/ai/neopsyke/agent/model/Enums.kt`
- `src/main/kotlin/ai/neopsyke/dashboard/DashboardServer.kt`
- `src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt`

### Tests to rename and expand

- `src/test/kotlin/ai/neopsyke/agent/goal/GoalStateMachineTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/goal/GoalManagerTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/goal/GoalPersistenceTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/goal/GoalOperationActionPluginTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/EgoGoalIntegrationTest.kt`
- `src/test/kotlin/ai/neopsyke/eval/AgentScenarioPackTest.kt`

## Implementation Sequence

### Milestone 1: Rename and boundary cleanup

- rename package, types, config, and root-ID terminology
- rename dashboard and operator surfaces
- rename the built-in operation action to `durable_work_operation`
- preserve behavior while changing names

Exit condition:

- code compiles
- all renamed tests pass unchanged in behavior

### Milestone 2: Work-item model and state namespaces

- introduce `WorkItem`, `WorkItemSnapshot`, and namespace-based durable state
- add `schemaVersion`, `planRevision`, `deliveryPolicy`, and `health`
- add minimal `monitor` state for simple dedupe and only-on-change semantics
- prefer a clean storage cut over a compatibility migration if migration logic
  would materially complicate the runtime in an unreleased prototype

Exit condition:

- persistence tests pass
- restart restoration still works

### Milestone 3: Lease and wake-coalescing runtime

- add per-item leases
- add pending wake reasons
- suppress duplicate activations
- emit only one ready activation per leased work item
- implement an explicit single-writer mutation path per work item
- add bounded ready/wait/pending-wake limits with visible overload behavior

Exit condition:

- duplicate wake tests pass
- no parallel activation of one work item is possible

### Milestone 4: Activation journal and recovery

- add activation journal
- add lease expiry and recovery path
- distinguish `FAILED`, `STALLED`, and `NEEDS_ATTENTION`
- add user-visible health projection
- record context materialized, step selected, and next wake scheduled as
  durable activation boundaries

Exit condition:

- restart and crash-recovery tests pass
- stalled detection appears in operator surfaces

### Milestone 5: Effect-intent ledger

- add `WorkEffectLedger`
- persist effect intent creation and confirmation
- add recovery-time reconciliation rules
- degrade unsupported mutating effects to staged/manual review behavior
- require stable `logicalEffectKey` ownership for mutating durable-work effects

Exit condition:

- crash-injection tests around effect boundaries pass
- autonomous replay respects idempotency rules

### Milestone 6: Delivery policy and observability

- add delivery policy projection
- add "why" explanations
- expand dashboard or API list/status response shape

Exit condition:

- operator view exposes all required fields
- digest vs immediate vs only-on-change behavior is testable

## Testing Plan

### Unit tests

- `WorkItemStateMachineTest`
- `DurableWorkRuntimeTest`
- `WorkItemStoreTest`
- `WorkEffectLedgerTest`
- `DurableWorkOperationActionPluginTest`

### Integration tests

- `EgoDurableWorkIntegrationTest`
- restart restoration with active lease
- duplicate wake coalescing
- cron recurrence after completion
- blocked wait timeout -> stalled or needs-attention
- delivery policy quieting repeated no-change runs
- overload/backpressure defers low-urgency work without dropping durable state
- recovery resumes from the last safe activation boundary

### Scenario and Freud coverage

Add deterministic scenario-pack coverage for:

- recurring timer work over multiple cycles
- simple dedupe across cycles
- revise-plan preserving prior history
- restart during active work
- crash between effect intent recorded and effect confirmed
- coalesced duplicate wakes with preserved wake reasons
- runtime overload with bounded queues and deferred low-urgency work

Use Freud replay to validate multi-activation execution rather than only
single-turn reasoning.

These scenarios should be treated as required phase-1 signoff coverage, not as
optional extra validation.

## Documentation Updates Required With The Code Change

When implementation starts, update these in the same patch series:

- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`
- user-facing docs for dashboard or status commands
- action and config docs affected by the rename

## Recommended First Patch

The safest first code patch is:

1. rename the runtime package and types to durable-work terminology
2. keep behavior the same
3. add `planRevision`, `deliveryPolicy`, and `health` fields with conservative
   defaults
4. wire the renamed gateway back into Ego unchanged

That creates the clean architecture boundary first, then lets the lease,
recovery, and side-effect work land on stable names.
