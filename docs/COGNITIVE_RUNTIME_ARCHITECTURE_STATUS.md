# Cognitive Runtime Architecture Status

> Status: Working architecture reference
>
> Date: 2026-03-31
>
> Purpose: Capture the intended finished cognitive-runtime architecture for
> NeoPsyke, describe the current implementation relative to that target, and
> define acceptance criteria for knowing when the architecture is fully realized.

---

## 1. Scope

This document answers three questions:

1. What is the intended finished architecture?
2. Where is the runtime today relative to that architecture?
3. How will we know when the architecture is fully implemented as intended?

This document does not prescribe implementation phases, migration sequencing, or
low-level refactor steps.

The working premise behind this document is:

- NeoPsyke is not only a chat agent
- NeoPsyke is becoming a general cognitive runtime
- the sensory boundary will grow substantially over time
- many future inputs will arrive through the same high-level stimulus families
- architecture must therefore control complexity before input diversity explodes

Reference documents that informed this summary:

- `docs/TEMP_COGNITIVE_ARCHITECTURE_NOTE.md`
- `docs/SECURITY_STRATEGY_SPEC.md`
- `docs/todo/TODO_SECURITY.md`
- `docs/security.md`
- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`

---

## 2. Intended Finished Architecture

### 2.1 Why this architecture exists

The intended architecture is meant to solve a real runtime problem, not only a
naming problem.

NeoPsyke must eventually handle:

- many different inbound stimuli
- mixed trusted and untrusted content
- async completions and resumptions
- goals, monitors, timers, and internal drives
- low-risk autonomous actions
- approval-backed or policy-gated commits
- future external connectors and richer environment observations

The architecture exists to keep those concerns coherent inside one cognitive
model instead of accumulating special-case routing paths.

### 2.2 Core value of the design

The design is useful because it creates functional boundaries:

- a single normalization boundary for diverse inputs
- a single continuity owner for ongoing reasoning
- a single place to shape admissible next moves before Ego chooses
- a clean split between "what the agent wants to do" and "how that move is
  authorized"
- a uniform feedback model for sync and async outcomes
- distributed deterministic security enforcement before final execution

If implemented correctly, these are behavioral and safety improvements, not
semantic ones.

### 2.3 Canonical target flow

The intended flow is:

`stimulus -> percept -> cognitive thread -> opportunity -> intention -> prepared action -> staged action -> authorized commit -> receipt`

This flow is divided into two halves:

- cognitive half:
  - `stimulus`
  - `percept`
  - `cognitive thread`
  - `opportunity`
  - `intention`
- secure execution half:
  - `prepared action`
  - `staged action`
  - `authorized commit`
  - `receipt`

The cognitive half determines what is happening and what next moves are
available. The execution half determines whether and how side effects may occur.

### 2.4 Stage purposes

#### Stimulus

Purpose:

- represent raw incoming reality at the sensory boundary
- classify arrivals into stable top-level families
- attach identity, provenance, and trust before cognition proceeds

Why it matters:

- many future integrations can share the same stimulus plane
- raw content should never enter cognition without security metadata

#### Percept

Purpose:

- normalize a stimulus into agent-facing meaning
- separate instruction trust, data trust, and control semantics
- preserve source identity and provenance in a form later stages can use

Why it matters:

- it prevents later stages from reasoning over naked transport-specific payloads
- it keeps diverse sources comparable without flattening them into identical
  semantics

#### Cognitive Thread

Purpose:

- own live continuity for one root cognitive line
- track status, waits, local context, thread identity, and thread security frame
- accumulate thread-level trust degradation and policy bounds over time

Why it matters:

- async completion, suspension, resumption, and mixed-origin feedback all need a
  durable continuity owner
- live thread state must not be conflated with durable memory services

#### Opportunity

Purpose:

- represent an admissible next move available to Ego attention
- shape the action surface before planner choice
- expose only lifecycle-appropriate, policy-appropriate, provenance-compatible
  next moves

Why it matters:

- Ego should not discover basic prohibitions only after proposing actions
- low-risk work can remain fast while high-risk options are already narrowed

#### Intention

Purpose:

- represent Ego's chosen next move
- separate intent kind from authorization mode
- provide an explicit object for review, revision, defer, staging, and commit
  progression

Why it matters:

- the runtime needs to distinguish "observe", "prepare", "stage",
  "request authorization", "commit", and "defer"
- commit mode must stay separate from the cognitive choice itself

#### Prepared Action

Purpose:

- represent a structured action candidate after intention formation
- preserve provenance, trust, thread context, and argument data trust

Why it matters:

- the planner should not jump directly to privileged commit semantics

#### Staged Action

Purpose:

- create a durable pending lifecycle object before or during authorization
- support approval-backed and policy-autonomous progression

Why it matters:

- approvals and durable side-effect control cannot live only in prompt memory

#### Authorized Commit

Purpose:

- represent the point at which commit authority is explicitly satisfied
- bind commit mode, policy version, approver or policy grant, and action hash

Why it matters:

- execution must be able to prove why a side effect was allowed

#### Receipt

Purpose:

- durably record the outcome of the authorized action
- make result, status, and provenance inspectable

Why it matters:

- the runtime needs durable auditability, resumability, and post-hoc diagnosis

### 2.5 Cross-cutting architectural rules

#### Cognitive plane versus control plane

The intended architecture separates:

- stimulus plane:
  - things the agent can perceive and reason about
- control plane:
  - things that manage the runtime itself

Examples of control-plane concerns:

- start or stop runtime
- shutdown
- pause or resume runtime execution
- config reload
- diagnostics toggles
- operator/debug commands

These should not be modeled as normal cognitive stimuli, percepts,
opportunities, or intentions.

#### Typed ingress, not flattened meaning

All cognitive arrivals go through `SensoryCortex`, but they do not become
"ordinary user input." User messages, internal drives, tool feedback, goal
runtime cues, async completions, and future observations remain distinct in
meaning even if they share the same top-level ingress framework.

#### Cognitive thread as the live state owner

`CognitiveThread` owns live operational continuity:

- current status
- waits
- resume points
- local thread context
- thread identity
- thread security context

`MemorySystem` remains responsible for recall, summarization, episodic storage,
durable memory, and reconstruction support. It is not the primary owner of live
execution continuity.

#### Policy is layered, not monolithic

The intended policy model answers questions at multiple scopes:

- deployment policy
- channel policy
- principal policy
- action policy
- full-autonomy policy

Thread and opportunity construction are expected to carry these bounds forward
so the planner does not discover basic policy through trial and error.

#### Security is distributed through the pipeline

Deterministic security enforcement is not only a Superego concern.

The intended enforcement points are:

- `SensoryCortex`: authenticate, classify, attach trust and provenance
- `Percept`: normalize and reject invalid or unauthenticated input
- `CognitiveThread`: establish thread-level security frame and policy scope
- `Opportunity`: prune prohibited next moves before Ego chooses
- `Intention` review: verify and judge candidate next action progression
- secure action lifecycle: enforce stage, authorization, and final no-bypass
  commit rules

#### Feedback re-enters cognition uniformly

Action outcomes, including synchronous ones, conceptually re-enter as typed
feedback stimuli:

`action result -> feedback stimulus -> percept -> cognitive thread update -> opportunity`

This creates one model for:

- sync success
- sync failure
- async wait
- async completion
- timeout
- partial progress

#### Goals remain inside cognition

Goal creation and goal mutation are intended to remain agent-mediated cognitive
acts, not a normal side-door mutation API. Direct read access for monitoring is
acceptable. Direct write access is only an emergency or maintenance path.

#### Scratchpad is layered

The intended scratchpad model has at least two scopes:

- thread-scoped scratchpad:
  - persists across suspension and resumption
  - carries ongoing working context for the thread
- intention-scoped scratchpad:
  - ephemeral drafts
  - evidence shaping
  - one-attempt intermediate artifacts

#### Concurrency frontier

The intended concurrency boundary is:

- parallelizable:
  - stimulus appraisal
  - thread-local updates
  - recall preparation
  - observation processing
  - opportunity generation
- centralized:
  - Ego attention
  - final intention selection
  - final verifier acceptance
  - final Superego judgment
  - final side-effect commitment

This keeps preparation scalable without creating multiple competing executive
agents.

#### Thoughts are internal deliberation artifacts

In the intended model, thoughts are not first-class scheduler queue items.
They remain internal Ego artifacts used during intention formation, revision,
and evaluation. The primary runtime objects of attention should be cognitive
threads, opportunities, intentions, and secure action-lifecycle objects.

#### Action workflows use semantic families plus lifecycle state

The intended security model does not explode one semantic action into many
unrelated action types for prepare, stage, approve, and commit. Instead, one
semantic action family progresses through lifecycle state. In v1, staged actions
remain single-operation objects, with room for future batch wrappers only if
needed later.

### 2.6 Benefits of the finished architecture

If the design is completed correctly, the main benefits are:

- better support for many future input types
- clearer ownership of live state versus memory state
- cleaner async and suspend/resume behavior
- earlier policy shaping of available actions
- stronger provenance handling across the entire loop
- better separation between cognition and secure execution
- better observability and auditability
- less future architectural drift into source-specific branches

### 2.7 Risks while implementing the target architecture

The full design is sound, but implementation carries real risks.

#### Risk: taxonomy without behavior

If stages become only renamed wrappers around current objects, complexity rises
without gaining control points.

Guardrail:

- each stage must own behavior, state, or enforcement responsibility

#### Risk: duplicated state ownership

If `CognitiveThread`, `MemorySystem`, scratchpad, goal runtime, and scheduler
all partially own the same continuity state, the runtime becomes harder to
reason about than today.

Guardrail:

- live thread continuity must have one primary owner

#### Risk: over-literal implementation

If every conceptual noun becomes a persisted or independently scheduled object,
the runtime may become heavier than the functional need requires.

Guardrail:

- keep only the abstractions that change runtime behavior or enforce policy

#### Risk: migration ambiguity

A partially migrated runtime is worse than either old or new architecture
because docs, tests, telemetry, and real execution drift apart.

Guardrail:

- current runtime docs must stay explicit about what is implemented versus
  aspirational

#### Risk: broken async coherence

If sync and async feedback paths diverge, thread continuity becomes inconsistent.

Guardrail:

- all action outcomes must share the same cognitive feedback model

#### Risk: performance regressions

A richer cognitive pipeline introduces more state transitions and more objects.

Guardrail:

- keep executive commitment centralized
- keep early-stage processing cheap
- keep concurrency scoped to read-heavy or thread-local paths

---

## 3. Current Implementation State

### 3.1 Bottom line

NeoPsyke has implemented a substantial part of the secure execution half and a
small but real part of the cognitive ingress/security model.

NeoPsyke has not yet implemented the full cognitive orchestration model
described above.

The current runtime is best understood as:

`stimulus -> enqueue input/impulse/goal work -> planner decision -> pending action -> secure action lifecycle`

not as:

`stimulus -> percept -> cognitive thread -> opportunity -> intention -> secure action lifecycle`

### 3.2 What is implemented today

#### Ingress security and typed stimulus model

Implemented today:

- `StimulusEnvelope`
- stable stimulus families
- `ConversationContext`
- `ConversationSecurityContext`
- provenance attachment at ingress
- input sanitization and session/interlocutor enrichment in `SensoryCortex`

This gives NeoPsyke a real sensory boundary with trust and provenance attached
early.

#### Control-plane split

Implemented today:

- runtime control signals are distinct from cognitive signals
- shutdown, exit, and config reload do not masquerade as cognitive stimuli

This already aligns with the intended control-plane separation, even though the
full cognitive-plane architecture is not yet implemented.

#### Thread-level security approximation

Implemented today:

- `CognitiveThreadSecurityContext`
- root-input scoped security tracking in `DeliberationEngine`
- aggregated data-trust degradation when external artifacts are observed
- planner-visible action filtering based on conversation instruction trust and
  thread aggregated data trust

This is a meaningful part of the intended design, but it is only a partial
thread model. It is thread security state without a real thread runtime object.

#### Current policy shape

Implemented today:

- normalized principal and channel dimensions
- policy scope id carried in conversation security
- action policy with real trust and effect-class checks

Current gap:

- actual authorization policy is still much narrower than the intended layered
  model
- current runtime meaningfully supports channel, principal, action, and
  full-autonomy policy shaping; deployment scope is a placeholder

#### Secure action lifecycle

Implemented today:

- `PreparedAction`
- `StagedAction`
- `CommitAuthorization`
- `ActionReceipt`
- `AuthorizationDecision`
- `ActionAuthorizationPolicy`
- durable action-control storage and background autonomous staged execution

This is the strongest completed area relative to the target architecture.

#### Deterministic and layered security review

Implemented today:

- deterministic `DecisionVerifier`
- deterministic Superego conscience
- policy authorization
- optional LLM Superego review
- final execution guard in the action lifecycle and motor path

This means action security is already much stronger than prompt-only tool
calling.

### 3.3 What exists only as model types or partial stubs

Implemented as types but not wired into the runtime orchestration:

- `Percept`
- `PerceptualAppraiser`
- `CognitiveThread`
- `Opportunity`
- `Intention`
- `ActionOpportunity`
- `IntentionKind`
- richer `AuthorizationProgress` shape including `ALLOW_PREPARE`

These are currently architecture markers, not live control objects.

### 3.4 What actually drives the loop today

The runtime still centers on legacy orchestration objects and branches:

- `OpportunityWorkItem`
- `PendingInput`
- `PendingThought`
- `PendingAction`
- queue-driven scheduling
- top-level source-specific branches:
  - `processInput`
  - `processImpulse`
  - `processGoalWork`
  - `processThought`
  - `processAction`

This works, but it is the main reason the intended architecture is not yet
real.

An important deviation from the target design is that thoughts remain explicit
queued runtime work items. In the intended design, thoughts are internal Ego
deliberation artifacts rather than first-class scheduler objects.

### 3.5 Current gap by architectural stage

#### Stimulus

Status: mostly implemented

Current state:

- typed stimulus families exist
- security and provenance are attached at ingress
- sensory enrichment is real

Gap:

- future broader stimulus diversity is not yet present

#### Percept

Status: defined but not in the execution path

Current state:

- `Percept` exists
- `PerceptualAppraiser` exists

Gap:

- stimuli are not actually appraised into live percept objects before entering
  the loop
- downstream stages do not consume percepts

#### Cognitive Thread

Status: conceptually approximated, not actually implemented

Current state:

- thread security context exists as a root-input scoped map
- some trust degradation behaves as if a thread existed

Gap:

- no live `CognitiveThread` store
- no actual thread lifecycle owner
- no explicit waiting/resume/status model owned by thread runtime
- no thread object as the unit of orchestration

#### Opportunity

Status: partially approximated by scheduler work items

Current state:

- `OpportunityWorkItem` exists
- scheduler handles input, impulse, and goal-work opportunities

Gap:

- opportunities are not policy-shaped cognitive objects
- they do not carry the intended admissible next-move surface
- they are queue categories, not cognitive opportunity objects

#### Intention

Status: partially represented by decisions and actions, not explicit

Current state:

- `IntentionKind` exists
- planner produces `EgoDecision`
- actions become `PendingAction`

Gap:

- there is no explicit intention object in the loop
- intent kind and lifecycle progression are not modeled end to end
- `ALLOW_PREPARE` is not used in live runtime flow

#### Prepared Action -> Staged Action -> Authorization -> Receipt

Status: substantially implemented

Current state:

- secure lifecycle objects exist
- durable persistence exists
- approval-backed and policy-autonomous progression exist

Gap:

- this half is ahead of the cognitive half
- prepared action is largely internal to action control, not a first-class
  cognitive handoff from intention formation
- lifecycle semantics are ahead of orchestration semantics

#### Policy shaping

Status: partially implemented

Current state:

- planner-visible actions are filtered by instruction trust and aggregated data
  trust
- action authorization policy enforces effect-class and trust rules

Gap:

- opportunity construction does not yet carry full layered policy shaping
- channel policy, principal policy, and full-autonomy policy are now
  operational runtime shaping layers; deployment scope remains a placeholder

### 3.6 Additional major gaps relative to the full target

#### Feedback re-entry is not yet fully cognitive

Current state:

- action outcomes are handled inside current action-processing paths
- some consequences update deliberation and evidence state directly

Gap:

- outcomes do not uniformly re-enter through `SensoryCortex` as typed feedback
  stimuli
- sync and async paths are not yet one cognitive model

#### Goals are not yet fully expressed as cognitive-thread continuity

Current state:

- goal runtime exists
- goal cues exist
- goal work can be enqueued

Gap:

- goal work still enters a separate queue branch instead of a unified thread
  update and opportunity-generation model

#### Scratchpad is not yet layered as designed

Current state:

- scratchpad is active and useful
- current practice is primarily request-scoped ephemeral workspace behavior

Gap:

- no explicit thread-scoped versus intention-scoped layering

#### Concurrency boundary is not yet expressed through the target model

Current state:

- execution remains mostly centralized

Gap:

- the intended "parallel up to opportunity generation, centralized from Ego
  attention onward" model is not yet embodied in thread-oriented orchestration

#### Thoughts are still first-class scheduled work

Current state:

- `PendingThought` is a primary queue object
- planner, fallback, verifier recovery, and follow-up behaviors all requeue
  thoughts explicitly

Gap:

- this is contrary to the intended architecture, where thoughts are internal
  deliberation artifacts and not the primary unit of scheduling

### 3.7 Summary assessment of current state

NeoPsyke is in a partial-migration state:

- the secure action lifecycle is real
- ingress trust and provenance are real
- thread-level security exists in approximated form
- the target cognitive architecture vocabulary exists in code
- the runtime loop still follows older queue-centric orchestration

In practical terms:

- the security redesign has landed further than the cognitive orchestration
  redesign
- the runtime currently behaves like a hybrid between old and target models
- the main missing value is not action security but full cognitive state
  ownership and orchestration around diverse future stimuli

---

## 4. Acceptance Criteria

This section defines how we will know the target architecture is complete.

The architecture is complete only when the runtime behavior, state ownership,
tests, and living docs all align with the intended design.

### 4.1 Stage-by-stage acceptance criteria

#### Stimulus acceptance criteria

Done when:

- every cognitive arrival enters through `SensoryCortex` as a typed `Stimulus`
- all supported inbound sources attach identity, channel, principal, trust, and
  provenance before later cognition
- no raw inbound source payload bypasses sensory classification into later
  runtime stages
- future source additions can map into existing stimulus families without
  requiring new top-level orchestration branches
- runtime/operator control remains outside the cognitive stimulus plane

Evidence:

- unit tests per stimulus family and source class
- integration tests for representative inbound channels

#### Percept acceptance criteria

Done when:

- every cognitive stimulus is appraised into a `Percept` before entering thread
  update logic
- percepts preserve source identity and normalized trust semantics
- later cognitive stages consume percepts rather than raw stimuli

Evidence:

- tests showing stimulus-to-percept mapping for each supported family
- tests proving invalid or unauthenticated stimuli are rejected before thread
  update

#### Cognitive Thread acceptance criteria

Done when:

- every root cognitive line has a live `CognitiveThread`
- thread runtime owns:
  - status
  - waits
  - resume points
  - local continuity state
  - thread identity
  - thread security context
- `MemorySystem` is not the primary owner of active thread continuity
- trust degradation and taint accumulation live on the thread state itself
- thread lifecycle states are observable and testable

Evidence:

- unit tests for thread creation, update, suspend, resume, resolve, and failure
- integration tests proving async completion resumes the correct thread

#### Opportunity acceptance criteria

Done when:

- opportunities are generated from cognitive-thread state, not from raw queue
  category wrappers
- opportunities carry the admissible next-move surface for Ego
- policy, provenance, and lifecycle restrictions are already reflected in the
  available opportunities
- opportunities carry policy-scope and rate/batch implications where relevant
- Ego no longer discovers first-order prohibitions only after proposing an
  action

Evidence:

- tests showing the same thread produces different opportunities under different
  trust, provenance, and policy conditions
- tests proving prohibited moves are absent before planner choice

#### Intention acceptance criteria

Done when:

- Ego forms explicit `Intention` objects from attended opportunities
- intention kind is represented distinctly from commit mode
- the runtime supports explicit intention categories at least for:
  - `OBSERVE`
  - `PREPARE`
  - `STAGE`
  - `REQUEST_AUTHORIZATION`
  - `COMMIT`
  - `DEFER`
- verifier, Superego, and action-control progression consume intention semantics
  where behavior differs by intention kind

Evidence:

- tests proving low-risk paths can commit without unnecessary staging
- tests proving high-risk paths can move through prepare, stage, authorization,
  and commit progression correctly
- tests proving deferred intentions preserve thread continuity and security state

#### Secure action lifecycle acceptance criteria

Done when:

- every side-effecting action that needs lifecycle control is represented through
  prepared, staged, authorized, and receipted execution objects
- one semantic action family progresses through lifecycle state rather than
  multiplying workflow-step action types
- staged actions remain single-operation objects unless the architecture is
  intentionally extended
- direct commit, approval-backed commit, and policy-autonomous staged execution
  all work according to policy
- commit cannot bypass required authorization artifacts
- durable records are queryable and inspectable

Evidence:

- integration tests for staged approval-backed actions
- integration tests for autonomous staged actions
- integration tests for denial and refusal paths
- integration tests for receipt and ledger persistence

### 4.2 Cross-cutting acceptance criteria

#### Uniform feedback model

Done when:

- synchronous and asynchronous action outcomes both re-enter the cognitive loop
  as typed feedback stimuli
- thread updates and next opportunities are driven through the same model for
  sync success, async completion, timeout, and failure

Evidence:

- tests proving both sync and async outcomes become feedback stimuli and update
  thread state consistently

#### Goal-runtime integration

Done when:

- goal work enters cognition through typed cues and thread updates rather than a
  separate orchestration regime
- goal changes that affect ongoing cognition surface back into live threads
- optional planful goal execution remains compatible with thread continuity

Evidence:

- integration tests for goal wake, resume, planful work, blocked work, and
  recurring/standing goals

#### Scratchpad boundaries

Done when:

- thread-scoped and intention-scoped scratchpad responsibilities are distinct
- suspension and resumption preserve thread working context
- one-attempt drafts do not pollute durable thread context unnecessarily

Evidence:

- tests showing resumed threads retain thread-scoped working state while
  intention-scoped drafts can be recreated safely

#### Security distribution

Done when:

- deterministic policy is enforced at ingress, thread construction,
  opportunity construction, intention review, and secure action lifecycle
- the Superego remains central but is not the only effective security boundary
- layered policy scopes can actually shape behavior by channel, principal, and
  action context where intended

Evidence:

- tests proving prohibited actions are blocked at the earliest intended stage
- tests proving untrusted or tainted content cannot directly widen privileged
  action surfaces

#### Observability and auditability

Done when:

- stage transitions are visible in telemetry and inspection surfaces
- operators can inspect thread state, staged actions, authorizations, receipts,
  and relevant denials
- the runtime can explain why a move was available, staged, denied, or executed

Evidence:

- dashboard or API inspection tests
- scenario runs that expose cognitive-stage and execution-stage artifacts

#### Thought-model alignment

Done when:

- the runtime no longer depends on `PendingThought` as a first-class scheduler
  category for normal cognitive progression
- internal deliberation artifacts are observable where useful, but primary
  scheduling and attention are driven by thread and opportunity semantics

Evidence:

- runtime tests showing normal progression can execute without explicit thought
  queue orchestration
- scenario tests showing recovery, defer, and follow-up still work under the
  thread/opportunity/intention model

### 4.3 Living-document acceptance criteria

Done when:

- `AGENT_LOGIC_SUMMARY.md` describes the real runtime shape and not a hybrid
  approximation
- `AGENT_LOGIC_DIAGRAM.md` matches actual control flow
- `docs/security.md` reflects the true current security architecture
- this document can be updated from real code rather than from architectural
  aspiration

### 4.4 Intentional architecture exceptions

#### Deferred intentions skip percept/thread/opportunity stages

Deferred intentions (`IntentionKind.DEFER`) re-enter the planner at the intention
processing level without creating a new percept, cognitive thread, or opportunity.

This is intentional. A deferred intention is not a new cognitive arrival — it is
continued internal deliberation within an existing thread. The original thread's
security context, percept binding, and opportunity constraints remain in effect.
Opportunity-level policy shaping was applied when the thread was first created.

If a future requirement demands re-shaping the opportunity surface on each
deliberation pass (for example, if policy can change mid-turn), this exception
should be revisited.

### 4.5 Final architecture completion test

The architecture should be considered fully implemented only when all of the
following are true:

- the runtime no longer relies on legacy queue-category orchestration as the
  primary control model
- `Percept`, `CognitiveThread`, `Opportunity`, and `Intention` are live runtime
  stages, not only model definitions
- runtime/operator control is clearly separated from the cognitive stimulus
  plane
- secure action lifecycle remains intact and integrated with those stages
- sync and async feedback share the same cognitive re-entry model
- layered policy shaping is operational rather than only encoded in data models
- goals, internal drives, user requests, and future external stimuli all fit the
  same cognitive-runtime architecture without adding new top-level orchestration
  branches per source class

If those conditions are met, NeoPsyke will have crossed from a partially secured
tool-using agent runtime into the intended general cognitive runtime
architecture.
