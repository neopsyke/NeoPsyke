# Continuation Refactor Implementation Plan

**Status:** Complete
**Related docs:** `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md`, `docs/specs/GOAL_WORK_REDESIGN.md`
**Date:** 2026-04-10

## Goal

Replace the current generic `DeferredIntention` / `EnqueueThought` mechanism
with one typed continuation mechanism that:

- removes plain "think more later" loops without new evidence
- keeps `ActionFeedback` as the execute-then-assess path
- preserves valid resumable workflow behavior
- does not add any new planner pipelines beyond the current architecture
- improves clarity by making continuation intent explicit in the type system

## End-State Architecture

By the end of this refactor, the runtime should have these properties:

1. `DeferredIntention` as a generic concept no longer exists.
2. The replacement generic type is `Continuation`.
3. Continuations use one shared queue/scheduler mechanism with typed variants:
   - `PlanStepContinuation`
   - `RetryAlternative`
   - `ConvergeNow`
4. `ActionFeedback` is the only execute-then-assess entry point after an action runs.
5. Planner prompts no longer encourage plain "enqueue thought for further processing"
   without a typed continuation reason.
6. The existing continuation-capable planner lane remains a single lane, but it is
   narrowed to typed continuation handling instead of generic defer-for-more-processing loops.
7. Plan-step progression, denial recovery, convergence recovery, and wait/resume
   semantics are explicit in typed continuation metadata rather than hidden in free text
   plus nullable fields.

## Naming Policy

The implementation must preserve a clear distinction between generic cognitive
language and concrete runtime types.

### Generic Umbrella Term

- `thought`

`thought` remains valid only as a generic informal/runtime umbrella term for
internal reasoning work in the Ego.

It may still appear in:

- high-level comments
- architecture prose
- memory or analytics language when referring to internal cognition generically

It must not remain as the concrete runtime type name for queued resumable work.

### Concrete Runtime Terms

- `Continuation` = the concrete queued/resumable workflow item type
- `ActionFeedback` = the concrete post-action reassessment trigger
- `GoalWork` = the concrete goal-runtime work trigger
- `Input` / `Impulse` = the other concrete trigger families

### Naming Rule

If a symbol represents a concrete runtime envelope, queue item, trigger, planner
lane, or continuation variant, it must use the concrete runtime term and not the
generic `thought` umbrella.

## Non-Goals

This refactor does not:

- add any new planner lanes or subplanners
- add parallel legacy compatibility paths
- keep `DeferredIntention` as an alias or shim
- redesign the goal runtime pipeline beyond replacing its use of generic deferred
  continuations
- remove planning or replanning entirely

## Decision Summary

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Replace `DeferredIntention` with `Continuation` | "Continuation" describes resumable workflow state without implying free-form inner monologue |
| D2 | Use one continuation mechanism with typed variants | Avoids four separate pipelines while making semantics explicit |
| D3 | Keep `ActionFeedback` as the only execute-then-assess path | Prevents overlap between feedback handling and generic continuation |
| D4 | Remove generic planner-directed "think more later" behavior | Evidence-free deferred processing is the low-value part of the current design |
| D5 | Reuse the current continuation-capable planner slot | Meets the requirement to avoid adding new planner pipelines |
| D6 | Prefer clean breaks over compatibility shims | Matches repository policy for prototype-stage architectural cleanup |

## Current Problems To Remove

The current design hides several different behaviors inside the same generic
deferred abstraction:

- plan-step execution via `planContext`
- invalid action recovery
- denied action recovery
- noop recovery
- convergence after plan suppression
- generic "process this later" text loops

This makes the type too broad and pushes workflow meaning into nullable fields
and prompt prose instead of explicit runtime structure.

It also conflates:

- `thought` as a generic internal cognition term
- the concrete queued runtime item used for resumption and workflow progression

That ambiguity must be removed.

## Target Model

### Generic Type

- `Continuation`

### Queue Envelope

- `QueuedContinuation`

### Trigger

- `EgoTrigger.Continuation`

### Planner Lane

Reuse the current deferred/continuation-capable lane and rename it to reflect the
new semantics, for example:

- `ContinuationPlanner`

No additional planner lanes should be introduced.

### Continuation Variants

#### `PlanStepContinuation`

Used for queued execution of explicit plan steps.

Carries:

- `planId`
- `planGoal`
- `stepIndex`
- `totalSteps`
- `stepDescription`
- inherited grounding and origin metadata

#### `RetryAlternative`

Used when the next action must be materially different because a prior path was
invalid, denied, redundant, or otherwise blocked.

Carries:

- denied action metadata
- denial reason and reason code
- origin action metadata when needed
- retry instruction or retry mode

#### `ConvergeNow`

Used when the system should stop expanding work and converge toward completion,
best-effort answer, or bounded fallback.

Carries:

- convergence reason
- source event, such as suppressed plan or bounded recovery
- fallback eligibility metadata

## Workstreams

### 1. Inventory Existing Deferred Usage

Audit every producer of `EgoDecision.EnqueueThought`, deferred scheduling, and
`DeferredIntention`-specific metadata.

Classify each site as one of:

- remove
- `PlanStepContinuation`
- `RetryAlternative`
- `ConvergeNow`

This inventory is required before changing runtime models.

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionDispatcher.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/FallbackHandler.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/AttentionScheduler.kt`
- planner lanes that currently emit `EgoDecision.EnqueueThought`

### 2. Replace Deferred Runtime Models

Refactor runtime queue models so the current deferred state is replaced by
typed continuation state.

Core rules:

- do not create a second queueing subsystem
- do not introduce compatibility wrappers that preserve the old abstraction
- use a sealed continuation payload instead of nullable-field accumulation

Likely file:

- `src/main/kotlin/ai/neopsyke/agent/model/QueueModels.kt`

Expected changes:

- remove `PendingThought`
- remove deferred-specific fields from `QueuedIntention`
- introduce `Continuation` sealed type
- introduce `QueuedContinuation`
- update queue snapshots and naming where needed
- preserve `thought` only where it is genuinely generic and non-type-bearing

### 3. Replace Trigger And Planner Wiring

Refactor the planner entry point and processing path:

- replace `EgoTrigger.DeferredIntention` with `EgoTrigger.Continuation`
- replace `processDeferredIntention(...)` with `processContinuation(...)`
- keep a single continuation lane in the planner hierarchy
- rename the deferred lane and its typed decision model to continuation language

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/model/CognitionModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/HierarchicalEgoPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/DeferredStepPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/model/StepDecision.kt`

Expected renames:

- `EgoTrigger.DeferredIntention` -> `EgoTrigger.Continuation`
- `DeferredStepPlanner` -> `ContinuationPlanner`
- `processDeferredIntention(...)` -> `processContinuation(...)`
- `enqueueDeferredIntention(...)` -> `enqueueContinuation(...)`

### 4. Remove Generic Enqueue-Thought Planner Semantics

Planner prompts and outputs should no longer support generic evidence-free
"process this later" behavior.

Implementation rules:

- remove prompt instructions that encourage generic defer-for-more-processing
- keep only typed continuation outcomes that correspond to valid runtime reasons
- ensure the continuation lane prompt is about explicit continuation handling,
  not open-ended further thinking
- avoid replacing the old prompt text with another synonym for generic "think more"

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/DeferredStepPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/FeedbackPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/GoalWorkPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/prompt/SharedPromptSections.kt`
- any L2 planner that currently returns `EgoDecision.EnqueueThought` for generic
  further processing

### 5. Make `ActionFeedback` The Only Execute-Then-Assess Path

Refactor the runtime so action completion is always reassessed through feedback,
not by queuing generic continuations that act as shadow feedback loops.

Rules:

- if an action ran and produced an outcome, use `ActionFeedback`
- typed continuations are for non-feedback resumptions only
- do not preserve dual semantics where both feedback and continuation cover the
  same execute-then-assess case

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionDispatcher.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/FeedbackPlanner.kt`

### 6. Rewire Continuation Production Sites

Map the remaining valid continuation sites to typed variants.

Expected mappings:

- plan expansion -> `PlanStepContinuation`
- invalid action retry -> `RetryAlternative`
- denied action retry -> `RetryAlternative`
- noop recovery -> `RetryAlternative` or remove, depending on case
- plan suppression recovery -> `ConvergeNow`

The refactor should aggressively delete continuation producers that only exist to
keep the model "thinking" without new state.

### 7. Update Scheduler And Queue Semantics

The scheduler should remain a single orchestration mechanism, but it must operate
on typed continuations instead of generic deferred payloads.

Rules:

- no new scheduler pipeline
- preserve current queue prioritization as much as possible
- update queue counts, telemetry, and saturation reporting to use continuation
  language instead of deferred-thought language
- retain `thought` wording only where it still means generic internal cognition
  rather than a concrete runtime item

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/AttentionScheduler.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/EgoTelemetry.kt`
- `src/main/kotlin/ai/neopsyke/agent/model/QueueModels.kt`

### 8. Update Tests

Replace current deferred expectations with typed continuation expectations.

Required coverage:

- plan-step continuation enqueue and processing
- retry alternative after invalid or denied action
- converge-now recovery after plan suppression
- wait-resume processing
- feedback remains the execute-then-assess path
- no remaining planner path emits generic evidence-free deferred processing

Likely files:

- planner acceptance tests
- scheduler and dispatcher tests
- goal integration tests
- feedback and fallback tests

### 9. Update Runtime Logic Docs

Update both living runtime docs in the same patch:

- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`

The docs must show:

- `Continuation` replacing `DeferredIntention`
- one continuation pipeline with typed variants
- `ActionFeedback` owning execute-then-assess
- explicit continuation categories and when they are used
- `thought` retained only as a generic umbrella term, not as the concrete queued
  continuation concept

## Acceptance Criteria

The implementation is complete only when all of the following are true:

1. Repo-wide searches confirm that the generic `DeferredIntention` concept has
   been removed from production code and replaced with `Continuation`.
2. There is one shared continuation mechanism, not four independent pipelines.
3. The only supported continuation variants are:
   - `PlanStepContinuation`
   - `RetryAlternative`
   - `ConvergeNow`
4. Planner prompts no longer instruct the model to "enqueue thought" or defer for
   generic further processing without a typed reason.
5. No planner lane preserves a plain free-form evidence-free defer loop as an
   ordinary outcome.
6. `ActionFeedback` is the sole execute-then-assess path after action execution.
7. Plan steps are represented as typed continuations rather than generic deferred
   text with nullable `planContext` semantics.
8. Retry and denial recovery use typed continuation metadata rather than generic
   deferred payload plus nullable denial fields.
9. Queue/scheduler telemetry and naming are updated from deferred language
   to continuation language where applicable.
10. `AGENT_LOGIC_SUMMARY.md` and `AGENT_LOGIC_DIAGRAM.md` accurately reflect the
    new continuation model.
11. Targeted tests covering all continuation variants pass.
12. The required deterministic validation gate passes:
    - `./freud/bin/freud run signoff-gate`
13. `thought` remains only as a generic umbrella term for internal reasoning work
    and is no longer used as the concrete runtime type name for resumable queued work.
14. The continuation-capable planner slot is still a single planner lane after the
    refactor; no new planner lanes or subplanners were added.

## Completion Checklist

- [x] Deferred inventory complete and classified
- [x] Runtime models switched from deferred thought to continuation
- [x] Trigger/planner wiring switched to continuation terminology
- [x] Generic evidence-free defer prompt behavior removed
- [x] `ActionFeedback` confirmed as execute-then-assess owner
- [x] Continuation producers rewired to the four typed variants
- [x] Scheduler and telemetry updated
- [x] Tests updated and passing
- [x] Thought terminology reviewed so concrete runtime items use `Continuation`
  while generic cognition language remains unambiguous
- [x] `AGENT_LOGIC_SUMMARY.md` updated
- [x] `AGENT_LOGIC_DIAGRAM.md` updated
- [x] `./freud/bin/freud run signoff-gate` passing
