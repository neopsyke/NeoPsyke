# Planner Test Mapping

**Spec:** `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md` (Acceptance Rule 10)
**Branch:** `refactor/typed-planner-redesign`

---

## Test Replacement Rule

Every removed planner test maps to an equivalent new test, a higher-level
integration/scenario test, or an explicit intentional-removal note.

---

## Deleted Tests and Their Replacements

### Action Verifier Tests (15 deleted from EgoPlannerTest — intentionally removed)

Action verifier was removed from the planner architecture per spec (Rule 8).
These tests verified a feature that no longer exists.

| Old Test | Replacement | Rationale |
|----------|------------|-----------|
| `planner calls action verifier for non-contact actions` | None (intentional removal) | Action verifier removed per spec |
| `planner skips action verifier for contact_user actions` | None (intentional removal) | Action verifier removed per spec |
| `planner skips action verifier when disabled` | None (intentional removal) | `actionVerifierEnabled` config removed |
| `action verifier rejection converts to noop with denied context` | None (intentional removal) | Action verifier removed per spec |
| `action verifier rewrite is applied to final action` | None (intentional removal) | Action verifier removed per spec |
| `action verifier parse failure falls back to original action` | None (intentional removal) | Action verifier removed per spec |
| `action verifier respects retry attempts` | None (intentional removal) | Action verifier removed per spec |
| `action verifier timeout falls back to original action` | None (intentional removal) | Action verifier removed per spec |
| `action verifier schema is correct` | None (intentional removal) | Action verifier removed per spec |
| `action verifier includes action context in prompt` | None (intentional removal) | Action verifier removed per spec |
| `action verifier includes security context in prompt` | None (intentional removal) | Action verifier removed per spec |
| `action verifier includes opportunity context in prompt` | None (intentional removal) | Action verifier removed per spec |
| `action verifier denial preserves urgency` | None (intentional removal) | Action verifier removed per spec |
| `action verifier uses independent model client` | None (intentional removal) | Action verifier removed per spec |
| `action verifier disabled by default` | None (intentional removal) | Action verifier removed per spec |

### Goal Regex Routing Tests (4 deleted from EgoPlannerTest — replaced by LLM routing)

Goal routing moved from regex-based (`shouldUseGoalCreationBranch()`) to
LLM-based (`InputIntentRouter`). Old deterministic tests are replaced by
typed route tests.

| Old Test | Replacement | Location |
|----------|------------|----------|
| `planner routes to goal creation branch on goal regex` | `goal creation with cron produces typed GoalCommand Create payload` | HierarchicalPlannerAcceptanceTest |
| `planner routes to goal creation branch for recurring phrases` | `goal creation with cron produces typed GoalCommand Create payload` | HierarchicalPlannerAcceptanceTest |
| `planner does not route non-goal input to goal branch` | `goal creation fallback response delivers assistant message` | HierarchicalPlannerAcceptanceTest |
| `goal creation branch normalizes cron expression` | `goal creation with cron produces typed GoalCommand Create payload` | HierarchicalPlannerAcceptanceTest |

### Action Verifier Scenario Test (1 deleted from AgentScenarioPackTest — intentionally removed)

| Old Test | Replacement | Rationale |
|----------|------------|-----------|
| `scenario_action_verifier_rewrites_unsafe_action` | None (intentional removal) | Action verifier removed per spec |

---

## Migrated Tests (updated for new architecture)

### EgoPlannerTest (26 tests, all updated)

Tests exercise the `LlmEgoPlanner` test shim which delegates to
`HierarchicalEgoPlanner`. All tests pass against the new architecture.

Key changes to existing tests:
- `callSite` values changed: `"planner"` -> lane-specific (`"deferred_step"`, `"general_action"`, etc.)
- Two-call pattern: input triggers now go through `input_intent_router` then sub-planner
- Response schemas reflect lane-specific formats
- `StubChatModelClient` default `input_intent_router` response returns `general_action`
- Plan response format uses `plan_goal`/`plan_steps` (unchanged from monolithic format)

### EgoAgentTest (43 tests, 8 updated)

End-to-end integration tests. Key changes:
- Router + sub-planner two-call pattern accounted for
- Plan response format adapted
- Defer-on-input now routes through DeferredStepPlanner
- Goal creation routes through `goal_creation` callSite

### AgentScenarioPackTest (14 tests, 6 updated)

Scenario tests. Key changes:
- Goal creation scenarios use `goal_creation` callSite
- Task decomposition uses `task_decomposition` callSite
- Direct answer scenarios route through `direct_response`

---

## New Tests Added

### HierarchicalPlannerAcceptanceTest (48 new tests)

Covers spec acceptance rules 3, 4, 5, 7, and 9:

**Rule 3 — Trigger-Family Coverage (5 tests):**
- `IncomingInput trigger routes to InputPlanner and returns decision`
- `DeferredIntention trigger routes to DeferredStepPlanner`
- `ActionFeedback trigger routes to FeedbackPlanner`
- `GoalWork trigger routes to GoalWorkPlanner`
- `IncomingImpulse trigger routes to ImpulsePlanner`

**Rule 4 — Decision-Shape Coverage (14 tests, positive + negative pairs):**
- Direct terminal response (positive + negative)
- Deferred continuation (positive + negative)
- Explicit action (positive + negative)
- Multi-step plan (positive + negative)
- Goal creation (positive)
- Goal management (positive)
- Clarification request (positive)
- Noop route (negative)

**Rule 5 — Constraint Preservation (12 tests):**
- Allowed intentions enforcement (DeferredStep, Feedback, GoalWork)
- Allowed commit modes enforcement
- Available actions enforcement (Feedback, GoalWork, Impulse)
- Resolution-draft gating (blocked outside plan context, allowed within)
- Max thought passes
- Impulse plan decision rejection

**Rule 7 — Goal-Semantics Acceptance (14 tests):**
- Goal creation with cron
- Goal list, pause, resume, complete, delete, delete_all
- Goal update with params, reprioritize, revise_plan
- Ambiguous goal reference -> clarification
- Unresolved goal reference -> clarification
- Goal creation fallback response

**Rule 9 — Shared-Runtime Preservation (6 tests):**
- Circuit breaker trips after repeated parse failures
- Retry policy respects configured attempts
- Telemetry emits planner_start, planner_lane_selected, planner_decision
- Prompt budget telemetry per lane
- Structured output repair callback
- Truncation retry with bumped token budget

---

## Test Coverage Summary

| Test File | Count | Status |
|-----------|-------|--------|
| EgoPlannerTest | 26 | All pass |
| EgoAgentTest | 43 | All pass |
| AgentScenarioPackTest | 14 | All pass |
| HierarchicalPlannerAcceptanceTest | 48 | All pass |
| **Total** | **131** | **All pass** |

No test was deleted without replacement or explicit intentional-removal justification.
