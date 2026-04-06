# Typed Hierarchical Planner -- Implementation Plan

**Spec:** `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md`
**Branch:** `refactor/typed-planner-redesign`
**Date:** 2026-04-06

---

## Decision Log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Cover both Phase 1 and Phase 2 in one plan | Owner decision |
| D2 | Hard replacement, no config-gated parallel path | Cleanest long-term architecture; no backwards-compat shims |
| D3 | Backup `LlmEgoPlanner` as dead code during implementation, delete after completion | Reference during migration, then cleanup |
| D4 | Refactor `GoalOperationActionPlugin` inline to accept typed `GoalCommand` | Clean break, no adapter layer |
| D5 | `DeterministicDecisionVerifier` (evidence-gating) stays untouched and is explicitly excluded from this redesign's acceptance scope | Architecturally distinct from the removed action verifier; redesign tracked separately in planned features |
| D6 | YAML config: `planner.lane_defaults` + `planner.lanes.<name>` overrides; Kotlin backend: map-based registry with typed `LaneConfig` | Human-friendly YAML, scalable Kotlin internals |
| D7 | L1 lanes return `EgoDecision` directly; no shared `PlannerOutcome` wrapper | Each L1 lane owns the mapping from its typed decision (`StepDecision`, `FeedbackDecision`, etc.) to `EgoDecision` internally. This avoids a gratuitous wrapper while keeping lane-specific sealed types as the typed intermediate results. |
| D8 | `PlanStepDirective` absorbed into `StepDecision.Execute(candidate: ExecutionCandidate)` | A separate `PlanStepDirective` type is unnecessary because `StepDecision.Execute` already carries the execution directive with typed metadata. |
| D9 | No parallel legacy planner path and no backwards-compatibility shims. Full type-first breaks are preferred. Serialized boundary payloads are allowed only where they remain part of the intended architecture boundary and must be generated directly from typed results. | Keeps the architecture clean while distinguishing real runtime boundaries from migration adapters |
| D10 | Action verifier removal happens in Phase 1 (Step 8), not deferred to after Phase 2 | Aligns with spec's "Recommended Delivery Phases > Phase 1" which lists verifier removal. |

---

## Package Structure

All new planner code lives under `ai.neopsyke.agent.ego.planner`.

```
agent/ego/planner/
    HierarchicalEgoPlanner.kt        -- L0 entry point (implements Ego.Planner)
    PlannerLane.kt                    -- Lane interface + LaneId enum
    LaneConfig.kt                     -- Per-lane LLM config data class

    runtime/
        PlannerRuntime.kt             -- Shared model-call, retry, circuit-breaker, telemetry
        StructuredOutputHandler.kt    -- JSON schema registry, parse, repair, escape fixing
        TruncationRetry.kt           -- Truncation detection + token-bump retry logic

    lane/
        InputPlanner.kt               -- L1: fresh user input
        DeferredStepPlanner.kt        -- L1: deferred continuations / plan-step execution
        FeedbackPlanner.kt            -- L1: action feedback
        GoalWorkPlanner.kt            -- L1: goal-runtime work
        ImpulsePlanner.kt             -- L1: Id/self-motivated work

    input/
        InputIntentRouter.kt          -- L2: semantic router returning InputRoute
        DirectResponsePlanner.kt      -- L2: terminal answer / clarification
        GeneralActionPlanner.kt       -- L2: single action intention
        TaskDecompositionPlanner.kt   -- L2: multi-step plan decomposition
        GoalCreationPlanner.kt        -- L2: semantic goal creation
        GoalManagementPlanner.kt      -- L2: semantic operations on existing goals

    model/
        InputRoute.kt                 -- Typed routing result from InputIntentRouter
        StepDecision.kt               -- Typed result from DeferredStepPlanner
        FeedbackDecision.kt           -- Typed result from FeedbackPlanner
        GoalWorkDecision.kt           -- Typed result from GoalWorkPlanner
        ImpulseDecision.kt            -- Typed result from ImpulsePlanner
        GoalCommand.kt                -- Typed goal command sealed hierarchy
        GoalReference.kt              -- Typed goal reference sealed hierarchy
        PlanDecomposition.kt          -- Typed multi-step plan structure
        ExecutionCandidate.kt         -- Typed single-action candidate
        ClarificationRequest.kt       -- Typed clarification request
        LaneRoutingResult.kt          -- Typed re-routing result for ambiguous cases

    prompt/
        PlannerPromptAssembler.kt     -- Base prompt assembly using PromptBudgetAllocator
        InputPromptProfile.kt         -- Section definitions for InputPlanner lanes
        DeferredStepPromptProfile.kt  -- Section definitions for DeferredStepPlanner
        FeedbackPromptProfile.kt      -- Section definitions for FeedbackPlanner
        GoalWorkPromptProfile.kt      -- Section definitions for GoalWorkPlanner
        ImpulsePromptProfile.kt       -- Section definitions for ImpulsePlanner
```

---

## YAML Configuration Shape

```yaml
planner:
  # Existing fields preserved (maxLoopStepsPerInput, maxThoughtPasses, etc.)

  lane_defaults:
    temperature: 0.2
    max_completion_tokens: 1200
    retry_attempts: 3
    structured_output: strict       # strict | relaxed | off
    # provider and model are omitted = inherit from global model client

  lanes:
    input_intent_router:
      temperature: 0.1
      max_completion_tokens: 400
    direct_response:
      # inherits lane_defaults
    general_action:
      # inherits lane_defaults
    task_decomposition:
      max_completion_tokens: 1400
    goal_creation:
      temperature: 0.0
      max_completion_tokens: 400
    goal_management:
      temperature: 0.0
      max_completion_tokens: 400
    deferred_step:
      # inherits lane_defaults
    feedback:
      max_completion_tokens: 800
    goal_work:
      max_completion_tokens: 800
    impulse:
      # inherits lane_defaults
```

In Kotlin, `PlannerConfig` gets:

```kotlin
/** Structured output mode for a lane's LLM response format. */
enum class StructuredOutputMode { STRICT, RELAXED, OFF }

data class LaneConfig(
    val provider: String? = null,           // null = use default client
    val model: String? = null,              // null = use default client
    val temperature: Double? = null,        // null = use lane_defaults
    val maxCompletionTokens: Int? = null,
    val retryAttempts: Int? = null,
    val structuredOutput: StructuredOutputMode? = null,  // strict/relaxed/off
)

// In PlannerConfig:
val laneDefaults: LaneConfig = LaneConfig()
val lanes: Map<String, LaneConfig> = emptyMap()
```

Lanes are keyed by `LaneId.configKey` (snake_case string matching the YAML key).
`PlannerRuntime` resolves effective config per lane: `lane override -> laneDefaults -> hardcoded fallback`.

When `structuredOutput` is `STRICT`, the runtime sends the strict JSON schema and falls back to `RELAXED`
(relaxed schema, no `maxLength` constraints) on first parse failure, matching current behavior.
When `OFF`, no `responseFormat` is sent (free-text response expected).

---

## Implementation Steps

### Step 0: Backup and Prep

**Goal:** Preserve `LlmEgoPlanner` as dead-code reference. Set up package structure.

1. Copy `LlmEgoPlanner.kt` to `agent/ego/LlmEgoPlanner.backup.kt.bak` (excluded from compilation).
2. Create the `agent/ego/planner/` package directory tree.
3. Create empty marker files for all new source files so the structure is visible.

**Validation:** Project compiles, tests pass (no behavioral change yet).

---

### Step 1: Typed Intermediate Models

**Goal:** Define all typed planner communication structures before any planner logic.

**Files to create under `agent/ego/planner/model/`:**

1. **`GoalCommand.kt`** -- Sealed interface with variants:
   - `GoalCommand.Create(title, instruction, priority, completionCriteria, cronExpression?)`
   - `GoalCommand.List`
   - `GoalCommand.Status(reference: GoalReference)`
   - `GoalCommand.Pause(reference: GoalReference)`
   - `GoalCommand.Resume(reference: GoalReference)`
   - `GoalCommand.Complete(reference: GoalReference)`
   - `GoalCommand.Delete(reference: GoalReference)`
   - `GoalCommand.DeleteAll`
   - `GoalCommand.Update(reference: GoalReference, title?, instruction?, priority?, completionCriteria?, cronExpression?)`
   - `GoalCommand.RevisePlan(reference: GoalReference, reason?)`
   - `GoalCommand.Reprioritize(reference: GoalReference, newPriority)`

2. **`GoalReference.kt`** -- Sealed interface:
   - `GoalReference.ByInternalId(id: String)`
   - `GoalReference.ByResolvedEntity(goalId: String, resolvedFrom: String)` -- LLM resolved a natural-language reference
   - `GoalReference.Ambiguous(candidates: List<String>, originalText: String)`
   - `GoalReference.Unresolved(originalText: String)`

3. **`InputRoute.kt`** -- Sealed interface:
   - `InputRoute.DirectResponse(reasoning: String)`
   - `InputRoute.GeneralAction(reasoning: String)`
   - `InputRoute.MultiStepTask(reasoning: String)`
   - `InputRoute.GoalCreation(reasoning: String)`
   - `InputRoute.GoalManagement(reasoning: String)`
   - `InputRoute.ClarificationNeeded(question: String)`
   - `InputRoute.Noop(reason: String)`

4. **`StepDecision.kt`** -- Sealed interface:
   - `Execute`, `RefinePlan`, `SkipStep`, `Answer`, `Defer`, `Clarify`, `Fail`

5. **`FeedbackDecision.kt`** -- Sealed interface:
   - `Answer`, `Retry`, `NextStep`, `Defer`, `MarkBlocked`, `MarkDone`

6. **`GoalWorkDecision.kt`** -- Sealed interface:
   - `ExecuteStep`, `DeferUntilCondition`, `MarkStepComplete`, `RequestClarification`, `FailStep`

7. **`ImpulseDecision.kt`** -- Sealed interface:
   - `Research`, `Reflect`, `ContactUser`, `Noop`

8. **`PlanDecomposition.kt`** -- Data class:
   - `goal: String, steps: List<PlanStep>`
   - `PlanStep(description: String, expectedActionType: ActionType?)`

9. **`ExecutionCandidate.kt`** -- Data class:
   - `intentionKind, commitModePreference, actionType, payload, summary`

10. **`ClarificationRequest.kt`** -- Data class:
    - `question: String, context: String?`

11. **`LaneRoutingResult.kt`** -- Sealed interface for lane-to-lane re-routing:
    - `LaneRoutingResult.Resolved(decision: EgoDecision)` -- lane produced a final decision
    - `LaneRoutingResult.Reroute(targetLane: LaneId, context: Map<String, Any>)` -- lane determined the next step is ambiguous and another lane should handle it
    - This satisfies the spec requirement (line 105): "A lane may itself return another typed routing result when the next step is semantically ambiguous." In practice, `InputPlanner` uses this via `InputIntentRouter` -> sub-planner dispatch. Other lanes may use `Reroute` in future if their LLM call determines the trigger was mis-classified. For now, only `InputPlanner` actively uses the re-routing path; other L1 lanes return `Resolved` only.

**Validation:** Project compiles. No wiring yet -- these are standalone data types.

---

### Step 2: Shared Planner Runtime

**Goal:** Extract reusable planner infrastructure from `LlmEgoPlanner` into shared components.

**Files to create under `agent/ego/planner/runtime/`:**

1. **`PlannerRuntime.kt`**
   - Owns: model-call execution, retry loop, circuit-breaker tracking, telemetry emission, prompt-budget telemetry.
   - Extracts from: `callPlanner()`, `callActionVerifierModel()`, retry loops, circuit-breaker maps.
   - **Prompt-budget telemetry:** After every `PromptBudgetAllocator.allocate()` call, the runtime emits a `planner_prompt_budget` instrumentation event carrying `Diagnostics.toTelemetryData(callSite)` with the `laneId` as the call site. This preserves the current prompt-budget telemetry behavior and extends it with per-lane granularity.
   - Interface:
     ```kotlin
     class PlannerRuntime(
         private val defaultModelClient: ChatModelClient,
         private val config: PlannerConfig,
         private val instrumentation: AgentInstrumentation,
     ) {
         fun call(
             laneId: LaneId,
             messages: List<ChatMessage>,
             options: ChatRequestOptions,
             modelClient: ChatModelClient? = null, // override per lane
         ): ChatCompletion

         fun resolvedConfig(laneId: LaneId): ResolvedLaneConfig

         fun emitPromptBudgetTelemetry(laneId: LaneId, diagnostics: PromptBudgetAllocator.Diagnostics)

         fun recordParseFailure(laneId: LaneId, rootInputId: String)
         fun isCircuitOpen(laneId: LaneId, rootInputId: String): Boolean
         fun resetCircuit(laneId: LaneId, rootInputId: String)
     }
     ```

2. **`StructuredOutputHandler.kt`**
   - Owns: JSON schema definitions per lane, response parsing, escape repair, payload normalization.
   - Extracts from: `parsePayloadWithRepair()`, `repairInvalidJsonEscapes()`, `normalizeActionPayload()`, response format constants.
   - Each lane registers its own output schema via a `SchemaRegistry` pattern.
   - Interface:
     ```kotlin
     object StructuredOutputHandler {
         fun <T> parseWithRepair(
             raw: String,
             targetType: KClass<T>,
             onRepair: () -> Unit = {},
         ): T?

         fun responseFormat(laneId: LaneId): ChatResponseFormat.JsonSchema
     }
     ```

3. **`TruncationRetry.kt`**
   - Owns: truncation detection heuristic, token-bump calculation, retry-with-higher-budget logic.
   - Extracts from: `requestPlannerTruncationRetry()`, truncation constants.

**Validation:** Compiles. Not yet wired -- `LlmEgoPlanner` still runs unchanged.

---

### Step 3: Lane Interface and Config Wiring

**Goal:** Define the lane abstraction and wire per-lane config into `PlannerConfig`.

1. **`PlannerLane.kt`**
   ```kotlin
   enum class LaneId(val configKey: String) {
       INPUT_INTENT_ROUTER("input_intent_router"),
       DIRECT_RESPONSE("direct_response"),
       GENERAL_ACTION("general_action"),
       TASK_DECOMPOSITION("task_decomposition"),
       GOAL_CREATION("goal_creation"),
       GOAL_MANAGEMENT("goal_management"),
       DEFERRED_STEP("deferred_step"),
       FEEDBACK("feedback"),
       GOAL_WORK("goal_work"),
       IMPULSE("impulse"),
   }

   interface PlannerLane<TriggerT, ResultT> {
       val laneId: LaneId
       fun plan(trigger: TriggerT, context: PlannerContext): ResultT
   }
   ```

2. **`LaneConfig.kt`** -- As described in YAML section above.

3. **Update `PlannerConfig`** -- Add `laneDefaults: LaneConfig` and `lanes: Map<String, LaneConfig>` fields.

4. **Update `AgentConfig` deserialization** -- Ensure the new YAML shape deserializes into `PlannerConfig`.

**Validation:** Compiles, existing tests pass, config deserialization handles new fields with defaults.

---

### Step 4: Prompt Assembly Infrastructure

**Goal:** Create per-lane prompt assembly that reuses `PromptBudgetAllocator` but with lane-specific section definitions.

1. **`PlannerPromptAssembler.kt`**
   - Shared logic: converts a `PromptProfile` + `PlannerContext` + trigger into `PromptBudgetAllocator.Section` list.
   - Each lane provides its own `PromptProfile` that declares which sections to include, their bands, importance, and floor tokens.
   - The assembler calls `PromptBudgetAllocator.allocate()` and returns messages + diagnostics.

2. **`PromptProfile` interface:**
   ```kotlin
   interface PromptProfile {
       fun sections(trigger: EgoTrigger, context: PlannerContext): List<PromptBudgetAllocator.Section>
   }
   ```

3. Create initial profile stubs for each lane (filled in during lane implementation steps).

**Validation:** Compiles. Profiles are empty stubs.

---

### Step 5: HierarchicalEgoPlanner (L0)

**Goal:** Create the top-level entry point that replaces `LlmEgoPlanner` behind `Ego.Planner`.

1. **`HierarchicalEgoPlanner.kt`**
   ```kotlin
   class HierarchicalEgoPlanner(
       private val runtime: PlannerRuntime,
       private val config: AgentConfig,
       private val instrumentation: AgentInstrumentation,
       // L1 lanes injected:
       private val inputPlanner: InputPlanner,
       private val deferredStepPlanner: DeferredStepPlanner,
       private val feedbackPlanner: FeedbackPlanner,
       private val goalWorkPlanner: GoalWorkPlanner,
       private val impulsePlanner: ImpulsePlanner,
   ) : Ego.Planner {

       override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
           instrumentation.emit("planner_start", ...)
           val decision = when (trigger) {
               is EgoTrigger.IncomingInput    -> inputPlanner.plan(trigger, context)
               is EgoTrigger.DeferredIntention -> deferredStepPlanner.plan(trigger, context)
               is EgoTrigger.ActionFeedback   -> feedbackPlanner.plan(trigger, context)
               is EgoTrigger.GoalWork         -> goalWorkPlanner.plan(trigger, context)
               is EgoTrigger.IncomingImpulse  -> impulsePlanner.plan(trigger, context)
           }
           instrumentation.emit("planner_decision", ...)
           return decision
       }

       override fun resetForInput(rootInputId: String) { ... }
   }
   ```

2. **Routing is purely typed:** `when (trigger)` dispatches on the sealed interface variant. No text inspection. This is deterministic routing on typed runtime metadata (the trigger sealed-class variant), which is explicitly allowed by the mandatory routing rule.

3. **Each L1 lane returns `EgoDecision` directly** (decision D7). The L0 orchestrator does not reinterpret. Each L1 lane owns the mapping from its lane-specific typed decision (`StepDecision`, `FeedbackDecision`, etc.) to `EgoDecision`. Lane-specific sealed types serve as the typed intermediate results; no shared wrapper is introduced.

4. **`resetForInput(rootInputId)`:** Delegates to `PlannerRuntime.resetAllCircuits(rootInputId)` to clear per-lane circuit breaker state, matching current `LlmEgoPlanner` behavior. Also calls `resetForInput` on any L1 lane that holds per-input state (initially none, but the contract is established).

**At this point:** `HierarchicalEgoPlanner` exists but L1 lanes are stubs. It is not yet wired into `Ego`.

---

### Step 6: InputPlanner + InputIntentRouter (Phase 1 Core)

**Goal:** Implement the fresh-input planning path end-to-end.

#### 6a: InputIntentRouter (L2)

- Receives `EgoTrigger.IncomingInput` + `PlannerContext`.
- Makes a single LLM call with a narrow prompt: "classify this user input into one of these route types."
- Returns `InputRoute` (sealed type).
- Prompt profile: system instructions (role + route definitions), recent dialogue, short-term context, action availability summary, trigger text.
- JSON schema for output: `{ "route": "direct_response|general_action|multi_step_task|goal_creation|goal_management|clarification|noop", "reasoning": "..." }`
- **Ambiguity policy:** If the router cannot confidently distinguish between materially different routes, it must return `InputRoute.ClarificationNeeded` with a targeted question. It must not silently fall back to `GeneralAction` just because that path is broad or flexible.

#### 6b: DirectResponsePlanner (L2)

- Receives `InputRoute.DirectResponse` + original trigger + context.
- Makes an LLM call with narrow prompt focused on generating a terminal answer.
- Returns `EgoDecision.FormIntention` with `CONTACT_USER` action type, or `EgoDecision.EnqueueThought` if more context needed.
- Prompt profile: system instructions (direct-answer rules), recent dialogue, short-term context, long-term memory, evidence hints, trigger text.

#### 6c: GeneralActionPlanner (L2)

- Receives `InputRoute.GeneralAction` + original trigger + context.
- Makes an LLM call with narrow prompt focused on selecting one action.
- Returns `EgoDecision.FormIntention`.
- Prompt profile includes: action definitions with payload guidance, available/dispatchable actions, allowed intentions, allowed commit modes, full context sections.
- JSON schema: same fields as current `EgoDecisionPayload` but only `intend` decision type.
- Preserves: intention kind validation, commit mode validation, action type availability check, action payload repair, summary synthesis.
- **Constraint shaping:** Injects `allowedIntentions`, `allowedCommitModes`, `dispatchableActions` from `PlannerContext` into the prompt and validates the LLM's output against them. If the LLM returns an intention/commit-mode/action-type not in the allowed set, returns `Noop` (matching current behavior). This is the primary enforcement point for allowed-intention shaping, allowed-commit-mode shaping, and action-availability shaping across the InputPlanner path.

#### 6d: TaskDecompositionPlanner (L2)

- Receives `InputRoute.MultiStepTask` + original trigger + context.
- Makes an LLM call focused on plan decomposition.
- Returns `EgoDecision.EnqueuePlan` with typed `PlanDecomposition`.
- JSON schema: `{ "goal": "...", "steps": [{"description": "...", "expected_action_type": "..."}] }`
- Preserves: max plan steps, max step description chars.

#### 6e: GoalCreationPlanner (L2)

- Receives `InputRoute.GoalCreation` + original trigger + context.
- Makes an LLM call focused on extracting goal creation parameters.
- Returns `EgoDecision.FormIntention` with action type `GOAL_OPERATION` and typed `GoalCommand.Create` serialized as payload.
- JSON schema: `{ "title": "...", "instruction": "...", "priority": "...", "completion_criteria": "...", "cron_expression": "..." }`
- **No regex heuristics.** Recurring intent, goal parameters -- all resolved by the LLM.
- Preserves: title/instruction/criteria char limits as named constants.

#### 6f: GoalManagementPlanner (L2)

- Receives `InputRoute.GoalManagement` + original trigger + context.
- Makes an LLM call that determines the goal operation and resolves the goal reference.
- Receives active goals list in prompt context for reference resolution.
- Returns `EgoDecision.FormIntention` with typed `GoalCommand.*` variant serialized as payload.
- JSON schema: `{ "operation": "...", "goal_reference": {"type": "by_id|by_resolved|ambiguous|unresolved", ...}, "params": {...} }`
- **No text heuristics for goal ID resolution.** The LLM resolves references; execution validates.

#### 6g: InputPlanner (L1)

- Orchestrates: calls `InputIntentRouter`, then dispatches to the appropriate L2 sub-planner.
- Translates L2 results into `EgoDecision`.
- Handles `InputRoute.ClarificationNeeded` by returning `EgoDecision.FormIntention` with `CONTACT_USER`.
- Handles `InputRoute.Noop` by returning `EgoDecision.Noop`.
- **Dispatch from `InputRoute` to sub-planner is deterministic on a typed result from an LLM call.** This is allowed by the mandatory routing rule because the routing is over a typed `InputRoute` variant (structured metadata), not over natural-language text.

#### Two-call pattern justification

The `InputPlanner` path makes two LLM calls: `InputIntentRouter` (classify) then a sub-planner (decide). This is consistent with the spec's principle "One semantic interpretation pass per domain boundary whenever possible" (line 82) because:
- The router and the sub-planner operate at different domain boundaries: the router classifies intent (broad), the sub-planner makes a specific decision (narrow).
- The sub-planner does not re-classify intent; it receives a typed route and decides within that scope.
- The two calls are not redundant interpretations of the same text.

#### GoalManagement clarification policy

The spec's open question "Which goal-management operations should require clarification instead of best-effort resolution?" is resolved as follows:
- **Always clarify:** `GoalReference.Ambiguous` (multiple matching goals) and `GoalReference.Unresolved` (no matching goal). These return a `ClarificationRequest` or fail with a user-facing message at execution time.
- **Best-effort resolve:** All other references where the LLM is confident in resolution. The LLM may still return `Ambiguous` or `Unresolved` if uncertain.
- **Never silently guess:** If the LLM cannot resolve a goal reference with high confidence, it must signal ambiguity rather than pick one. This is enforced by prompt design: the goal management prompt instructs the LLM to return `ambiguous` or `unresolved` reference types rather than hallucinating an ID.

**Validation after Step 6:**
- Unit tests for each L2 planner with mocked `ChatModelClient`.
- Unit tests for `InputPlanner` routing logic.
- Tests for `InputIntentRouter` with various input types.
- Tests proving no text heuristics in the routing path.

---

### Step 7: GoalOperationActionPlugin Refactor

**Goal:** Make the goal execution plugin a validator/executor over typed `GoalCommand`, removing text heuristics.

1. **Remove `normalizeOperation()`** -- operation normalization ("inspect" -> "status", "revise" -> "revise_plan", etc.) is no longer needed. The planner emits canonical `GoalCommand` variants.

2. **Remove `looksLikeDeleteAllIntent()`** -- deterministic text heuristic, eliminated.

3. **Remove `resolveGoalId()` fuzzy matching** -- case-insensitive title match and token-overlap scoring are text heuristics. Goal references are resolved by the LLM in `GoalManagementPlanner` and arrive as typed `GoalReference`.

4. **New payload contract:** The plugin receives a JSON-serialized `GoalCommand` variant instead of a free-form `ProjectOperationPayload`.
   ```kotlin
   // New payload shape (example):
   // {"command":"create","title":"...","instruction":"...","priority":"HIGH",...}
   // {"command":"status","goal_reference":{"type":"by_id","id":"abc-123"}}
   ```

5. **`repairPlannerPayload()`** simplifies to basic JSON validation -- no semantic normalization.

6. **`execute()`** deserializes `GoalCommand`, maps to `GoalOperation` + `GoalOperationRequest`, calls `GoalsGateway`.

7. **Goal reference validation in execute:**
   - `ByInternalId` / `ByResolvedEntity` -> resolve directly, fail if not found.
   - `Ambiguous` -> return failure with disambiguation message.
   - `Unresolved` -> return failure with "goal not found" message.

8. **Update `GoalOperationActionPluginTest`** to use new typed payloads.

**Validation:** All existing goal operation tests pass with new payload format. New tests for typed reference handling.

---

### Step 8: Wire HierarchicalEgoPlanner into Ego + Remove Action Verifier

**Goal:** Replace `LlmEgoPlanner` with `HierarchicalEgoPlanner` in the runtime wiring. Remove the action verifier. This step completes Phase 1 per the spec's recommended delivery phases.

#### 8a: Phase 2 lane stubs

Before wiring, implement stub versions of Phase 2 lanes that replicate current `LlmEgoPlanner` behavior for their trigger types. Each stub:
- Uses `PlannerRuntime` for model calls (shared retry, circuit breaker, telemetry).
- Reuses the current prompt-building logic extracted from `LlmEgoPlanner.buildMessages()` for the relevant trigger type.
- Parses responses using the current `EgoDecisionPayload` schema via `StructuredOutputHandler`.
- Returns `EgoDecision` directly.

This ensures no behavioral regression at cutover. Stubs are replaced in Steps 9-12 with dedicated lane implementations and narrower prompts.
Known bugs, accidental defects, and existing limitations are still not parity targets during this cutover; the stubs exist to preserve intended end-to-end behavior while Phase 2 lanes are being replaced.

#### 8b: Wiring

1. Find the factory/bootstrap site where `LlmEgoPlanner` is instantiated.
2. Replace with `HierarchicalEgoPlanner` construction, injecting `InputPlanner` (Phase 1) + 4 Phase 2 stubs.
3. Remove `LlmEgoPlanner` import from construction site.

#### 8c: Action verifier removal (Phase 1 requirement per spec)

1. Remove `actionVerifierEnabled` from `PlannerConfig`.
2. Remove `actionVerifierModelClient`, `actionVerifierContextWindow` parameters from any remaining references.
3. Remove action verifier JSON schema, prompt builder, and circuit breaker code from `LlmEgoPlanner` (now dead code).
4. Determine `AdaptiveCompletionBudget` usage -- if only used by action verifier, remove it. If used elsewhere, keep it.
5. Remove/update tests that referenced action verifier behavior.
6. Add replacement tests proving planner correctness via lane design + typed outputs.

**Validation:**
- All existing tests pass.
- Run `./freud/bin/freud run signoff-gate` to verify deterministic gate.
- Manual smoke test with a few representative inputs.
- Verify no planner decision path depends on action verifier (Rule 8).

---

### Step 9: DeferredStepPlanner (Phase 2)

**Goal:** Handle `EgoTrigger.DeferredIntention` with a dedicated lane. Replace the corresponding Phase 2 stub from Step 8a.

1. **`DeferredStepPlanner`** receives deferred intention context: original thought content, pass count, plan context, denied action info, recall query results.
2. **No deterministic text routing.** All decision logic is LLM-based. The lane receives typed trigger metadata (plan context, denial reason codes, pass count) and uses these typed facts for any deterministic pre-checks (e.g., max thought passes exceeded -> `Fail`). Semantic interpretation of the deferred content is always model-based.
3. Returns `StepDecision` which maps to `EgoDecision`:
   - `Execute(candidate: ExecutionCandidate)` -> `FormIntention` (absorbs `PlanStepDirective` per D8)
   - `RefinePlan` -> `EnqueuePlan`
   - `SkipStep` / `Fail` -> `Noop`
   - `Answer` -> `FormIntention(CONTACT_USER)`
   - `Defer` -> `EnqueueThought`
   - `Clarify` -> `FormIntention(CONTACT_USER)` with clarification content
4. Prompt profile: narrower than general -- includes plan context, denied action context, continuation state. Excludes sections irrelevant to continuations.
5. Preserves: resolution-draft gating (only allowed within active plan context), allowed-intention constraints, thought-pass limits.
6. **Constraint pass-through:** Receives and enforces `allowedIntentions`, `allowedCommitModes`, `dispatchableActions`, plan-context restrictions from `PlannerContext`.

**Validation:** Unit tests for each `StepDecision` variant. Integration test for deferred continuation flow.

---

### Step 10: FeedbackPlanner (Phase 2)

**Goal:** Handle `EgoTrigger.ActionFeedback` with a dedicated lane. Replace the corresponding Phase 2 stub from Step 8a.

1. **`FeedbackPlanner`** receives feedback cue: action type, execution status, outcome, evidence observed.
2. **No deterministic text routing.** Deterministic pre-checks are limited to typed facts: execution status enum, retry budget counters, follow-up-allowed flag. Interpretation of feedback content is always model-based.
3. Returns `FeedbackDecision` mapped to `EgoDecision`:
   - `Answer` -> `FormIntention(CONTACT_USER)`
   - `Retry` -> `FormIntention` (same action type, possibly modified payload)
   - `NextStep` -> `FormIntention` (different action) or `EnqueueThought`
   - `Defer` -> `EnqueueThought`
   - `MarkBlocked` / `MarkDone` -> context-specific `Noop` or goal-state update
4. Prompt profile: focused on action outcome interpretation. Includes: feedback details, evidence hints, plan context if in plan, action definitions.
5. Preserves: follow-up constraints, retry budget enforcement.
6. **Constraint pass-through:** Receives and enforces `allowedIntentions`, `allowedCommitModes`, `dispatchableActions`, conversation/thread trust and provenance context from `PlannerContext`.

**Validation:** Unit tests for feedback variants (success, failure, waiting). Integration test for action-feedback loop.

---

### Step 11: GoalWorkPlanner (Phase 2)

**Goal:** Handle `EgoTrigger.GoalWork` with a dedicated lane. Replace the corresponding Phase 2 stub from Step 8a.

1. **`GoalWorkPlanner`** receives `GoalRunActivation`: goal ID, step ID, step description, acceptance criteria, working context, action suggestion.
2. **No deterministic text routing.** Typed facts from `GoalRunActivation` (step status, attempt count, acceptance criteria) drive deterministic pre-checks. Semantic interpretation of step description and working context is always model-based.
3. Returns `GoalWorkDecision` mapped to `EgoDecision`:
   - `ExecuteStep` -> `FormIntention`
   - `DeferUntilCondition` -> `EnqueueThought`
   - `MarkStepComplete` -> context-dependent (`Noop` or next-step trigger)
   - `RequestClarification` -> `FormIntention(CONTACT_USER)`
   - `FailStep` -> `Noop` with failure reason
4. Prompt profile: goal-focused. Includes: goal work summary, step details, acceptance criteria, available actions, plan context.
5. Preserves: goal-runtime constraints, working context char limits.
6. **Constraint pass-through:** Receives and enforces `allowedIntentions`, `allowedCommitModes`, `dispatchableActions`, goal-runtime constraints from `PlannerContext`.

**Validation:** Unit tests for goal work variants. Integration test with `GoalManager`.

---

### Step 12: ImpulsePlanner (Phase 2)

**Goal:** Handle `EgoTrigger.IncomingImpulse` with a dedicated lane. Replace the corresponding Phase 2 stub from Step 8a.

1. **`ImpulsePlanner`** receives `PendingImpulse`: need ID, prompt, tension, raw value, convergence mode. Also receives `IdStateSnapshot` from `PlannerContext`.
2. **No deterministic text routing.** Typed facts from `IdStateSnapshot` (tension, convergence mode, escalation flag) drive deterministic pre-checks. Semantic interpretation of the impulse prompt is always model-based.
3. Returns `ImpulseDecision` mapped to `EgoDecision`:
   - `Research` -> `FormIntention` with research action
   - `Reflect` -> `EnqueueThought`
   - `ContactUser` -> `FormIntention(CONTACT_USER)`
   - `Noop` -> `EgoDecision.Noop`
4. Prompt profile: drive-modulated. Includes: Id state snapshot, tension levels, convergence constraints, need description, available actions.
5. Preserves: Id convergence constraints, escalation rules.
6. **Constraint pass-through:** Receives and enforces `allowedIntentions`, `allowedCommitModes`, `dispatchableActions`, Id convergence constraints from `PlannerContext`.

**Validation:** Unit tests for impulse variants. Integration test with Id lifecycle.

---

### Step 13: Delete LlmEgoPlanner Backup and Final Cleanup

**Goal:** Remove all dead code and legacy references.

1. Delete `LlmEgoPlanner.backup.kt.bak`.
2. Delete `LlmEgoPlanner.kt` if not already removed.
3. Remove any remaining references to `LlmEgoPlanner` in imports, factories, tests.
4. Remove goal-creation regex heuristics (`explicitGoalCreationRegex`, `reminderIntentRegex`, `monitoringIntentRegex`, `recurringScheduleHintRegex`) -- these should already be gone from the code path.
5. Remove `PlanningBranch` enum (GENERAL / GOAL_CREATION).
6. Remove `GoalCreationPayload` and `GoalOperationPayload` private data classes from old planner.
7. Remove `fallbackGoalCreationSpec()` heuristic.
8. **Broad text-heuristic audit:** Grep for regex/keyword/substring matching on natural-language input across the planner/orchestrator/goal-semantic paths in `agent/`, not just `agent/ego/planner/`. The audit scope must include action plugins, goal execution paths, and any other code that interprets planner-produced text as part of this redesign. `DecisionVerifier.kt` is a documented out-of-scope exception for this project and must be excluded from blocking signoff.
9. Remove Phase 2 stub implementations (now replaced by dedicated lane implementations from Steps 9-12).

**Validation:** Full compile, full test suite, grep audit clean for in-scope paths, with the `DecisionVerifier.kt` exception documented.

---

### Step 14: Acceptance Verification

**Goal:** Satisfy all 13 acceptance rules from the spec.

1. **Scope control** (Rule 1): Review all behavioral changes introduced during implementation. Each change must be either (a) an explicitly approved removal/reshaping from the spec, or (b) flagged as an intentional change with dedicated tests and documentation. Any behavior change not explicitly approved blocks signoff.
   This review is against current end-to-end functionality and behavior, not only internal planner contracts.
   Existing bugs, accidental defects, and known limitations are not parity targets and do not need to be preserved. If the redesign fixes one, document it explicitly as a bug fix or limitation removal.

2. **Parity inventory** (Rule 2): Produce `docs/PLANNER_PARITY_INVENTORY.md` mapping every current capability to its new owner. Every entry classified as: preserved / preserved-narrower / intentionally-changed / intentionally-removed. The inventory must explicitly include all 18 required items from the spec:
   - fresh input planning
   - deferred intention / continuation planning
   - action feedback planning
   - goal-work planning
   - Id/self-motivated planning
   - direct answer path
   - single-action path
   - multi-step planning path
   - goal creation
   - goal management
   - allowed-intention shaping
   - allowed-commit-mode shaping
   - action-availability shaping
   - plan-step continuation semantics
   - resolution-draft gating
   - structured-output retry / recovery behavior
   - planner output repair behavior that remains in scope
   - planner telemetry and prompt-budget telemetry
   Nothing may be left unclassified.
   Existing bugs and known limitations must not be listed as preserved behavior; if fixed during the redesign they must be called out separately in the signoff report.

3. **Trigger-family coverage** (Rule 3): Dedicated test class per trigger family (`IncomingInput`, `DeferredIntention`, `ActionFeedback`, `IncomingImpulse`, `GoalWork`) showing correct lane entry, correct typed intermediate result, and preservation of runtime constraints.

4. **Decision-shape coverage** (Rule 4): Test matrix with at least one positive and one negative/guardrail case for each decision shape: direct terminal response, deferred continuation, explicit next action/intention, multi-step plan decomposition, goal creation intent, goal management intent, clarification request.

5. **Constraint preservation** (Rule 5): Tests verifying all of:
   - allowed intentions
   - allowed commit modes
   - available actions (full set)
   - dispatchable actions (planner-visible subset)
   - conversation / thread trust and provenance shaping
   - plan-context restrictions (resolution-draft gating)
   - goal-runtime constraints
   - Id convergence constraints
   For each constraint, test that it flows through to the lane that enforces it, and that the lane does not bypass it.

6. **No text-heuristic regression** (Rule 6):
   - Code-level audit: grep planner/orchestrator/goal-semantic paths in `agent/` (not just `agent/ego/planner/`) for regex, keyword matching, substring matching, token overlap scoring, or comparable deterministic text heuristics on any semantic text path.
   - Tests proving semantic routing succeeds on natural-language inputs through model-based typed outputs.
   - Any deterministic logic found on a semantic text path blocks signoff.
   - **Scoped exception:** existing `DecisionVerifier.kt` evidence-gating remains out of scope for this redesign, is tracked separately, and must be documented as an explicit exclusion in the signoff artifacts.

7. **Goal semantics** (Rule 7): Tests for:
   - goal creation
   - goal listing / status
   - goal pause / resume
   - goal completion / delete
   - goal update / revise-plan / reprioritize
   - ambiguous goal references
   - unresolved goal references
   - multilingual phrasing: at least 3 non-English languages (e.g., Spanish, Japanese, Arabic) demonstrating that goal routing and reference resolution succeed without English-specific heuristics.

8. **Action verifier removal** (Rule 8): Verify no planner decision path depends on the current action verifier. Verify no signoff depends on re-enabling it. Replacement tests demonstrating planner correctness via lane design, typed outputs, and existing runtime controls.

9. **Shared runtime preservation** (Rule 9): Tests for:
   - model call retry policy (per lane)
   - structured output handling (strict + relaxed fallback per lane)
   - parse failure handling (circuit breaker per lane)
   - truncation recovery
   - telemetry emission (planner_start, planner_decision, per-lane events)
   - prompt-budget allocation telemetry (per-lane `Diagnostics.toTelemetryData`)
   If any current behavior is intentionally removed or replaced, that change must be documented in the parity inventory.

10. **Test replacement** (Rule 10): Produce a test mapping artifact at `docs/PLANNER_TEST_MAPPING.md` that maps every removed or reorganized planner test to its replacement. Format: `| Old Test | New Test | Coverage Notes |`. No removed test may lack a mapping entry.

11. **Documentation** (Rule 11): Update:
    - `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md` -- remain aligned with the final implemented architecture, including the scoped `DecisionVerifier` exception
    - `AGENT_LOGIC_SUMMARY.md` -- reflect new planner/orchestrator flow
    - `AGENT_LOGIC_DIAGRAM.md` -- reflect new planner/orchestrator flow diagrams

12. **Final verification** (Rule 12): Run `./freud/bin/freud run signoff-gate`. Produce a signoff report at `docs/PLANNER_SIGNOFF_REPORT.md` containing:
    - which test suites were run and their results
    - whether current end-to-end functionality and behavior were preserved, intentionally changed, or intentionally removed (reference parity inventory and end-to-end test evidence)
    - which existing bugs or limitations were intentionally fixed or removed during the redesign
    - which scoped exceptions were explicitly excluded from signoff and why
    - whether any open gaps remain
    - explicit evidence for each acceptance rule

13. **Default failure rule** (Rule 13): Any planner capability where there is uncertainty about whether it has been preserved is treated as not accepted. Silence, implicit assumptions, or deleted tests do not count as proof of parity. This rule is enforced by the parity inventory requirement (every item must be classified) and the test mapping requirement (every removed test must have a replacement).

---

## Step Dependency Graph

```
Step 0 (backup/prep)
  |
Step 1 (typed models)
  |
Step 2 (shared runtime)  ----+
  |                           |
Step 3 (lane interface/config)|
  |                           |
Step 4 (prompt assembly) ----+
  |
Step 5 (L0 orchestrator)
  |
Step 6 (InputPlanner + L2 sub-planners)  -- Phase 1 core
  |
Step 7 (GoalOperationActionPlugin refactor)
  |
Step 8 (Wire into Ego + remove action verifier)  -- Phase 1 complete
  |
  +-- Step 9  (DeferredStepPlanner) --+
  +-- Step 10 (FeedbackPlanner) ------+-- Phase 2 (can parallelize)
  +-- Step 11 (GoalWorkPlanner) ------+
  +-- Step 12 (ImpulsePlanner) -------+
  |
Step 13 (Delete backup, final cleanup, broad audit)
  |
Step 14 (Acceptance verification)
```

Steps 9-12 are independent of each other and can be implemented in any order or in parallel.

---

## Test Strategy

### Unit Tests (per lane)

Each lane gets a dedicated test class with mocked `ChatModelClient`:
- `InputIntentRouterTest` -- route classification for each `InputRoute` variant
- `DirectResponsePlannerTest` -- terminal answer generation
- `GeneralActionPlannerTest` -- action selection, validation, constraint enforcement
- `TaskDecompositionPlannerTest` -- plan decomposition, step limits
- `GoalCreationPlannerTest` -- goal parameter extraction, no regex
- `GoalManagementPlannerTest` -- operation + reference resolution, no text heuristics
- `DeferredStepPlannerTest` -- continuation logic, plan context, resolution-draft gating
- `FeedbackPlannerTest` -- feedback interpretation, retry budget
- `GoalWorkPlannerTest` -- goal step execution, acceptance criteria
- `ImpulsePlannerTest` -- drive-modulated decisions, convergence constraints

### Integration Tests

- `HierarchicalEgoPlannerTest` -- end-to-end trigger -> `EgoDecision` for all trigger families
- `GoalOperationActionPluginTest` -- updated for typed `GoalCommand` payloads
- Existing `EgoAgentTest`, `EgoGoalIntegrationTest`, `EgoAsyncFeedbackIntegrationTest` -- must continue to pass

### Scenario Pack

- Update scenario selectors in `AgentScenarioPackTest.kt` if planner class names changed.
- Add scenarios for typed goal command round-trips.

### Audit Tests

- `PlannerNoTextHeuristicAuditTest` -- grep-like test scanning the entire `agent/` package (not just `agent/ego/planner/`) for regex, keyword matching, substring matching, or token-overlap scoring on any semantic text path. Allowlist for legitimate uses (e.g., JSON parsing, log formatting). Any unlisted pattern on a semantic path fails the test.

---

## Risk Register

| Risk | Mitigation |
|------|------------|
| InputIntentRouter misclassifies edge cases | Do not silently default to `GeneralAction`. If routing remains materially ambiguous, return `InputRoute.ClarificationNeeded` with a targeted question. Add scenario pack cases for ambiguous routing and multilingual phrasing. |
| Two LLM calls per input (router + sub-planner) increases latency | Router uses low token budget (400 max completion). Monitor latency via telemetry. Future: router can be merged with sub-planner for high-confidence routes. |
| GoalManagementPlanner fails to resolve goal references | `GoalReference.Unresolved` handled gracefully with user-facing error. Plugin validates reference before execution. |
| Phase 2 lanes (Deferred, Feedback, GoalWork, Impulse) have subtler context requirements | Each lane tested against intended end-to-end behavior using existing integration tests as coverage input, while avoiding accidental preservation of known bugs or limitations. |
| Prompt regression from narrower per-lane prompts | Per-lane prompt profiles audited against current `buildMessages()` section list. Missing context sections caught by test failures. |

---

## Files Modified (Existing)

| File | Change |
|------|--------|
| `agent/config/PlannerConfig.kt` | Add `laneDefaults`, `lanes` fields |
| `agent/ego/Ego.kt` | Wire `HierarchicalEgoPlanner` instead of `LlmEgoPlanner` |
| `agent/ego/LlmEgoPlanner.kt` | Backup then delete |
| `agent/cortex/motor/actions/plugin/builtin/GoalOperationActionPlugin.kt` | Refactor to typed `GoalCommand` |
| `agent/goal/GoalsGateway.kt` | No change (already typed `GoalOperation` + `GoalOperationRequest`) |
| Bootstrap/factory code (TBD) | Update planner construction |
| `AGENT_LOGIC_SUMMARY.md` | Update planner architecture section |
| `AGENT_LOGIC_DIAGRAM.md` | Update planner flow diagrams |
| `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md` | Update if implementation diverges |
| Test files | Update/replace as described |

---

## Files Created (New)

~31 new source files under `agent/ego/planner/` as listed in Package Structure section, plus ~12 new test files.

### Acceptance Artifacts (created at signoff)

| Artifact | Purpose |
|----------|---------|
| `docs/PLANNER_PARITY_INVENTORY.md` | Maps every current planner capability to new owner (Rule 2) |
| `docs/PLANNER_TEST_MAPPING.md` | Maps every removed/reorganized test to replacement (Rule 10) |
| `docs/PLANNER_SIGNOFF_REPORT.md` | Final verification evidence for all 13 rules (Rule 12) |

---

## Spec Open Questions -- Resolutions

The spec lists 4 open questions. Here are the resolutions adopted by this plan:

| # | Open Question | Resolution |
|---|--------------|------------|
| 1 | Minimum typed intermediate representation that can survive migration without excessive adapter code? | Each L1 lane returns `EgoDecision` directly (D7). Lane-specific sealed types (`InputRoute`, `StepDecision`, etc.) serve as typed intermediates within the lane. No `PlannerOutcome` wrapper. No migration adapter layers or legacy compatibility path (D9). `ExecutionCandidate` standardizes how lanes produce `FormIntention` decisions, and any required serialized boundary payload is emitted directly from typed results. |
| 2 | Which goal-management operations should require clarification vs. best-effort resolution? | Clarify on `Ambiguous` and `Unresolved` references. Best-effort resolve otherwise. Never silently guess. See "GoalManagement clarification policy" in Step 6. |
| 3 | Which evaluator/analyzer structure should own future evaluator-optimizer loops? | Out of scope for this redesign per spec. No decision needed now. |
| 4 | Which planner lanes should be introduced in first migration step vs. later? | Phase 1: `InputPlanner` hierarchy + `GoalCommand` + action verifier removal. Phase 2: `DeferredStepPlanner`, `FeedbackPlanner`, `GoalWorkPlanner`, `ImpulsePlanner`. Matches spec recommendation. |

---

## Spec Alignment

The spec and implementation plan are aligned for implementation start.
Any future implementation-driven architecture change must update the spec before signoff.

---

## Signoff Criteria

Implementation is complete when:
1. `./freud/bin/freud run signoff-gate` passes
2. All 13 acceptance rules from spec are satisfied with explicit evidence in `docs/PLANNER_SIGNOFF_REPORT.md`
3. `docs/PLANNER_PARITY_INVENTORY.md` is complete with all 18 required items classified
4. `docs/PLANNER_TEST_MAPPING.md` maps every removed/reorganized test to its replacement
5. `AGENT_LOGIC_SUMMARY.md` and `AGENT_LOGIC_DIAGRAM.md` are updated
6. `docs/specs/TYPED_HIERARCHICAL_PLANNER_REDESIGN.md` remains aligned with the implemented architecture and the scoped `DecisionVerifier` exception
7. No references to `LlmEgoPlanner` remain in production code
8. No text heuristics remain in planner or goal-operation execution paths (broad audit passes), with the scoped `DecisionVerifier.kt` exclusion explicitly documented
9. End-to-end integration/scenario evidence shows current in-scope functionality and behavior are preserved
10. Backup file deleted
11. No unresolved uncertainty about feature preservation (Rule 13 enforced)
