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
2. Add a `PlanRefiner` that validates and improves all plans before they are committed.
3. Surface plans to the user for approval before work items are created.
4. Allow the user to request changes using existing approval flows, not ad-hoc code.
5. Generalize the approval system to carry rich context, reusable beyond durable work.

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
[PlanRefiner] refines the steps (same Ego cycle, same context)
    - Single LLM call: validates achievability, prunes wasteful steps, fixes ordering
    - On refinement failure: accepts original plan (best-effort)
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
[PlanRefiner] refines the steps (in DecisionDispatcher, before enqueue)
    - Same single LLM call, same validation criteria
    - On refinement failure: accepts original plan
    |
    v
[DecisionDispatcher] enqueues refined steps as continuations (existing flow)
```

---

## Phase 1: PlanRefiner Component

### Interface

```kotlin
// src/main/kotlin/ai/neopsyke/agent/ego/planner/PlanRefiner.kt

interface PlanRefiner {
    fun refine(request: PlanRefinementRequest): PlanRefinementResult
}

data class PlanRefinementRequest(
    val goal: String,
    val instruction: String,
    val steps: List<PlanStepCandidate>,
    val availableActions: List<ActionSummary>,
    val runtimeFacts: Map<String, String>,
    val completionCriteria: String = "",
)

data class PlanStepCandidate(
    val id: String,
    val description: String,
    val acceptanceCriteria: String = "",
    val groundingRequirement: String = "not_required",
)

data class ActionSummary(
    val actionType: String,
    val description: String,
)

data class PlanRefinementResult(
    val accepted: Boolean,
    val steps: List<PlanStepCandidate>,
    val droppedSteps: List<DroppedStep>,
    val reason: String,
)

data class DroppedStep(
    val originalId: String,
    val reason: String,
)
```

### Implementations

**`LlmPlanRefiner`** --- makes one LLM call. Follows the standard LLM caller
pattern (retry loop, required-field validation, safe fallback). On any failure
(LLM error, parse failure, timeout), returns the original plan unchanged.

**`NoopPlanRefiner`** --- returns the plan unchanged. Used in tests and when
refinement is disabled via config.

### LLM Refinement Prompt

The refiner receives:

- The original goal/instruction
- The generated plan steps (as JSON array)
- Available actions with descriptions
- Runtime facts (date, time, timezone)
- Completion criteria

Validation criteria baked into the system prompt:

1. **Achievability** --- can each step be executed with exactly one available action?
2. **Non-redundancy** --- does the step add value beyond runtime facts and prior steps?
3. **Data flow** --- do step outputs feed forward correctly?
4. **Minimal sufficiency** --- can steps be merged or dropped without losing capability?
5. **Final delivery** --- does the plan end with user-facing output (contact_user)?

Response schema:

```json
{
  "accepted": true
}
```

or:

```json
{
  "accepted": false,
  "revised_steps": [
    {
      "id": "step1",
      "description": "Search for current Hamburg weather forecast",
      "acceptance_criteria": "Weather data retrieved",
      "grounding_requirement": "required"
    },
    {
      "id": "step2",
      "description": "Send weather summary to the user",
      "acceptance_criteria": "User receives forecast message",
      "grounding_requirement": "not_required"
    }
  ],
  "dropped_steps": [
    { "original_id": "step1", "reason": "Date is a runtime fact, no step needed" }
  ],
  "reason": "Removed redundant date-lookup step; merged fetch+format"
}
```

### Configuration

Add to `PlannerConfig`:

```kotlin
val planRefinementEnabled: Boolean = true
```

The refiner uses the planner cognitive role's model client --- no separate provider.

### Where It Plugs In

**Call site 1 --- Durable work CREATE (Phase 2):**
After the Ego planner generates plan steps as part of the CREATE decision,
before the action is staged for approval.

**Call site 2 --- Ego inline plans:**
In `DecisionDispatcher.dispatch()`, inside the `EgoDecision.EnqueuePlan` branch,
before the plan hash/dedup/scratchpad/step-enqueue logic (currently ~line 363).
The dispatcher calls `planRefiner.refine(...)` and uses the refined steps.

The `PlanRefiner` is injected into `DecisionDispatcher` (constructor parameter)
alongside the existing dependencies. For call site 1, the refiner is accessed
through the planner infrastructure that the Ego already uses.

---

## Phase 2: Move Plan Generation to the Ego

### Current State

```
Ego planner → EgoDecision.FormIntention(durable_work_operation, CREATE payload)
    → ActionPlugin.execute() → DurableWorkRuntime.createWorkItem()
        → LlmWorkPlanBuilder.generatePlan()   ← LLM call in motor layer (wrong)
        → stores plan in work item
```

### Target State

```
Ego planner → generates plan steps with full context
    → PlanRefiner refines the steps
    → EgoDecision.FormIntention(durable_work_operation, CREATE payload WITH steps)
    → ActionPlugin.execute() → DurableWorkRuntime.createWorkItem()
        → uses pre-built plan from payload (no LLM call)
```

### Step 2a: Extend CREATE Payload to Carry Plan Steps

**`DurableWorkCommand.Create`** gains an optional `planSteps` field:

```kotlin
data class Create(
    val title: String,
    val instruction: String,
    val priority: WorkItemPriority = WorkItemPriority.MEDIUM,
    val completionCriteria: String = "",
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val planSteps: List<PlanStepPayload>? = null,  // NEW
) : DurableWorkCommand
```

**`SerializedDurableWorkCommand`** gains the corresponding JSON field:

```kotlin
@param:JsonProperty("plan_steps")
val planSteps: List<SerializedPlanStep>? = null,
```

With:

```kotlin
data class SerializedPlanStep(
    val id: String? = null,
    val description: String,
    @param:JsonProperty("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    @param:JsonProperty("grounding_requirement")
    val groundingRequirement: String? = null,
)
```

### Step 2b: Make the Ego Planner Generate Steps

The goal planner lane that handles durable work creation requests
(in the input intent router → goal planner path) currently produces a
`DurableWorkCommand.Create` with title, instruction, cron, etc. It needs
to also produce `planSteps`.

This is done by extending the planner prompt for durable work creation to
also output plan steps. The planner already has full context (available actions,
memory, episodic recall, dialogue) --- it just needs the schema to include steps.

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
      "grounding_requirement": "required|not_required"
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

### Step 2c: DurableWorkRuntime Uses Pre-Built Plan

`DurableWorkRuntime.createWorkItem()` changes:

```kotlin
// If the CREATE payload includes plan steps, use them directly.
// Otherwise, fall back to DeterministicWorkPlanBuilder (single-step).
val plan = if (planSteps != null && planSteps.isNotEmpty()) {
    WorkItemPlan(
        steps = planSteps.mapIndexed { i, step ->
            PlanStep(
                id = step.id ?: "step-${i + 1}",
                description = step.description,
                status = StepStatus.PENDING,
                acceptanceCriteria = step.acceptanceCriteria ?: completionCriteria,
                groundingRequirement = parseGroundingRequirement(step.groundingRequirement),
            )
        },
        generatedAt = Instant.now(),
    )
} else {
    DeterministicWorkPlanBuilder().generatePlan(workItem)
}
```

**`LlmWorkPlanBuilder` is deleted.** Plan generation is now the Ego's
responsibility. `DeterministicWorkPlanBuilder` remains as the fallback for
CREATE commands without explicit steps.

---

## Phase 3: Generalized Approval Context

### Problem

The approval system currently shows the user:
- `summary`: "Create goal: Daily weather reminder"
- `reason`: "Recurring goal mutations require staged approval."

There is no mechanism to display rich context (like plan steps, cost estimates,
impact descriptions) alongside the approval prompt. This is needed for durable
work plans but is a general capability gap.

### Solution: Add `approvalContext` to StagedAction

**Do not** create durable-work-specific approval rendering. Instead, add a
generic structured context field that any action type can populate.

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
    val contentType: ApprovalContextContentType = ApprovalContextContentType.TEXT,
)

enum class ApprovalContextContentType {
    TEXT,
    MARKDOWN,
    JSON,
}
```

This is intentionally simple --- labeled key-value pairs with an optional
content type hint for rendering. Any action plugin or staging policy can
attach context entries.

#### Where Context Is Attached

The `ActionControlRuntime` (or the staging policy that creates the `StagedAction`)
populates `approvalContext` at staging time. For durable work CREATE:

```kotlin
approvalContext = listOf(
    ApprovalContextEntry(
        label = "Plan",
        content = planSteps.mapIndexed { i, step ->
            "${i + 1}. ${step.description}"
        }.joinToString("\n"),
        contentType = ApprovalContextContentType.MARKDOWN,
    ),
)
```

The context entries are derived from the action payload at staging time.
This does not require the `ActionControlRuntime` to understand durable work
semantics --- the action plugin descriptor can declare a
`buildApprovalContext(payload): List<ApprovalContextEntry>` method that the
staging logic calls generically.

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

**Dashboard API** can return the context entries as structured data for
richer rendering (expandable sections, markdown formatting, etc.).

#### Future Reuse

Other action types can attach approval context:

- **email_send**: recipient, subject, body preview
- **calendar_observe_events**: date range, filter criteria
- **Any new action**: whatever context helps the user make an informed decision

No new code paths needed --- just populate `approvalContext` in the action
descriptor's `buildApprovalContext()` method.

---

## Phase 4: User Feedback on Plans ("Ask for Changes")

### Problem

When the user sees a plan they don't like, they need to be able to say
"no, use a web search instead of an API call" or "combine steps 2 and 3."

### Solution: Use DENY_AND_REISSUE (Existing Flow)

The approval system already has `DENY_AND_REISSUE`:

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
the planner everything it needs to revise the plan. No structured
"plan revision" protocol is required.

### What Needs Attention

The `DENY_AND_REISSUE` classification in `ApprovalInterpreter` must handle
plan-feedback replies well. Currently it's a general-purpose classifier.
Examples that should classify as `DENY_AND_REISSUE`:

- "no, use web search instead"
- "combine the first two steps"
- "add a step to check the temperature unit"
- "looks good but remove the date step"

These are all denials with alternative instructions. The existing
`ApprovalInterpreter` (deterministic + LLM fallback) should handle these
since they're clearly "I don't want this, do this instead" --- which is
the core `DENY_AND_REISSUE` pattern. No changes to the interpreter are
expected, but this should be validated during testing.

---

## Implementation Order

### Phase 1: PlanRefiner (can ship independently)
1. Create `PlanRefiner` interface and `LlmPlanRefiner` implementation
2. Add `NoopPlanRefiner` for tests
3. Add `planRefinementEnabled` config knob to `PlannerConfig`
4. Wire `PlanRefiner` into `DecisionDispatcher` for `EgoDecision.EnqueuePlan`
5. Add `PlanRefiner` to `PlannerRuntime` as a shared service
6. Test with existing Ego inline plans

### Phase 2: Move Plan Generation to Ego (depends on Phase 1)
1. Extend `DurableWorkCommand.Create` and `SerializedDurableWorkCommand` with `planSteps`
2. Extend the goal planner's CREATE output schema to include plan steps
3. Call `PlanRefiner` on the generated steps before forming the intention
4. Update `DurableWorkRuntime.createWorkItem()` to use pre-built plans
5. Update `DurableWorkOperationActionPlugin.repairPlannerPayload()` for new schema
6. Delete `LlmWorkPlanBuilder`
7. Test end-to-end: user request → plan generation → refinement → staged → approved → work item with plan

### Phase 3: Generalized Approval Context (depends on Phase 2)
1. Add `ApprovalContextEntry` model
2. Add `approvalContext` field to `StagedAction`
3. Add `buildApprovalContext()` to `ActionDescriptor` or `AgentActionPlugin`
4. Implement for `DurableWorkOperationActionPlugin` (plan steps rendering)
5. Update `ApprovalRuntime.deliverPrompt()` to render context entries
6. Update dashboard API to return context entries
7. Test: user sees plan steps in approval prompt

### Phase 4: Validate "Ask for Changes" Path (depends on Phase 3)
1. Verify `DENY_AND_REISSUE` classification handles plan-feedback replies
2. Verify episodic recall surfaces the denial context for re-planning
3. Verify the re-generated plan goes through refinement and re-approval
4. Add scenario pack cases for the full deny → reissue → re-plan → re-approve cycle
5. Document the user-facing interaction pattern

---

## Files Changed (Estimated)

### Phase 1
| File | Change |
|------|--------|
| `agent/ego/planner/PlanRefiner.kt` | NEW: interface + LlmPlanRefiner + NoopPlanRefiner |
| `agent/ego/DecisionDispatcher.kt` | Wire PlanRefiner into EnqueuePlan branch |
| `agent/config/PlannerConfig.kt` | Add `planRefinementEnabled` |
| `agent/ego/planner/PlannerRuntime.kt` | Expose PlanRefiner as shared service |

### Phase 2
| File | Change |
|------|--------|
| `agent/ego/planner/model/DurableWorkCommand.kt` | Add `planSteps` to Create |
| `agent/ego/planner/model/SerializedDurableWorkCommand.kt` | Add `plan_steps` JSON field |
| `agent/ego/planner/input/DurableWorkPlanner.kt` | Generate plan steps in CREATE handler |
| `agent/durablework/DurableWorkRuntime.kt` | Use pre-built plan if present |
| `agent/durablework/WorkPlanBuilder.kt` | Delete `LlmWorkPlanBuilder` |
| `cortex/motor/actions/plugin/builtin/DurableWorkOperationActionPlugin.kt` | Update payload repair |

### Phase 3
| File | Change |
|------|--------|
| `agent/model/ActionLifecycleModels.kt` | Add `ApprovalContextEntry`, field on `StagedAction` |
| `cortex/motor/actions/ActionPluginContracts.kt` | Add `buildApprovalContext()` to descriptor |
| `cortex/motor/actions/plugin/builtin/DurableWorkOperationActionPlugin.kt` | Implement context builder |
| `admin/approvals/ApprovalRuntime.kt` | Render context entries in prompt |
| `dashboard/DashboardServer.kt` | Return context entries in API |

### Phase 4
| File | Change |
|------|--------|
| `freud/scenarios/` | New scenario pack cases for deny-reissue-replan cycle |
| `admin/approvals/ApprovalInterpreter.kt` | Validate classification (likely no changes) |

---

## Risks and Mitigations

**Risk:** PlanRefiner LLM call adds latency to every plan.
**Mitigation:** Single call, ~500 prompt tokens. Config knob to disable.
Fallback to original plan on any failure.

**Risk:** Planner produces CREATE payload without plan steps (model regression).
**Mitigation:** `DeterministicWorkPlanBuilder` fallback in `createWorkItem()`.
Work item is always created with at least a single-step plan.

**Risk:** DENY_AND_REISSUE doesn't carry enough context for re-planning.
**Mitigation:** Episodic memory records the denial. The reissued message
contains the user's explicit feedback. Together these provide sufficient
context. Validated in Phase 4 scenario pack.

**Risk:** Approval context entries grow unbounded.
**Mitigation:** Clamp total context size at rendering time (same pattern
as `GOAL_WORKING_CONTEXT_MAX_CHARS`). Individual entries capped by the
action payload size limit.
