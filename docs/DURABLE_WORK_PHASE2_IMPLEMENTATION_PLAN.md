# Durable Work Phase 2 Implementation Plan

**Status:** Proposed implementation plan
**Date:** 2026-04-12
**Scope:** Phase 2 only
**Primary reference:** `docs/specs/DURABLE_WORK_RUNTIME_VISION.md`
**Assumes:** the current `agent/durablework` runtime is the phase-1 nucleus

## Purpose

Implement phase 2 by extending the current `DurableWorkRuntime` from stable
durable sequential work into a runtime that can support:

- long-lived monitoring loops
- open-ended long-term objectives with remembered operational state
- delta-aware delivery and reporting over many activations
- Id-triggered review wakes for existing work items

This plan is intentionally conservative about architecture:

- keep the runtime shell responsible for durability, state, wakes, and delivery
- keep the Ego as the shared reasoning engine for one activation at a time
- avoid introducing a separate monitoring planner or workflow engine
- preserve phase-1 contracts rather than replacing them

## Phase-2 Outcome

At the end of phase 2, NeoPsyke should support:

- durable monitoring state for seen items, cursors, report windows, and
  meaningful-change detection
- recurring search and monitoring tasks that avoid re-reporting the same items
- open-ended objectives that can be reviewed and advanced across many cycles
- richer delivery policy for digest windows, delta-only summaries, and quiet
  no-change periods
- Id review wakes that can revisit or advance existing durable work items
- long-lived storage behavior suitable for monitoring workloads, including
  event-log rollover or archival without weakening replayability

Phase 2 does not need:

- generic external-event ingestion as a broad workflow substrate
- branching workflow orchestration or approval graphs
- autonomous Id creation of new work items by default
- a new planner family dedicated to monitoring
- a free-form runtime blob that pushes semantics into prompts

## Preconditions

Phase 2 should build on a stable phase-1 core. Before implementation starts,
these phase-1 seams should remain intact:

- single-writer mutation per work item
- leases and wake coalescing
- activation journal and recovery boundaries
- effect-intent recording and replay safety
- explicit `health`, `deliveryPolicy`, and `planRevision`
- namespaced durable state persisted by the runtime

If any of those are still unstable, fix them first. Phase 2 should deepen the
runtime, not compensate for weak phase-1 invariants with more heuristics.

## Design Constraints

### Keep one durable-work substrate

- Do not create separate runtimes for reminders, monitors, and long-term goals.
- Monitoring behavior should be expressed through typed durable state,
  trigger policy, and delivery policy.
- `WorkItemKind` should stay small. Do not add new kinds unless operator UX
  genuinely needs one.

### Keep one reasoning path

- The Ego should still receive one typed `DurableWorkActivation`.
- Monitoring-specific context shaping is acceptable.
- Monitoring-specific planner families are not.

### Push semantics into runtime state, not prompt folklore

- Dedupe, seen-item memory, delivery suppression, report windows, and cursor
  advancement belong in runtime-owned state and policy.
- The planner may interpret what changed, but it should not be responsible for
  remembering every previously reported item in free text.

### Preserve phase-1 contracts

- Existing lease, replay, side-effect, and observability rules remain valid.
- Schema evolution must be additive and migration-friendly.
- Recovery must remain anchored to event history and persisted state.

## Target Architecture Additions

### 1. Strengthen typed monitoring state

The current `MonitorState` is intentionally minimal. Phase 2 should evolve it
into a typed monitoring envelope with explicit ownership and bounded purpose.

Recommended shape:

- `MonitorState`
  - `schemaVersion`
  - `sources`
  - `seenItems`
  - `changeLedger`
  - `reporting`
  - `review`

Recommended subtypes:

- `MonitorSourceState`
  - source key
  - source kind
  - cursor or checkpoint
  - last scan time
  - last successful scan time
  - last scan summary
- `SeenItemRecord`
  - stable item key
  - first seen time
  - last seen time
  - last reported time
  - last fingerprint
  - lifecycle status
- `ChangeRecord`
  - item key
  - change class
  - observed at
  - report eligibility
- `ReportingWindowState`
  - active window key
  - window opened/closed at
  - items included
  - last digest watermark
- `ReviewState`
  - last review time
  - next review due
  - skipped-review counters
  - latest review reason

Rules:

- Each sub-structure must be runtime-owned and documented.
- Keep cardinality bounded by config.
- When bounds are exceeded, demote old detail into summarized artifacts instead
  of letting state grow without limit.

### 2. Replace stringly wake metadata with typed wake reasons

The spec requires typed activation. Phase 2 is the right time to stop leaning
on free-form wake strings for monitoring-heavy work.

Introduce typed wake models such as:

- `WakeReason`
  - `timer_due`
  - `wait_resolved`
  - `delivery_flush`
  - `manual_review`
  - `id_review`
  - `recovery`
  - `overdue_check`
  - `monitor_change_detected`
- `ActivationContext`
  - work item id
  - plan revision
  - wake sequence
  - typed wake reasons
  - runtime urgency
  - delivery mode
  - monitoring summary snapshot

Implementation goal:

- persist typed wake reasons in events and snapshots
- keep user-facing explanations readable by projecting from typed data
- avoid semantic routing based on natural-language wake text

### 3. Add durable monitoring policies

Phase 2 should let the runtime encode monitoring behavior directly.

Required policy concepts:

- dedupe horizon
- quiet-after-no-change horizon
- report-window size
- maximum retained seen items
- maximum retained change records
- stale-monitor review interval
- overdue-objective review interval

These should live in `DurableWorkConfig` or a new `MonitoringConfig`
sub-configuration, not as scattered literals.

### 4. Upgrade delivery from coarse flushes to report assembly

Phase 1 delivery is intentionally coarse. Phase 2 should support richer
delivery assembly without turning delivery into a separate workflow engine.

Recommended additions:

- report window accumulation
- delta-only summaries
- suppress repeated unchanged summaries
- combine multiple relevant changes from one work item into one delivery unit
- explicit reasons for notify vs defer vs suppress

Recommended runtime model:

- `DeliveryState`
  - pending entries as typed records, not raw strings
  - digest watermark
  - last delivered delta signature
  - last suppression reason
  - last meaningful-change timestamp

### 5. Add Id review wakes for existing durable work

Phase 2 allows the Id to request review of existing work items. It should not
create new work items autonomously by default.

Required behavior:

- Id emits a review request against existing work items only
- runtime decides whether the item is eligible for review
- review requests coalesce with existing pending wakes
- review priority is below overdue obligations and ready resumptions
- operator surfaces show when a wake came from Id review

The runtime should own this bridge. Do not let Id bypass the durable-work
runtime and inject ad hoc long-lived work directly into Ego.

### 6. Prepare long-lived operational storage

Monitoring workloads are long-lived. Phase 2 should add operational controls for
event growth without weakening auditability.

Recommended additions:

- event-log segment rollover
- archival policy for completed or old segments
- projection rebuild from segments
- retention policy for old monitor detail moved into summaries or artifacts

Constraints:

- append-only history remains authoritative
- snapshots remain projections
- compaction must never destroy the ability to explain why something was or was
  not reported

## Data Model Changes

Phase 2 should extend current models rather than replacing them.

### `WorkItem`

Add or strengthen:

- `reviewPolicy`
- `operatorSummary`
- `lastMeaningfulChangeAt`
- `lastReviewAt`
- `nextReviewAt`
- `monitoringEnabled` only if needed for projection clarity

Avoid turning `metadata` into the real state model.

### `DurableWorkState`

Evolve toward:

- `runtime`
- `plan`
- `delivery`
- `artifacts`
- `monitor`
- `review` only if it cannot stay cleanly inside `monitor`

Recommendation:

- keep monitoring and review close together unless a separate namespace is
  required for ownership clarity
- prefer one well-designed `monitor` namespace over several tiny namespaces
  with blurry boundaries

### `DeliveryState`

Replace phase-1 coarse strings with typed records:

- `PendingDeliveryEntry`
- `DeliveryDecision`
- `DeliverySuppressionReason`
- `DigestWindow`

### `WorkItemEvent`

Add event families such as:

- `MonitorScanStarted`
- `MonitorScanCompleted`
- `MonitorCursorAdvanced`
- `SeenItemRecorded`
- `SeenItemUpdated`
- `MeaningfulChangeDetected`
- `ReportWindowOpened`
- `ReportWindowClosed`
- `DeliverySuppressed`
- `IdReviewRequested`
- `IdReviewAccepted`
- `IdReviewDeferred`
- `EventSegmentRolled`

Keep event names factual and runtime-owned. Do not encode planner prose into
event taxonomies.

## Runtime Behavior Changes

### Monitoring loop semantics

For recurring monitoring work:

1. Wake occurs.
2. Runtime loads item and typed monitor state.
3. Runtime materializes scan context, including cursor, seen-item hints, and
   reporting window summary.
4. Ego reasons about the current step using the shared durable-work path.
5. Runtime records observed items, dedupe outcomes, and delivery decisions.
6. Runtime schedules the next wake or review.

Key rule:

- "nothing important changed" must be representable as runtime state and a
  delivery decision, not only as an LLM-written note.

### Long-term objective review semantics

For open-ended objectives:

- allow periodic review wakes even when no timer-driven monitor exists
- preserve durable state about prior recommendations, attempts, and blockers
- let the runtime request review because an objective is stale or overdue
- keep review history append-only

### Delivery decision boundary

Delivery should become an explicit post-activation runtime decision:

- `notify_now`
- `queue_for_digest`
- `suppress_as_duplicate`
- `suppress_as_no_change`
- `manual_review_only`

The planner may generate candidate summaries, but the runtime should own the
decision and persist the reason.

## File And Module Plan

### Primary runtime files

- [DurableWorkRuntime.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/DurableWorkRuntime.kt)
- [WorkItemModels.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/WorkItemModels.kt)
- [WorkItemEvents.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/WorkItemEvents.kt)
- [WorkItemProjection.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/WorkItemProjection.kt)
- [WorkItemStore.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/WorkItemStore.kt)
- [WorkContextLoader.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/WorkContextLoader.kt)
- [DurableWorkConfig.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/DurableWorkConfig.kt)
- [ActivationJournal.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/durablework/ActivationJournal.kt)

### Runtime-adjacent integration files

- [Ego.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt)
- [AttentionScheduler.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/ego/AttentionScheduler.kt)
- [DurableWorkLanePlanner.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/DurableWorkLanePlanner.kt)
- [SharedPromptSections.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/ego/planner/prompt/SharedPromptSections.kt)
- [Id.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/agent/id/Id.kt)
- [DashboardServer.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/dashboard/DashboardServer.kt)
- [DashboardStateStore.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt)

### Tests to expand

- [DurableWorkRuntimeTest.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/test/kotlin/ai/neopsyke/agent/durablework/DurableWorkRuntimeTest.kt)
- [WorkItemStateMachineTest.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/test/kotlin/ai/neopsyke/agent/durablework/WorkItemStateMachineTest.kt)
- [WorkItemPersistenceTest.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/test/kotlin/ai/neopsyke/agent/durablework/WorkItemPersistenceTest.kt)
- [EgoDurableWorkIntegrationTest.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/test/kotlin/ai/neopsyke/agent/EgoDurableWorkIntegrationTest.kt)
- [AgentScenarioPackTest.kt](/Users/victor.toral/atomitl/ai/NeoPsyke/src/test/kotlin/ai/neopsyke/eval/AgentScenarioPackTest.kt)

## Implementation Sequence

### Milestone 1: Phase-2 state model and typed wake contract

- design the phase-2 `MonitorState` and typed delivery records
- replace free-form wake metadata with typed wake reasons and activation context
- add schema-versioned persistence and projection upgrades
- keep current operator projections working during the migration

Exit condition:

- existing work items restore cleanly
- typed wakes flow from runtime to Ego without planner regressions
- no phase-1 behavior changes are required to create or resume work items

### Milestone 2: Monitoring memory and dedupe runtime

- persist stable seen-item records and source cursors
- add bounded change ledger support
- add meaningful-change detection and no-change suppression rules
- prevent repeated reporting of the same stable item across cycles

Exit condition:

- recurring monitors can remember prior items across many activations
- duplicate item reporting is suppressed by runtime state, not by prompt-only
  wording

### Milestone 3: Delivery and report-window assembly

- replace coarse digest entries with typed delivery entries
- implement report windows and delta-aware summaries
- persist reasons for notify, defer, or suppress
- expose runtime explanations for quiet periods

Exit condition:

- one work item can accumulate a report window across cycles
- repeated no-change runs do not generate noisy summaries
- operator surfaces can explain why nothing was sent

### Milestone 4: Long-term objective review loop

- add stale-objective review scheduling
- persist review history and review outcomes
- support periodic advancement of broad objectives without forcing a one-shot
  reminder model
- keep plan revisions append-only

Exit condition:

- an open-ended objective can be reviewed and advanced across multiple
  activations with preserved history

### Milestone 5: Id review wake integration

- define the runtime-facing Id review request contract
- let runtime accept, defer, or coalesce review wakes
- surface Id-origin review in projections and dashboard state
- keep review wakes below overdue obligations in scheduling priority

Exit condition:

- Id can request review of existing work items
- Id cannot create new work items autonomously through this path
- runtime scheduling remains bounded and predictable

### Milestone 6: Long-lived storage and archival operations

- introduce event-log rollover or segmented history
- add projection rebuild support across segments
- add retention rules for summarized monitor detail
- validate restart and recovery behavior with long-lived histories

Exit condition:

- monitoring workloads can run for long periods without unbounded local growth
- replayability and operator explanation surfaces remain intact

### Milestone 7: Operator surface and documentation

- extend dashboard and status views with monitoring-specific fields
- add explanations for dedupe, suppression, stale review, and digest windows
- update runtime docs and diagrams

Exit condition:

- the user can tell why a work item is active, quiet, stale, or notified
- monitoring state is inspectable without reading raw event logs

## Testing Plan

### Unit tests

- monitor-state projection upgrades
- seen-item insert/update/dedupe rules
- delivery suppression decisions
- typed wake-reason serialization
- report-window rollover
- review scheduling rules

### Integration tests

- repeated monitor cycles preserve seen-item state across restarts
- apartment-hunt-style search suppresses already reported items
- digest window collects deltas across several cycles before one summary
- stale objective review wake advances an existing work item
- Id review wake coalesces with a pending timer wake
- segmented event logs restore correctly after restart

### Scenario and Freud coverage

Add deterministic scenario coverage for:

- monitor finds same item across three cycles and reports it once
- monitor finds a changed item and reports only the delta
- long-term objective review after quiet period
- Id review wake for an existing work item
- restart during active monitoring window
- archival or segment rollover with successful restoration

Phase-2 signoff should continue using the deterministic Freud gate, plus new
multi-activation scenarios that exercise monitoring memory and delivery
policies over time.

## Risks And Guardrails

### Main risks

- turning monitor memory into an unbounded blob
- pushing dedupe semantics into prompts instead of runtime state
- allowing Id review wakes to starve routine durable work
- overcomplicating delivery into a workflow subsystem
- weakening replay or auditability while introducing archival behavior

### Guardrails

- every new monitor field must have a named owner and bounded retention rule
- every delivery suppression must record a typed reason
- every wake source must remain typed and observable
- every archival change must preserve replay and explanation semantics
- phase-1 tests must remain green while phase-2 state is added

## Documentation Updates Required With The Code Change

When implementation starts, update these in the same patch series:

- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`
- dashboard or status documentation for new operator fields
- durable-work configuration docs
- any prompt or planner docs that refer to durable-work wake metadata

## Recommended First Patch

The safest first phase-2 patch is:

1. expand `WorkItemModels.kt` with typed phase-2 monitor and delivery records
2. add schema-versioned persistence and projection upgrades in the store and
   projection layers
3. introduce typed wake reasons without changing the overall Ego handoff shape
4. keep behavior conservative until monitor dedupe and delivery rules land

That creates the phase-2 data contract first, so the monitoring, delivery, and
Id-review work can build on explicit runtime ownership instead of ad hoc
strings and metadata maps.
