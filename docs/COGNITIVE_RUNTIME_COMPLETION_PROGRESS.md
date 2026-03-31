# Cognitive Runtime Completion Progress

Last updated: 2026-03-31

## Scope
This document tracks implementation progress against [COGNITIVE_RUNTIME_COMPLETION_PLAN.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/COGNITIVE_RUNTIME_COMPLETION_PLAN.md).

`docs/COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md` remains the acceptance source of truth.

## Current Status
- Phase 0: complete
- Phase 1: substantially implemented
- Phase 2: implemented and revalidated on current HEAD
- Phase 3: implemented and revalidated on current HEAD
- Phase 4: implemented and revalidated on current HEAD
- Phase 5: started, not complete
- Phase 6: not started as a dedicated completion pass
- Phase 7: not started as a dedicated completion pass

## Completed Work
### Phase 0
- Added named Freud phase gates for the cognitive-runtime rollout.
- Hardened Freud replay/session validation behavior.
- Added deterministic coverage for thread retention and dashboard inspection seams.
- Wrote the execution plan to [COGNITIVE_RUNTIME_COMPLETION_PLAN.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/COGNITIVE_RUNTIME_COMPLETION_PLAN.md).

### Phase 1
- `CognitiveThreadStore` now retains live and terminal thread state for ordinary roots.
- Ordinary feedback waits mark threads `WAITING`.
- Ordinary completion/failure now preserves terminal thread snapshots instead of dropping them immediately.
- Dashboard thread inspection endpoints were added for live and retained terminal state.

### Phase 2
- Added `StimulusIngressCoordinator` to unify post-sensory routing for input, feedback, impulse, and goal-runtime cues.
- Removed remaining top-level hardcoded routing branches from the normal cognitive ingress path.
- Enforced runtime action-surface restrictions against the active opportunity contract.
- Revalidated current HEAD with:
  - `./freud/bin/freud run cognitive-runtime-p2-opportunities`
  - `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-2-opportunity-shaping.txt --timeout 120`

### Phase 3
- Replaced planner action proposals with planner-native `FormIntention`.
- Updated planner schema from `thought|action|plan|noop` to `defer|intend|plan|noop`.
- Removed dispatcher-side heuristic intention-kind inference.
- Added runtime validation for `intention_kind`, `action_type`, and `commit_mode_preference`.
- Revalidated current HEAD with:
  - `./freud/bin/freud run cognitive-runtime-p3-intentions`
  - `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-3-intentions.txt --timeout 120`
  - `./freud/bin/freud bbh --live --lane low-llm --record` (`24/24`)

### Phase 4
- Async waits no longer auto-generate an executor-side planner continuation.
- Feedback now re-enters as a real `EgoTrigger.ActionFeedback` path instead of a synthetic follow-up prompt.
- The executor no longer tags feedback with a continuation verdict; continuation is regenerated only after cognitive re-entry.
- Successful non-follow-up feedback now resolves the owning thread instead of leaving it spuriously active.
- Revalidated current HEAD with:
  - `./freud/bin/freud run cognitive-runtime-p4-feedback`
  - `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-4-feedback-reentry.txt --timeout 120`
  - `./freud/bin/freud bbh --live --lane low-llm --record` (`24/24`)
  - `./freud/bin/freud test-freud-replay --lane low-llm`

## Deferred Follow-Up
- After the schema migration is fully complete across all planner request paths, rerun `boolean_01` and the full BBH low-llm lane to confirm whether the issue disappears without compatibility layers.
- If `boolean_01` still fails after the fully migrated schema path is in place, treat it as a separate convergence bug:
  - investigate exact-match terminal-answer routing
  - verify that direct-answer cases cannot loop through repeated `noop -> defer -> noop`
  - fix the convergence path architecturally, not with legacy-schema support

## Validation Snapshot
- Current HEAD passes targeted Kotlin suites covering:
  - async feedback
  - planner/agent fallback
  - Id lifecycle follow-up behavior
  - scenario-pack regression coverage
- Current HEAD passes:
  - `./freud/bin/freud run cognitive-runtime-p2-opportunities`
  - `./freud/bin/freud run cognitive-runtime-p3-intentions`
  - `./freud/bin/freud run cognitive-runtime-p4-feedback`
- Current HEAD passes recorded live evals for:
  - Phase 2 low-llm
  - Phase 3 low-llm
  - Phase 4 low-llm
- Current HEAD passes:
  - recorded BBH low-llm (`24/24`)
  - `./freud/bin/freud test-freud-replay --lane low-llm`

## Next Actions
1. Trace the remaining dedicated goal-work orchestration path and identify where it still diverges from generic thread/opportunity progression.
2. Fold goal wake/resume/block flows further into the generic feedback/thread lifecycle.
3. Separate thread-scoped context from intention-scoped drafts more cleanly in scratchpad handling.
4. Run `./freud/bin/freud run cognitive-runtime-p5-goals-scratchpad` once the goal-runtime refactor is ready.
5. Run the recorded Phase 5 low-llm eval, recorded BBH low-llm, and replay validation on the Phase 5 boundary.
