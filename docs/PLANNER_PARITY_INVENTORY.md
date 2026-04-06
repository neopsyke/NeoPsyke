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
| 2 | Deferred intention / continuation planning | Preserved (narrower) | `DeferredStepPlanner` | Dedicated lane with narrower prompt. Supports defer/intend/plan/noop. |
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
| 14 | Plan-step continuation semantics | Preserved | `DeferredStepPlanner` | Plan context, pass count, denial codes carried via typed `QueuedIntention` fields. |
| 15 | Resolution-draft gating | Preserved | `DeferredStepPlanner` | `allowResolutionDraft = thought.planContext != null` check. Only in DeferredStep lane. |
| 16 | Structured-output retry / recovery | Preserved | `PlannerRuntime` | Retry loop with strict-to-relaxed schema fallback. Shared across all lanes. |
| 17 | Planner output repair | Preserved | `StructuredOutputHandler` + `PlannerRuntime` | Invalid JSON escape repair, bare URL wrapping, missing summary synthesis. |
| 18 | Planner telemetry | Preserved | `PlannerRuntime` + `HierarchicalEgoPlanner` | `planner_start`, `planner_lane_selected`, `planner_decision`, `prompt_budget_allocation`. |
| 19 | Prompt-budget allocation telemetry | Preserved | `PlannerRuntime.emitPromptBudgetTelemetry()` | Emitted per-lane with lane-specific call_site. |
| 20 | Circuit-breaker behavior | Preserved | `PlannerRuntime` (per-lane, per-rootInputId) | 3 consecutive parse failures trip the breaker. |
| 21 | Truncation retry | Preserved | `TruncationRetry` (shared utility) | Detects `finish_reason=length` or unclosed JSON, bumps token budget. |

---

## Intentionally Changed Capabilities

| # | Capability | Classification | Details |
|---|-----------|---------------|---------|
| C1 | Goal-operation normalization | Intentionally changed | Was deterministic `normalizeOperation()` text heuristics. Now LLM-resolved typed `GoalCommand`. |
| C2 | Goal-ID resolution | Intentionally changed | Was `resolveGoalId()` with case-insensitive title matching and token-overlap scoring. Now LLM-resolved `GoalReference` types. |
| C3 | Goal-creation routing | Intentionally changed | Was `shouldUseGoalCreationBranch()` regex. Now LLM-based `InputIntentRouter`. |
| C4 | Delete-all intent detection | Intentionally changed | Was `looksLikeDeleteAllIntent()` keyword matching. Now LLM-resolved `GoalCommand.DeleteAll`. |
| C5 | Input routing | Intentionally changed | Was single monolithic planner call deciding everything. Now two-call pattern: `InputIntentRouter` classifies, then L2 sub-planner decides. |

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
