# Cognitive Runtime Architecture Implementation Plan

This document is the mutable execution ledger for the cognitive runtime architecture migration.

- Frozen acceptance reference: [COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md](./COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md)
- Rule: do not edit the frozen status document during implementation
- Rule: update this document as phases close, validations run, and acceptance items are satisfied

## Execution Rules

- The architecture is implemented in stable phases that replace legacy orchestration rather than preserving it.
- Primary deterministic gate per phase: `./freud/bin/freud run <phase-id>`
- Mandatory live validation after each deterministic pass:
  - recorded curated low-llm eval suite
  - recorded BBH low-llm smoke suite
- Replay is the default live-debug workflow:
  - curated evals use `--cache-replay` and `--session-replay`
  - BBH uses recorded suite replay support added in Phase 0
- Do not change BBH tests, normalizers, or expectations to make tests pass.
- Update relevant living docs at the end of each finished phase when runtime truth changes:
  - `AGENT_LOGIC_SUMMARY.md`
  - `AGENT_LOGIC_DIAGRAM.md`
  - `docs/security.md`
  - `docs/evaluation.md`
  - other affected docs
- Final overall validation still requires `./freud/bin/freud run signoff-gate`

## Phase Plan

### Phase 0. Validation Harness Expansion

Scope:
- add architecture-defining deterministic tests for the intended seams
- add low-risk parity scenarios
- add curated low-llm recorded eval suites for later phases
- extend Freud/BBH tooling so BBH low-llm runs are recordable and replay-debuggable with stored artifacts and divergence reporting

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p0-tests`

Mandatory live validation after deterministic pass:
- recorded curated low-llm eval suite
- recorded BBH low-llm smoke suite

### Phase 1. Percept and Thread Foundation

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p1-foundation`

### Phase 2. Opportunity-Driven Scheduling

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p2-opportunities`

### Phase 3. Intention Progression and Thought Removal

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p3-intentions`

### Phase 4. Unified Feedback Re-entry

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p4-feedback`

### Phase 5. Goal Runtime and Scratchpad Boundaries

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p5-goals-scratchpad`

### Phase 6. Layered Policy and Control Plane Completion

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p6-policy-control`

### Phase 7. Legacy Removal, Observability, and Final Convergence

Deterministic gate:
- `./freud/bin/freud run cognitive-runtime-p7-convergence`

Final signoff:
- `./freud/bin/freud run signoff-gate`

## Acceptance Coverage Map

- `Stimulus`: Phase 1, finalized for control-plane separation in Phase 6
- `Percept`: Phase 1
- `CognitiveThread`: Phase 1 core ownership, Phase 5 full continuity and goal integration
- `Opportunity`: Phase 2, completed for full policy shaping in Phase 6
- `Intention`: Phase 3
- secure action lifecycle: preserved from start, integrated with intention semantics in Phase 3, policy-complete in Phase 6
- uniform feedback: Phase 4
- goal-runtime integration: Phase 5
- scratchpad boundaries: Phase 5
- security distribution: Phase 6
- observability and auditability: Phase 7
- thought-model alignment: Phase 3
- final architecture completion test: Phase 7 plus final `signoff-gate`

## Validation Ledger

### Phase 0

Status:
- In progress

Scope notes:
- first deliverable is the harness and validation substrate needed to make the architecture migration safe
- BBH replay support is part of this phase because the existing CLI does not expose BBH recording or replay flags

Deterministic runs:
- `cd freud && GOCACHE=/tmp/go-build-cache go test ./cli ./internal/...`
  - result: pass
- `cd freud && GOCACHE=/tmp/go-build-cache go test ./internal/orchestrator -run 'TestBBHSmoke(RecordCreatesReplayArtifacts|SessionReplayAggregatesReplayStats)|TestBBHSmokeAllPass|TestBBHSmokeProgressArtifacts' -v`
  - result: pass
- `cd freud && GOCACHE=/tmp/go-build-cache go test ./internal/orchestrator -run 'TestBBHSmoke(RecordCreatesReplayArtifacts|SessionReplayAggregatesReplayStats|AllPass|Mismatch|ProgressArtifacts)' -v`
  - result: pass
- `./gradlew --no-daemon test --tests 'ai.neopsyke.agent.SensoryCortexTest' --tests 'ai.neopsyke.eval.AgentScenarioPackTest'`
  - result: pass
- `./freud/bin/freud run cognitive-runtime-p0-tests`
  - result: pass
  - run dir: `/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T012046Z-cognitive-runtime-p0-tests-4150672821`

Recorded curated eval suites:
- `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-1-thread-foundation.txt --timeout 120`
  - result: pass
  - run dir: `/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T012148Z-live-eval-78070097`
- repo-owned eval pack added under `freud/evals/cognitive-runtime/`

Recorded BBH suites:
- `./freud/bin/freud bbh --live --lane low-llm --record`
  - result: fail
  - run dir: `/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T012217Z-bbh-low-llm-1272732936`
  - failing case: `date_02`
  - note: strict frozen exact-match BBH semantics preserved; no prompt/case/normalizer changes made

Replay-debug sessions:
- BBH suite replay path is now implemented and covered by deterministic Freud orchestrator tests
- replay debugging was exercised against the recorded BBH run while developing the harness
- the strict exact-match BBH contract is intentionally preserved, so the recorded `date_02` failure remains an open live-lane issue rather than a harness-normalization change

Acceptance items closed:
- mutable implementation ledger created
- BBH CLI now supports `--record` and `--session-replay`
- BBH suite now threads per-case recording and session replay through `LiveEval`
- BBH suite now writes suite-level replay stats artifacts for recorded or replayed runs
- curated low-llm eval pack added under `freud/evals/cognitive-runtime/`
- initial architecture-seam tests added for:
  - `Stimulus -> Percept` family mapping
  - control-plane passthrough at `SensoryCortex`
- initial low-risk parity scenarios added for:
  - direct answer single-step flow
  - search then answer flow

Open issues:
- Phase 0 is still incomplete:
  - more architecture-defining tests are still needed before the core orchestration rewrite starts
  - recorded BBH low-llm strict exact-match run currently fails on `date_02` and is being left strict per user instruction
  - relevant living docs have not been updated yet because Phase 0 is not being called complete in this state

### Phase 1

Status:
- Completed

Deterministic runs:
- `./gradlew --no-daemon compileKotlin compileTestKotlin`
  - result: pass
- `./gradlew --no-daemon test --tests 'ai.neopsyke.agent.SensoryCortexTest' --tests 'ai.neopsyke.agent.ego.CognitiveThreadStoreTest' --tests 'ai.neopsyke.agent.ego.SessionScopedDeliberationTest' --tests 'ai.neopsyke.agent.AttentionSchedulerTest'`
  - result: pass
- `./freud/bin/freud run cognitive-runtime-p1-foundation`
  - result: pass
  - run dir: `/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T020149Z-cognitive-runtime-p1-foundation-2762201783`

Recorded curated eval suites:
- `./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-1-thread-foundation.txt --timeout 120`
  - result: pass
  - run dir: `/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T020247Z-live-eval-1605269145`

Recorded BBH suites:
- `./freud/bin/freud bbh --live --lane low-llm --record`
  - result: pass
  - run dir: `/Users/victor.toral/atomitl/ai/NeoPsyke/.neopsyke/runs/freud/20260331T020306Z-bbh-low-llm-2964147509`
  - pass rate: 24/24 (100.0%)

Replay-debug sessions:
- none required; deterministic gate and both recorded live validations passed without replay iteration

Acceptance items closed:
- `SensoryCortex` is now the mandatory live `Stimulus -> Percept` boundary for cognitive work
- `CognitiveSignal.StimulusReceived` now carries an appraised percept
- `PendingInput` now carries bound percept/thread metadata
- `CognitiveThreadStore` now owns live root-thread identity, status, latest percept, and thread security state
- `DeliberationEngine` now reads thread security state from `CognitiveThreadStore` instead of maintaining its own root-input security map
- observed external artifacts now degrade trust on the owning cognitive thread
- planner context now includes percept summary/family and cognitive thread id/status
- planner prompt now sees percept and thread context explicitly
- living runtime docs updated: `AGENT_LOGIC_SUMMARY.md`, `AGENT_LOGIC_DIAGRAM.md`, `docs/security.md`

Open issues:
- full thread lifecycle ownership is still incomplete for later phases:
  - explicit waiting/blocked/resolved transitions
  - opportunity-driven scheduling
  - unified feedback re-entry
  - goal-runtime full continuity ownership beyond root binding

### Phase 2

Status:
- Not started

Deterministic runs:
- pending

Recorded curated eval suites:
- pending

Recorded BBH suites:
- pending

Replay-debug sessions:
- pending

Acceptance items closed:
- pending

Open issues:
- pending

### Phase 3

Status:
- Not started

Deterministic runs:
- pending

Recorded curated eval suites:
- pending

Recorded BBH suites:
- pending

Replay-debug sessions:
- pending

Acceptance items closed:
- pending

Open issues:
- pending

### Phase 4

Status:
- Not started

Deterministic runs:
- pending

Recorded curated eval suites:
- pending

Recorded BBH suites:
- pending

Replay-debug sessions:
- pending

Acceptance items closed:
- pending

Open issues:
- pending

### Phase 5

Status:
- Not started

Deterministic runs:
- pending

Recorded curated eval suites:
- pending

Recorded BBH suites:
- pending

Replay-debug sessions:
- pending

Acceptance items closed:
- pending

Open issues:
- pending

### Phase 6

Status:
- Not started

Deterministic runs:
- pending

Recorded curated eval suites:
- pending

Recorded BBH suites:
- pending

Replay-debug sessions:
- pending

Acceptance items closed:
- pending

Open issues:
- pending

### Phase 7

Status:
- Not started

Deterministic runs:
- pending

Recorded curated eval suites:
- pending

Recorded BBH suites:
- pending

Replay-debug sessions:
- pending

Acceptance items closed:
- pending

Open issues:
- pending
