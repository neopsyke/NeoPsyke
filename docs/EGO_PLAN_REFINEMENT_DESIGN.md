# Ego-Level Plan Generation and Refinement

## Problem

Plan generation for durable work items currently happens in the motor layer
(`LlmWorkPlanBuilder.generatePlan()` inside `DurableWorkRuntime.createWorkItem()`).
This LLM call has no access to ambient context, long-term memory, episodic recall,
available actions, or deliberation state. It produces low-quality plans (e.g.,
"step 1: determine today's date" for a weather reminder).

Additionally, there is no refinement pass for any plan generated in the system ---
neither durable work plans nor Ego inline plans (`EgoDecision.EnqueuePlan`).

## Goals

1. Move plan generation into the Ego, where all reasoning belongs.
2. Add a bounded `PlanRefiner` that validates and improves all plans before they are committed.
3. Surface plans to the user for approval before work items are created.
4. Allow the user to request changes using existing approval flows, not ad-hoc code.
5. Generalize the approval system to carry rich context, reusable beyond durable work.
6. Preserve the full durable-work step contract when planning moves into Ego.
7. Make both durable-work `CREATE` and `REVISE_PLAN` fully Ego-owned planning flows.

## Scope of This Update

This document describes a constrained planner/approval consolidation step after
the initial durable-work phase-1 foundations exist.

It is not intended to start the broader phase-2 durable-runtime work from
`docs/specs/DURABLE_WORK_RUNTIME_VISION.md`. It tightens planner ownership and
approval flow without expanding the runtime surface area.

### In Scope

- add a bounded `PlanRefiner`
- move durable-work `CREATE` and `REVISE_PLAN` planning fully into Ego
- keep inline Ego plans and durable-work plans on the same refinement path
- show plan steps in the approval flow
- let users request plan changes through the existing deny/reissue path

### Out of Scope

- new trigger types
- lease, wake-coalescing, or crash-recovery redesign
- durable-state namespace expansion
- delivery-policy redesign
- monitor-specific typed state or other broader phase-2 runtime work
- new durable-work-specific plan-edit protocols

## Design Overview

### New Flow for Durable Work Creation

```
User: "remind me of the weather every day at 15:15"
    |
    v
[Ego planner] decides: durable_work_operation CREATE
    - Planner generates plan steps as part of the CREATE payload
    - Has full access to: available actions, memory, episodic context, runtime facts
    |
    v
[PlanRefiner] repairs and refines the plan (same Ego cycle, same context)
    - Single bounded LLM pass: repairs malformed-but-recoverable structure,
      validates achievability, prunes wasteful steps, fixes ordering, preserves
      step semantics
    - On refinement failure: accepts the original plan when meaning is still
      preserved and mechanical boundary checks still pass
    |
    v
[Action staged for approval]
    - User sees: title, instruction, cron, AND the plan steps
    - Approval prompt includes plan details via generic approval context
    |
    v
[User approves / denies / asks for changes]
    - Approve: action executes, work item created with pre-built plan
    - Deny: action cancelled, planner receives denial feedback
    - "Change the plan": DENY_AND_REISSUE --- user's message is forwarded
      as normal input, planner re-generates with feedback (existing flow)
```

### New Flow for Ego Inline Plans

```
[Planner lane] returns EgoDecision.EnqueuePlan(goal, steps)
    |
    v
[PlanRefiner] repairs and refines the plan (in DecisionDispatcher, before enqueue)
    - Single bounded LLM pass
    - Validation criteria depend on plan kind and terminal policy
    - On refinement failure: accepts the original plan when meaning is still
      preserved and mechanical boundary checks still pass
    |
    v
[DecisionDispatcher] enqueues refined steps as continuations (existing flow)
```

---

## Work Slice 1: PlanRefiner Component

### Interface

```kotlin
// src/main/kotlin/ai/neopsyke/agent/ego/planner/PlanRefiner.kt

interface PlanRefiner {
    fun refine(request: PlanRefinementRequest): PlanRefinementResult
}

data class PlanRefinementRequest(
    val planKind: PlanKind,
    val terminalPolicy: TerminalPolicy,
    val goal: String,
    val instruction: String,
    val completionCriteria: String = "",
    val steps: List<PlanStepCandidate>,
    val availableActions: List<ActionSummary>,
    val runtimeFacts: Map<String, String>,
    val recentDialogue: List<String> = emptyList(),
    val shortTermContextSummary: String = "",
    val longTermMemoryRecall: String = "",
    val episodicRecall: String = "",
    val evidenceHints: String = "",
    val userFeedbackHint: String? = null,
)

data class PlanStepCandidate(
    val id: String,
    val description: String,
    val acceptanceCriteria: String = "",
    val groundingRequirement: String = "not_required",
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
    val maxAttempts: Int = 3,
)

enum class PlanKind {
    INLINE_EGO,
    DURABLE_WORK_CREATE,
    DURABLE_WORK_REVISE,
}

enum class TerminalPolicy {
    MUST_END_WITH_USER_DELIVERY,
    MAY_END_WITH_USER_DELIVERY,
    DELIVERY_CONTROLLED_BY_WORK_ITEM,
}

data class ActionSummary(
    val actionType: String,
    val description: String,
)

data class PlanRefinementResult(
    val steps: List<PlanStepCandidate>,
    val droppedSteps: List<DroppedStep> = emptyList(),
    val refinementMode: PlanRefinementMode = PlanRefinementMode.UNCHANGED,
    val reason: String = "",
)

data class DroppedStep(
    val originalId: String,
    val reason: String,
)

enum class PlanRefinementMode {
    UNCHANGED,
    LLM_REWRITTEN,
}
```

### Implementations

**`LlmPlanRefiner`** --- makes one LLM call. Follows the standard LLM caller
pattern (retry loop, required-field validation, safe fallback). Its job is to
repair malformed-but-recoverable plans, preserve intent, and return one
canonical final plan. On any failure (LLM error, parse failure, timeout), the
system falls back to the original planner plan when meaning is still preserved
and the final mechanical boundary checks still pass.

**`NoopPlanRefiner`** --- returns the plan unchanged. Used in tests and when
refinement is disabled via config.

### LLM Refinement Prompt

The refiner receives:

- plan kind and terminal policy
- The original goal/instruction
- The generated plan steps (as JSON array)
- Available actions with descriptions
- Runtime facts (date, time, timezone)
- Completion criteria
- selected planner context already available in Ego
- optional user feedback hint for revise-plan flows

Prompt philosophy:

- preserve intent over literal wording
- do not reject a plan just because it is messy
- repair malformed or partial structure when meaning is still recoverable
- prefer conservative edits over aggressive rewrites
- do not invent new requirements unless strongly implied by the goal, context,
  or existing steps
- if uncertain, keep more of the original plan rather than dropping intent
- return one canonical typed plan every time

Validation criteria baked into the system prompt:

1. **Achievability** --- is each step plausibly executable using the currently
   available action surface, without requiring missing capabilities?
2. **Non-redundancy** --- does the step add value beyond runtime facts and prior steps?
3. **Data flow** --- do step outputs feed forward correctly?
4. **Minimal sufficiency** --- can steps be merged or dropped without losing capability?
5. **Contract preservation** --- preserve `requires`, `produces`, and `maxAttempts`
   unless there is a concrete reason to change them.
6. **Terminal policy** --- only require final user delivery when
   `terminalPolicy == MUST_END_WITH_USER_DELIVERY`.
7. **Recoverability** --- if the plan is malformed or underspecified but the
   intended meaning is recoverable, repair it instead of rejecting it.

Response schema:

```json
{
  "steps": [
    {
      "id": "step1",
      "description": "Search for current Hamburg weather forecast",
      "acceptance_criteria": "Weather data retrieved",
      "grounding_requirement": "required",
      "requires": [],
      "produces": ["weather_data"],
      "max_attempts": 3
    },
    {
      "id": "step2",
      "description": "Send weather summary to the user",
      "acceptance_criteria": "User receives forecast message",
      "grounding_requirement": "not_required",
      "requires": ["weather_data"],
      "produces": ["user_delivery"],
      "max_attempts": 1
    }
  ],
  "dropped_steps": [],
  "refinement_mode": "unchanged",
  "reason": "Original plan already satisfies the rubric"
}
```

or, when rewritten:

```json
{
  "steps": [
    {
      "id": "step1",
      "description": "Search for current Hamburg weather forecast",
      "acceptance_criteria": "Weather data retrieved",
      "grounding_requirement": "required",
      "requires": [],
      "produces": ["weather_data"],
      "max_attempts": 3
    },
    {
      "id": "step2",
      "description": "Send weather summary to the user",
      "acceptance_criteria": "User receives forecast message",
      "grounding_requirement": "not_required",
      "requires": ["weather_data"],
      "produces": ["user_delivery"],
      "max_attempts": 1
    }
  ],
  "dropped_steps": [
    { "original_id": "step1", "reason": "Date is a runtime fact, no step needed" }
  ],
  "refinement_mode": "llm_rewritten",
  "reason": "Removed redundant date-lookup step; merged fetch+format"
}
```

### Configuration

Add to `PlannerConfig`:

```kotlin
val planRefinementEnabled: Boolean = true
```

The refiner uses the planner cognitive role's model client --- no separate provider.

### Mechanical Boundary Checks

Outside the refiner, the system performs only minimal mechanical validation for
execution/storage safety. These checks are intentionally non-semantic:

- steps list is present and non-empty when the caller contract requires a plan
- step ids are normalized and unique after refinement
- `groundingRequirement` parses to a known enum value
- `requires` only references declared step outputs or otherwise allowed runtime
  inputs for that plan kind
- dependency references are not missing and do not form an obvious cycle
- `maxAttempts` is within a bounded valid range
- serialized payload sizes stay under configured limits

No deterministic text heuristics are used here to reinterpret planner meaning.

### Where It Plugs In

**Call site 1 --- Durable work CREATE:**
After the Ego planner generates plan steps as part of the CREATE decision,
before the action is staged for approval.

**Call site 2 --- Durable work REVISE_PLAN:**
After the Ego planner regenerates a work-item plan using current work-item
state, failure history, and the user's revise reason, before the plan revision
is written back into durable work state.

**Call site 3 --- Ego inline plans:**
In `DecisionDispatcher.dispatch()`, inside the `EgoDecision.EnqueuePlan` branch,
before the plan hash/dedup/scratchpad/step-enqueue logic (currently ~line 363).
The dispatcher calls `planRefiner.refine(...)` and uses the refined steps. Plan
hashing and deduplication operate on the refined plan, not the raw planner
output.

The `PlanRefiner` is injected into `DecisionDispatcher` (constructor parameter)
alongside the existing dependencies. For call site 1, the refiner is accessed
through the planner infrastructure that the Ego already uses. Durable-work
`CREATE` and `REVISE_PLAN` both use the same Ego-side helper to build a
durable-work plan before execution.

---

## Work Slice 2: Move Plan Generation to the Ego

### Current State

```
Ego planner → EgoDecision.FormIntention(durable_work_operation, CREATE payload)
    → ActionPlugin.execute() → DurableWorkRuntime.createWorkItem()
        → LlmWorkPlanBuilder.generatePlan()   ← LLM call in motor layer (wrong)
        → stores plan in work item

Ego planner → EgoDecision.FormIntention(durable_work_operation, REVISE_PLAN payload)
    → ActionPlugin.execute() → DurableWorkRuntime.executeOperation(REVISE_PLAN)
        → LlmWorkPlanBuilder.generatePlan()   ← same ownership problem
        → stores revised plan in work item
```

### Target State

```
CREATE
Ego planner → generates durable-work plan with full context
    → PlanRefiner canonicalizes/refines it
    → EgoDecision.FormIntention(durable_work_operation, CREATE payload WITH plan)
    → ActionPlugin.execute() → DurableWorkRuntime.createWorkItem()
        → uses pre-built plan from payload (no LLM call)

REVISE_PLAN
Ego planner → regenerates durable-work plan with current work-item context
    → PlanRefiner canonicalizes/refines it
    → EgoDecision.FormIntention(durable_work_operation, REVISE_PLAN payload WITH revised plan)
    → ActionPlugin.execute() → DurableWorkRuntime.executeOperation(REVISE_PLAN)
        → applies supplied revised plan (no LLM call)
```

### Step 2a: Extend durable-work payloads to carry full plan steps

Introduce a shared durable-work plan-step payload that preserves the existing
runtime step semantics:

```kotlin
data class DurableWorkPlanStepPayload(
    val id: String? = null,
    val description: String,
    @param:JsonProperty("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    @param:JsonProperty("grounding_requirement")
    val groundingRequirement: String? = null,
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
    @param:JsonProperty("max_attempts")
    val maxAttempts: Int? = null,
)
```

**`DurableWorkCommand.Create`** gains a `planSteps` field that remains nullable
for parser compatibility and rollout safety, but normal production emission from
the Ego must provide a non-empty plan:

```kotlin
data class Create(
    val title: String,
    val instruction: String,
    val priority: WorkItemPriority = WorkItemPriority.MEDIUM,
    val completionCriteria: String = "",
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val planSteps: List<DurableWorkPlanStepPayload>? = null,
) : DurableWorkCommand
```

**`DurableWorkCommand.RevisePlan`** gains the same field with the same contract:

```kotlin
data class RevisePlan(
    val reference: WorkItemReference,
    val reason: String? = null,
    val planSteps: List<DurableWorkPlanStepPayload>? = null,
) : DurableWorkCommand
```

**`SerializedDurableWorkCommand`** gains the corresponding JSON field:

```kotlin
@param:JsonProperty("plan_steps")
val planSteps: List<DurableWorkPlanStepPayload>? = null,
```

### Step 2a.1: Normalize and stabilize step ids

After refinement and before payload storage/execution:

- preserve existing ids when the refiner keeps a step materially intact
- assign deterministic fallback ids when ids are missing
- ensure uniqueness after any rewrite or merge
- use the normalized ids for plan hashing, runtime storage, and later revision
  comparisons

This keeps the refiner free to improve structure while giving the runtime a
stable mechanical boundary.

### Step 2b: Make the Ego Planner Generate Steps

The goal planner lane that handles durable work creation requests
(in the input intent router → goal planner path) currently produces a
`DurableWorkCommand.Create` with title, instruction, cron, etc. It needs
to also produce `planSteps`.

This is done by extending the planner prompt for durable work creation to
also output plan steps. The planner already has full context (available actions,
memory, episodic recall, dialogue) --- it just needs the schema to include the
full durable-work step contract.

The planner's structured output schema for the `durable_work_operation` action
payload gains:

```json
{
  "command": "create",
  "title": "...",
  "instruction": "...",
  "plan_steps": [
    {
      "id": "step1",
      "description": "...",
      "acceptance_criteria": "...",
      "grounding_requirement": "required|not_required",
      "requires": [],
      "produces": ["artifact_key"],
      "max_attempts": 3
    }
  ],
  ...
}
```

This is a schema extension, not a new action type. The planner produces plan
steps as part of the same decision that creates the work item.

After parsing the planner output, and before forming the `EgoDecision.FormIntention`,
the code calls `PlanRefiner.refine()` on the generated steps. The refined steps
replace the original ones in the CREATE payload.

### Step 2c: Add an Ego-owned revise-plan path

`REVISE_PLAN` must follow the same ownership rule as `CREATE`: Ego plans,
runtime applies. The runtime no longer generates revised plans itself.

The revise flow becomes:

1. User or system requests `revise_plan`
2. Ego resolves the target work item and loads current durable-work projection
3. Ego builds a `PlanRefinementRequest` with:
   - current plan steps
   - current plan revision
   - work item instruction and completion criteria
   - current step status
   - failure history / recent failure summary
   - durable artifact summary when relevant
   - revise reason
   - available actions and runtime facts
4. Ego produces a new durable-work plan
5. `PlanRefiner` repairs/refines it with `planKind=DURABLE_WORK_REVISE`
6. Ego emits `DurableWorkCommand.RevisePlan(..., planSteps=...)`
7. Runtime applies the provided plan via `WorkItemEvent.PlanRevised(...)`

This keeps planning inside Ego for both initial creation and later revision.

This work slice does not redefine the broader autonomy policy for revisions. The
existing durable-work approval/staging policy remains authoritative for whether
the resulting revise action is staged for approval or applied directly.

### Step 2d: DurableWorkRuntime uses pre-built plans only

`DurableWorkRuntime.createWorkItem()` changes:

```kotlin
// CREATE expects a pre-built plan from Ego.
// If the system is in explicit recovery mode, a deterministic fallback may be used.
val plan = if (planSteps != null && planSteps.isNotEmpty()) {
    WorkItemPlan(
        steps = planSteps.mapIndexed { i, step ->
            PlanStep(
                id = step.id ?: "step-${i + 1}",
                description = step.description,
                status = StepStatus.PENDING,
                acceptanceCriteria = step.acceptanceCriteria ?: completionCriteria,
                requires = step.requires,
                produces = step.produces,
                maxAttempts = step.maxAttempts ?: 3,
                groundingRequirement = parseGroundingRequirement(step.groundingRequirement),
            )
        },
        generatedAt = Instant.now(),
    )
} else {
    error("CREATE requires pre-built plan steps from Ego")
}
```

`DurableWorkRuntime.executeOperation(REVISE_PLAN)` changes similarly:

```kotlin
// REVISE_PLAN now requires a pre-built revised plan from Ego.
// The runtime applies it; it does not generate it.
applyEvent(
    workItemId,
    WorkItemEvent.PlanRevised(
        workItemId = workItemId,
        plan = buildPlanFromPayload(planSteps),
        reason = request.reason ?: "Revised by user request",
    )
)
```

**`LlmWorkPlanBuilder` is deleted.** Plan generation is now the Ego's
responsibility. The clean-break default is fail-closed if `CREATE` or
`REVISE_PLAN` arrives without a plan. `DeterministicWorkPlanBuilder` may remain
behind an explicit recovery/migration flag, but it is no longer part of the
normal production control flow.

### Step 2e: Rollout and migration behavior

This slice needs an explicit rollout stance so the nullable payload fields do
not create ambiguous behavior:

- newly generated `CREATE` and `REVISE_PLAN` commands must include non-empty
  `planSteps`
- older stored payloads or in-flight staged actions may be accepted only through
  an explicit migration/recovery path
- the fallback path must be opt-in, observable, and outside the normal control
  flow
- telemetry should distinguish normal Ego-planned traffic from migration/recovery
  traffic
- once rollout is complete, missing-plan paths should fail closed by default

---

## Work Slice 3: Generalized Approval Context

### Problem

The approval system currently shows the user:
- `summary`: "Create goal: Daily weather reminder"
- `reason`: "Recurring goal mutations require staged approval."

There is no mechanism to display rich context (like plan steps, cost estimates,
impact descriptions) alongside the approval prompt. This is needed for durable
work plans but is a general capability gap.

### Solution: Add `approvalContext` to StagedAction

**Do not** create durable-work-specific approval rendering. Instead, add a
generic approval-context field that any action type can populate.

#### Data Model

**`StagedAction`** gains:

```kotlin
val approvalContext: List<ApprovalContextEntry> = emptyList(),
```

**`ApprovalContextEntry`** is a generic labeled block:

```kotlin
data class ApprovalContextEntry(
    val label: String,
    val content: String,
)
```

This is intentionally simple --- labeled text blocks only. The producer is
responsible for turning structured action data into display-ready text. The
renderer preserves and shows that text; it does not infer list semantics or
reformat the content into a different structure. No extra typing or
format-specific constraints are introduced here. The action plugin owns how
action-specific context is assembled; the staging runtime only invokes that
hook generically.

#### Where Context Is Attached

The action plugin descriptor declares
`buildApprovalContext(payload): List<ApprovalContextEntry>`. The staging runtime
calls that hook generically when it creates the `StagedAction`. For durable work
CREATE:

```kotlin
approvalContext = listOf(
    ApprovalContextEntry(
        label = "Plan",
        content = planSteps.mapIndexed { i, step ->
            "${i + 1}. ${step.description} " +
                "(acceptance: ${step.acceptanceCriteria}; " +
                "requires: ${step.requires.joinToString(",")}; " +
                "produces: ${step.produces.joinToString(",")}; " +
                "max_attempts: ${step.maxAttempts})"
        }.joinToString("\n"),
    ),
)
```

The context entries are derived from the action payload at staging time. For
durable work, the canonical runtime contract remains the structured
`planSteps`. The approval-context `Plan` entry is a display-only rendering
produced from that structured plan. It is not a second source of truth and must
not be parsed back into runtime plan state. This does not require the staging
runtime to understand durable-work semantics.

#### Rendering

**`ApprovalRuntime.deliverPrompt()`** renders context entries after the
existing summary/reason block:

```
Approval required.
Action: durable_work_operation
Summary: Create goal: Daily weather reminder (15 15 * * *)
Reason: Recurring goal mutations require staged approval.

Plan:
1. Search for current Hamburg weather forecast
2. Send weather summary to the user

Approval ref: ...
```

The dashboard/API can return the same context entries as structured data, but
that is a secondary consumer. The primary goal of this slice is approval prompt
clarity and interpreter context, not a broader rendering system. The context
model stays generic so other features can reuse it to show user-facing details
without adding new per-feature approval-display types. For durable work,
compatibility is preserved because the structured `planSteps` payload remains
the canonical runtime plan and `approvalContext` carries only a user-facing text
view derived from it.

The same approval context is also made available to the approval
interpreter as additional classification input. This is important for replies
like "merge steps 2 and 3" or "approve, but use web search first", where the
user is reacting to plan details rather than just the summary line.

#### Future Reuse

Other action types can attach approval context:

- **email_send**: recipient, subject, body preview
- **calendar_observe_events**: date range, filter criteria
- **Any new action**: whatever context helps the user make an informed decision

No new code paths needed --- just populate `approvalContext` in the action
descriptor's `buildApprovalContext()` method.

---

## Work Slice 4: User Feedback on Plans ("Ask for Changes")

### Problem

When the user sees a plan they don't like, they need to be able to say
"no, use a web search instead of an API call" or "combine steps 2 and 3."

### Solution: Use DENY_AND_REISSUE (Existing Flow)

The approval system already has `DENY_AND_REISSUE`, but this path must be
strengthened for plan-edit feedback:

1. User replies: "no, search the web instead of using an API"
2. `ApprovalInterpreter.classify()` classifies this as `DENY_AND_REISSUE`
3. `handleDeny()` cancels the staged action
4. `forwardReissued()` forwards the user's message as a normal input to
   the sensory ingress
5. The Ego receives this as a fresh input with the user's feedback
6. The planner re-generates the CREATE decision with the new instruction,
   producing a new plan
7. The new plan goes through `PlanRefiner` and is staged for approval again

This works because:

- The Ego has episodic memory of the previous plan attempt and its denial
- The user's feedback is their natural language correction
- The planner sees the denial context and adjusts accordingly
- No special "plan revision" flow is needed

### What Makes This Work Well

The denial callback path already feeds context back to the planner:

- `onApprovalDenied` receives the cancelled `StagedAction` with reason
- The Ego records the denial as an episodic event (via journaling)
- The user's reissued message arrives as a new input
- Episodic recall surfaces the recent denial when the planner processes
  the new input
- The planner sees: "User denied the previous plan because [reason].
  User now says: [reissued message]"

The key insight: the episodic memory and the reissued input together give
the planner everything it needs to revise the plan. No separate durable-work-
specific "edit plan" protocol is required.

### What Needs Attention

The `DENY_AND_REISSUE` classification in `ApprovalInterpreter` must handle
plan-feedback replies well. This is a real implementation item, not just a
test note, because the current interpreter is intentionally coarse.
Examples that should classify as `DENY_AND_REISSUE`:

- "no, use web search instead"
- "combine the first two steps"
- "add a step to check the temperature unit"
- "looks good but remove the date step"

Planned changes:

1. Extend `ApprovalInterpreterInput` with approval-context text.
2. Include the staged action summary plus rendered plan context in the LLM
   fallback prompt.
3. Treat mixed replies like "approve, but ..." as `DENY_AND_REISSUE`, not
   `APPROVE`.
4. Keep plan-edit classification model-based/context-assisted rather than
   adding new deterministic marker parsing.
5. Validate this path with scenario-pack coverage, not just unit tests.

---

## Implementation Order

These are bounded work slices inside one consolidation update, not broader
spec-phase milestones.

### Work Slice 1: PlanRefiner
1. Create `PlanRefiner` request/result types
2. Add `LlmPlanRefiner` and `NoopPlanRefiner`
3. Add `planRefinementEnabled` to `PlannerConfig`
4. Wire `PlanRefiner` into `DecisionDispatcher` for `EgoDecision.EnqueuePlan`
5. Expose a shared Ego-side helper for durable-work planning and revision
6. Test with existing Ego inline plans
7. Add telemetry for `refinement_mode`, dropped-step count, repair usage, raw-plan parse failure, and fallback usage

### Work Slice 2: Move Plan Generation to Ego
1. Extend `DurableWorkCommand.Create` and `DurableWorkCommand.RevisePlan` with full `planSteps`
2. Extend `SerializedDurableWorkCommand` with the same step contract
3. Extend the goal planner's CREATE output schema to include full plan steps
4. Add step-id normalization and uniqueness rules after refinement
5. Add an Ego-owned revise-plan flow that builds revised plans from work-item context
6. Call `PlanRefiner` on generated durable-work plans before forming the intention
7. Update `DurableWorkRuntime.createWorkItem()` to use pre-built plans
8. Update `DurableWorkRuntime.executeOperation(REVISE_PLAN)` to apply supplied plans only
9. Update `DurableWorkOperationActionPlugin.repairPlannerPayload()` for the new schema
10. Delete `LlmWorkPlanBuilder`
11. Add rollout/migration handling for old or in-flight missing-plan payloads
12. Test end-to-end: create, approve, execute, revise, and re-approve flows

### Work Slice 3: Generalized Approval Context
1. Add `ApprovalContextEntry` model
2. Add `approvalContext` field to `StagedAction`
3. Add `buildApprovalContext()` to `ActionDescriptor` or `AgentActionPlugin`
4. Implement for `DurableWorkOperationActionPlugin` (plan steps rendering)
5. Update `ApprovalRuntime.deliverPrompt()` to render context entries
6. Update dashboard API to return context entries
7. Pass approval context into `ApprovalInterpreterInput`
8. Test: user sees plan steps in approval prompt and they are available to the interpreter

### Work Slice 4: Validate "Ask for Changes" Path
1. Update `ApprovalInterpreter` prompts/rules for plan-edit replies
2. Verify `DENY_AND_REISSUE` classification handles mixed replies and plan edits
   without adding new semantic text heuristics outside the model
3. Verify episodic recall surfaces the denial context for re-planning
4. Verify the re-generated plan goes through refinement and re-approval
5. Add scenario pack cases for the full deny -> reissue -> re-plan -> re-approve cycle
6. Document the user-facing interaction pattern
7. Add explicit coverage for unchanged-vs-rewritten refiner outcomes and fail-closed missing-plan behavior

---

## Files Changed (Estimated)

### Work Slice 1
| File | Change |
|------|--------|
| `agent/ego/planner/PlanRefiner.kt` | NEW: plan types + LlmPlanRefiner + NoopPlanRefiner |
| `agent/ego/DecisionDispatcher.kt` | Wire PlanRefiner into EnqueuePlan branch |
| `agent/config/PlannerConfig.kt` | Add `planRefinementEnabled` |
| `agent/ego/planner/PlannerRuntime.kt` | Expose PlanRefiner as shared service/helper |

### Work Slice 2
| File | Change |
|------|--------|
| `agent/ego/planner/model/DurableWorkCommand.kt` | Add full `planSteps` to Create and RevisePlan |
| `agent/ego/planner/model/SerializedDurableWorkCommand.kt` | Add `plan_steps` JSON field |
| `agent/ego/planner/input/DurableWorkPlanner.kt` | Generate plan steps in CREATE handler |
| `agent/ego/...` | NEW/updated Ego-side revise-plan flow using work-item context |
| `agent/durablework/DurableWorkRuntime.kt` | Use pre-built plan if present; apply Ego-supplied revised plans |
| `agent/durablework/WorkPlanBuilder.kt` | Delete `LlmWorkPlanBuilder` |
| `cortex/motor/actions/plugin/builtin/DurableWorkOperationActionPlugin.kt` | Update payload repair |

### Work Slice 3
| File | Change |
|------|--------|
| `agent/model/ActionLifecycleModels.kt` | Add `ApprovalContextEntry`, field on `StagedAction` |
| `cortex/motor/actions/ActionPluginContracts.kt` | Add `buildApprovalContext()` to descriptor |
| `cortex/motor/actions/plugin/builtin/DurableWorkOperationActionPlugin.kt` | Implement context builder |
| `admin/approvals/ApprovalRuntime.kt` | Render context entries in prompt |
| `admin/approvals/ApprovalInterpreter.kt` | Accept approval context as classification input |
| `dashboard/DashboardServer.kt` | Return context entries in API |

### Work Slice 4
| File | Change |
|------|--------|
| `freud/scenarios/` | New scenario pack cases for deny-reissue-replan cycle |
| `admin/approvals/ApprovalInterpreter.kt` | Add plan-edit classification coverage |

---

## Risks and Mitigations

**Risk:** PlanRefiner LLM call adds latency to every plan.
**Mitigation:** Keep it to one bounded call only. Do not add pre-refinement
logic layers. Fallback to the original planner plan on failure when meaning is
still preserved and final mechanical boundary checks pass.

**Risk:** Planner produces CREATE or REVISE payload without plan steps.
**Mitigation:** Clean break by policy: fail closed by default. Silent runtime
re-planning is not allowed once ownership moves into Ego. A deterministic
fallback may exist only behind an explicit migration/emergency flag.

**Risk:** DENY_AND_REISSUE doesn't carry enough context for re-planning.
**Mitigation:** Episodic memory records the denial, the approval context is
included in interpreter input, and the reissued message contains the user's
explicit feedback. Validate in Work Slice 4 scenario pack.

**Risk:** Mixed replies like "approve, but ..." are misclassified and bypass the
re-plan path.
**Mitigation:** Treat mixed approval-plus-edit replies as `DENY_AND_REISSUE`,
pass approval context into interpreter input, and cover this with scenario-pack
tests.

**Risk:** Approval context entries grow unbounded.
**Mitigation:** Clamp total context size at rendering time (same pattern
as `GOAL_WORKING_CONTEXT_MAX_CHARS`). Individual entries capped by the
action payload size limit.

**Risk:** Nullable plan fields blur the intended fail-closed ownership boundary.
**Mitigation:** Keep nullability only for parser compatibility and controlled
rollout. New planner output must always emit non-empty `planSteps`, and missing
plans should be observable migration/recovery traffic rather than silent normal
flow.

**Risk:** A single universal refiner prompt distorts durable-work behavior.
**Mitigation:** Pass `planKind` and `terminalPolicy` explicitly. The refiner
uses different rubric branches for inline Ego plans, durable-work create, and
durable-work revise.

**Risk:** Adding a non-LLM preprocessing stage would reintroduce brittle
deterministic semantics and hidden planner-output mutation.
**Mitigation:** Do not add a standalone normalizer. Keep semantic repair inside
the refiner. Outside the refiner, only perform minimal mechanical boundary
checks needed for execution/storage safety.

---

## Strict Completion Criteria

This plan is fully complete only when all of the following are true:

1. Durable-work `CREATE` planning is fully Ego-owned.
   `DurableWorkCommand.Create` is emitted with non-empty structured `planSteps`,
   those steps pass through `PlanRefiner`, and the normal runtime path does not
   generate its own plan.
2. Durable-work `REVISE_PLAN` planning is fully Ego-owned.
   `DurableWorkCommand.RevisePlan` is emitted with non-empty structured
   `planSteps`, those steps pass through `PlanRefiner`, and the runtime applies
   the supplied plan rather than generating one.
3. Inline Ego plans use the same refinement path.
   `EgoDecision.EnqueuePlan` goes through `PlanRefiner` before enqueue, and plan
   dedup/hash behavior uses the refined plan rather than the raw planner output.
4. The refiner remains bounded and safe.
   `LlmPlanRefiner` uses the standard retry/validation/fallback pattern,
   `NoopPlanRefiner` exists for tests/disabled mode, and fallback to the
   original plan is gated by the documented mechanical boundary checks only.
5. The runtime plan contract is preserved.
   The structured durable-work step model still carries `id`, `description`,
   `acceptanceCriteria`, `groundingRequirement`, `requires`, `produces`, and
   `maxAttempts`, with deterministic id normalization after refinement.
6. Runtime-side hidden planning is removed from normal control flow.
   `LlmWorkPlanBuilder` is deleted or no longer reachable in standard
   `CREATE`/`REVISE_PLAN` execution, and missing-plan behavior fails closed
   outside an explicit migration/recovery path.
7. Approval context is generic and display-only.
   `StagedAction` carries `approvalContext` as labeled text entries, the
   producer builds display-ready text from canonical structured payloads, and
   that text is never parsed back into runtime plan state.
8. Durable-work plan approval shows the plan that will actually execute.
   The durable-work plugin builds a `Plan` approval-context entry from the
   refined structured plan, and the approval prompt shows that text to the user
   before work-item creation.
9. Plan-edit feedback works through the existing approval loop.
   Approval interpretation receives approval context as input, plan-edit replies
   classify to `DENY_AND_REISSUE`, mixed replies like "approve, but ..." do not
   bypass replanning, and the reissued input goes back through planning,
   refinement, and approval.
10. Rollout behavior is explicit and observable.
    Nullable `planSteps` support exists only for parser compatibility and
    controlled migration, with telemetry that distinguishes normal Ego-planned
    traffic from migration/recovery traffic.
11. Telemetry is present for the new control points.
    At minimum this includes refinement mode, dropped-step count, refiner
    fallback usage, raw-plan parse/validation failure, and missing-plan
    fail-closed or migration-path events.
12. Automated validation covers the whole change.
    Unit/integration coverage exists for unchanged-vs-rewritten refinement,
    mechanical boundary check failures, durable-work create/revise happy paths,
    fail-closed missing-plan behavior, approval-context rendering, and
    deny/reissue plan-edit handling.
13. Scenario-level validation covers the user-visible loop.
    Scenario-pack coverage exists for create -> approve -> execute and deny ->
    reissue -> re-plan -> re-approve, and the deterministic signoff gate passes
    before the work is considered complete.
