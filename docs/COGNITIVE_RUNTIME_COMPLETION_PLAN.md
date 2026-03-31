# Cognitive Runtime Completion Plan

## Summary
Implement the remaining architecture gaps as a clean-break refactor of the runtime control model. This run treats `docs/COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md` as the only acceptance source of truth and treats prior implementation ledgers as historical notes only. A phase may close only when code, deterministic tests, live evals, replay validation, BBH low-llm, and living docs all agree with the frozen acceptance criteria.

## Phase Plan
### Phase 0: Harness and acceptance-gap expansion
- Add deterministic tests for the missed seams:
  - ordinary non-goal thread `WAITING/BLOCKED/RESOLVED/FAILED` lifecycle
  - thread retention and terminal inspection after normal completion
  - explicit intention-origin semantics instead of dispatcher-derived semantics
  - invalid opportunity-to-intention transitions rejected by runtime, not only by prompting
  - non-goal async feedback re-entry and resume through the same thread/opportunity path
  - dashboard/API inspection of live and terminal thread state
- Add scenario-pack coverage for:
  - observe -> wait -> resume -> commit on a normal user root
  - prohibited moves absent before planner choice
  - deferred intention continuity across retries and feedback
- Extend the cognitive-runtime live eval pack only if any acceptance gap lacks a representative eval input.
- Extend Freud phase gates so every later phase has an exact deterministic gate matching its scope.

### Phase 1: Thread lifecycle ownership
- Refactor `CognitiveThreadStore` into the authoritative live continuity service for every root type, not only goal roots.
- Replace normal-root cleanup-on-success with terminal thread transitions and bounded terminal retention.
- Put wait metadata, last denial/block reason, resume metadata, latest percept/opportunity/intention, and terminal summary on thread state.
- Make `DeliberationEngine`, scratchpad cleanup, and root cleanup consume thread lifecycle rather than deleting thread state opportunistically.

### Phase 2: Opportunity-driven ingress and enforcement
- Introduce a unified post-sensory ingress coordinator that appraises/binds stimuli to threads and emits opportunities for all cognitive arrivals.
- Remove remaining source-specific orchestration branches from the normal runtime path after sensory classification.
- Make opportunities executable constraints, not just planner context:
  - allowed intention kinds enforced at runtime
  - allowed commit modes enforced at runtime
  - prohibited first-order moves absent before planner choice

### Phase 3: Native intention progression
- Replace planner action proposals with planner-formed explicit intentions.
- Change planner schema/output and runtime models so the planner emits:
  - `DEFER`
  - `OBSERVE`
  - `PREPARE`
  - optional commit preference where admissible
- Remove dispatcher heuristics that infer intention kind from effect class.
- Keep `STAGE`, `REQUEST_AUTHORIZATION`, and `COMMIT` as progression states on the same intention lineage, persisted and inspectable end to end.
- Remove `ALLOW_PREPARE` and any other dormant lifecycle semantics not needed by the final architecture.

### Phase 4: Uniform feedback and async re-entry
- Route all side-effect outcomes, including waiting, timeout, failure, and completion, back into cognition as typed feedback stimuli.
- Update ordinary roots to move to `WAITING` on async wait and resume from feedback-driven opportunity generation.
- Remove any executor-side direct continuation queueing or direct deliberation mutation that bypasses sensory re-entry.

### Phase 5: Goal runtime and scratchpad completion
- Fold goal wake/resume/block/wait flows fully into thread continuity and generic opportunity/intention progression.
- Remove the dedicated goal-work orchestration regime from the normal path.
- Keep scratchpad layering explicit:
  - thread-scoped working context
  - intention-scoped drafts
- Ensure suspension/resumption preserves thread context without polluting durable thread state with one-attempt draft material.

### Phase 6: Layered policy and control-plane completion
- Promote early policy shaping into a hard runtime contract at opportunity construction time.
- Make channel, principal, scope, and effect-class differences materially change:
  - available opportunities
  - allowed intention kinds
  - allowed commit modes
  - planner-visible action surface
- Preserve strict separation between runtime/operator control and the cognitive stimulus plane.

### Phase 7: Legacy removal, inspection, and convergence
- Remove remaining normal-path dependence on `PendingThought` as a scheduler concept.
- Keep any deferred continuation helper internal-only and non-schedulable as a primary runtime category.
- Add first-class thread inspection service and dashboard/API surfaces for live and terminal threads, including why a move was available, blocked, staged, denied, or executed.
- Update all living docs to match the actual final runtime shape:
  - `AGENT_LOGIC_SUMMARY.md`
  - `AGENT_LOGIC_DIAGRAM.md`
  - `docs/security.md`
  - `docs/evaluation.md`

## Validation and Exit Criteria
- Deterministic phase gates must use the named Freud runs:
  - `cognitive-runtime-p0-tests`
  - `cognitive-runtime-p1-foundation`
  - `cognitive-runtime-p2-opportunities`
  - `cognitive-runtime-p3-intentions`
  - `cognitive-runtime-p4-feedback`
  - `cognitive-runtime-p5-goals-scratchpad`
  - `cognitive-runtime-p6-policy-control`
  - `cognitive-runtime-p7-convergence`
- After every phase:
  - run the phase-specific Freud deterministic gate
  - run the phase’s curated cognitive-runtime low-llm eval with `--record`
  - run `./freud/bin/freud bbh --live --lane low-llm --record`
  - require BBH low-llm pass rate `>= 90%`
  - update the relevant living docs
- Replay-debug loop on every live failure:
  1. preserve the failing recorded run
  2. after each code change, replay with `--session-replay`
  3. also use `--cache-replay` when isolating LLM determinism issues
  4. continue until replay passes and the underlying bug is fixed
  5. rerun the recorded live command
- Final completion requires:
  - `./freud/bin/freud run signoff-gate`
  - the full cognitive-runtime live eval pack recorded green
  - BBH low-llm recorded green at `>= 90%`
  - final acceptance cross-check against the frozen architecture-status document only

## Important Constraints
- Breaking refactors are preferred over compatibility layers.
- Do not preserve legacy queue-era naming or behavior just to minimize churn.
- Do not count telemetry-only stage labels as acceptance for runtime stages; acceptance requires the stages to be real runtime objects with behavior and inspection surfaces.
- Do not let docs or ledgers declare completion unless the matching acceptance criteria are directly demonstrable in code and tests.

## Assumptions and Defaults
- Phase 0 is mandatory because the previous run failed by closing phases without enough seam-specific acceptance coverage.
- The prior implementation-plan ledger is not trusted as evidence of completion; only its phase structure and validation workflow are reused.
- BBH remains strict; no changes to BBH cases, normalizers, or expectations to make architecture work appear complete.
