# Cognitive Runtime Completion Progress

Last updated: 2026-03-31

## Scope
This document tracks implementation progress against [COGNITIVE_RUNTIME_COMPLETION_PLAN.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/COGNITIVE_RUNTIME_COMPLETION_PLAN.md).

`docs/COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md` remains the acceptance source of truth.

## Current Status
- Phase 0: complete
- Phase 1: complete and validated on current HEAD
- Phase 2: complete and validated on current HEAD
- Phase 3: complete and validated on current HEAD
- Phase 4: complete and validated on current HEAD
- Phase 5: complete and validated on current HEAD
- Phase 6: complete and validated on current HEAD
- Phase 7: complete and validated on current HEAD

## Acceptance Cross-Check
- The prior ledger overstated Phase 2 through Phase 5 completion relative to the frozen acceptance criteria in [COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md).
- The remaining code gaps identified in the prior assessment are now closed on current HEAD:
  - threads retain live and terminal continuity state for ordinary, goal, feedback, and Id-rooted work
  - opportunities now carry shaped `availableActions`, `dispatchableActions`, action definitions, allowed intentions, allowed commit modes, and policy metadata at enqueue time
  - planner/runtime intention semantics now cover `OBSERVE`, `PREPARE`, `STAGE`, `REQUEST_AUTHORIZATION`, `COMMIT`, and `DEFER`
  - secure action progression records `STAGE`, `REQUEST_AUTHORIZATION`, and `COMMIT` onto thread/intention inspection state instead of leaving them as telemetry-only labels
  - normal continuation work now runs through `DEFER` intentions; `PendingThought` remains only as an internal reconstruction/debug helper, not the primary scheduler category
  - dashboard/API thread snapshots now preserve latest opportunity/intention plus last blocked and denied reason codes
- Validation on current HEAD is now complete:
  - deterministic `signoff-gate`
  - recorded low-llm cognitive-runtime live eval pack
  - recorded BBH low-llm
- The architecture migration targeted by the completion plan is now closed on current HEAD.

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
- Planner/runtime progression now supports explicit `STAGE`, `REQUEST_AUTHORIZATION`, and `COMMIT`.
- Normal deferred continuation now attends `DEFER` intentions directly instead of using a standalone thought scheduler path.
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

### Phase 6
- Opportunity contracts are now shaped before planner choice, not only during planner-context assembly:
  - enqueued opportunities now carry `availableActions`, `dispatchableActions`, and policy-shaped action definitions
  - allowed intentions and commit modes now shrink when the current action surface is observe-only or lacks autonomous/stageable work
  - `opportunity_enqueued` telemetry now exposes the shaped planner-visible action surface and opportunity policy metadata for inspection
- Shaped opportunities are now re-recorded onto thread state so inspection surfaces reflect the exact contract the planner saw.
- Thread snapshots now preserve:
  - last blocked reason / reason code
  - last denied reason / reason code
  - latest intention progression through `STAGE`, `REQUEST_AUTHORIZATION`, and `COMMIT`
- Deterministic coverage added for:
  - observe-only opportunities dropping `PREPARE` and stageable commit affordances
  - goal/control-plane opportunities preserving commit progression when policy actually allows it
  - opportunity telemetry proving blocked control-plane actions are absent before planner choice
- Validation completed on current HEAD:
  - `./gradlew --no-daemon test --tests 'ai.neopsyke.agent.ego.CognitivePolicyShaperTest' --tests 'ai.neopsyke.agent.EgoAsyncFeedbackIntegrationTest'`

### Phase 7
- Closed the final convergence gap where user-origin repeated `DEFER` loops could exhaust `maxThoughtPasses` and terminate silently without emitting a terminal answer.
- `DecisionDispatcher` now defaults non-Id defer chains to `allowFallbackExplanation=true`, so max-pass exhaustion resolves into a fallback `contact_user` action instead of a silent stop.
- Fixed direct Freud session replay so `./freud/bin/freud eval --live --session-replay <run-dir>` auto-detects replay cache from either `session/llm-cache.jsonl` or `artifacts/llm-cache.jsonl`.
- Added deterministic coverage for:
  - direct user-origin defer loops resolving into fallback explanation on max-pass exhaustion
  - Freud live-eval replay fallback to `artifacts/llm-cache.jsonl`
- Validation completed on current HEAD:
  - `./gradlew --no-daemon test --tests 'ai.neopsyke.agent.EgoAgentTest'`
  - `cd freud && GOCACHE=/tmp/go-build-cache go test ./internal/... -run TestLiveEvalSessionReplayFallsBackToArtifactsCache -v`
  - replay-debug of the first failing low-llm recording now passes on current HEAD:
    - `.neopsyke/runs/freud/20260331T182316Z-live-eval-2466479304` recorded failing baseline
    - `.neopsyke/runs/freud/20260331T184501Z-live-eval-3033449536` replayed fixed path (`6/6` cached planner calls, `0` real calls, terminal answer emitted)

## Deferred Follow-Up
- Monitor future live-lane regressions for post-answer `long_term_memory_assessment` cache divergence during replay. Current fixed replay kept `6/6` planner calls cached with `0` real calls and one harmless post-answer divergence in the assessment path.

## Validation Snapshot
- Deterministic current-head validation passed:
  - `./freud/bin/freud run signoff-gate`
    - run dir: [.neopsyke/runs/freud/20260331T184641Z-signoff-gate-3867945903](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T184641Z-signoff-gate-3867945903)
- Recorded low-llm cognitive-runtime live evals passed on current HEAD:
  - Phase 1: [.neopsyke/runs/freud/20260331T184753Z-live-eval-3259913020](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T184753Z-live-eval-3259913020)
  - Phase 2: [.neopsyke/runs/freud/20260331T185632Z-live-eval-3706841166](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185632Z-live-eval-3706841166)
  - Phase 3: [.neopsyke/runs/freud/20260331T185647Z-live-eval-2841022618](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185647Z-live-eval-2841022618)
  - Phase 4: [.neopsyke/runs/freud/20260331T185659Z-live-eval-3771191881](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185659Z-live-eval-3771191881)
  - Phase 5: [.neopsyke/runs/freud/20260331T185710Z-live-eval-191545522](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185710Z-live-eval-191545522)
  - Phase 6: [.neopsyke/runs/freud/20260331T185722Z-live-eval-807229177](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185722Z-live-eval-807229177)
  - Phase 7: [.neopsyke/runs/freud/20260331T185732Z-live-eval-594774908](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185732Z-live-eval-594774908)
- Recorded BBH low-llm passed on current HEAD:
  - `24/24`
  - run dir: [.neopsyke/runs/freud/20260331T185753Z-bbh-low-llm-2999209459](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185753Z-bbh-low-llm-2999209459)
- Validation note:
  - the first fresh Phase 1 live-eval failure on current HEAD was a sandbox-network transport issue (`DNS lookup failed for api.groq.com`) rather than a runtime regression
  - rerunning the same command outside the sandbox passed immediately
  - the earlier phase-2 timeout was a real runtime convergence bug plus a direct session-replay cache-path bug, both fixed on current HEAD

## Next Actions
1. Keep the replay-harness fallback (`session/` or `artifacts/` cache path) under regression coverage as Freud live-eval changes continue.
2. If future live replay debugging is needed, start from the preserved failing phase-2 recording and the fixed replay pass documented above.

## Hand-Off Summary
- Current code checkpoint:
  - Phase 0 remains complete.
  - Phases 1 through 7 are implemented and validated on current HEAD.
  - Latest successful deterministic checkpoint:
    - [.neopsyke/runs/freud/20260331T184641Z-signoff-gate-3867945903](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T184641Z-signoff-gate-3867945903)
  - Latest successful low-llm BBH checkpoint:
    - [.neopsyke/runs/freud/20260331T185753Z-bbh-low-llm-2999209459](/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T185753Z-bbh-low-llm-2999209459) (`24/24`)
- Current convergence changes on this checkpoint:
  - enqueued opportunities now carry policy-shaped `availableActions`, `dispatchableActions`, and planner action definitions
  - observe-only opportunities drop stageable intent/commit affordances before planner choice
  - shaped opportunities are written back onto thread state so inspection surfaces see the actual planner contract
  - planner/runtime intention semantics now include explicit `STAGE`, `REQUEST_AUTHORIZATION`, and `COMMIT`
  - normal continuation work now attends `DEFER` intentions directly rather than relying on a standalone thought scheduler lane
  - thread snapshots now preserve blocked/denied reason codes and the latest intention progression state
  - non-Id repeated defer chains now terminate with fallback explanation instead of silent max-pass exhaustion
  - direct Freud session replay now finds LLM cache in either `session/` or `artifacts/`
- Validation completed on this checkpoint:
  - `./freud/bin/freud run signoff-gate`
  - recorded low-llm cognitive-runtime evals (phases 1 through 7)
  - recorded BBH low-llm (`24/24`)
- Recommended next session start:
  - inspect `docs/COGNITIVE_RUNTIME_COMPLETION_PLAN.md`
  - inspect this progress ledger
  - if replay debugging is needed, reuse the preserved phase-2 recording first before making a new live recording
- Keep excluded from commits unless explicitly requested:
  - [docs/SECURITY_STRATEGY_SPEC.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/SECURITY_STRATEGY_SPEC.md)
  - [docs/TEMP_COGNITIVE_ARCHITECTURE_NOTE.md](/Users/victor.toral/atomitl/ai/NeoPsyke/docs/TEMP_COGNITIVE_ARCHITECTURE_NOTE.md)
