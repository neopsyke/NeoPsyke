# NeoPsyke Security Strategy Spec

> Status: Draft
>
> Date: 2026-03-23
>
> Purpose: Define the concrete architectural redesign for secure tool/action
> execution in NeoPsyke. This document translates the threat analysis in
> `docs/security/SECURE_TOOLS_STRATEGY.md` into a real implementation plan,
> target abstractions, migration sequence, and rollout model.

---

## 1. Scope

This spec covers the redesign of NeoPsyke's action system so the agent can
eventually support:

- read-only automation
- approval-gated writes
- policy-governed autonomous commits
- first-party and future third-party action connectors
- single-user and shared/team-facing deployments

This spec is intentionally architecture-first. Backwards compatibility is not a
goal. Clarity, security, and long-term extensibility take priority.

---

## 2. Decisions Locked In

These decisions are treated as constraints for the redesign:

1. NeoPsyke must eventually support fully autonomous commits.
2. During rollout and testing, approval-gated execution is allowed and expected.
3. Trust level must be policy-driven, so future extenders can choose stricter or
   looser commit behavior without redesigning the architecture.
4. `goal_operation` must remain an agent-level capability driven by natural
   language user instructions, not an admin-only side path.
5. The only true admin bypass path is monitoring plus emergency hard
   stop/cancel.
6. Third-party action plugin connectors will be needed in the future. The design
   must be zero-trust-ready now, even if third-party loading is not implemented
   yet.
7. NeoPsyke must support both single-user/personal assistant deployments and
   shared/team-facing agents.
8. There is one main owner/operator. Other messaging participants are external
   by default unless a future explicit role model says otherwise.
9. Provenance must exist both in enforcement logic and planner-visible context.
10. The action API should be refactored now.
11. Non-owner team participants remain external-only in v1.
12. Autonomous public posting defaults to deny-until-enabled per
    connector/account.
13. Approval and authorization artifacts must be durable across restarts.
14. Staged actions should be single-operation in v1, with room for future batch
    wrappers.
15. `DEFER` remains an intention-level concept; waiting state lives on staged or
    execution objects.
16. `reflect` remains planner-visible in v1, with narrowed provenance and
    policy.
17. Policy should support Kotlin config plus external YAML, provided YAML is
    operator-managed and not agent-writable.
18. Action workflows should use one semantic action family plus lifecycle state,
    not separate action types for each workflow step.
19. Direct commit should be enabled only by per-action explicit opt-in.
20. Durable staged actions, authorizations, and receipts should live in the
    existing embedded SQLite store, with dedicated tables and clean repository
    boundaries.
21. Policy/config reload requires restart in v1.
22. Deterministic policy is final; it may deny a path, but the Ego must still be
    able to replan from that denial and pursue alternatives.
23. Channel/principal identity normalization should be introduced immediately.
24. Recurring goal creation should require stricter commit policy than one-shot
    goal updates.
25. Receipts and staged items may be backend-first in v1, but they must be
    inspectable.

---

## 3. Design Goals

### 3.1 Primary goals

- Make privileged action execution safe by construction, not only by prompt.
- Preserve the cognitive model and metaphor:
  - `SensoryCortex` should classify and authenticate incoming reality.
  - `Ego` should choose among policy-shaped opportunities, not discover basic
    authorization by trial and error.
  - `Superego` should judge whether a prepared action is permissible under
    policy, trust, provenance, and context.
  - `MotorCortex` should execute only actions that have passed the required
    policy and lifecycle stages.
- Separate observation, preparation, approval, and commitment.
- Keep future connector growth possible without turning the plugin system into a
  full-trust attack surface.

### 3.2 Non-goals

- Preserving the current `AgentActionPlugin.execute(...)` contract.
- Solving third-party sandboxing in full in the first implementation.
- Adding new high-risk connectors before the security model lands.

---

## 4. Core Architectural Shift

The current model is:

`plan -> review -> execute`

The target model is:

`observe -> prepare -> stage -> authorize -> commit -> record`

This is the central redesign.

### 4.1 Why this shift is required

The current action pipeline treats all actions as direct execution candidates once
the planner proposes them. That is workable for low-risk evidence actions, but it
is the wrong abstraction for:

- email send/reply/unsubscribe
- social publishing
- recurring goal creation
- persistent memory changes influenced by tainted content
- any future third-party connector with write authority

For those actions, NeoPsyke needs explicit intermediate representations, not
direct plugin calls.

---

## 5. Trust Model

### 5.1 Principals

NeoPsyke must reason about who originated a request, not only what was said.

Proposed principal classes:

- `OWNER`
- `SYSTEM_INTERNAL`
- `APPROVED_AUTOMATION`
- `EXTERNAL_PARTICIPANT`
- `UNAUTHENTICATED_EXTERNAL`
- `ADMIN_CONTROL`

Notes:

- `OWNER` is the main operator.
- `EXTERNAL_PARTICIPANT` covers other people in team/shared contexts by default.
- `ADMIN_CONTROL` is not a conversational user role. It is for monitoring,
  emergency stop, cancellation, circuit-breaker override, and operational
  controls outside the agent.

### 5.2 Channels

Channel identity must be part of security context, not loose metadata.

Proposed channel classes:

- `WEBAPP_OWNER_CHAT`
- `WEBAPP_SHARED_CHAT`
- `TELEGRAM_OWNER_DM`
- `TELEGRAM_GROUP`
- `WHATSAPP_OWNER_DM`
- `WHATSAPP_GROUP`
- `AUTOMATION_RUN`
- `ADMIN_CONSOLE`

### 5.3 Trust classes

Trust is not binary. The system should distinguish:

- `TRUSTED_INSTRUCTION`
- `TRUSTED_DATA`
- `EXTERNAL_DATA`
- `SANITIZED_EXTERNAL_DATA`
- `ADMIN_OVERRIDE`

Current operating assumption:

- only the owner's approved channels produce `TRUSTED_INSTRUCTION`
- other channels are external by default
- future role expansion can introduce more instruction classes without changing
  the action architecture

---

## 6. Provenance Model

Simple boolean taint is not enough.

NeoPsyke should track provenance explicitly through the pipeline.

### 6.1 Target abstraction

```kotlin
data class Provenance(
    val instructionTrust: InstructionTrust,
    val dataTrust: DataTrust,
    val sourceKind: SourceKind,
    val sourceRef: String? = null,
    val sanitization: SanitizationRecord? = null,
)

enum class InstructionTrust {
    TRUSTED_INSTRUCTION,
    UNTRUSTED_INSTRUCTION,
}

enum class DataTrust {
    TRUSTED_DATA,
    EXTERNAL_DATA,
    SANITIZED_EXTERNAL_DATA,
}
```

### 6.2 Where provenance must exist

- inbound message/channel boundary
- planner-visible context
- scratchpad entries
- memory entries
- staged action arguments
- action receipts

### 6.3 Enforcement principle

External data may inform decisions, but must not directly become privileged
action arguments unless:

- it passes explicit sanitization
- the action contract allows that provenance
- policy allows autonomous use for that action/effect class

---

## 7. Action Security Model

The current `ActionCapability` model is too coarse.

We need two different concepts:

- behavioral capability
- security effect class

### 7.1 Proposed split

```kotlin
enum class ActionCapability {
    GATHERS_EVIDENCE,
    PRODUCES_USER_OUTPUT,
    MODIFIES_MEMORY,
    MODIFIES_GOALS,
    TOUCHES_EXTERNAL_SYSTEM,
}

enum class ActionEffectClass {
    OBSERVE,
    PREPARE,
    STAGE,
    COMMIT_PRIVATE,
    COMMIT_PUBLIC,
    COMMIT_STATEFUL,
    CONTROL_PLANE,
}
```

Rationale:

- `ActionCapability` describes what kind of thing the action can do.
- `ActionEffectClass` describes blast radius and policy handling.

This keeps the metaphor cleaner than putting all security meaning into a single
enum.

### 7.2 Action contract

Replace the current direct-execution contract with an action contract that can
declare lifecycle and policy requirements.

Proposed shape:

```kotlin
data class ActionContract(
    val type: ActionType,
    val effectClass: ActionEffectClass,
    val capabilities: Set<ActionCapability>,
    val supportsAutonomousCommit: Boolean,
    val requiresStaging: Boolean,
    val requiresApprovalWhen: ApprovalRequirement,
    val allowedInstructionTrust: Set<InstructionTrust>,
    val allowedArgumentDataTrust: Set<DataTrust>,
)
```

Important:

- direct commit is not implied by effect class alone
- direct commit must be explicitly enabled per action family by policy and
  contract
- workflow steps should not explode into separate action types when they belong
  to one semantic family

### 7.3 Lifecycle objects

```kotlin
data class PreparedAction(...)
data class StagedAction(...)
data class CommitAuthorization(...)
data class ActionReceipt(...)
```

The planner should target `PreparedAction` intent, not raw commit intent.

---

## 8. Policy Model

Policy must be first-class and explicit.

### 8.1 Policy scopes

Policy should be layered:

- deployment policy
- channel policy
- principal policy
- action policy
- full-autonomy policy

### 8.2 Policy questions the system must answer

For any candidate action:

- Is this action type visible to the planner in this context?
- May the agent prepare it?
- May the agent stage it?
- May the agent commit it autonomously?
- Must the owner confirm it?
- Is it blocked entirely on this channel/principal?
- What rate limits and batch limits apply?

### 8.3 Who enforces policy

- `SensoryCortex` enforces stimulus/percept-level policy:
  - channel authentication
  - identity normalization
  - initial trust/provenance assignment
  - early rejection of invalid or unauthenticated input
- cognitive-thread construction enforces thread-level policy:
  - root trust scope
  - policy scope
  - visible action-family bounds
- opportunity construction enforces opportunity-level policy:
  - which next moves are actually available in this thread
  - which lifecycle transitions are available
  - which options are removed due to provenance, limits, or policy
- `Ego` selects among already policy-shaped opportunities. It must not widen the
  action surface it receives.
- `SuperegoDeterministicConscience` enforces hard policy invariants on intended
  action progression.
- `SuperegoReviewEngine` handles contextual judgment within policy bounds.
- `MotorCortex` refuses to execute commits that lack a valid authorization
  artifact or violate final execution constraints.

The superego remains central, but it must not be the sole enforcement boundary.
Hard policy belongs in deterministic code.

---

## 9. Refactored Cognitive Boundary

To keep the metaphor clean:

### 9.1 SensoryCortex

Responsibilities:

- authenticate inbound channels
- classify principal and channel
- attach provenance
- sanitize externally sourced content
- mark whether content is trusted instruction or external data

### 9.2 Ego

Responsibilities:

- select among policy-shaped opportunities
- compose prepared/staged actions, not raw commits
- reason with provenance-aware context
- avoid hallucinating trust it did not receive
- never widen the action surface or lifecycle permissions established upstream

### 9.3 Superego

Responsibilities:

- judge whether a prepared or staged action is permissible
- apply policy-sensitive contextual review
- distinguish:
  - trusted owner instruction
  - external influence
  - autonomous policy-authorized commit

Important:

The superego should become more explicit about authorization mode:

- `deny`
- `allow_prepare`
- `allow_stage`
- `allow_commit_with_approval`
- `allow_autonomous_commit`

### 9.4 MotorCortex

Responsibilities:

- execute only staged/authorized commits
- isolate connector execution from planning
- emit auditable receipts
- enforce final no-bypass checks

---

## 10. Cognitive Ingress Integration

The security redesign must integrate cleanly with NeoPsyke's cognitive ingress
model:

`stimulus -> percept -> cognitive thread -> opportunity -> intention`

The redesign must preserve this chain, not bypass it with ad hoc security side
paths.

### 10.1 Security-enriched ingress chain

Target chain:

`stimulus -> percept -> cognitive thread -> opportunity -> intention -> prepared action -> staged action -> authorized commit -> receipt`

The first five stages remain cognitive. The later stages are the secure action
lifecycle that begins only when an intention actually targets an action.

### 10.2 Stage responsibilities

#### Stimulus

Raw incoming reality.

Examples:

- owner webapp chat message
- shared channel message
- email body
- RSS item
- calendar event
- webhook payload
- automation trigger

Security duties at this stage:

- authenticate source/channel
- resolve principal
- classify source kind
- attach initial provenance
- sanitize untrusted external content when appropriate

No raw stimulus should enter later cognition without security metadata.

#### Percept

Normalized agent-facing interpretation of the stimulus.

This is where the system must distinguish:

- trusted instruction
- trusted data
- external data
- sanitized external data
- admin control signal

Security duties at this stage:

- reject invalid or unauthenticated input
- normalize trust and provenance
- preserve source identity for later policy checks

#### Cognitive Thread

The bounded reasoning thread created from a root input/event.

This is the main security context for the entire loop.

A cognitive thread should carry:

- root principal
- root channel
- instruction trust
- aggregated provenance
- policy scope
- visible action-family bounds
- rate/batch context

The planner should not discover basic authorization by trial and error. The
thread should already define the security frame and high-level bounds for what
is even thinkable in this context.

#### Opportunity

A candidate next step that is visible and available to Ego.

Security duties at this stage:

- prune impossible or prohibited next steps
- expose only actions allowed by policy and provenance
- expose lifecycle-appropriate next steps
- preserve loop momentum for low-risk work

By the time an object becomes an `Opportunity`, it should already be admissible
for Ego consideration. Ego may rank and choose it, but should not be the first
component to discover that it was prohibited.

The shift here is important:

- old pattern: planner can intend almost anything, then later gets blocked
- target pattern: planner mainly sees policy-shaped opportunities, then superego
  judges borderline or high-impact cases

#### Intention

The chosen next move by Ego.

Intentions should remain fast and loop-friendly. The redesign must not force all
intentions through staging or approval when the effect class and policy do not
require it.

### 10.3 Intention model

The previous shorthand list:

- observe
- prepare
- stage
- request approval
- commit autonomously if policy allows

is directionally correct but incomplete as a full intention model.

The missing issue is that intent type and authorization mode are not the same
thing. If they stay collapsed, the loop becomes awkward and high-friction.

Target split:

- `IntentionKind`
- `CommitMode`

Proposed `IntentionKind` set:

- `OBSERVE`
- `PREPARE`
- `STAGE`
- `REQUEST_AUTHORIZATION`
- `COMMIT`
- `DEFER`

Definitions:

- `OBSERVE`: gather evidence or inspect state. This should remain cheap and
  should usually preserve the current fast loop.
- `PREPARE`: transform thread context into a structured candidate action or
  draft. This includes reply drafting, plan materialization, and structured goal
  preparation.
- `STAGE`: create a durable pending action that is not yet committed.
- `REQUEST_AUTHORIZATION`: ask for approval or other explicit authorization when
  policy requires it.
- `COMMIT`: execute the action. Whether this is autonomous or approval-backed is
  determined separately by `CommitMode`.
- `DEFER`: intentionally cycle back into the normal loop with updated context,
  waiting state, new evidence, or follow-up cognition. This preserves the
  existing Ego feedback pattern without inventing fake commits.

Proposed `CommitMode` set:

- `NOT_APPLICABLE`
- `APPROVAL_BACKED`
- `POLICY_AUTONOMOUS`
- `ADMIN_OVERRIDE`

Key rule:

- `COMMIT` is the intention kind.
- `APPROVAL_BACKED` vs `POLICY_AUTONOMOUS` is how commit authorization is
  satisfied.

This keeps low-risk execution fast:

- low-risk owner-visible answer or evidence action can go
  `opportunity -> intention(COMMIT) -> authorized commit`
  with no staging if policy allows direct commit for that effect class
- high-risk action can go
  `opportunity -> intention(PREPARE) -> intention(STAGE) -> intention(REQUEST_AUTHORIZATION) -> intention(COMMIT)`

### 10.4 Feedback loop preservation

The redesign must preserve NeoPsyke's normal cycling behavior.

That means:

- `OBSERVE` should still feed evidence back into deliberation quickly
- `PREPARE` should be usable for cheap internal structuring, not just for
  high-friction privileged actions
- `DEFER` should be explicit, so the loop can re-evaluate after observation,
  staging, async waits, denials, or partial progress
- low-risk actions must not pay the full stage/approval cost if policy and
  effect class do not require it

The target behavior is selective friction:

- no additional friction for safe/cheap evidence and ordinary low-risk progress
- structured friction for privileged or high-blast-radius effects

### 10.5 Mapping to the metaphor

- `SensoryCortex`: authenticates and classifies the world into trusted or
  untrusted percepts
- cognitive-thread and opportunity builders: shape the security frame and the
  available move set
- `Ego`: selects among allowed opportunities and forms intentions
- `Superego`: determines whether an intended action may progress, and under what
  authorization mode
- `MotorCortex`: carries out only valid commits

This keeps the metaphor intact:

- the Ego still cycles intentions normally
- the Superego still judges
- the MotorCortex still acts

The difference is that the action lifecycle is now explicit and policy-aware.

---

## 11. Connector Boundary

### 11.1 First-party now

Initially, connectors can remain in-process and first-party.

### 11.2 Zero-trust-ready later

The architecture must still anticipate future third-party connectors.

That means the interface should be designed now so a connector can later run:

- in-process
- out-of-process
- under sandbox
- under allowlisted IPC/RPC

without changing the agent's cognitive/control model.

### 11.3 Required future-ready abstraction

Introduce a connector runtime boundary:

```kotlin
interface ConnectorRuntime {
    suspend fun prepare(...)
    suspend fun stage(...)
    suspend fun commit(...)
    suspend fun health(...)
}
```

Later, a `ConnectorRuntime` may be backed by:

- built-in Kotlin implementation
- isolated subprocess
- signed remote/local plugin host

### 11.4 Zero-trust assumptions for future third-party connectors

Treat third-party connectors as:

- able to lie in metadata
- able to mutate tool descriptions
- able to attempt secret access
- able to return malicious content
- never entitled to raw process environment

This implies future work on:

- connector manifests
- capability declarations
- description pinning
- secret-handle injection instead of raw env injection
- policy-scoped connector permissions

---

## 12. Approval and Autonomy Model

Approval must be a mode, not a separate architecture.

### 12.1 Target modes

- `MANUAL_ONLY`
- `APPROVAL_REQUIRED`
- `POLICY_AUTONOMOUS`
- `ADMIN_EMERGENCY_ONLY`

### 12.2 Key rule

The same action type may run in different modes depending on:

- channel
- principal
- deployment
- policy
- rollout stage

Examples:

- `email_send` in testing: `APPROVAL_REQUIRED`
- `email_send` in mature owner-only automation: `POLICY_AUTONOMOUS`
- `social_post` in production public account: maybe still `APPROVAL_REQUIRED`
- emergency cancellation: `ADMIN_EMERGENCY_ONLY`

Direct commit should use per-action explicit opt-in only. This means the system
may choose which action families are allowed to bypass staging under policy,
rather than inferring direct-commit eligibility from a broad class alone.

### 12.3 Enabling autonomous public posting

Autonomous public posting must be explicitly enabled. It is never available
merely because a connector exists.

Enablement should require all of:

- connector/account-specific policy enablement
- owner-controlled configuration
- explicit account allowlisting
- explicit effect-class allowance for `COMMIT_PUBLIC`
- rollout mode not set to approval-only

Proposed enablement posture:

- default: denied
- testing: approval-backed only
- production opt-in: policy-autonomous only after explicit owner enablement

This should be modeled as policy, not as ad hoc special-case logic inside a
social connector.

### 12.4 Approval artifact

Approval should not be implicit conversational memory.

It should create an explicit authorization object bound to:

- staged action id
- action hash
- approver principal
- channel
- expiry
- policy version

This is required even if approval UX is initially simple.
Approval and authorization artifacts must be durable across restarts.

---

## 13. Immediate Refactor Targets

These current components should be migrated first because they already stress the
new security model.

### 13.1 `goal_operation`

Why first:

- it mutates persistent behavior
- it can create recurring future work
- it must stay agent-driven, per product decision

Target treatment:

- classify as `CONTROL_PLANE`
- owner trusted instruction may prepare/stage/commit under policy
- non-owner channels may not create or revise goals
- autonomous recurring goal creation must be separately policy-gated
- recurring goal creation should require stricter commit policy than one-shot
  goal updates

### 13.2 `reflect`

Why second:

- it writes durable internal state
- it is the obvious path for memory poisoning

Target treatment:

- classify as `COMMIT_STATEFUL`
- prohibit reflection writes derived directly from external untrusted instruction
- require provenance-aware filtering

### 13.3 `email_send`

Why third:

- it already exists
- it is externally visible and potentially irreversible

Target treatment:

- separate `email_prepare_draft` from `email_commit_send`
- make autonomous send a policy decision, not the default contract

---

## 14. Implementation Phases

The phases below are organized around architecture, not product features.

### Phase 0 — Core Contract Rewrite

Deliverables:

- new action lifecycle types
- new intention kind / commit mode split
- new action contract model
- effect class model
- policy surface skeleton
- commit authorization artifact
- single-operation staged action model
- semantic action family plus lifecycle-state model

Files likely affected:

- `agent/actions/ActionPluginContracts.kt`
- `agent/model/QueueModels.kt`
- `agent/model/CognitionModels.kt`
- `agent/ego/ActionReviewPipeline.kt`
- `agent/cortex/motor/MotorCortex.kt`
- `agent/actions/ActionRegistry.kt`

### Phase 1 — Trust and Provenance Foundation

Deliverables:

- principal/channel trust context
- provenance model
- security-enriched stimulus/percept/thread context
- planner-visible trust/provenance summaries
- scratchpad and memory provenance carriage
- unified external content ingestion pipeline
- `reflect` retained as planner-visible with narrowed provenance/policy
- normalized channel/principal identity model introduced immediately

Files likely affected:

- `agent/model/ConversationModels.kt`
- `agent/cortex/sensory/SensoryCortex.kt`
- `agent/ego/Ego.kt`
- `agent/ego/MemorySystem.kt`
- `agent/memory/scratchpad/**`
- new provenance/support files

### Phase 2 — Superego Authorization Redesign

Deliverables:

- deterministic policy layer for prepare/stage/commit
- superego outputs extended to authorization modes
- action visibility filtered by policy before planner prompt build
- low-risk direct-commit path retained for safe effect classes
- final motor commit refusal without valid authorization
- deny-until-enabled policy for public autonomous commit
- deterministic denial feeds replanning instead of dead-ending work

Files likely affected:

- `agent/superego/Superego.kt`
- `agent/superego/SuperegoDeterministicConscience.kt`
- `agent/superego/SuperegoPolicy.kt`
- `agent/ego/LlmEgoPlanner.kt`

### Phase 3 — Existing Action Migration

Priority:

1. `goal_operation`
2. `reflect`
3. `email_send`
4. evidence actions where needed for consistency

Deliverables:

- migrated action contracts
- split prepare/commit where applicable
- policy-aware descriptors

### Phase 4 — Connector Boundary Hardening

Deliverables:

- connector runtime abstraction
- secret-handle injection boundary
- future zero-trust plugin host stubs
- MCP description pinning stubs
- operator-managed external policy loading boundary
- restart-required policy loading for v1

This phase must make future third-party loading possible without reopening the
architecture.

### Phase 5 — New Product Actions on Top of New Model

Only after Phases 0-4:

- calendar/weather/news/task read actions
- messaging send actions
- inbox automation actions
- social publishing actions

---

## 15. Rollout Order for Product Features

Product rollout should follow security maturity, not value alone.

### 15.1 First

Read-mostly owner-facing briefing flows:

- calendar read
- weather read
- news read
- task read
- staged message delivery to owner

### 15.2 Second

Inbox preparation flows:

- read inbox
- classify
- summarize
- draft reply
- stage archive/unsubscribe/send

### 15.3 Third

Policy-autonomous inbox operations after validation:

- send replies
- archive
- unsubscribe

### 15.4 Fourth

Public publishing:

- draft post bundle
- stage post
- approval or autonomous publish by policy

Public broadcast is the highest bar and should be last.

---

## 16. Testing Strategy

The redesign needs dedicated security tests, not just unit coverage.

### 16.1 Deterministic tests

- owner trusted chat can create a goal
- external participant cannot create a goal
- tainted external content cannot directly become commit payload
- staged action without authorization cannot commit
- policy-hidden actions do not appear in planner action set
- low-risk observe/commit loop does not require staging when policy says direct
  commit is allowed
- deferred intentions re-enter the loop without losing thread security context

### 16.2 Scenario/red-team tests

- poisoned email tries to create recurring goal
- poisoned RSS tries to trigger public post
- calendar event title tries to write memory instructions
- compacted conversation should not bypass authorization
- repeated send loop hits action policy/rate limit

### 16.3 Future plugin-host tests

- mutated connector metadata
- connector attempts env access outside secret handles
- connector returns prompt-injecting payload

---

## 17. Open Questions

Remaining open questions should be kept narrow and implementation-specific.

At the architecture level, the previously open questions are now resolved:

- non-owner team channels remain external-only in v1
- `reflect` remains planner-visible in v1, with narrowed provenance and policy
- public autonomous broadcast defaults to deny-until-enabled
- policy may be loaded from Kotlin config plus operator-managed external YAML
- action workflows use one semantic family plus lifecycle state
- direct commit is explicit per-action opt-in only
- durable staged/authorization state lives in existing SQLite with dedicated
  tables
- restart is required for policy reload in v1
- deterministic policy is final, but denial still feeds Ego replanning
- recurring goal creation has stricter policy than one-shot goal updates
- staged items and receipts are backend-first but inspectable

Important constraint from the YAML decision:

- policy YAML must be treated as trusted operator configuration
- the agent must not have write access to policy files
- future file-editing capabilities must not include policy/config write paths by
  default

---

## 18. Immediate Next Step

The next implementation document should be a focused refactor plan for Phase 0.

That plan should define:

- the replacement for `AgentActionPlugin.execute(...)`
- the new action lifecycle model
- the new policy and authorization types
- how `goal_operation` migrates first
