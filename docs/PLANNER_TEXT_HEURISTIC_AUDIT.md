# Planner Text-Heuristic Audit

**Spec:** `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md` (Acceptance Rule 6)
**Branch:** `refactor/typed-planner-redesign`
**Audit date:** 2026-04-06

---

## Scope

All files under:
- `src/main/kotlin/ai/neopsyke/agent/ego/planner/` (33 files)
- `src/main/kotlin/ai/neopsyke/agent/cortex/motor/actions/plugin/builtin/GoalOperationActionPlugin.kt`

## Rule

> NO DETERMINISTIC ROUTING MUST BE DONE AT ANY STAGE ON ANY NATURAL LANGUAGE OR TEXT INPUT.

## Result: FULLY COMPLIANT -- Zero Violations

---

## Text Processing Patterns Found (All Allowed)

### 1. Enum Parsing (7 locations)

Converts LLM-produced canonical values to typed enums via `lowercase()`/`uppercase()`.
Examples: `"defer"` -> decision branch, `"observe"` -> `IntentionKind.OBSERVE`.

Files: `DecisionValidation.kt`, `ContinuationPlanner.kt`, `FeedbackPlanner.kt`,
`GoalWorkPlanner.kt`, `ImpulsePlanner.kt`, `GoalCreationPlanner.kt`, `GoalManagementPlanner.kt`

**Verdict:** Allowed. Matching enum values, not interpreting NL.

### 2. LLM Output Routing (4 locations)

Dispatches on typed values from JSON-schema-constrained LLM structured output.
Example: `InputIntentRouter` routes on `payload.route` (one of 7 canonical strings).

Files: `InputIntentRouter.kt`, `InputPlanner.kt`, `GoalManagementPlanner.kt`, `GoalCreationPlanner.kt`

**Verdict:** Allowed. Semantic interpretation happens at the LLM layer. Post-LLM dispatch is on typed results.

### 3. JSON Repair (2 locations)

`StructuredOutputHandler.repairInvalidJsonEscapes()` removes invalid backslash sequences.
`TruncationRetry.isLikelyTruncated()` checks `finishReason` and JSON completeness.

**Verdict:** Allowed. Structural repair, not semantic routing.

### 4. Whitespace Normalization (2 locations)

`GeneralActionPlanner` and `DirectResponsePlanner` normalize whitespace in synthesized summaries.

**Verdict:** Allowed. Display formatting, not routing.

### 5. Goal ID Resolution (1 location)

`GoalOperationActionPlugin.resolveGoalIdTyped()` matches exact ID or numeric index.
No case-insensitive title matching, fuzzy matching, or token-overlap scoring.

**Verdict:** Allowed. Typed matching only.

---

## Patterns Explicitly Absent

- Regex-based intent classification on user text
- Keyword matching / substring checks for routing
- Token-overlap scoring
- Case-insensitive title matching for semantic resolution
- Deterministic locale-sensitive text normalization for routing
- `contains()` / `startsWith()` / `endsWith()` on user input for classification

---

## Removed Heuristics (verified deleted)

| Heuristic | Was In | Status |
|-----------|--------|--------|
| `shouldUseGoalCreationBranch()` (regex) | `LlmEgoPlanner` | Deleted with `LlmEgoPlanner.kt` |
| `normalizeOperation()` (text normalization) | `GoalOperationActionPlugin` | Replaced by `resolveOperation()` on typed `command` field |
| `looksLikeDeleteAllIntent()` (keyword matching) | `GoalOperationActionPlugin` | Replaced by LLM-resolved `GoalCommand.DeleteAll` |
| `resolveGoalId()` (fuzzy title matching + token-overlap) | `GoalOperationActionPlugin` | Replaced by `resolveGoalIdTyped()` (exact ID / numeric index) |

---

## Scoped Exception

`DeterministicDecisionVerifier` evidence-gating path is excluded from this audit
per spec. Its existing deterministic text heuristics remain a known out-of-scope
issue tracked separately. No new deterministic NL heuristics were added to it
as part of this redesign.
