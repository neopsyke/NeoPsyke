# Typed Hierarchical Planner Review 1

## Scope

Review target:
- `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md`
- current planner implementation under `src/main/kotlin/ai/neopsyke/agent/ego/planner/**`
- current goal-operation execution boundary
- signoff/supporting docs

This review excludes multilingual acceptance coverage as a current signoff requirement.

## Acceptance Assessment

The redesign is directionally implemented, but the acceptance criteria are not fully met yet.

Main reasons:
- per-lane LLM configuration is specified but not fully wired end-to-end
- the goal execution boundary is still serialized-map based rather than a true typed `GoalCommand` / `GoalReference` execution contract
- `GoalCreationPlanner` has an unsafe generic-goal fallback on planner failure/misparse
- shared-runtime structured-output fallback behavior is weaker than the parity inventory currently claims
- several typed intermediate result models exist but are not actually used as the internal lane decision boundary

## Findings

### 1. Per-lane LLM configuration is incomplete

Severity: High

Problem:
- `PlannerRuntime.resolvedConfig()` resolves per-lane `provider` and `model`, but `PlannerRuntime.call()` still always uses `defaultModelClient`.
- `AgentRuntimeConfig` does not parse the documented `planner.lane_defaults` / `planner.lanes` YAML shape yet.
- some planners hardcode temperature/max-tokens directly instead of relying on resolved lane config

Code points:
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/runtime/PlannerRuntime.kt`
- `src/main/kotlin/ai/neopsyke/config/AgentRuntimeConfig.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/InputIntentRouter.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GoalCreationPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GoalManagementPlanner.kt`

Impact:
- the spec requirement that each lane have an independent LLM configuration entry point is not truly satisfied
- documentation currently overstates what the runtime supports

### 2. Goal-operation execution is not yet a real typed semantic boundary

Severity: High

Problem:
- `GoalCreationPlanner` and `GoalManagementPlanner` create ad-hoc serialized maps instead of serializing a typed `GoalCommand` boundary object
- `GoalOperationActionPlugin` still accepts both `command` and legacy `operation`, reparses into a plugin-local payload DTO, and performs execution from that payload shape
- the plugin still performs goal-id repair from raw payload values, even if the heuristic is now narrow

Code points:
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GoalCreationPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GoalManagementPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/cortex/motor/actions/plugin/builtin/GoalOperationActionPlugin.kt`

Impact:
- the system is better than the old heuristic path, but it still falls short of “validator/executor over typed semantic intent”
- the goal execution boundary remains more migration-shaped than target-architecture-shaped

### 3. Goal creation has an unsafe generic fallback

Severity: High

Problem:
- if `GoalCreationPlanner` does not get a valid `create_goal` payload, it can fall back to `createGenericGoal(...)`
- this means planner failure, parse failure, or route misclassification can still create a persistent goal

Code points:
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GoalCreationPlanner.kt`

Impact:
- this is not a safe fallback for a persistent control-plane action
- the planner should clarify or answer, not synthesize a generic goal from failed semantics

### 4. Shared-runtime fallback behavior is weaker than parity docs claim

Severity: Medium

Problem:
- the parity inventory currently states that strict-to-relaxed schema fallback is preserved as shared runtime behavior
- the runtime only swaps to relaxed schema on provider-side schema-validation exceptions
- ordinary parse failures from model output are handled later by lane-local retry prompts, not by a true shared strict-to-relaxed parse recovery path

Code points:
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/runtime/PlannerRuntime.kt`
- `docs/PLANNER_PARITY_INVENTORY.md`

Impact:
- the implementation may be acceptable if documented as a changed/reduced behavior
- it is not acceptable to claim preserved behavior if the mechanism is materially different and weaker

### 5. Typed intermediate decision models are underused

Severity: Medium

Problem:
- `StepDecision`, `FeedbackDecision`, `GoalWorkDecision`, and `ImpulseDecision` exist
- actual lane implementations still parse raw JSON payloads and branch directly on strings like `"defer"`, `"intend"`, and `"plan"`
- the typed lane result models are therefore mostly documentary rather than architectural

Code points:
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/model/*.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/*.kt`

Impact:
- this keeps the architecture more stringly-typed than the redesign intends
- it makes the codebase look more complete than it is

### 6. Signoff docs needed parity-inventory expansion

Severity: Medium

Problem:
- the parity inventory was missing explicit classification of several capabilities that the spec requires to be called out
- specifically: dispatchable actions, conversation/thread trust and provenance shaping, goal-runtime constraints, and Id convergence constraints

Status:
- fixed in `docs/PLANNER_PARITY_INVENTORY.md` as part of this review pass

## Fix Plan

### Workstream 1: Complete per-lane config wiring

Goal:
- make lane config real, not nominal

Changes:
- extend `AgentRuntimeYamlPlanner` to parse:
  - `lane_defaults`
  - `lanes`
- map those into `PlannerConfig.laneDefaults` and `PlannerConfig.lanes`
- introduce lane-aware model-client resolution in runtime construction so a lane can actually select:
  - provider
  - model
  - temperature
  - max completion tokens
  - structured-output mode
  - retry attempts
- remove hardcoded lane token/temperature overrides where resolved lane config should own them
- add targeted config-loader and runtime tests proving different lanes can resolve different settings

Exit criteria:
- lane YAML is parsed
- at least one test proves two lanes can resolve different configs
- runtime no longer ignores resolved `provider` / `model`

### Workstream 2: Make goal execution truly typed

Goal:
- replace the serialized-map migration boundary with a typed command boundary

Changes:
- define a single serialized contract for `GoalCommand` and nested `GoalReference`
- have goal planners serialize that typed structure directly, not hand-built maps
- refactor `GoalOperationActionPlugin` to deserialize typed command variants instead of a plugin-local generic payload
- remove legacy `operation` compatibility from the redesigned planner path
- move validation onto typed command fields and typed goal-reference variants
- keep exact-id / numeric-index resolution only if it remains an explicitly intended runtime boundary behavior; otherwise remove plugin-side repair entirely

Exit criteria:
- planner emits typed serialized `GoalCommand`
- plugin consumes typed serialized `GoalCommand`
- no semantic reinterpretation remains in the plugin boundary

### Workstream 3: Remove unsafe generic goal fallback

Goal:
- ensure planner failure cannot silently create persistent goals

Changes:
- delete `createGenericGoal(...)`
- on invalid or missing `create_goal` payload:
  - prefer assistant fallback if the model explicitly returned fallback
  - otherwise return clarification or `Noop`, depending on the safest current contract
- add tests proving parse failure or malformed goal-creation output does not create a goal operation

Exit criteria:
- no planner-failure path creates a generic persistent goal

### Workstream 4: Align shared-runtime behavior with spec and docs

Goal:
- either strengthen the runtime behavior or narrow the parity claim so they match

Changes:
- decide one of:
  - implement true shared strict-to-relaxed retry on parse failure
  - or reclassify this as intentionally changed behavior in parity docs
- centralize as much retry/repair behavior as practical in `PlannerRuntime`
- reduce repeated lane-local retry logic where shared runtime can own it cleanly
- add tests for the chosen final behavior

Exit criteria:
- implementation and parity inventory say the same thing
- Rule 9 evidence is accurate

### Workstream 5: Use typed lane decision models for real

Goal:
- make typed intermediate models part of execution flow, not dead scaffolding

Changes:
- refactor each L1 lane to:
  - parse into its lane-specific typed result
  - map typed result to `EgoDecision`
- stop branching directly on raw string decision values in lane code where a typed result model exists
- keep JSON schema outputs canonical, but convert them immediately into typed internal models

Exit criteria:
- lane implementations primarily operate on `StepDecision`, `FeedbackDecision`, `GoalWorkDecision`, and `ImpulseDecision`

### Workstream 6: Keep signoff docs in sync

Goal:
- prevent signoff artifacts from overstating completeness

Changes:
- keep `PLANNER_PARITY_INVENTORY.md` explicit and complete
- update parity classifications if any runtime behavior is intentionally changed during the fix work
- update `AGENT_LOGIC_SUMMARY.md` and `AGENT_LOGIC_DIAGRAM.md` if planner control flow materially changes during remediation

Exit criteria:
- no required capability is omitted from parity inventory
- docs reflect the actual post-fix architecture

## Suggested Execution Order

1. Per-lane config wiring
2. Goal boundary typing
3. Remove unsafe goal-creation fallback
4. Shared-runtime parity alignment
5. Typed lane-result refactor
6. Final doc consistency pass

## Validation After Fixes

Minimum validation for the remediation patch set:
- planner acceptance tests
- goal operation plugin tests
- new config-loading tests for lane defaults / lane overrides
- targeted planner runtime tests for lane config and shared retry behavior
- deterministic signoff gate before final signoff
