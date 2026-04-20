# Typed Hierarchical Planner Redesign

## Status
- Draft
- Scope: Ego planner redesign and adjacent assignment-operation semantics

## Purpose
- Replace the current monolithic planner with a typed hierarchical planner architecture.
- Preserve all current end-to-end functionality and behavior, including current planner-driven feature coverage, while improving separation of concerns, prompt scope, architectural clarity, and future extensibility.
- Remove deterministic natural-language routing and semantic text heuristics from the planner stack and adjacent assignment-operation execution path.

## Problem Statement
- [`LlmEgoPlanner`](../../src/main/kotlin/ai/neopsyke/agent/ego/LlmEgoPlanner.kt) currently combines multiple responsibilities in one class:
  - branch selection
  - prompt construction
  - model calling and retry behavior
  - structured output repair and parsing
  - assignment creation handling
  - plan generation
  - next-action selection
  - post-planner action verification
- The current design requires a broad prompt and wide decision surface for many planner calls.
- Current assignment-related routing and execution still depend on deterministic text heuristics in multiple places, which is brittle and does not scale across languages or model choices.
- The current action verifier is not accepted as a stable architectural component for the redesign. It is disabled by default and tends to overwrite or rewrite prior planner outputs instead of acting as a trustworthy evaluator.

## Core Design Decisions
- Split planner logic by decision shape, not by code ownership alone.
- Use a typed hierarchical planner design.
- Keep the immediate redesign as a single-agent typed hierarchical planner.
- Allow deterministic routing only when based on typed runtime metadata already present in the opportunity, trigger, provenance, principal, policy, trust, or similar structured facts.
- Disallow deterministic routing on any natural-language or free-text input at every stage.
- Require semantic routing over natural-language input to be model-based and to return typed results.
- Move assignment semantics into typed semantic planner outputs rather than deterministic execution-time normalization.
- Give every sub-planner its own LLM configuration so it can use an independent model/provider/settings from other planner layers.
- Remove the current action verifier from the target planner architecture.
- Defer evaluator-optimizer design to a later iteration, likely outside the planner core and closer to meta-reasoner/analyzer structures.
- Treat `DeterministicDecisionVerifier` evidence-gating redesign as out of scope for this project and track it separately.

## Mandatory Routing Rule
- NO DETERMINISTIC ROUTING MUST BE DONE AT ANY STAGE ON ANY NATURAL LANGUAGE OR TEXT INPUT.
- This prohibition applies to planner layers, execution-adjacent semantic normalization, and any review/gating layer that interprets user or planner free text.
- This includes:
  - regexes
  - keyword spotting
  - substring matching
  - token overlap scoring
  - case-insensitive title matching for semantic resolution
  - deterministic intent classification on user text
  - deterministic semantic normalization of planner-produced free text
- Deterministic routing remains allowed when it is driven only by typed runtime facts such as:
  - trigger type
  - opportunity kind
  - provenance
  - principal role
  - instruction trust
  - data trust
  - policy scope
  - action availability
  - commit constraints
  - other structured metadata already computed upstream
- Scoped exception for this redesign:
  - the existing `DeterministicDecisionVerifier` evidence-gating path is not being redesigned here
  - its current deterministic text heuristics remain a known out-of-scope issue tracked separately
  - this exception does not permit any new deterministic natural-language routing or semantic heuristics in the planner/orchestrator/assignment-semantic paths covered by this redesign

## Goals
- Preserve all current end-to-end functionality and behavior during migration, except for the explicitly approved removals and reshaping in this spec.
- Do not treat existing bugs, accidental defects, or known architectural limitations as required behavior to preserve.
- Reduce prompt width by narrowing each planner call to one decision family.
- Make planner classes easier to navigate, reason about, test, and evolve.
- Support future planner capabilities without growing a single monolithic prompt.
- Support multilingual operation according to whatever the backing model supports.
- Ensure semantic planner outputs are typed and canonical before execution.
- Keep room for independent model selection per planner layer.

## Non-Goals
- Immediate cost optimization through cheaper models.
- Immediate introduction of a multi-agent framework.
- Any evaluator-optimizer implementation work as part of this redesign.
- Redesign of `DeterministicDecisionVerifier` evidence gating.
- Changing user-visible behavior unless required by architectural cleanup and explicitly approved.

## Architectural Principles
- Typed boundaries over stringly-typed heuristics.
- Narrow prompts over one large universal prompt.
- Planner specialization by decision family.
- Deterministic control flow only from typed runtime state.
- Semantic interpretation only in model-based components.
- One semantic interpretation pass per domain boundary whenever possible; downstream components should validate and execute typed intent rather than reinterpret it.
- Execution layers validate and execute typed commands; they do not reinterpret planner intent from text.

## Target Architecture Summary

### 1. Planner Orchestrator
- A top-level planner orchestrator remains the entry point behind `Ego.Planner`.
- Its responsibilities are intentionally narrow:
  - inspect typed trigger/opportunity/runtime facts
  - choose the next planner lane only from typed metadata
  - call the selected sub-planner
  - return the final `EgoDecision`
- Lane-specific sealed types remain the typed intermediate results inside planner boundaries; a shared `PlannerOutcome` wrapper is not required.
- It must not inspect natural-language content deterministically.

### 2. Typed Planner Lanes
- The redesign should split the current planner into distinct lanes shaped by decision family.
- Initial target top-level lanes:
  - `InputPlanner`
  - `DeferredStepPlanner`
  - `FeedbackPlanner`
  - `AssignmentPlanner`
  - `ImpulsePlanner`
- Additional lanes may be introduced later if a branch remains semantically overloaded.
- A lane may itself return another typed routing result when the next step is semantically ambiguous.

### 3. Shared Planner Runtime
- Retry policy, schema handling, structured-output repair, telemetry, and circuit-breaker behavior should move to shared reusable planner runtime components.
- Prompt assembly should be split per lane rather than shared through one monolithic builder.
- Model-call plumbing must be reusable and lane-agnostic.

### 4. Typed Intermediate Results
- Planner layers should communicate through typed intermediate structures rather than free-form text.
- Examples of useful intermediate types:
  - `PlannerLane`
  - `InputRoute`
  - `AssignmentCommand`
  - `WorkItemReference`
  - `PlanDecomposition`
  - `ExecutionCandidate`
- Intermediate types must be expressive enough to avoid pushing semantics back into execution-time text normalization.

## Concrete Hierarchy

### Level 0: Entry Point
- `HierarchicalEgoPlanner`
- This remains the single entry point behind `Ego.Planner`.
- Responsibilities:
  - inspect typed trigger/opportunity/runtime facts
  - deterministically select the planner lane only when the lane is already implied by typed metadata
  - invoke the next planner lane
  - return the final `EgoDecision` produced by the selected lane
- It must not use deterministic logic over natural-language input.

### Level 1: Top-Level Lanes

#### `InputPlanner`
- Used only for fresh user input.
- This is the only top-level lane that should perform broad semantic routing for standard conversational requests.
- It is responsible for deciding which narrower input-specific planner should run next.

#### `DeferredStepPlanner`
- Used for deferred continuations and active plan-step execution.
- It operates with narrower context than fresh-input planning because plan context, prior denials, and continuation state are already known.

#### `FeedbackPlanner`
- Used for `ActionFeedback`.
- It decides what a completed/failed/waiting action implies for the next agent step.

#### `AssignmentPlanner`
- Used for `EgoTrigger.Assignment`.
- It handles active assignment-runtime work and should remain separate from user-facing assignment creation/management semantics.

#### `ImpulsePlanner`
- Used for Id/self-motivated work.
- It remains separate because self-motivated planning has materially different behavioral rules from user-request-driven planning.

### Level 2: InputPlanner Sub-Planners

#### `InputIntentRouter`
- Semantic router for fresh user input.
- It returns a typed route rather than a final action decision.

#### `DirectResponder`
- Used when the request can be answered directly from current context.
- It should produce a terminal answer decision or clarification request.

#### `GeneralActionPlanner`
- Used when one explicit action/intention is sufficient.

#### `TaskDecompositionPlanner`
- Used when the request requires a multi-step task or plan decomposition.
- It should emit typed plan structures rather than unstructured string lists.

#### `AssignmentCreationPlanner`
- Used only for semantic assignment creation.

#### `AssignmentManagementPlanner`
- Used only for semantic operations on existing assignments.

## Proposed Typed Outputs

### Lane Selection / Routing
- `PlannerLane`
- `InputRoute`

### Execution / Tasking
- `ExecutionCandidate`
- `PlanDecomposition`
- `ClarificationRequest`

### Goals
- `AssignmentCommand`
- `WorkItemReference`

## Proposed Lane Output Shapes

### `InputPlanner`
- `InputRoute.DirectResponse`
- `InputRoute.GeneralAction`
- `InputRoute.MultiStepTask`
- `InputRoute.AssignmentCreation`
- `InputRoute.AssignmentManagement`
- `InputRoute.ClarificationNeeded`
- `InputRoute.Noop`

### `DeferredStepPlanner`
- `StepDecision.Execute`
- `StepDecision.RefinePlan`
- `StepDecision.SkipStep`
- `StepDecision.Answer`
- `StepDecision.Defer`
- `StepDecision.Clarify`
- `StepDecision.Fail`

### `FeedbackPlanner`
- `FeedbackDecision.Answer`
- `FeedbackDecision.Retry`
- `FeedbackDecision.NextStep`
- `FeedbackDecision.Defer`
- `FeedbackDecision.MarkBlocked`
- `FeedbackDecision.MarkDone`

### `AssignmentPlanner`
- `AssignmentDecision.ExecuteStep`
- `AssignmentDecision.DeferUntilCondition`
- `AssignmentDecision.MarkStepComplete`
- `AssignmentDecision.RequestClarification`
- `AssignmentDecision.FailStep`

### `ImpulsePlanner`
- `ImpulseDecision.Research`
- `ImpulseDecision.Reflect`
- `ImpulseDecision.ContactUser`
- `ImpulseDecision.Noop`

## Assignment Semantics Requirements

### Current Problem
- Assignment-related semantics are currently split across planner-time and execution-time heuristics.
- This includes text-triggered assignment-creation routing and plugin-side operation normalization / assignment identifier matching.
- That shape is explicitly out of bounds for the redesign.

### Requirement
- Assignment semantics must be resolved semantically and returned as typed assignment commands before execution.
- The assignment execution plugin must become a validator/executor over typed semantic intent, not a second semantic interpreter.
- Assignment-command adaptation into execution payloads, if still needed during migration, must happen from typed semantic structures rather than from raw natural-language reinterpretation.

### Target Shape
- Introduce a typed assignment command model, for example:
  - `AssignmentCommand.Create`
  - `AssignmentCommand.List`
  - `AssignmentCommand.Status`
  - `AssignmentCommand.Pause`
  - `AssignmentCommand.Resume`
  - `AssignmentCommand.Complete`
  - `AssignmentCommand.Delete`
  - `AssignmentCommand.DeleteAll`
  - `AssignmentCommand.Update`
  - `AssignmentCommand.RevisePlan`
  - `AssignmentCommand.Reprioritize`
- Assignment reference handling should also become typed, for example:
  - `WorkItemReference.ByInternalId`
  - `WorkItemReference.ByResolvedEntity`
  - `WorkItemReference.Ambiguous`
  - `WorkItemReference.Unresolved`
- Assignment references should be represented by typed resolution results rather than string heuristics.
- If a natural-language assignment reference is ambiguous, the semantic planner should resolve it or request clarification.
- Execution-time code must not use deterministic text heuristics to reinterpret semantic intent.

## LLM Configuration Requirements
- Every new sub-planner must have its own LLM configuration.
- Independent configuration must support, at minimum:
  - provider
  - model
  - temperature
  - max completion tokens
  - structured-output settings
  - retry policy overrides if needed
- Sub-planners may still share defaults, but they must not be forced to share one planner model forever.
- This requirement exists for architectural isolation first, not for immediate cost optimization.

## Action Verifier Decision
- The current action verifier is not part of the target planner architecture.
- It should be removed during the redesign rather than preserved as a first-class planner stage.
- Its removal is a requirement of this redesign, not an optional cleanup.
- Rationale:
  - it is disabled by default
  - it tends to rewrite/overwrite planner decisions
  - it blurs generation and evaluation boundaries
  - it increases planner coupling instead of clarifying responsibilities

## Evaluator-Optimizer Position
- A future evaluator-optimizer may be added later.
- It is explicitly out of scope for this redesign.
- It should be designed as a separate architecture concern rather than embedded into the core planner decision path by default.
- Preferred future placement is closer to meta-reasoner or analyzer structures than to the planner core.
- If added later, it should critique or gate outputs through typed feedback channels rather than silently rewriting prior planner outputs.

## Orchestrator-Worker Pattern Relevance
- Orchestrator-worker means a central orchestrator decomposes a task into subtasks, delegates those subtasks to specialized workers, then synthesizes the result.
- In this project, that pattern is potentially useful in limited areas:
  - large multi-step research/search tasks
  - decomposition of complex multi-source evidence gathering
  - future planner-assisted synthesis over multiple independent worker outputs
- It is not the first step of this redesign.
- The immediate redesign should stay inside a single-agent typed hierarchical planner architecture.
- The more relevant takeaway is structural:
  - keep one narrow orchestrator
  - keep workers specialized
  - keep typed contracts between them
- If orchestrator-worker is adopted later, worker responsibilities should map to bounded decision families or evidence-gathering jobs rather than free-form personas.

## Migration Requirements
- Prefer clean architectural breaks over compatibility shims or parallel legacy planner paths.
- Do not preserve old planner structure just for migration convenience.
- If a final runtime boundary still intentionally uses serialized payloads, those payloads must be generated directly from typed planner results, not from downstream text heuristics.
- Add black-box tests around typed planner contracts and resulting `EgoDecision` behavior.
- Remove natural-language routing heuristics as part of the migration, not as a later cleanup.
- Remove assignment-operation semantic heuristics from execution-time plugin code as part of the migration to typed assignment commands.
- Keep planner behavior observable with explicit telemetry per lane.

## Recommended Delivery Phases

### Phase 1
- Introduce:
  - `HierarchicalEgoPlanner`
  - `InputPlanner`
  - `InputIntentRouter`
  - `DirectResponder`
  - `GeneralActionPlanner`
  - `TaskDecompositionPlanner`
  - `AssignmentCreationPlanner`
  - `AssignmentManagementPlanner`
  - typed `AssignmentCommand`
- Remove the current action verifier from the planner path.

### Phase 2
- Introduce:
  - `DeferredStepPlanner`
  - `FeedbackPlanner`
  - `AssignmentPlanner`
  - `ImpulsePlanner`
- Continue narrowing prompts and replacing monolithic planner responsibilities with lane-specific planners.

## Acceptance Summary
- No planner/orchestrator routing logic performs deterministic interpretation of natural-language input.
- No assignment-operation execution path performs deterministic semantic reinterpretation of planner text.
- The existing `DeterministicDecisionVerifier` evidence-gating path is excluded from this redesign's acceptance scope and tracked separately.
- All current end-to-end functionality and behavior covered by the planner stack are preserved unless explicitly approved otherwise in this spec.
- The immediate redesign stays within a single-agent typed hierarchical planner architecture.
- Planner logic is split into multiple smaller classes aligned to decision family.
- Each planner lane has its own prompt and its own LLM configuration entry point.
- The current action verifier is removed from the redesigned planner path.
- Any intentionally retained serialized boundary payloads are generated from typed semantic outputs rather than deterministic text parsing.
- The redesign preserves intended current end-to-end behavior with equivalent or better test coverage, without treating existing bugs or limitations as parity targets.

## Open Questions
- No open question remains that blocks implementation start.
- Non-blocking future question:
  - which evaluator/analyzer structure should own future evaluator-optimizer loops?

## Detailed Acceptance Criteria and Signoff Rules

This section defines how to determine that the redesign has successfully
replaced the current planner without losing required behavior.

### 1. Scope-Control Rule
- The redesign is accepted only if it preserves all current end-to-end functionality and behavior within scope, except for the explicitly approved removals and reshaping in this spec.
- The current action verifier is the only planner-stage removal explicitly approved by this spec.
- Any other behavior change must be called out explicitly as an intentional change with dedicated tests and documentation updates.
- Existing bugs, accidental defects, and known limitations do not count as required behavior for parity and do not need to be preserved.
- Fixing an existing bug or removing an accidental limitation is allowed, but it must be documented explicitly so it is not mistaken for silent behavioral drift.

### 2. Feature-Parity Inventory Rule
- Before signoff, the implementation must produce a planner parity inventory that maps current planner capabilities to their new owner components.
- Every currently supported planner capability must be marked as one of:
  - preserved with equivalent behavior
  - preserved with narrower typed architecture
  - intentionally changed
  - intentionally removed
- Existing bugs and known limitations must not be classified as preserved capabilities. If they are fixed as part of the redesign, the parity/signoff artifacts must describe them as bug fixes or limitation removals rather than parity targets.
- Nothing may be left unclassified.
- The parity inventory must explicitly include, at minimum:
  - fresh input planning
  - deferred intention / continuation planning
  - action feedback planning
  - assignment-work planning
  - Id/self-motivated planning
  - direct answer path
  - single-action path
  - multi-step planning path
  - assignment creation
  - assignment management
  - allowed-intention shaping
  - allowed-commit-mode shaping
  - action-availability shaping
  - plan-step continuation semantics
  - resolution-draft gating
  - structured-output retry / recovery behavior
  - planner output repair behavior that remains in scope
  - planner telemetry and prompt-budget telemetry

### 3. Trigger-Family Coverage Rule
- The redesigned planner is not accepted unless every currently supported planner trigger family is covered by dedicated tests and mapped to a specific planner lane.
- Required trigger families:
  - `IncomingInput`
  - `DeferredIntention`
  - `ActionFeedback`
  - `IncomingImpulse`
  - `Assignment`
- For each trigger family, tests must show:
  - correct lane entry
  - correct typed intermediate result or final `EgoDecision`
  - preservation of the expected runtime constraints carried by context

### 4. Decision-Shape Coverage Rule
- The redesigned planner must preserve the currently supported planner decision shapes unless explicitly superseded by typed equivalents that adapt back to the same `EgoDecision` contract.
- Required decision-shape coverage:
  - direct terminal response
  - deferred continuation
  - explicit next action/intention
  - multi-step plan decomposition
  - assignment creation intent
  - assignment management intent
  - clarification request when semantic resolution is insufficient
- Tests must cover at least one positive and one negative/guardrail case for each decision shape.

### 5. Constraint-Preservation Rule
- The redesign is not accepted if planner lanes can bypass or forget constraints that are currently provided through typed runtime context.
- Tests must verify preservation of:
  - allowed intentions
  - allowed commit modes
  - available actions
  - dispatchable actions
  - conversation / thread trust and provenance shaping
  - plan-context restrictions such as resolution-draft gating
  - assignment-runtime constraints
  - Id convergence constraints
- If a lane receives fewer inputs than the old monolithic planner, tests must still show that all required constraints remain enforced.

### 6. No Text-Heuristic Regression Rule
- The redesign is not accepted if any planner, router, assignment semantic resolver, or execution-adjacent semantic adapter performs deterministic natural-language interpretation.
- Verification must include both:
  - tests that prove semantic routing succeeds on natural-language inputs through model-based typed outputs
  - a code-level audit that no planner-semantic path relies on regex, keyword matching, substring matching, token overlap scoring, or comparable deterministic text heuristics
- Any deterministic logic found on a semantic text path blocks signoff.
- Scoped exception:
  - the existing `DeterministicDecisionVerifier` evidence-gating path is excluded from this redesign's Rule 6 audit and signoff
  - this exception must be documented explicitly in the parity/signoff artifacts
  - no new deterministic natural-language heuristics may be added there as part of this redesign

### 7. Assignment-Semantics Acceptance Rule
- Assignment behavior is accepted only if assignment creation and assignment management semantics are resolved before execution as typed assignment commands.
- The assignment execution path must not reinterpret planner intent from raw text.
- Acceptance requires tests for:
  - assignment creation
  - assignment listing / status
  - assignment pause / resume
  - assignment completion / delete
  - assignment update / revise-plan / reprioritize as applicable
  - ambiguous assignment references
  - unresolved assignment references
- Multilingual phrasing support remains a design goal of the architecture, but multilingual acceptance coverage is not a required signoff item for this iteration.

### 8. Removed-Action-Verifier Rule
- The current action verifier must be absent from the redesigned planner path.
- Acceptance requires:
  - no planner decision path depends on the current action verifier
  - no planner signoff depends on re-enabling the current action verifier
  - replacement tests demonstrating that planner correctness is achieved through lane design, typed outputs, and existing runtime controls rather than post-hoc rewriting

### 9. Shared-Runtime Preservation Rule
- Moving logic out of `LlmEgoPlanner` must not silently drop reliability behavior that remains in scope.
- Acceptance requires coverage for the shared planner runtime behaviors that are still intended to exist after redesign:
  - model call retry policy
  - structured output handling
  - parse failure handling
  - truncation recovery if still supported
  - telemetry emission
  - prompt-budget allocation telemetry
- If a current behavior is intentionally removed or replaced, that change must be documented explicitly in the parity inventory.

### 10. Test-Replacement Rule
- Existing planner tests may be reorganized, renamed, or split, but they may not be deleted without explicit replacement coverage.
- Every removed planner test must map to:
  - an equivalent new test
  - a higher-level integration/scenario test that covers the same user-visible contract
  - or an explicit intentional-removal note in the parity inventory
- The redesign is not accepted if coverage is reduced by omission.

### 11. Documentation-Consistency Rule
- The redesign is not accepted until runtime behavior documentation reflects the new planner structure.
- Required docs updates at signoff:
  - this spec reflects the final implemented architecture
  - `AGENT_RUNTIME_LOGIC.md` reflects the final planner/orchestrator flow
  - the affected files under `docs/agent-logic/` reflect the final planner/orchestrator flow
- If the implemented hierarchy differs from this spec, the spec must be updated before signoff.

### 12. Final Verification Rule
- The redesign is only accepted when all of the following are true:
  - planner/unit/integration tests for the redesign pass
  - existing required deterministic validation passes
  - the planner parity inventory is complete
  - end-to-end scenario/integration evidence shows current in-scope behavior is preserved
  - no blocked acceptance rule above remains unresolved
- Final validation must be reported with explicit evidence, including:
  - which test suites were run
  - whether current end-to-end functionality and behavior were preserved, intentionally changed, or intentionally removed
  - which existing bugs or limitations were intentionally fixed or removed during the redesign
  - which known scope exceptions were explicitly excluded from signoff and why
  - whether any open gaps remain

### 13. Default Failure Rule
- If there is uncertainty about whether a current planner feature has been preserved, that feature is treated as not accepted until explicit evidence is added.
- Silence, implicit assumptions, or deleted tests do not count as proof of parity.
