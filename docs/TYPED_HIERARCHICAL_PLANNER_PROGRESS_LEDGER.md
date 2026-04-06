# Typed Hierarchical Planner -- Progress Ledger

**Spec:** `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md`
**Plan:** `docs/TYPED_HIERARCHICAL_PLANNER_IMPLEMENTATION_PLAN.md`
**Branch:** `refactor/typed-planner-redesign`
**Started:** 2026-04-06

---

## Phase Tracking

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| 0 | Backup and prep | done | Package structure created |
| 1 | Typed intermediate models | done | 11 sealed types + LaneId |
| 2 | Shared planner runtime | done | PlannerRuntime, StructuredOutputHandler, TruncationRetry, DecisionValidation |
| 3 | Lane interface and config wiring | done | PlannerConfig + laneDefaults/lanes |
| 4 | Prompt assembly infrastructure | done | PromptProfile, PlannerPromptAssembler, SharedPromptSections |
| 5 | HierarchicalEgoPlanner (L0) | done | Typed trigger dispatch |
| 6 | InputPlanner + L2 sub-planners | done | InputIntentRouter + 5 L2 planners + InputPlanner L1 |
| 7 | GoalOperationActionPlugin refactor | done | Typed GoalCommand, removed text heuristics |
| 8 | Wire into Ego + remove action verifier | done | Phase 1 complete, HierarchicalEgoPlanner wired in AppModeRunners |
| 9 | DeferredStepPlanner | done | Dedicated lane with narrower prompt, supports defer/intend/plan/noop |
| 10 | FeedbackPlanner | done | Dedicated lane for action feedback interpretation |
| 11 | GoalWorkPlanner | done | Dedicated lane for goal-step execution |
| 12 | ImpulsePlanner | done | Dedicated lane for Id/self-motivated work |
| 13 | Delete backup + final cleanup | done | LlmEgoPlanner.kt deleted, MonolithicLaneStub.kt deleted, actionVerifierEnabled removed, tests migrated |
| 14 | Acceptance verification | done | All artifacts created, all tests pass, Freud signoff-gate PASS (4/4) |

---

## Step 13 Detailed Log

### Deletions
- `src/main/kotlin/ai/neopsyke/agent/ego/LlmEgoPlanner.kt` — deleted (2500+ lines, production dead code)
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/MonolithicLaneStub.kt` — deleted (Phase 2 stub, replaced by dedicated lanes)

### Removals
- `PlannerConfig.actionVerifierEnabled` — removed from data class
- `AgentRuntimeConfig.AgentRuntimeYamlPlanner.actionVerifierEnabled` — removed from YAML parser
- `config/agent-runtime.yaml: action_verifier_enabled` — removed from default config
- All test references to `actionVerifierEnabled` — removed

### Test Migration
- Created `src/test/kotlin/ai/neopsyke/agent/ego/LlmEgoPlanner.kt` — test-only shim that wraps `HierarchicalEgoPlanner` with the old constructor signature
- Created `src/test/kotlin/ai/neopsyke/support/TestPlannerFactory.kt` — `buildTestHierarchicalPlanner()` factory
- Updated `StubChatModelClient` — added default `input_intent_router` response returning `general_action`
- `EgoPlannerTest`: 15 action verifier tests deleted (removed feature), 4 goal regex routing tests deleted (replaced by LLM router), remaining tests updated for new callSite values and response schemas. 26 tests pass.
- `EgoAgentTest`: 8 tests updated for two-call pattern (router + sub-planner), plan response format, and defer-on-input architectural change. All 43 tests pass.
- `AgentScenarioPackTest`: 1 action verifier test deleted, 6 tests updated for new response routing (goal_creation, task_decomposition callSites). All tests pass.
- `AgentRuntimeSettingsLoaderTest`: removed `actionVerifierEnabled` assertion.

### Follow-Up Cleanup (Done in Step 14)
- `actionVerifierClient` `.use {}` blocks removed from AppModeRunners (both interactive + eval modes).
- `action_verifier` cognitive role removed from `LlmRuntimeConfig`, `LlmCognitiveRolesConfig`, YAML deserialization, validation.
- `LlmRoleLabels.ACTION_VERIFIER` constant and classify() branch removed.
- `EgoReasonCodes.ACTION_VERIFIER_REJECT_REASON_CODE` deleted; `shouldAllowRepeatedVerifierDisagreement()` dead code removed from `DecisionDispatcher`.
- `action_verifier` removed from all YAML configs, test fixtures, dashboard HTML, and all docs.

### Done in Step 14
- Broad text-heuristic audit completed: zero violations (`docs/PLANNER_TEXT_HEURISTIC_AUDIT.md`).
- Feature-parity inventory: `docs/PLANNER_PARITY_INVENTORY.md`
- Test mapping: `docs/PLANNER_TEST_MAPPING.md`
- 48 new acceptance tests: `HierarchicalPlannerAcceptanceTest.kt`
- Agent logic docs updated (stale action verifier / LlmEgoPlanner refs removed)
- Stale `action_verifier_repair` scenario removed from Freud manifest
- Freud signoff-gate: PASS 4/4

---

## Freud Gate Results

| Run | Timestamp | Result | Failing Steps | Notes |
|-----|-----------|--------|---------------|-------|
| 1 | 2026-04-06T03:17Z | PASS | none | After Phase 1 complete (Step 8) |
| 2 | 2026-04-06T03:22Z | PASS | none | After Phase 2 lanes (Steps 9-12) |
| 3 | 2026-04-06T07:53Z | PASS | none | Final deterministic gate |
| 4 | 2026-04-06T07:54Z | FAIL (live) | reasoning_eval_model | Superego model errors (not planner); 7/24 pass |
| 5 | 2026-04-06T12:34Z | FAIL | scenario_pack | Stale action_verifier_repair entry in scenario manifest |
| 6 | 2026-04-06T12:35Z | PASS | none | After Step 14 acceptance (manifest fixed, 48 new tests, all docs) |

---

## Bug Fixes During Redesign

| # | Description | Was Bug/Limitation | Details |
|---|-------------|-------------------|---------|
| 1 | Removed normalizeOperation() text heuristics | Limitation | Operation normalization was text-based; now typed planner |
| 2 | Removed looksLikeDeleteAllIntent() | Limitation | Keyword matching for delete_all; now typed GoalCommand |
| 3 | Removed resolveGoalId() fuzzy matching | Limitation | Case-insensitive title + token-overlap; now LLM-resolved |
| 4 | Removed shouldUseGoalCreationBranch() regex | Limitation | Regex-based routing; now LLM-based InputIntentRouter |
| 5 | Removed action verifier from planner path | Limitation | Disabled by default, rewrote planner outputs; removed per spec |

---

## Acceptance Criteria Status

| Rule | Description | Status | Evidence |
|------|-------------|--------|----------|
| 1 | Scope control | done | All behavioral changes documented in ledger + `docs/PLANNER_PARITY_INVENTORY.md` |
| 2 | Feature-parity inventory | done | `docs/PLANNER_PARITY_INVENTORY.md` — 21 preserved, 5 changed, 1 removed, 5 bugs fixed |
| 3 | Trigger-family coverage | done | 5 dedicated tests in `HierarchicalPlannerAcceptanceTest` (one per trigger family) |
| 4 | Decision-shape coverage | done | 14 positive + negative tests in `HierarchicalPlannerAcceptanceTest` |
| 5 | Constraint preservation | done | 12 constraint tests in `HierarchicalPlannerAcceptanceTest` (intentions, commit modes, actions, resolution-draft, passes) |
| 6 | No text-heuristic regression | done | Formal audit: `docs/PLANNER_TEXT_HEURISTIC_AUDIT.md` — zero violations |
| 7 | Goal-semantics acceptance | done | 14 goal tests in `HierarchicalPlannerAcceptanceTest` (all operations, ambiguous/unresolved refs, fallback) |
| 8 | Removed-action-verifier | done | No planner path references action verifier; tests prove it |
| 9 | Shared-runtime preservation | done | 6 shared-runtime tests in `HierarchicalPlannerAcceptanceTest` (circuit breaker, retry, telemetry, truncation) |
| 10 | Test-replacement | done | `docs/PLANNER_TEST_MAPPING.md` — 131 total tests, all deletions mapped |
| 11 | Documentation consistency | done | `AGENT_LOGIC_SUMMARY.md` + `AGENT_LOGIC_DIAGRAM.md` updated (stale refs removed) |
| 12 | Final verification | done | Freud signoff-gate PASS 4/4 (2026-04-06T12:35Z), 131 tests pass |
| 13 | Default failure rule | done | All capabilities classified in parity inventory |

---

## Architecture Note: AGENTS.md Update

Added "Gradle Concurrency (Critical)" section near the top of `AGENTS.md` to prevent agents from running parallel `./gradlew` commands in the same checkout. This was previously only documented deep in the Freud Workflow section.
