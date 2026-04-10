# Planner Parity Inventory

**Spec:** `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md` (Acceptance Rule 2)
**Branch:** `refactor/typed-planner-redesign`

---

## Classification Key

- **Preserved**: Equivalent behavior, same component or 1:1 replacement.
- **Preserved (narrower)**: Equivalent behavior, now in a typed/narrower architecture.
- **Intentionally changed**: Behavior differs from old planner by design.
- **Intentionally removed**: Removed per spec approval.

---

## Capability Inventory

| # | Capability | Classification | New Owner | Notes |
|---|-----------|---------------|-----------|-------|
| 1 | Fresh input planning | Preserved (narrower) | `InputPlanner` -> `InputIntentRouter` -> L2 sub-planners | Two-call pattern: classify then decide. Old: single monolithic call. |
| 2 | Continuation planning | Preserved (narrower) | `ContinuationPlanner` | Dedicated lane with narrower prompt. Supports continuation/intend/plan/noop. |
| 3 | Action feedback planning | Preserved (narrower) | `FeedbackPlanner` | Dedicated lane for action outcome interpretation. |
| 4 | Goal-work planning | Preserved (narrower) | `GoalWorkPlanner` | Dedicated lane for goal-step execution. |
| 5 | Id/self-motivated planning | Preserved (narrower) | `ImpulsePlanner` | Dedicated lane. No plan decision (only defer/intend/noop). |
| 6 | Direct answer path | Preserved (narrower) | `DirectResponsePlanner` | New L2 sub-planner for answering from context. |
| 7 | Single-action path | Preserved (narrower) | `GeneralActionPlanner` | New L2 sub-planner with full validation stack. |
| 8 | Multi-step planning path | Preserved (narrower) | `TaskDecompositionPlanner` | New L2 sub-planner emitting typed `EnqueuePlan`. |
| 9 | Goal creation | Preserved (narrower) | `GoalCreationPlanner` | LLM-resolved parameters including cron. No regex heuristics. |
| 10 | Goal management | Preserved (narrower) | `GoalManagementPlanner` | LLM-resolved goal references. No fuzzy text matching. |
| 11 | Allowed-intention shaping | Preserved | All L1/L2 lanes via `PlannerContext.allowedIntentions` | Each lane checks `ik !in context.allowedIntentions` before forming intention. |
| 12 | Allowed-commit-mode shaping | Preserved | All L1/L2 lanes via `PlannerContext.allowedCommitModes` + `DecisionValidation` | Commit mode resolved and validated per lane. |
| 13 | Action-availability shaping | Preserved | All L1/L2 lanes via `PlannerContext.availableActions` | Each lane checks `at !in context.availableActions`. |
| 14 | Dispatchable-action shaping | Preserved | `SharedPromptSections.actionGuidanceBlock()` + `SharedPromptSections.plannerVisibleActionSchemaEnum()` + `InputIntentRouter` / `GoalCreationPlanner` | Planner-visible action surface is restricted to dispatchable actions; goal routes also gate on `GOAL_OPERATION in context.dispatchableActions`. |
| 15 | Conversation / thread trust and provenance shaping | Preserved | Shared prompt context sections across L1/L2 lanes | `securityContextSection`, `triggerProvenanceSection`, `perceptThreadSection`, and prompt instructions carry trust/provenance constraints into planner decisions. |
| 16 | Goal-runtime constraints | Preserved | `GoalWorkPlanner` + `SharedPromptSections.formatTriggerText()` | Goal step description, acceptance criteria, wake reason, and working context are preserved in the goal-work lane prompt and decision path. |
| 17 | Id convergence constraints | Preserved | Upstream `CognitivePolicyShaper` / `Ego` shaping, consumed by `ImpulsePlanner` and deferred lanes through `PlannerContext` | Convergence-mode action/intention restrictions are preserved as typed context constraints before planner choice, then enforced by planner-visible action surface and lane validation. |
| 18 | Plan-step continuation semantics | Preserved | `ContinuationPlanner` | Plan context, pass count, denial codes carried via typed `QueuedContinuation` fields. |
| 19 | Resolution-draft gating | Preserved | `ContinuationPlanner` | `allowResolutionDraft = continuation.planContext != null` check. Only in the continuation lane. |
| 20 | Structured-output retry / recovery | Preserved (narrower) | `PlannerRuntime` + lane-local retry paths | Runtime owns model-call retry and provider-side schema-validation fallback. Parse-failure retries are lane-local (`truncation_retry` + strict-JSON retry). |
| 21 | Planner output repair | Preserved | `StructuredOutputHandler` + `PlannerRuntime` | Invalid JSON escape repair, bare URL wrapping, missing summary synthesis. |
| 22 | Planner telemetry | Preserved | `PlannerRuntime` + `HierarchicalEgoPlanner` | `planner_start`, `planner_lane_selected`, `planner_decision`, `prompt_budget_allocation`. |
| 23 | Prompt-budget allocation telemetry | Preserved | `PlannerRuntime.emitPromptBudgetTelemetry()` | Emitted per-lane with lane-specific call_site. |
| 24 | Circuit-breaker behavior | Preserved | `PlannerRuntime` (per-lane, per-rootInputId) | 3 consecutive parse failures trip the breaker. |
| 25 | Truncation retry | Preserved | `TruncationRetry` (shared utility) | Detects `finish_reason=length` or unclosed JSON, bumps token budget. |
| 26 | Per-lane LLM configuration entry points | Preserved (narrower) | `PlannerConfig.laneDefaults` + `PlannerConfig.lanes` + `PlannerRuntime` lane client resolver | Lanes can override provider, model, temperature, token budget, retries, and structured-output mode independently. |

---

## Intentionally Changed Capabilities

| # | Capability | Classification | Details |
|---|-----------|---------------|---------|
| C1 | Goal-operation normalization | Intentionally changed | Was deterministic `normalizeOperation()` text heuristics. Now LLM-resolved typed `GoalCommand`. |
| C2 | Goal-ID resolution | Intentionally changed | Was `resolveGoalId()` with case-insensitive title matching and token-overlap scoring. Now LLM-resolved `GoalReference` types. |
| C3 | Goal-creation routing | Intentionally changed | Was `shouldUseGoalCreationBranch()` regex. Now LLM-based `InputIntentRouter`. |
| C4 | Delete-all intent detection | Intentionally changed | Was `looksLikeDeleteAllIntent()` keyword matching. Now LLM-resolved `GoalCommand.DeleteAll`. |
| C5 | Input routing | Intentionally changed | Was single monolithic planner call deciding everything. Now two-call pattern: `InputIntentRouter` classifies, then L2 sub-planner decides. |
| C6 | Shared parse-failure recovery shape | Intentionally changed | Runtime preserves provider-side schema-validation fallback. Parse-failure recovery remains lane-level (truncation retry + strict-JSON retry), not a centralized strict→relaxed parse-retry layer. |

---

## Intentionally Removed Capabilities

| # | Capability | Classification | Spec Authority |
|---|-----------|---------------|----------------|
| R1 | Action verifier (post-planner rewriting) | Intentionally removed | Spec section "Action Verifier Decision" and "Removed-Action-Verifier Rule" (Rule 8). Disabled by default, rewrote planner outputs, blurred gen/eval boundaries. |

---

## Existing Bugs / Limitations Fixed During Redesign

| # | Item | Was | Fix |
|---|------|-----|-----|
| B1 | `normalizeOperation()` text heuristics | Limitation (locale-sensitive, brittle) | Replaced by typed `GoalCommand` from LLM |
| B2 | `looksLikeDeleteAllIntent()` keyword matching | Limitation (English-only) | Replaced by LLM semantic resolution |
| B3 | `resolveGoalId()` fuzzy matching | Limitation (case-insensitive title + token-overlap) | Replaced by LLM `GoalReference` resolution |
| B4 | `shouldUseGoalCreationBranch()` regex | Limitation (regex-based routing on NL text) | Replaced by `InputIntentRouter` LLM classification |
| B5 | Action verifier rewriting planner outputs | Limitation (disabled by default) | Removed from planner architecture |

---

## Scope Exceptions

- `DeterministicDecisionVerifier` evidence-gating path: excluded from this redesign per spec. Its existing deterministic text heuristics remain a known out-of-scope issue tracked separately. No new deterministic NL heuristics were added to it as part of this redesign.

---

## Verification

- All capabilities classified above are covered by tests in:
  - `EgoPlannerTest` (26 tests)
  - `EgoAgentTest` (43 tests)
  - `AgentScenarioPackTest` (14 tests)
  - `HierarchicalPlannerAcceptanceTest` (48 tests)
- No capability is left unclassified.
