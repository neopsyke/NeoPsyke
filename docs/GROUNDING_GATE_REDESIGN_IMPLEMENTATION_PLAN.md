# Grounding Gate Redesign Implementation Plan

**Status:** Draft plan
**Spec:** `docs/specs/GROUNDING_GATE_REDESIGN.md`
**Locked spec commit:** `8e9d9fc` (`docs(spec): lock grounding gate redesign`)

## Goal

Implement the grounding redesign exactly as defined in the locked spec:

- remove keyword-based post-hoc grounding classification
- classify grounding once at the correct domain boundary
- carry grounding as typed runtime metadata through the Ego flow
- expose the requirement to all relevant planner lanes
- enforce it at the post-gate using typed evidence state
- preserve bounded convergence and retry semantics

This plan assumes **clean cuts are preferred** over compatibility scaffolding.
We do not keep legacy verifier semantics, legacy reason codes, or dual-path
plumbing unless the spec explicitly requires temporary coexistence.

## End-State Architecture

By the end of implementation, the runtime should have these properties:

1. Fresh input is classified into `GroundingMetadata` exactly once.
2. `GroundingMetadata` is carried forward on runtime envelopes and never parsed
   back out of model output.
3. `PlannerContext` exposes `groundingMetadata` to every answer-producing lane.
4. Shared prompt injection adds grounding guidance only when
   `GroundingRequirement.REQUIRED`.
5. Goal work arrives with typed grounding policy already attached.
6. The post-gate reads:
   - `PendingAction.groundingMetadata`
   - typed external evidence state
   - typed forced-terminal marker
   - evidence action availability
7. Legacy verifier enums/assessment types/telemetry are gone.

## Deliberate Clean Cuts

These are intentional removals, not migration accidents:

- Delete `TaskIntentCategory`
- Delete `VolatilityLevel`
- Delete `DecisionVerifierAssessment`
- Delete `TaskClassification`
- Delete all keyword/regex signal sets and helper methods
- Delete `isForcedTerminalAnswer()` string heuristic
- Delete old verifier reason codes from execution paths
- Delete old verifier telemetry shape from runtime and dashboard

Do not preserve legacy aliases unless the implementation is blocked without
them. The system is an unreleased prototype; architectural clarity wins.

## Acceptance-Critical Invariants

These requirements are easy to under-specify in implementation even when the
high-level architecture looks correct:

- classification ordering is part of correctness: `GroundingMetadata` must
  exist before the first answer-producing planner lane runs for a root input;
  "eventually present" is insufficient
- trigger ownership is explicit:
  - `IncomingInput` -> input pre-filter / classifier
  - `DeferredIntention` -> inherited metadata
  - `ActionFeedback` trigger / `ActionFeedbackCue` carrier -> inherited metadata
  - `GoalWork` -> goal-step policy
  - `IncomingImpulse` -> `NOT_REQUIRED`
- ambiguous-route classifier failures still count as classifier-originated:
  fail-open must emit classifier-origin telemetry and a `fallback_reason`;
  do not relabel it as a pre-filter result
- forced-terminal degraded allows are only acceptable when the final delivered
  answer contains an explicit verification-failure disclaimer
- legacy removal is part of feature completion, not cleanup after the fact:
  the migration is incomplete until repo-wide searches confirm old symbols,
  reason codes, telemetry fields, and dashboard references are gone

## Workstreams

### 1. Introduce Typed Grounding Models

Add a small shared runtime model set:

- `GroundingRequirement`
- `GroundingSource`
- `GroundingMetadata`

Likely home:

- `src/main/kotlin/ai/neopsyke/agent/model/`

Requirements:

- runtime-owned, not planner-output-owned
- serializable/loggable enough for telemetry
- no tri-state `UNKNOWN`
- minimum `GroundingSource` set must cover:
  - `INPUT_PREFILTER`
  - `INPUT_CLASSIFIER`
  - `GOAL_STEP_POLICY`
  - `INHERITED`

Also add a typed forced-terminal marker to execution metadata. This can be:

- a boolean on `PendingAction`
- or a typed field on `ActionOrigin` plus a convenience property on action

Prefer the narrowest model that cleanly represents the behavior.

### 2. Propagate Grounding Through Runtime Envelopes

Extend the runtime carrier set identified in the spec:

- `PendingInput`
- scheduled opportunity / opportunity wrapper
- `QueuedIntention` / pending thought
- `PendingAction`
- `ActionFeedbackCue`
- `GoalRunActivation`

Relevant code paths likely include:

- `src/main/kotlin/ai/neopsyke/agent/model/CognitionModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/model/QueueModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/cortex/sensory/SensoryCortex.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/StimulusIngressCoordinator.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/CognitiveThreadStore.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionDispatcher.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/FallbackHandler.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/AttentionScheduler.kt`

Implementation rule:

- when runtime derives one envelope from another, copy `groundingMetadata`
- never reconstruct it from text, prompt content, or LLM output

Also wire trigger ownership explicitly instead of letting planner entry points
fall back to a silent default:

- `IncomingInput` uses the new classifier result
- deferred intentions inherit from the denied or requeued work item
- feedback continuations inherit from the originating action/root
- goal work uses the typed policy on `GoalRunActivation`
- incoming impulses are the only trigger type that may synthesize
  `NOT_REQUIRED` locally

If a propagated trigger arrives without grounding metadata where inheritance is
required, treat that as a bug to surface in tests/logging rather than silently
masking it with a default `NOT_REQUIRED`.

### 3. Add Grounding Classification At Input Intake

Implement the new input-time classifier after `InputIntentRouter` and before
the L2 sub-planner dispatch.

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/InputPlanner.kt`
- new helper/class under `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/`

Recommended structure:

- `GroundingClassifier` class
- deterministic pre-filter on `InputRoute`
- LLM fallback only for ambiguous routes

Deterministic pre-filter matrix:

- `GoalCreation` -> `NOT_REQUIRED`
- `GoalManagement` -> `NOT_REQUIRED`
- `ClarificationNeeded` -> `NOT_REQUIRED`
- `Noop` -> `NOT_REQUIRED`
- `DirectResponse` -> classifier LLM call
- `GeneralAction` -> classifier LLM call
- `MultiStepTask` -> classifier LLM call

Responsibilities:

- produce `GroundingMetadata`
- emit classification telemetry/logs
- coerce classifier failure to `NOT_REQUIRED`
- document in code that this fail-open behavior is provisional
- complete classification before sub-planner dispatch; add validation that the
  first planner lane invocation for a root already sees the resolved metadata
- emit route + source on resolution and `fallback_reason` on fail-open
- ensure deterministic routes emit no `grounding_classifier` LLM call event
- ensure ambiguous routes emit exactly the classifier-origin event path

LLM caller requirements must follow the standard project pattern:

- retry loop
- required-field validation for `grounding_required`
- safe fallback to `NOT_REQUIRED`

The fail-open result should remain classifier-originated for observability:

- `requirement=NOT_REQUIRED`
- `source=INPUT_CLASSIFIER` (or equivalent classifier-origin marker)
- warning log + `grounding_classification_fallback_not_required`

### 4. Add Cognitive Role / Runtime Config For Classifier

Add `grounding_classifier` to LLM runtime config and wiring.

Likely files:

- runtime wiring in `src/main/kotlin/ai/neopsyke/AppModeRunners.kt`
- cognitive role/config models
- YAML config under runtime config files

Requirements:

- cheap/fast model path
- same reliability patterns expected of other LLM callers
- structured JSON only
- explicit role/event naming so validation can prove when the
  `grounding_classifier` path was invoked

### 5. Extend PlannerContext, Entry-Point Wiring, And Prompt Plumbing

Add `groundingMetadata` to `PlannerContext`.

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/model/CognitionModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`

Then add one shared prompt section:

- `SharedPromptSections.groundingRequirementSection(context): Section?`

Likely file:

- `src/main/kotlin/ai/neopsyke/agent/ego/planner/prompt/SharedPromptSections.kt`

Inject this section into all answer-producing lanes named by the spec:

- `DirectResponsePlanner`
- `GeneralActionPlanner`
- `TaskDecompositionPlanner`
- `DeferredStepPlanner`
- `FeedbackPlanner`
- `GoalWorkPlanner`

If another lane can produce a final user-facing factual answer, include it too.

Implementation guardrails:

- every planner entry path must populate `PlannerContext.groundingMetadata`
  from the trigger/carrier that owns it; do not rely on a constructor default
  to stand in for propagated metadata
- the shared section must be the only grounding-specific prompt injection path
- the section must have zero token cost when grounding is not required
- tests must verify both presence and absence for every lane in the matrix,
  not just one representative planner

### 6. Type Goal-Work Grounding Policy

Do not let `GoalWorkPlanner` infer grounding from prose.

Extend goal planning/runtime models so the activation already knows the
grounding requirement:

- `PlanStep`
- `GoalRunActivation`
- whichever goal-planning component constructs them

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/goal/GoalModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalContextLoader.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalManager.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/GoalWorkPlanner.kt`

Target behavior:

- goal creation path sets `NOT_REQUIRED` for the operational confirmation
- goal execution path carries typed per-step grounding policy
- changing step prose alone must not change the grounding requirement when the
  typed goal-step policy stays the same

The typed grounding policy must be decided when goal steps or activations are
constructed, not inferred later during `GoalWorkPlanner` execution.

### 7. Rebuild The Post-Gate As GroundingGate

Replace the current verifier implementation behind the existing interface.

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DeliberationEngine.kt`

The new gate should read:

- `action.groundingMetadata`
- `DeliberationEngine.evidenceFor(...)`
- evidence action availability/dispatchability
- typed forced-terminal marker

Required reason codes:

- `GROUNDING_EVIDENCE_REQUIRED`
- `TECH_GROUNDING_EVIDENCE_FAILURE`
- `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`

Important: preserve the distinction between:

- no evidence attempted
- technical evidence failure
- evidence unavailable

The gate implementation must emit the new `grounding_gate_review` shape on all
branches and must not leak old verifier fields or event names.

### 8. Rework Forced-Terminal Semantics

Remove summary-string detection. Replace it with typed metadata.

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/DeliberationEngine.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt`
- action/envelope models

Target behavior:

- `NOT_REQUIRED` -> allow forced-terminal answer
- `REQUIRED` + successful evidence -> allow
- `REQUIRED` + technical failures below threshold -> deny/retry
- `REQUIRED` + technical failures above threshold -> allow degraded answer
  with explicit verification-failure disclaimer
- `REQUIRED` + no evidence attempt -> deny

This needs a single authoritative bounded-failure rule. Avoid scattering
threshold logic across multiple classes.

The degraded-answer path needs an explicit owner. One component must be
responsible for attaching the verification-failure disclaimer to the final
user-facing answer when the gate allows a best-effort forced-terminal outcome
after bounded technical failures. Allowing the action without guaranteeing the
disclaimer would fail the spec.

### 9. Update Denial / Retry Guidance

The denial text and repeated-denied-action behavior must reflect the new reason
code semantics.

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/FallbackHandler.kt`
- `src/main/kotlin/ai/neopsyke/agent/support/DenialReasonClassifier.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionDispatcher.kt`

Requirements:

- `GROUNDING_EVIDENCE_REQUIRED` tells the planner to go gather evidence
- `TECH_GROUNDING_EVIDENCE_FAILURE` explicitly allows retrying evidence
- repeated-denied-action logic must not suppress same-or-similar evidence retry
  after technical failure

Use the spec's two denial framings as the canonical semantics for requeue
guidance:

- missing evidence -> gather evidence before answering and do not repeat the
  denied `contact_user`
- technical evidence failure -> retry the same or an alternate evidence source;
  a repeated evidence attempt is allowed here

### 10. Replace Telemetry And Dashboard Aggregation

Runtime must emit:

- `grounding_classification_started`
- `grounding_classification_resolved`
- `grounding_classification_fallback_not_required`
- `grounding_metadata_propagated`
- `grounding_gate_review`

Dashboard aggregation must only use:

- `grounding_gate_review`

Likely files:

- `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt`
- `src/main/kotlin/ai/neopsyke/instrumentation/StructuredLogSink.kt`
- `src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt`
- dashboard tests

Required event fields for `grounding_gate_review`:

- `action_id`
- `root_input_id`
- `session_id`
- `action_type`
- `allow`
- `grounding_required`
- `evidence_gathered`
- `evidence_failed_technically`
- `evidence_unavailable`
- `forced_terminal`
- `reason_code`

Required fields for classification/progression telemetry:

- `root_input_id`
- `session_id`
- `requirement`
- `source`
- `route` for input-trigger classification
- `fallback_reason` when fail-open coercion occurs

Required fields for propagation telemetry:

- `root_input_id`
- `from_envelope_type`
- `to_envelope_type`
- `grounding_required`
- `source`

Also remove the old verifier-specific event and log shape entirely:

- `task_verifier_review`
- `task_verifier.review`
- `intent_category`
- `volatility_level`
- `volatility_score`

### 11. Perform Explicit Legacy Removal Audit

Treat removal criteria as a first-class workstream, not an implicit side effect
of refactoring.

Repo-wide audit must prove absence of:

- `TaskIntentCategory`
- `VolatilityLevel`
- `DecisionVerifierAssessment`
- `TaskClassification`
- `classifyTaskIntent`
- `volatilityScore`
- `containsAny`
- `countSignals`
- `isForcedTerminalAnswer`
- `recencySignals`
- `dynamicDomainSignals`
- `transformationSignals`
- `personalMemorySignals`
- `subjectiveAdviceSignals`
- `staticReasoningSignals`
- `factualQuerySignals`
- `dateSensitiveRegex`
- `DeterministicDecisionVerifier`
- `TASK_EVIDENCE_REQUIRED`
- `TECH_EXTERNAL_EVIDENCE_FAILURE`
- `TASK_EVIDENCE_UNAVAILABLE_GRACEFUL`
- `task_verifier_review`
- `task_verifier.review`
- `intent_category`
- `volatility_level`
- `volatility_score`

This audit must include runtime code, tests, dashboard code, runtime-facing
docs, and scenario fixtures so legacy semantics do not survive in "dead"
references that keep the wrong behavior alive in validation.

Explicit exception: the locked redesign spec and this implementation plan may
still mention removed symbols when documenting the migration/removal contract.

### 12. Rewrite / Expand Tests To Match The Spec

Primary test surfaces:

- ordering tests proving classification completes before first sub-planner use
- classifier unit tests
- classifier fail-open fallback tests
- propagation unit tests
- planner-context/prompt tests
- gate unit tests
- retry/technical-denial behavior tests
- forced-terminal degraded-answer disclaimer tests
- goal-work typed grounding tests
- dashboard aggregation tests
- legacy-removal audits / assertions
- deterministic scenario pack coverage

Likely files:

- `src/test/kotlin/ai/neopsyke/agent/ego/DecisionVerifierTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/DenialReasonClassifierTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/ego/planner/HierarchicalPlannerAcceptanceTest.kt`
- `src/test/kotlin/ai/neopsyke/dashboard/DashboardStateStoreTest.kt`
- `src/test/kotlin/ai/neopsyke/agent/EgoAgentTest.kt`
- `src/test/kotlin/ai/neopsyke/eval/AgentScenarioPackTest.kt`
- `freud/scenarios/v1/neopsyke-agent-scenarios.json`

Prefer replacing obsolete tests over preserving them with compatibility shims.

Acceptance coverage should map directly to spec criteria 17-29, including:

- full route pre-filter matrix
- fail-open fallback emission + unchanged planner visibility
- propagation across every named carrier edge
- planner-context visibility across every answer-producing lane
- full gate branch coverage
- technical retry eligibility after denial
- deferred-intention guidance with the reason-specific retry framing
- forced-terminal matrix, including degraded disclaimer content
- goal-work typed grounding with a negative prose-only assertion
- the three deterministic scenario-pack cases
- dashboard aggregation against only `grounding_gate_review`
- updates or removal of all obsolete tests tied to keyword heuristics

### 13. Update Living Runtime Docs In The Same Change

The grounding redesign changes control flow and core behavior. Update:

- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`

These should reflect:

- new input-time classifier
- metadata propagation through the loop
- new post-gate semantics
- forced-terminal grounded behavior

## Recommended Execution Order

This order is optimized for the final architecture, not for producing
intermediate shippable states.

1. Add grounding models and forced-terminal typed marker.
2. Extend runtime envelopes to carry metadata.
3. Extend `PlannerContext` and prompt plumbing.
4. Implement input-time grounding classifier and runtime config wiring.
5. Type goal-work grounding in goal models/runtime.
6. Replace verifier implementation with new grounding gate.
7. Rework denial/retry semantics and forced-terminal behavior.
8. Replace telemetry/logging/dashboard aggregation.
9. Perform explicit legacy-removal audit and delete any surviving old paths.
10. Rewrite tests and scenario coverage.
11. Update living architecture docs.
12. Run full deterministic validation and fix fallout.

## Validation Plan

Final validation should match the locked spec, not merely “tests are green”.

Validation must prove the acceptance criteria, not approximate them.

Required validation checklist:

1. Ordering and classification proof:
   - prove `GroundingMetadata` is resolved before the first L2 sub-planner runs
   - prove deterministic routes emit no `grounding_classifier` LLM call
   - prove ambiguous routes do emit the `grounding_classifier` path
2. Planner and propagation proof:
   - verify propagation across every carrier edge named in the spec
   - verify inherited grounding for deferred-intention and feedback replans
   - verify every answer-producing lane sees the same value in
     `PlannerContext.groundingMetadata`
   - verify the shared prompt section is present only when required
3. Behavioral regression proof:
   - reproduce the triggering incident and confirm goal-creation confirmation
     is delivered without denial
   - verify volatile-fact answers are denied with
     `GROUNDING_EVIDENCE_REQUIRED` when ungrounded, including exact
     `grounding_gate_review` assertions for `allow=false`,
     `grounding_required=true`, and `evidence_gathered=false`
   - verify deny -> requeue -> replan uses the correct reason-specific deferred
     guidance for both missing-evidence and technical-failure paths
   - verify graceful allow with
     `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`, including exact
     `grounding_gate_review` assertions for `allow=true`,
     `grounding_required=true`, and `evidence_unavailable=true`
   - verify forced-terminal degraded allow only after bounded technical
     failures and only with an explicit verification-failure disclaimer
4. Removal proof:
   - run a repo-wide audit that old verifier symbols, reason codes, event
     names, and telemetry fields no longer exist
   - scope that audit to implementation surfaces, tests, scenarios, and
     runtime-facing docs; exclude the locked spec and this migration plan,
     which intentionally reference removed symbols
   - verify dashboard aggregation reads only `grounding_gate_review`
5. Coverage surfaces:
   - targeted unit tests for models, classifier, fail-open fallback,
     propagation, planner visibility, gate logic, retry semantics,
     forced-terminal behavior, goal-work typing, and dashboard aggregation
   - deterministic scenario-pack coverage for recurring goal creation
     confirmation, volatile-fact grounding enforcement, and technical evidence
     retry
6. Full deterministic signoff gate:
   - `./freud/bin/freud run signoff-gate`

Do not report the redesign as validated until the deterministic signoff gate
passes.

## Implementation Notes

### Use existing typed state where it already fits

Do not create a second ad hoc evidence owner. External evidence state already
exists in `DeliberationEngine`; the gate should consume typed evidence from
there instead of adding scratchpad-based shadow state.

### Prefer root-scoped runtime propagation over central lookup registries

Carrying `GroundingMetadata` on envelopes keeps the design explicit and
composable. Avoid introducing a separate mutable registry unless the runtime
cannot cleanly propagate the metadata through existing types.

### Keep prompt injection centralized

One shared section helper is the right scaling point. Avoid duplicating custom
grounding prose in each lane.

### Avoid heuristic drift

Do not reintroduce deterministic text heuristics in:

- gate logic
- goal-work routing
- retry semantics
- forced-terminal handling

Natural-language interpretation belongs only in the model-based classifier and
existing model-based planners.

## Likely Files To Touch

This is a starting map, not an exhaustive list.

- `src/main/kotlin/ai/neopsyke/agent/model/CognitionModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/model/...` (new grounding model file if split out)
- `src/main/kotlin/ai/neopsyke/agent/model/QueueModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/CognitiveThreadStore.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DecisionDispatcher.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/FallbackHandler.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/DeliberationEngine.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/InputPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/InputIntentRouter.kt` (inspect for interface/wiring assumptions; redesign should not change its classification behavior)
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/prompt/SharedPromptSections.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/DirectResponsePlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GeneralActionPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/TaskDecompositionPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/DeferredStepPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/FeedbackPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/GoalWorkPlanner.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalModels.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalContextLoader.kt`
- `src/main/kotlin/ai/neopsyke/agent/goal/GoalManager.kt`
- `src/main/kotlin/ai/neopsyke/AppModeRunners.kt`
- `src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt`
- `src/main/kotlin/ai/neopsyke/instrumentation/StructuredLogSink.kt`
- `src/test/kotlin/ai/neopsyke/agent/ego/...`
- `src/test/kotlin/ai/neopsyke/agent/ego/planner/...`
- `src/test/kotlin/ai/neopsyke/dashboard/...`
- `src/test/kotlin/ai/neopsyke/eval/...`
- `freud/scenarios/v1/neopsyke-agent-scenarios.json`
- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`

## Completion Condition

The work is done only when all of these are true:

1. The runtime behavior matches the locked spec.
2. Legacy verifier semantics are removed, not merely bypassed.
3. The new telemetry/dashboard path is live.
4. Acceptance-criteria coverage exists in tests/scenarios.
5. Repo-wide legacy-removal audit is clean.
6. The deterministic signoff gate passes.
