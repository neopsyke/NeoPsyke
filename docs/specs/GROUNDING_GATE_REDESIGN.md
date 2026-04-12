# Grounding Gate Redesign

**Status:** Draft
**Created:** 2026-04-07
**Branch:** refactor/typed-planner-redesign

## Problem Statement

The current `DeterministicDecisionVerifier` is a post-planning gate that decides
whether a `contact_user` action needs to be backed by fresh external evidence
before delivery. It uses keyword-based heuristics on the raw user input text to
classify intent and score volatility.

This design has three architectural flaws:

1. **Scope mismatch.** The verifier classifies volatility from the *user's input
   text* but gates individual *actions*. The user's input and the action's
   semantic purpose can diverge. Example: "Create a daily weather goal" is a
   system-mutation request, but the verifier sees "weather" + "daily" and
   demands external evidence before allowing the operational confirmation
   "Goal created: Daily Hamburg Weather."

2. **Late classification.** The grounding decision happens *after* planning and
   execution, at delivery time. When evidence is required but missing, the
   system enters a deny-requeue-replan loop that wastes a full planning cycle.
   The planner was never told that grounding was needed.

3. **Keyword heuristics.** The verifier uses substring matching against
   hardcoded signal lists (`"weather"`, `"price"`, `"today"`, etc.) to
   classify intent. This violates the project's planner routing rule: no
   deterministic routing on natural-language input. It is also brittle,
   locale-dependent, and impossible to reason about exhaustively.

### Triggering Incident

In run `20260407T035932Z-56036`, the user requested creation of a recurring
weather goal. The goal was created successfully (action_id=2, `goal_operation`,
allowed). The system then generated a `contact_user` action (action_id=3) to
confirm the goal was created. The verifier blocked it:

```
task_verifier.review action_id=3 allow=false
  reason_code=TASK_EVIDENCE_REQUIRED
  intent=volatile_fact volatility=high/5
  requires_external_evidence=true
  had_successful_evidence=false
```

The confirmation message ("Goal created: Daily Hamburg Weather. Recurs on cron
'5 6 * * *'.") contained zero volatile facts -- it was reporting the outcome of
a system operation. The verifier blocked it because the user's input mentioned
"weather" and "daily."

---

## Current Architecture

### Component: `DeterministicDecisionVerifier`

**Location:** `src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt`

**Position in pipeline:** Called by `ActionReviewPipeline.passesDecisionVerifier()`
(ActionReviewPipeline.kt:441) after the planner has planned and actions are ready
for dispatch or delivery.

**Single responsibility:** Decide whether a `contact_user` action must be backed
by at least one successful external evidence action (web_search, website_fetch,
Google Workspace observe actions, etc.) before being delivered to the user.

### Decision Flow

```
contact_user action ready for delivery
  |
  +-- Non-contact_user or isFallbackExplanation? --> ALLOW (bypass)
  +-- Summary contains "forced terminal answer"?  --> ALLOW (bypass)
  |
  +-- Classify user input via keyword matching:
  |     TRANSFORMATION / PERSONAL_MEMORY / SUBJECTIVE_ADVICE --> ALLOW
  |     STATIC_REASONING / STABLE_FACT                       --> ALLOW
  |     VOLATILE_FACT (score >= 3)                           --> requires evidence
  |     UNKNOWN (score >= 3)                                 --> requires evidence
  |
  +-- Evidence gate (only for requiresExternalEvidence=true):
        evidence gathered successfully?         --> ALLOW
        evidence tools unavailable?             --> ALLOW (graceful)
        evidence tools failed?                  --> DENY (TECH_EXTERNAL_EVIDENCE_FAILURE)
        evidence not attempted?                 --> DENY (TASK_EVIDENCE_REQUIRED)
```

### Downstream Consumers

The verifier's classification fields (`intentCategory`, `volatilityLevel`,
`volatilityScore`) are emitted as telemetry only. No downstream component reads
them for behavioral decisions. The only behavioral outputs are:

| Output             | Consumers                               |
|--------------------|-----------------------------------------|
| `allow` (boolean)  | ActionReviewPipeline: block or pass      |
| `reasonCode`       | FallbackHandler, SharedPromptSections, MemorySystem: classify denial as technical vs. policy, generate retry guidance |
| `reason` (text)    | FallbackHandler: choose fallback explanation type |

### What Gets Removed

The entire `DeterministicDecisionVerifier` class and all its supporting types:

- `DeterministicDecisionVerifier` (DecisionVerifier.kt:61-349)
- `TaskIntentCategory` enum (DecisionVerifier.kt:18-26)
- `VolatilityLevel` enum (DecisionVerifier.kt:28-32)
- `DecisionVerifierAssessment` data class (DecisionVerifier.kt:34-43)
- `TaskClassification` private data class (DecisionVerifier.kt:128-133)
- All keyword signal sets: `recencySignals`, `dynamicDomainSignals`,
  `transformationSignals`, `personalMemorySignals`, `subjectiveAdviceSignals`,
  `staticReasoningSignals`, `factualQuerySignals`, `dateSensitiveRegex`
- `classifyTaskIntent()`, `volatilityScore()`, `containsAny()`, `countSignals()`,
  `isForcedTerminalAnswer()` methods

The `DecisionVerifier` interface and `NoopDecisionVerifier` remain available as
the post-gate interface (see below).

---

## Proposed Architecture

Split the current single component into two concerns at the correct pipeline
positions:

```
User input arrives
  |
  [1] GROUNDING CLASSIFIER (new, at input intake)
  |     Determines: does this input need grounded external data?
  |     Sets: typed grounding metadata on the runtime envelope
  |     Mechanism: typed hierarchy pre-filter + small LLM call for ambiguous cases
  |
  [2] PLANNER receives grounding metadata in PlannerContext
  |     Plans accordingly (includes evidence-gathering actions when true)
  |
  [3] Runtime copies grounding metadata forward on every derived envelope
  |     (opportunity -> thought -> action -> feedback), never asking an LLM to
  |     echo or reconstruct it
  |
  [4] GROUNDING GATE (simplified, at delivery)
        Enforces: if requirement=REQUIRED && !hadSuccessfulEvidence --> DENY
        Mechanism: typed metadata + typed evidence state, no text analysis
```

### 1. Grounding Classifier

**Position:** After input intake, before planner dispatch. Runs as part of the
InputIntentRouter flow (InputPlanner orchestration).

**Purpose:** Classify whether the user's request requires the final answer to
be backed by fresh external evidence.

**Output:** Typed grounding metadata attached to the input envelope and carried
forward by runtime-created envelopes. The LLM never returns, echoes, or
reconstructs this metadata.

**Behavioral contract:** Binary only.

```kotlin
enum class GroundingRequirement {
    REQUIRED,
    NOT_REQUIRED,
}

enum class GroundingSource {
    INPUT_PREFILTER,
    INPUT_CLASSIFIER,
    GOAL_STEP_POLICY,
    INHERITED,
}

data class GroundingMetadata(
    val requirement: GroundingRequirement,
    val source: GroundingSource,
)
```

There is deliberately no `UNKNOWN` runtime state. For now, ambiguous-route
classifier failures are coerced to `NOT_REQUIRED`. This is a conscious
graceful-degradation choice, not an assertion that the request was definitely
safe to answer ungrounded. Implementation comments should call this out
explicitly so it can be revisited if production telemetry shows the fail-open
behavior is too permissive.

#### Typed Hierarchy Pre-Filter (Deterministic, No LLM)

The existing `InputRoute` sealed hierarchy already classifies the structural
intent of the input. Many routes can deterministically skip the grounding
question entirely:

| InputRoute Variant     | Grounding result                    | Rationale                                 |
|------------------------|-------------------------------------|-------------------------------------------|
| `GoalCreation`         | `GroundingRequirement.NOT_REQUIRED` | System mutation, not a factual answer     |
| `GoalManagement`       | `GroundingRequirement.NOT_REQUIRED` | System mutation, not a factual answer     |
| `ClarificationNeeded`  | `GroundingRequirement.NOT_REQUIRED` | Agent is asking the user, not answering   |
| `Noop`                 | `GroundingRequirement.NOT_REQUIRED` | No action taken                           |
| `DirectResponse`       | **needs LLM**                       | Could be volatile fact or static reasoning |
| `GeneralAction`        | **needs LLM**                       | Could be evidence-gathering or internal   |
| `MultiStepTask`        | **needs LLM**                       | Could include volatile-fact subtasks      |

For non-input triggers (EgoTrigger variants that bypass InputIntentRouter):

| Trigger Type                   | Grounding result                    | Rationale                                      |
|--------------------------------|-------------------------------------|------------------------------------------------|
| `EgoTrigger.GoalWork`          | *per-step policy*                   | Goal step execution may need grounding; see Goal Work section below |
| `EgoTrigger.DeferredIntention` | *inherited metadata*                | Carries the same metadata from the original root |
| `EgoTrigger.ActionFeedback`    | *inherited metadata*                | Carries the same metadata from the originating action/root |
| `EgoTrigger.IncomingImpulse`   | `GroundingRequirement.NOT_REQUIRED` | Self-motivated; no user-facing factual claim    |

#### LLM Classification Call (For Ambiguous Routes)

When the pre-filter cannot decide (`DirectResponse`, `GeneralAction`,
`MultiStepTask`), a lightweight LLM call classifies the input:

- **Model:** Use a cheap, fast model (same tier as `approval_interpreter`,
  currently `openai/gpt-5-nano`). This becomes a new cognitive role:
  `grounding_classifier`.
- **Input:** The user's message text (same as what InputIntentRouter already has).
- **Output:** Structured JSON with a single boolean field:
  ```json
  { "grounding_required": true }
  ```
- **System prompt:** Focused on one question: "Does answering this request
  require fetching up-to-date information from external sources (web, APIs,
  live data)? Answer true only if the user is asking for facts that change
  over time and cannot be answered from general knowledge alone."
- **Fallback on parse/model failure:** coerce to `NOT_REQUIRED` for now. Emit a
  warning log and explicit classification telemetry so this fail-open behavior
  is observable and can be tightened later if it proves unsafe.

#### Timing

The grounding classification runs **after** the InputIntentRouter LLM call
returns the InputRoute, but **before** the L2 sub-planner runs. This means:

1. InputIntentRouter classifies input -> `InputRoute` variant
2. Typed pre-filter checks the variant (deterministic, zero-cost)
3. If ambiguous: grounding classifier LLM call (cheap, parallel-safe)
4. `GroundingMetadata` set on the runtime input envelope
5. L2 sub-planner receives it via `PlannerContext` and plans accordingly

The grounding classifier call can run **in parallel** with other pre-planning
work (memory recall, episodic recall) since it has no dependencies on those
results.

#### Goal Work: Per-Step Grounding

When a goal fires (cron trigger), the resulting `GoalRunActivation` must carry
typed grounding metadata determined earlier by goal-step policy. `GoalWorkPlanner`
must consume this typed metadata; it must not infer grounding from free-text
step descriptions.

This requires goal-step/activation models to grow a typed grounding field
(for example on `PlanStep`, `GoalRunActivation`, or both). The exact storage
site is an implementation choice, but the activation handed to the Ego loop
must already know whether grounding is required.

This means: creating a weather goal (`GoalCreation`) -> `NOT_REQUIRED`.
Running the weather goal at 06:05 (`GoalWork`) -> `REQUIRED` because the goal
step policy marks the delivery step as requiring fresh evidence.

### 2. Runtime Metadata Ownership And Propagation

`GroundingMetadata` is execution metadata, not scratchpad content. The scratchpad
is not the owner and must not be consulted as the source of truth.

The metadata should live on the runtime envelope objects that already traverse
the Ego loop. It must be copied forward whenever the runtime derives a new
envelope from an existing one.

```kotlin
// Examples of carriers; exact field names may vary.
PendingInput.groundingMetadata: GroundingMetadata
ScheduledOpportunity.groundingMetadata: GroundingMetadata
QueuedIntention.groundingMetadata: GroundingMetadata
PendingAction.groundingMetadata: GroundingMetadata
ActionFeedbackCue.groundingMetadata: GroundingMetadata
GoalRunActivation.groundingMetadata: GroundingMetadata
```

**Immutability guarantee:** for a given root-scoped execution branch, the
metadata is set exactly once at classification/policy time and then copied
forward unchanged. Downstream components may read it, but not reinterpret it
from natural-language content and not ask any model to return it.

**Inheritance rule:** when a deferred intention, action feedback cue, or any
other continuation is created from an existing rooted envelope, the new
envelope inherits the same `GroundingMetadata`.

**No LLM relay rule:** grounding metadata is runtime-only. It must never be
encoded into a prompt and then parsed back from the model as a control flag.

### 3. Planner Hint

`PlannerContext` gets the typed grounding metadata so every planner lane can
see the same root-scoped requirement.

```kotlin
data class PlannerContext(
    ...
    val groundingMetadata: GroundingMetadata = GroundingMetadata(
        requirement = GroundingRequirement.NOT_REQUIRED,
        source = GroundingSource.INPUT_PREFILTER,
    ),
)
```

When `groundingMetadata.requirement == REQUIRED`, the planner prompt includes a
grounding directive. This must be implemented as one shared prompt section
helper used by all relevant lanes, returning `null` when grounding is not
required so there is zero token cost for ordinary requests.

**Recommended pattern:**

```kotlin
SharedPromptSections.groundingRequirementSection(context): Section?
```

- Returns `null` when `NOT_REQUIRED`
- Returns one short section when `REQUIRED`
- Included by all answer-producing planner lanes (`DirectResponsePlanner`,
  `GeneralActionPlanner`, `TaskDecompositionPlanner`, `DeferredStepPlanner`,
  `FeedbackPlanner`, `GoalWorkPlanner`, and any future equivalent lane)

The guidance should be operational, short, and only present when grounding is
required:

```
GROUNDING REQUIREMENT: This request requires up-to-date external information.
You MUST gather external evidence before any final user-facing factual answer.
Use one or more evidence-gathering actions and base the answer on gathered
results. If prior evidence attempts failed technically, retry evidence gathering
with the same or an alternate evidence source before giving up.
```

This turns the grounding requirement into a proactive planning constraint
rather than a reactive post-hoc denial.

### 4. Grounding Gate (Post-Gate)

**Position:** Same position as the current `DeterministicDecisionVerifier` in
`ActionReviewPipeline.passesDecisionVerifier()`.

**Purpose:** Enforce that evidence was actually gathered when required. This is
a safety net against planner non-compliance or tool failures, not the primary
classification mechanism.

**Logic:**

```
fun review(action, context):
    if action.type != CONTACT_USER:
        return ALLOW

    if action.isFallbackExplanation:
        return ALLOW

    if action.isForcedTerminal && action.groundingMetadata.requirement == NOT_REQUIRED:
        return ALLOW

    if action.isForcedTerminal && evidenceGathered:
        return ALLOW

    if action.groundingMetadata.requirement == NOT_REQUIRED:
        return ALLOW

    evidenceGathered = deliberation.externalEvidence.hadSuccessfulEvidence
    if evidenceGathered:
        return ALLOW

    evidenceUnavailable = no evidence action types available or dispatchable
    if evidenceUnavailable:
        return ALLOW  // graceful degradation, same as current behavior

    evidenceFailedTechnically = deliberation.externalEvidence.hadExternalFailures
    if evidenceFailedTechnically:
        if action.isForcedTerminal && failureBudgetExceeded:
            return ALLOW_DEGRADED_BEST_EFFORT
        return DENY(TECH_GROUNDING_EVIDENCE_FAILURE)

    return DENY(GROUNDING_EVIDENCE_REQUIRED)
```

This is the complete logic. No keyword matching, no intent classification, no
volatility scoring. The gate reads:

- carried `GroundingMetadata`
- typed external evidence state
- evidence tool availability
- typed forced-terminal marker

#### Forced Terminal Answers

The old summary-string heuristic (`"forced terminal answer"`) must be removed.
Forced-terminal behavior becomes typed execution metadata on the action and/or
origin.

Recommended behavior:

- `NOT_REQUIRED` -> forced terminal answers are allowed normally
- `REQUIRED` + successful evidence present -> allowed, but the answer must use
  gathered evidence
- `REQUIRED` + only technical evidence failures present -> deny with
  `TECH_GROUNDING_EVIDENCE_FAILURE` until the bounded failure budget is
  exceeded; then allow a degraded best-effort terminal answer with an explicit
  verification-failure disclaimer
- `REQUIRED` + no evidence attempted -> do not bypass the gate; force an
  evidence-gathering step first

This preserves convergence behavior without silently dropping grounding.

#### Deny -> Requeue -> Replan Flow

When the gate denies, it follows the existing `FallbackHandler.handleDeniedAction()`
flow with denial thoughts that depend on reason code.

**Reason codes:**

- `GROUNDING_EVIDENCE_REQUIRED`
- `TECH_GROUNDING_EVIDENCE_FAILURE`
- `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL` (allow path only; optional but
  recommended for observability)

**Denial thought for `GROUNDING_EVIDENCE_REQUIRED`:**

```
Grounding gate: this request requires external evidence, but no successful
evidence-gathering action has run yet. Dispatch an evidence-gathering action
and use its results before answering. Do not repeat the denied contact_user.
```

**Denial thought for `TECH_GROUNDING_EVIDENCE_FAILURE`:**

```
Grounding gate: this request requires external evidence and prior evidence
attempts failed for technical/transient reasons. Retry evidence gathering with
the same or an alternate evidence source before answering. A repeated evidence
attempt is allowed here.
```

This protects against:
- Planner ignoring the grounding hint and answering from training data
- Evidence action dispatched but failed silently
- LLM hallucinating evidence content without actually fetching it

The requeue uses the same `enqueueDeferredIntention()` path as today, so
existing infrastructure (DeferredStepPlanner, SharedPromptSections retry
guidance, lesson recording) continues to work unchanged.

Keeping `TECH_GROUNDING_EVIDENCE_FAILURE` distinct matters because repeated
denied-action handling already treats technical denials differently from hard
task/policy denials. Collapsing the two would incorrectly penalize legitimate
retries after transient evidence tool failures.

### 5. Telemetry

The grounding gate emits a structured telemetry event replacing the current
verifier telemetry/logging path (`task_verifier_review` event and
`task_verifier.review` structured log line):

```
grounding_gate_review
  action_id=<id>
  root_input_id=<id>
  session_id=<id>
  action_type=<type>
  allow=<bool>
  grounding_required=<bool>
  evidence_gathered=<bool>
  evidence_failed_technically=<bool>
  evidence_unavailable=<bool>
  forced_terminal=<bool>
  reason_code=<code or null>
```

This is the only grounding-specific event that the dashboard must aggregate.
Other existing events may include the carried `grounding_required` field for
traceability, but dashboard aggregation should treat `grounding_gate_review` as
the canonical source for post-gate grounding outcomes.

### 6. Logging And Observability

The redesign must be intentionally observable. Emit abundant, structured logs
and telemetry at the points where grounding decisions are made or enforced.

**Required logs/telemetry:**

- `grounding_classification_started`
- `grounding_classification_resolved`
- `grounding_classification_fallback_not_required`
- `grounding_metadata_propagated`
- `grounding_gate_review`

**Minimum fields for classification result telemetry/logs:**

- `root_input_id`
- `session_id`
- `requirement`
- `source`
- `route` (for input-trigger classification)
- `fallback_reason` when fail-open coercion to `NOT_REQUIRED` occurs

**Minimum fields for propagation telemetry/logs:**

- `root_input_id`
- `from_envelope_type`
- `to_envelope_type`
- `grounding_required`
- `source`

**Dashboard requirement:**

- Aggregate only `grounding_gate_review`
- Remove all old verifier histograms and counters
- Keep reason-code breakdowns for the new grounding gate

---

## What Does NOT Change

- **InputIntentRouter:** Unchanged. It already classifies inputs into typed
  routes; the grounding classifier consumes its output.
- **InputRoute sealed hierarchy:** Unchanged. Used as-is for the deterministic
  pre-filter.
- **ActionReviewPipeline structure:** The `passesDecisionVerifier()` call site
  remains; only the implementation behind it changes.
- **FallbackHandler.handleDeniedAction():** Unchanged. The deny-requeue-replan
  flow is reused as-is.
- **DeferredStepPlanner:** Unchanged structurally. It must consume the same
  carried grounding metadata via `PlannerContext`.
- **SharedPromptSections:** Updated to inject grounding hint only when
  grounding is required, but structure unchanged.
- **Superego review:** Unchanged. Orthogonal concern (policy/safety vs.
  grounding).
- **ActionCapability.GATHERS_EVIDENCE:** Unchanged. Already marks which action
  types produce evidence; used by the gate for availability checks.
- **Scratchpad content:** Not an owner of grounding metadata and not consulted
  as the source of truth for gate decisions.
- **External evidence tracking owner:** Remains the typed execution-side
  evidence state, not scratchpad prose.
- **Lesson recording on denial:** Unchanged. FallbackHandler already records
  lessons with reason codes; the new code uses a new code but the same path.

---

## Migration

This is a clean replacement, not an incremental refactor.

1. **Add** grounding classifier (pre-filter + LLM call) in InputPlanner
   orchestration.
2. **Add** `GroundingMetadata` to runtime envelope objects and copy it forward
   through the Ego flow.
3. **Add** `groundingMetadata` to `PlannerContext`.
4. **Add** grounding hint injection in SharedPromptSections for all relevant
   planner lanes, rendered only when grounding is required.
5. **Add** typed forced-terminal marker on action/origin metadata; remove the
   summary-string heuristic.
6. **Replace** `DeterministicDecisionVerifier` with `GroundingGate`
   implementation behind the existing `DecisionVerifier` interface.
7. **Remove** all keyword signal sets, `TaskIntentCategory` enum,
   `VolatilityLevel` enum, `DecisionVerifierAssessment`, `TaskClassification`,
   and all heuristic classification methods.
8. **Update** telemetry event shape, structured logs, and dashboard aggregation.
9. **Add** `grounding_classifier` cognitive role to LLM runtime config.
10. **Add** typed grounding field to goal-step activation flow so `GoalWork`
    inherits policy-decided grounding without prose inference.

---

## Acceptance Criteria

### Functional Requirements

1. **Grounding classification happens at input intake, before planning.**
   The `GroundingMetadata` must be set on the root input envelope before any
   L2 sub-planner runs for that rootInputId. Validation must prove ordering,
   not just eventual presence: the first planner lane invocation for that root
   must already see `PlannerContext.groundingMetadata`.

2. **Typed pre-filter skips LLM call for deterministic routes.**
   Inputs routed to `GoalCreation`, `GoalManagement`, `ClarificationNeeded`,
   and `Noop` must set `GroundingRequirement.NOT_REQUIRED` without any LLM call.
   Verify by checking both:
   - no `grounding_classifier` LLM call event is emitted for these routes
   - `grounding_classification_resolved` is emitted with
     `source=INPUT_PREFILTER` and `requirement=NOT_REQUIRED`

3. **LLM classification runs only for ambiguous routes.**
   Inputs routed to `DirectResponse`, `GeneralAction`, or `MultiStepTask` must
   trigger a `grounding_classifier` LLM call. Verify by checking both:
   - a `grounding_classifier` LLM call event is emitted
   - `grounding_classification_resolved` is emitted with
     `source=INPUT_CLASSIFIER`

4. **Planner receives grounding hint.**
   When `GroundingRequirement.REQUIRED`, every answer-producing planner lane
   must receive `groundingMetadata` in `PlannerContext`, and the shared prompt
   section must inject grounding guidance. When grounding is not required, the
   section must be absent. Verify both presence and absence for all relevant
   lanes named in the spec, not just the initial input lane.

5. **Grounding gate allows operational confirmations.**
   A `contact_user` action confirming a goal creation (or any system operation
   where grounding is `NOT_REQUIRED`) must pass the gate without denial.
   **Regression test:** Reproduce the triggering incident (create a recurring
   weather goal via interactive input). The confirmation message must be
   delivered without denial.

6. **Grounding gate blocks ungrounded volatile-fact answers.**
   A `contact_user` action answering a volatile-fact question (e.g., "What is
   the weather in Hamburg right now?") must be denied if no evidence-gathering
   action was executed successfully for that rootInputId. Validation must assert
   the exact post-gate outcome via `grounding_gate_review`:
   - `allow=false`
   - `grounding_required=true`
   - `evidence_gathered=false`
   - `reason_code=GROUNDING_EVIDENCE_REQUIRED`

7. **Deny-requeue-replan flow works on gate denial.**
   When the grounding gate denies, the action must be requeued via
   `FallbackHandler.handleDeniedAction()` with reason code
   `GROUNDING_EVIDENCE_REQUIRED` or `TECH_GROUNDING_EVIDENCE_FAILURE`, and the
   deferred intention must contain reason-specific retry guidance. Validation
   must assert both the reason code and the presence of the correct retry
   framing in the deferred intention payload/context.

8. **Graceful degradation when evidence tools are unavailable.**
   When grounding is `REQUIRED` but no evidence action types are available
   or dispatchable, the gate must allow the `contact_user` action through
   (same graceful-allow behavior as the legacy verifier path) and emit
   `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`. Validation must assert the exact
   post-gate event:
   - `allow=true`
   - `grounding_required=true`
   - `evidence_unavailable=true`
   - `reason_code=GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`

9. **Metadata propagation preserves grounding unchanged.**
   Once `GroundingMetadata` is set on a root input, all downstream runtime
   envelopes created from it must carry the same value unless a documented
   policy transition explicitly replaces it (for example a new goal-work root
   with its own typed grounding policy). Validation must check the concrete
   carrier set named in the spec, not just one representative object.

10. **Grounding metadata inherited by deferred intentions and feedback.**
    When a denial triggers a deferred intention under the same rootInputId, the
    re-planned actions must see the same carried grounding metadata. The same
    inheritance requirement applies to feedback-driven continuations. Validation
    must assert that the inherited value is copied by runtime code and not
    reconstructed from prompt or action text.

11. **Goal work grounding is per-step.**
    When a goal fires via cron (`EgoTrigger.GoalWork`), the `GoalRunActivation`
    must already carry typed grounding metadata determined by goal-step policy.
    `GoalWorkPlanner` must consume that metadata, not infer it from free text
    and not reuse the original goal-creation input semantics.

12. **Forced-terminal behavior preserves grounding semantics.**
    Forced-terminal actions must use a typed flag, not a summary-string
    heuristic. They may bypass normal convergence pressure, but not grounding:
    - grounding `NOT_REQUIRED` -> allow
    - grounding `REQUIRED` with successful evidence -> allow
    - grounding `REQUIRED` with only technical evidence failures -> retry until
      bounded failure threshold, then allow degraded best-effort answer with
      disclaimer
    - grounding `REQUIRED` with no evidence attempt -> deny
    Validation must assert both:
    - the typed forced-terminal marker is used instead of summary-text matching
    - the degraded allowed answer after bounded technical failures contains an
      explicit verification-failure disclaimer rather than presenting itself as
      freshly grounded

### Removal Criteria

13. **No keyword-based intent classification remains.**
    The following must not exist anywhere in the codebase after migration:
    - `TaskIntentCategory` enum
    - `VolatilityLevel` enum
    - `DecisionVerifierAssessment` data class
    - `TaskClassification` data class
    - `classifyTaskIntent()` method
    - `volatilityScore()` method
    - `containsAny()` and `countSignals()` helper methods
    - `isForcedTerminalAnswer()` method
    - All keyword signal sets: `recencySignals`, `dynamicDomainSignals`,
      `transformationSignals`, `personalMemorySignals`,
      `subjectiveAdviceSignals`, `staticReasoningSignals`,
      `factualQuerySignals`, `dateSensitiveRegex`
    - `DeterministicDecisionVerifier` class

14. **No old verifier reason codes remain.**
    The old reason codes `TASK_EVIDENCE_REQUIRED`,
    `TECH_EXTERNAL_EVIDENCE_FAILURE`, and `TASK_EVIDENCE_UNAVAILABLE_GRACEFUL`
    must not be emitted by any component. The grounding gate uses:
    - `GROUNDING_EVIDENCE_REQUIRED`
    - `TECH_GROUNDING_EVIDENCE_FAILURE`
    - `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`

15. **No old verifier telemetry/logging shape remains.**
    The old verifier telemetry/logging shape containing `intent_category`,
    `volatility_level`, and `volatility_score` fields must not be emitted.
    The new `grounding_gate_review` event must be emitted instead, and the old
    verifier-specific structured log line must be removed or updated to the new
    grounding-gate shape. Validation must check both event type/name and field
    shape.

16. **Dashboard updated.**
    `DashboardStateStore` must not reference old assessment fields
    (`intent_category`, `volatility_level` histogram buckets). It must
    consume the new `grounding_gate_review` event shape. Validation must assert
    that dashboard aggregation reads the new fields:
    - `allow`
    - `reason_code`
    - `grounding_required`
    - `evidence_gathered`
    - `evidence_failed_technically`
    - `evidence_unavailable`

### Test Coverage

17. **Unit test: grounding classifier pre-filter.**
    For each `InputRoute` variant, verify the deterministic pre-filter produces
    the correct `GroundingRequirement` value without invoking the LLM.

18. **Unit test: classifier fail-open fallback.**
    When the grounding classifier returns invalid JSON or fails technically for
    an ambiguous route, the runtime must coerce to `NOT_REQUIRED`, emit warning
    telemetry/logs, and carry that value forward. Code comments should note
    that this fail-open policy is provisional and may be tightened later.
    Validation must assert:
    - `grounding_classification_fallback_not_required` emission
    - resulting `GroundingMetadata(requirement=NOT_REQUIRED, source=INPUT_CLASSIFIER)`
      or equivalent classifier-origin marker
    - planner receives the fallback result unchanged

19. **Unit test: metadata propagation.**
    Cover propagation across:
    - input -> opportunity
    - opportunity -> deferred intention
    - intention -> pending action
    - action -> feedback cue
    - feedback cue -> replanning context
    - goal work activation -> planner context

20. **Unit test: planner context visibility.**
    Every answer-producing lane must receive `groundingMetadata` in
    `PlannerContext`. The shared prompt section must appear only when grounding
    is required. The test matrix must cover every lane listed in Functional
    Requirement 4.

21. **Unit test: grounding gate logic.**
    Cover all gate branches:
    - Non-`contact_user` -> allow
    - `isFallbackExplanation` -> allow
    - grounding `NOT_REQUIRED` -> allow
    - grounding `REQUIRED`, evidence gathered -> allow
    - grounding `REQUIRED`, evidence unavailable -> allow (graceful)
    - grounding `REQUIRED`, technical evidence failure -> deny with
      `TECH_GROUNDING_EVIDENCE_FAILURE`
    - grounding `REQUIRED`, no evidence -> deny with
      `GROUNDING_EVIDENCE_REQUIRED`
    - forced-terminal + grounding `REQUIRED` + bounded technical failure budget
      exhausted -> allow degraded best-effort answer path

22. **Unit test: repeated-denied-action technical retry semantics.**
    A planner retry after `TECH_GROUNDING_EVIDENCE_FAILURE` must remain
    eligible for same-or-similar evidence retry logic and must not be treated
    as a forbidden hard repeat of a denied action.

23. **Unit test: forced-terminal behavior.**
    Cover:
    - forced-terminal + grounding `NOT_REQUIRED`
    - forced-terminal + grounding `REQUIRED` + successful evidence
    - forced-terminal + grounding `REQUIRED` + technical failures below retry
      threshold
    - forced-terminal + grounding `REQUIRED` + technical failures above retry
      threshold
    - forced-terminal + grounding `REQUIRED` + no evidence attempted

24. **Unit test: goal-work typed grounding.**
    Verify that `GoalRunActivation` arrives with typed grounding metadata and
    `GoalWorkPlanner` consumes it without inspecting free-text step prose for
    grounding semantics. Include a negative assertion that changing the prose
    alone does not alter the grounding requirement when the typed policy input
    remains constant.

25. **Scenario pack: goal creation confirmation.**
    Deterministic scenario that creates a recurring goal and verifies the
    confirmation is delivered without denial.

26. **Scenario pack: volatile-fact grounding enforcement.**
    Deterministic scenario that asks a volatile-fact question and verifies
    evidence is gathered before the answer is delivered.

27. **Scenario pack: technical evidence retry.**
    Deterministic scenario that simulates evidence tool failure, verifies
    `TECH_GROUNDING_EVIDENCE_FAILURE`, and confirms the planner is guided to
    retry evidence gathering rather than being forced into a materially
    different non-evidence path.

28. **Dashboard aggregation test.**
    Verify `DashboardStateStore` aggregates only `grounding_gate_review` for
    grounding outcomes and no longer expects verifier-specific histograms.
    Classification/progression events may still be emitted, but must not be
    treated as the canonical dashboard source for gate outcomes. The test should
    verify reason-code counters and allow/deny counters against the new event
    shape only.

29. **Existing tests pass.**
    All existing tests referencing the old `DecisionVerifier` behavior are
    either updated to test the new grounding gate or removed if they tested
    keyword classification logic that no longer exists.
