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
- Phase 5: implemented and revalidated on current HEAD
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

### Phase 5
- Scratchpad layering is now more explicit:
  - thread workspaces remain keyed by `rootInputId`
  - transient answer drafts stay inside one active draft sequence and reset when cognition leaves answer-drafting work
- Goal scratchpad creation now happens when queued goal work is actually processed, not at cue ingress time.
- Goal work now passes through the same meta-guidance and pressure-override deliberation path as ordinary planner-driven cognition.
- Terminal goal cycle cleanup now captures a scratchpad digest before workspace destruction so resolved goal context survives as a compact signal.
- Revalidated current HEAD with:
  - `./freud/bin/freud run cognitive-runtime-p5-goals-scratchpad`
  - `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-5-goal-runtime.txt --timeout 120`
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
  - scratchpad draft-sequence isolation
  - goal runtime regressions
  - planner/agent fallback
  - Id lifecycle follow-up behavior
  - scenario-pack regression coverage
- Current HEAD passes:
  - `./freud/bin/freud run cognitive-runtime-p2-opportunities`
  - `./freud/bin/freud run cognitive-runtime-p3-intentions`
  - `./freud/bin/freud run cognitive-runtime-p4-feedback`
  - `./freud/bin/freud run cognitive-runtime-p5-goals-scratchpad`
- Current HEAD passes recorded live evals for:
  - Phase 2 low-llm
  - Phase 3 low-llm
  - Phase 4 low-llm
  - Phase 5 low-llm
- Current HEAD passes:
  - recorded BBH low-llm (`24/24`)
  - `./freud/bin/freud test-freud-replay --lane low-llm`

## Next Actions
1. Start the dedicated Phase 6 completion pass around opportunity-time policy shaping and control-plane separation.
2. Verify that channel, principal, scope, and effect-class constraints materially change available intentions, commit modes, and planner-visible actions before proposal time.
3. Keep the `boolean_01` follow-up note open until the fully migrated schema path is rechecked after later planner/control-plane work.

## Hand-Off Summary
- Current validated checkpoint:
  - Phase 0 through Phase 5 are implemented and revalidated on current HEAD.
  - Latest successful deterministic Phase 5 run:
    - [.neopsyke/runs/freud/20260331T144722Z-cognitive-runtime-p5-goals-scratchpad-1434832118](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T144722Z-cognitive-runtime-p5-goals-scratchpad-1434832118)
  - Latest successful Phase 5 low-llm eval:
    - [.neopsyke/runs/freud/20260331T144805Z-live-eval-2069699373](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T144805Z-live-eval-2069699373)
- Phase 5 code changes on this checkpoint:
  - goal scratchpads are created when queued goal work is actually processed, not when the cue is ingested
  - goal work now uses the same meta-guidance and pressure-override path as ordinary planner-driven cognition
  - terminal goal cleanup captures a scratchpad digest before workspace destruction
  - scratchpad final-pass drafts now follow one active answer-drafting sequence per thread and reset when cognition leaves `resolution_draft` / `contact_user` work
- Validation completed on this checkpoint:
  - `./gradlew --no-daemon test --tests 'ai.neopsyke.agent.ScratchpadStoreTest' --tests 'ai.neopsyke.agent.EgoAgentTest'`
  - `./gradlew --no-daemon test --tests 'ai.neopsyke.agent.EgoGoalIntegrationTest' --tests 'ai.neopsyke.agent.goal.GoalManagerTest' --tests 'ai.neopsyke.agent.ScratchpadStoreTest'`
  - `./freud/bin/freud run cognitive-runtime-p5-goals-scratchpad`
  - `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-5-goal-runtime.txt --timeout 120`
  - `./freud/bin/freud bbh --live --lane low-llm --record`
  - `./freud/bin/freud test-freud-replay --lane low-llm`
- Recommended next session start:
  - inspect `docs/COGNITIVE_RUNTIME_COMPLETION_PLAN.md`
  - inspect this progress ledger
  - begin Phase 6 in the runtime policy/opportunity shaper path
- Keep excluded from commits unless explicitly requested:
  - [docs/SECURITY_STRATEGY_SPEC.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/SECURITY_STRATEGY_SPEC.md)
  - [docs/TEMP_COGNITIVE_ARCHITECTURE_NOTE.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/TEMP_COGNITIVE_ARCHITECTURE_NOTE.md)
