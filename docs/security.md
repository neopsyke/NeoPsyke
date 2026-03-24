# NeoPsyke Security Model

> Status: Current implementation reference
>
> Last updated: 2026-03-24
>
> Source of truth: current code under `src/main/kotlin/ai/neopsyke/**`

---

## 1. Purpose

This document explains the security model that NeoPsyke actually implements
today.

It is not a roadmap and it is not an aspirational design brief. It is a manual
for the current runtime:

- what the system trusts
- what the system treats as untrusted
- how actions are reviewed
- how side effects are authorized
- what is durably recorded
- what remains risky or incomplete

This is the single authoritative security reference for the project. When this
document and the code diverge, the code wins.

---

## 2. Motivation

NeoPsyke is built as an agent that can reason, plan, communicate, mutate its
own persistent goal system, and execute external actions. That combination is
useful, but it creates a real security problem:

- the model reasons over mixed trusted and untrusted content
- some actions produce irreversible or high-impact side effects
- approvals cannot safely live only in prompt memory
- the runtime must remain usable for low-risk work without turning every action
  into an operator ceremony

The current design tries to solve this by combining:

- explicit trust classification at ingress
- deterministic review before model judgment
- policy-based authorization for action lifecycle progression
- durable staging and approval artifacts
- a final execution guard at commit time
- an inspectable ledger for staged, denied, executed, and bypassed actions

The core philosophy is:

- trust must be explicit
- approvals must be durable
- policy must be authoritative
- the LLM must not be the only security layer
- denials must not dead-end work silently

---

## 3. Security Paradigm

NeoPsyke's current model is a layered control system, not a single gate.

The runtime separates:

- `instruction trust`
  - whether a source is allowed to instruct the agent
- `data trust`
  - whether content is trusted as factual input or must be treated as external
    material
- `action effect`
  - whether an action merely observes, communicates privately, mutates durable
    state, publishes publicly, or changes control-plane state
- `authorization lifecycle`
  - whether an action may commit immediately, must stage, or requires explicit
    approval

The design is intentionally closer to a controlled workflow engine than to a
"tool call directly from model output" architecture.

At a high level:

1. Ingress assigns trust and provenance
2. Deterministic validators reject obviously invalid or disallowed actions
3. Policy decides whether the action may commit, must stage, or must be denied
4. Optional LLM superego review can still deny an action
5. Action control persists staged work and approval artifacts
6. Motor execution refuses non-observe side effects that lack required
   authorization

---

## 4. Trust Model

### 4.1 Principals

NeoPsyke normalizes the actor behind a conversation or runtime event into a
principal role:

- `OWNER`
- `APPROVED_AUTOMATION`
- `EXTERNAL_PARTICIPANT`
- `SYSTEM_INTERNAL`
- `ADMIN_CONTROL`
- `UNAUTHENTICATED_EXTERNAL`

This is implemented in
[SecurityModels.kt](src/main/kotlin/ai/neopsyke/agent/model/SecurityModels.kt).

Current practical meaning:

- owner-origin direct channels are trusted to issue instructions
- internal automation and admin-control channels are also trusted instruction
  sources
- external participants are treated as untrusted instruction sources
- untrusted participants may still cause staging proposals, but not autonomous
  privileged commits

### 4.2 Channels

Channels are modeled with stable semantic dimensions rather than provider
enums:

- `ChannelSurface`
  - `DIRECT`, `GROUP`, `SHARED_WORKSPACE`, `AUTOMATION`, `ADMIN`
- `TransportClass`
  - `CHAT`, `WEBHOOK`, `API`, `INTERNAL`

Provider details are data, not core enum values. That keeps the model stable
while still allowing policy to distinguish sources by provider, account, and
channel id.

### 4.3 Instruction Trust

NeoPsyke distinguishes:

- `TRUSTED_INSTRUCTION`
- `UNTRUSTED_INSTRUCTION`

This is central to action authorization. Some actions, such as
`goal_operation` and `email_send`, are explicitly constrained to trusted
instruction only.

### 4.4 Data Trust and Provenance

NeoPsyke separately tracks the trust level of content:

- `TRUSTED_DATA`
- `EXTERNAL_DATA`
- `SANITIZED_EXTERNAL_DATA`

Each source also carries structured provenance:

- provider
- content kind
- object type
- part
- source reference
- optional sanitization record

This allows the runtime to preserve the distinction between:

- a trusted owner instruction
- an internal system signal
- external material that the model may reason over but must not obey

At runtime this is also active thread state, not just passive metadata. Each
root input carries aggregated data trust plus taint-source summaries. When
observe-style actions ingest external artifacts, the thread trust degrades and
stays degraded for the lifetime of that root input.

---

## 5. Ingress Security Model

### 5.1 Stimulus to Percept

The sensory layer attaches security context at ingress, not later.

Implemented examples in
[SensoryCortex.kt](src/main/kotlin/ai/neopsyke/agent/cortex/sensory/SensoryCortex.kt):

- stdin chat input is treated as an owner direct trusted instruction source
- `id` cue signals are treated as trusted internal automation
- `goal-runtime` cues are treated as trusted internal automation

The percept appraiser preserves provenance from the stimulus into the percept.

### 5.2 Why this matters

NeoPsyke does not operate on "naked text" once content enters the cognitive
system. Security-relevant metadata travels with the conversation context and
the provenance object.

This is one of the system's strongest current architectural properties:

- trust is attached at ingress
- trust is visible to later policy
- later stages do not need to guess where input came from

### 5.3 Cognitive Thread Security Context

Each cognitive thread carries a security context established from its root input:

- root principal and channel
- instruction trust
- aggregated data trust with taint-source summaries
- policy scope
- visible action-family bounds

When observe-style actions ingest external artifacts during a thread, the
thread's trust degrades and stays degraded for the lifetime of that root input.
This ensures that externally tainted content cannot later be treated as trusted
material within the same reasoning chain.

### 5.4 Security Through the Cognitive Pipeline

Security context flows through the full cognitive sequence, not just at the
boundaries:

```
stimulus → percept → cognitive thread → opportunity → intention →
  prepared action → staged action → authorized commit → receipt
```

The first five stages are cognitive. The later stages are the secure action
lifecycle that begins when an intention targets an action.

At each stage:

- **Stimulus**: source is authenticated, principal is resolved, initial
  provenance is attached. No raw stimulus enters cognition without security
  metadata.
- **Percept**: trust is normalized and classified (trusted instruction, trusted
  data, external data, sanitized external data). Invalid or unauthenticated
  input is rejected.
- **Cognitive thread**: the security frame is established. The thread defines
  what is thinkable in this context through its policy scope and action-family
  bounds.
- **Opportunity**: prohibited or impossible next steps are pruned. Only actions
  allowed by policy and provenance are surfaced to the Ego.
- **Intention**: the Ego selects from already policy-shaped opportunities. It
  cannot widen the action surface it receives.
- **Prepared/staged/committed action**: the action lifecycle enforces
  deterministic policy, Superego judgment, durable authorization, and final
  motor guard.

### 5.5 Distributed Policy Enforcement

The Superego is central to governance, but it is not the sole enforcement
boundary. Hard policy belongs in deterministic code, distributed across the
cognitive pipeline:

- `SensoryCortex` enforces stimulus/percept-level policy:
  - channel authentication
  - identity normalization
  - initial trust/provenance assignment
  - early rejection of invalid or unauthenticated input
- Cognitive-thread construction enforces thread-level policy:
  - root trust scope
  - policy scope
  - visible action-family bounds
- Opportunity construction enforces opportunity-level policy:
  - which next moves are actually available in this thread
  - which lifecycle transitions are permitted
  - which options are removed due to provenance, limits, or policy
- `Ego` selects among already policy-shaped opportunities. It must not widen
  the action surface it receives.
- `SuperegoDeterministicConscience` enforces hard policy invariants on intended
  action progression through deterministic code rules.
- `SuperegoReviewEngine` handles contextual judgment within policy bounds,
  optionally using an LLM with two-stage escalation support.
- `MotorCortex` refuses to execute any commit that lacks a valid authorization
  artifact or violates final execution constraints.

This means that by the time the Ego forms an intention, the action surface has
already been narrowed by multiple independent policy layers. The Superego
reviews what remains, and the MotorCortex enforces the final guard.

---

## 6. Action Security Architecture

### 6.1 Action Contracts

Each action descriptor exposes a security contract through
[ActionPluginContracts.kt](src/main/kotlin/ai/neopsyke/agent/actions/ActionPluginContracts.kt).

Current contract fields include:

- action type
- effect class
- capabilities
- whether direct commit is allowed
- whether autonomous commit is supported
- allowed instruction trust
- allowed argument data trust

This allows the security model to reason about actions as classes of behavior,
not just tool names.

### 6.2 Effect Classes

Current effect classes are:

- `OBSERVE`
- `COMMIT_PRIVATE`
- `COMMIT_PUBLIC`
- `COMMIT_STATEFUL`
- `CONTROL_PLANE`

These classes drive policy and execution behavior. For example:

- observe-class actions may direct-commit autonomously
- public commits are deny-until-enabled for autonomous use
- control-plane actions are treated more strictly than normal observation

### 6.3 Current First-Party Action Surface

Examples from current plugins:

- `contact_user`
  - private commit
  - direct commit allowed
  - autonomous commit supported
- `goal_operation`
  - control-plane
  - trusted instruction only
  - trusted argument data only
  - direct commit allowed by contract, but recurring mutations are staged by
    policy
- `email_send`
  - private commit
  - trusted instruction only
  - staged by default under current policy
- `reflect_internal`
  - commit-stateful
  - trusted data only
  - intended only for trusted self-observation / internal lessons
- `reflect_evidence`
  - commit-stateful
  - sanitized external data only
  - accepts same-root evidence artifact references only
  - persists into quarantined evidence memory, not normal trusted self-memory

Relevant code:

- [ContactUserActionPlugin.kt](src/main/kotlin/ai/neopsyke/agent/actions/builtin/ContactUserActionPlugin.kt)
- [GoalOperationActionPlugin.kt](src/main/kotlin/ai/neopsyke/agent/actions/builtin/GoalOperationActionPlugin.kt)
- [MicrosoftGraphEmailActionPlugin.kt](src/main/kotlin/ai/neopsyke/agent/actions/email/MicrosoftGraphEmailActionPlugin.kt)
- [ReflectActionPlugin.kt](src/main/kotlin/ai/neopsyke/agent/actions/builtin/ReflectActionPlugin.kt)

---

## 7. Review and Authorization Pipeline

The current action path is implemented in
[ActionReviewPipeline.kt](src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt).

The path is:

1. scratchpad finalization
2. deterministic `DecisionVerifier`
3. `Superego` review
4. `ActionControlService`
5. `MotorCortex`

### 7.1 DecisionVerifier

The decision verifier is a deterministic pre-answer gate for volatile factual
answers delivered through `contact_user`.

Implemented in
[DecisionVerifier.kt](src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt).

It classifies requests into categories such as:

- volatile fact
- stable fact
- transformation
- personal memory
- subjective advice
- static reasoning

It can require successful external evidence for volatile requests when evidence
tools are available and dispatchable.

This matters because verification-sensitive factual answers are not left purely
to model confidence.

### 7.2 Superego

The current superego is layered, not purely LLM-based.

Implemented in:

- [Superego.kt](src/main/kotlin/ai/neopsyke/agent/superego/Superego.kt)
- [SuperegoDeterministicConscience.kt](src/main/kotlin/ai/neopsyke/agent/superego/SuperegoDeterministicConscience.kt)

Current order:

1. deterministic conscience
2. authorization policy
3. optional LLM review

Important properties:

- deterministic denials are authoritative
- policy denials are authoritative
- the LLM can still deny after deterministic and policy allow
- the LLM is not the first or only safety layer
- certain internal `reflect_internal` paths may bypass LLM review after deterministic
  and policy checks

### 7.3 Policy Authorization

Authorization policy is implemented in
[ActionAuthorizationPolicy.kt](src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionAuthorizationPolicy.kt).

Current policy properties:

- operator-overridable via YAML
- loaded at startup
- restart required for reload
- builtin default policy version is `builtin-defaults-v1`

Current default rules:

- `contact_user`
  - direct commit enabled
  - autonomous commit enabled
- `goal_operation`
  - direct commit enabled
  - autonomous commit enabled
  - recurring goal create/revise requires approval
- `email_send`
  - direct commit disabled
  - autonomous commit disabled
- `reflect`
  - direct commit enabled
  - autonomous commit enabled

Current policy behavior also enforces:

- instruction trust restrictions from the action contract
- external participants may propose non-observe actions only by staging them
  for owner approval
- public commit autonomy is deny-until-enabled through explicit target
  allowlists
- observe actions may direct-commit autonomously

---

## 8. Action Lifecycle and Durable Control

### 8.1 Lifecycle Model

The durable lifecycle types are implemented in
[ActionLifecycleModels.kt](src/main/kotlin/ai/neopsyke/agent/model/ActionLifecycleModels.kt).

Current lifecycle objects:

- `PreparedAction`
- `StagedAction`
- `CommitAuthorization`
- `ActionReceipt`
- `ActionLedgerEntry`

Current staged statuses:

- `READY`
- `WAITING_AUTHORIZATION`
- `AUTHORIZED`
- `EXECUTING`
- `WAITING_EXTERNAL`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

Current authorization modes:

- `NOT_APPLICABLE`
- `APPROVAL_BACKED`
- `POLICY_AUTONOMOUS`
- `ADMIN_OVERRIDE`

### 8.2 Why this matters

Approvals are durable artifacts, not remembered conversation state.

This is one of the strongest security properties currently implemented. Once an
action is staged, the system can inspect:

- what was staged
- why it was staged
- how it was authorized
- what policy version was used
- whether it executed
- whether it waited, failed, or completed

### 8.3 Storage

Durable action-control state is persisted in SQLite via
[SqliteActionControlStore.kt](src/main/kotlin/ai/neopsyke/agent/actioncontrol/SqliteActionControlStore.kt).

Current durable tables:

- `staged_actions`
- `commit_authorizations`
- `action_receipts`
- `action_ledger_entries`

The store enables WAL mode and persists action-control state independently of
request memory.

### 8.4 Autonomous Worker

Autonomous staged actions are processed by a runtime-owned background worker in
[ActionControlAutonomousWorker.kt](src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionControlAutonomousWorker.kt).

This means `READY` staged actions are not executed opportunistically inside a
random interactive request path. They are drained by a dedicated runtime worker.

### 8.5 Ordering and Execution Keys

The staged-action scheduler currently uses two runtime-only ordering fields:

- `threadSequence`
  - preserves side-effect order within a cognitive thread
- `executionKey`
  - serializes same-target work

The SQLite store computes runnable `READY` autonomous actions using SQL, not
just in-memory filtering. It blocks execution when:

- an earlier nonterminal action exists in the same thread
- another nonterminal action is already active on the same execution key

This reduces overtaking and same-target concurrency bugs.

### 8.6 Important current caveat

`threadSequence` assignment is currently safe for the present runtime model once
the action is staged, but it is not yet a single transactional allocation path
for future high-concurrency same-root staging across multiple threads or
processes.

That is a known limitation already documented in the code.

---

## 9. Receipts, Ledger, and Operator Visibility

### 9.1 Durable Receipts

NeoPsyke records durable receipts for action outcomes. It also records a richer
ledger for staging and denial events.

Current receipt importance levels:

- `SIGNAL`
- `BACKGROUND`
- `TRACE`

Current ledger kinds:

- `STAGED`
- `AUTHORIZED`
- `EXECUTED`
- `WAITING_EXTERNAL`
- `DENIED`
- `REFUSED`
- `CANCELLED`
- `BYPASS_EXECUTED`

### 9.2 Why there are both receipts and ledger entries

Receipts represent execution outcomes.

Ledger entries record broader lifecycle truth, including:

- staged for approval
- staged for autonomous execution
- authorized
- denied by gate/policy
- refused by action control
- cancelled by operator
- bypass-executed fallback paths

This is important because actions can be blocked before commit, and those
security-relevant denials are part of the runtime truth.

### 9.3 Current UI and API

The dashboard exposes action control at `/action-control` through
[DashboardServer.kt](src/main/kotlin/ai/neopsyke/dashboard/DashboardServer.kt).

Current APIs:

- `/api/action-control/staged`
- `/api/action-control/receipts`
- `/api/action-control/ledger`
- `POST /api/action-control/staged/{id}/authorize`
- `POST /api/action-control/staged/{id}/deny`

Current UX behavior:

- UI defaults to important activity
- background activity can be shown
- trace activity is reserved for deeper operator/debug inspection

---

## 10. No Silent Dead Ends

One of the most important current behavioral properties is that denials do not
simply disappear.

In the review pipeline:

- task verifier denials are recorded in the ledger and fed back into fallback /
  replanning behavior
- superego denials are recorded and fed back
- action-control refusals are recorded and fed back
- dashboard cancellations are also turned into planner-visible blocked-action
  feedback

This means the security system is designed to constrain behavior without
silently terminating the agent's work. The runtime still expects Ego to choose
the next step, but the denial itself becomes durable and inspectable.

---

## 11. Final Execution Guard

The final execution guard lives in
[MotorCortex.kt](src/main/kotlin/ai/neopsyke/agent/cortex/motor/MotorCortex.kt).

Current behavior:

- if an action contract exists
- and the action is not `OBSERVE`
- and direct commit is not allowed
- and no authorization artifact is present

then execution fails with a commit-authorization-required result.

This matters because it prevents the earlier planning layers from being the only
enforcement point.

---

## 12. External Content Hardening

### 12.1 Prompt Injection Defense

Implemented in
[PromptInjectionDefense.kt](src/main/kotlin/ai/neopsyke/agent/support/PromptInjectionDefense.kt).

Current protections:

- deterministic scan for common instruction-override patterns
- role-like line redaction
- code-fence neutralization
- unified external-content ingestion for current external observe paths before
  they become planner/scratchpad/memory inputs
- explicit untrusted-data framing with:
  - `UNTRUSTED_EXTERNAL_DATA_BEGIN`
  - `UNTRUSTED_EXTERNAL_DATA_END`
- explicit instruction to treat framed content as data only and not follow
  embedded instructions

This is a meaningful hardening layer, but it is not a full isolation boundary.

### 12.2 Action Payload Security

Implemented in
[ActionPayloadSecurity.kt](src/main/kotlin/ai/neopsyke/agent/support/ActionPayloadSecurity.kt).

Current deterministic helpers include:

- secret exfil intent detection
- sensitive PII exfil intent detection
- inline secret material detection
- public HTTPS URL enforcement
- localhost / `.local` / RFC1918 / link-local rejection
- sensitive endpoint path detection
- sensitive query param detection
- timezone syntax validation

These checks are used by plugins as hard validation helpers before execution.

---

## 13. Current Advantages

The current NeoPsyke implementation already has several strong security
properties.

### 13.1 Explicit trust at ingress

Security metadata is attached early and carried through the runtime.

### 13.2 Durable approvals and staging

Approval is not merely conversational. It is a stored authorization artifact.

### 13.3 Layered review

Current review is not purely LLM-based:

- deterministic request verification
- deterministic superego conscience
- policy authorization
- optional LLM superego review
- final motor guard

### 13.4 Denial visibility

Blocked work is durably recorded and fed back for alternative planning rather
than being silently discarded.

### 13.5 Durable operator inspection

Staged actions, receipts, and ledger events are queryable and visible from the
dashboard.

### 13.6 Autonomous work is runtime-owned

Autonomous staged execution is handled by a background worker, not mixed into an
arbitrary user request path.

### 13.7 Policy is operator-controlled

The runtime already supports startup-loaded YAML-backed policy overrides for
core authorization behavior.

---

## 14. Current Remaining Risks and Limitations

This section is intentionally direct. These are not hypothetical concerns; they
are current implementation boundaries.

### 14.1 Plugins are still trusted in-process code

Action plugins are currently discovered with Java `ServiceLoader` and run
in-process through
[ActionRegistry.kt](src/main/kotlin/ai/neopsyke/agent/actions/ActionRegistry.kt).

This means the current plugin model assumes trusted first-party code.

NeoPsyke does not yet implement a real third-party out-of-process connector
runtime.

### 14.2 Plugin factories still receive ambient environment-backed secrets

`ActionPluginFactoryContext` currently exposes:

- `env: Map<String, String> = System.getenv()`
- `secretProvider = EnvActionSecretProvider(env)`

This is acceptable for the current first-party runtime but is not a sufficient
zero-trust model for third-party connectors.

### 14.3 Third-party connector isolation is only stubbed today

[ConnectorBoundaryModels.kt](src/main/kotlin/ai/neopsyke/agent/actions/ConnectorBoundaryModels.kt)
defines the concept of out-of-process isolation, but the actual runtime only
implements `FIRST_PARTY_IN_PROCESS`.

### 14.4 Dashboard approval currently assumes local owner trust

The dashboard server binds to `127.0.0.1` by default, which reduces network
exposure, but the action-control authorize/deny endpoints currently construct an
owner security context directly once the request reaches the local server.

There is no separate authentication layer in `DashboardServer.kt` itself.

This is a real trust assumption:

- localhost access is currently treated as operator access

### 14.5 Prompt-injection defense is heuristic, not isolation

The current prompt-injection controls are useful and deterministic, but they do
not constitute a sandbox against hostile content. They reduce risk; they do not
eliminate it.

### 14.6 High-concurrency same-thread staging is not yet fully hardened

Same-thread ordering is enforced once actions are staged, but sequence
allocation is not yet a stronger transactional multiprocess allocator.

### 14.7 Some policy safety still depends on correct action contracts

The policy model is only as good as the action contract metadata exposed by the
registered plugin. If a future action were misclassified, policy could be too
permissive or too weakly staged.

---

## 15. Email and External-Side-Effect Reality

The current runtime already has real side-effecting capability.

For example, `email_send`:

- is implemented in-process
- uses Microsoft Graph directly
- validates recipients, sender, subject, body, inline secret material, and
  allowed domains
- is trusted-instruction only
- is staged by default under builtin policy

This is good progress, but it is also why the runtime needs the stronger
lifecycle model it now has. High-impact actions are no longer modeled as "just
another tool call."

---

## 16. Logging and Auditability

NeoPsyke currently has two complementary inspection layers:

- normal runtime logs
- durable action-control records

The durable records are the more stable source for staged/authorized/executed
truth. Logs remain important for implementation diagnostics and debugging.

The current advertised security story should therefore be:

- receipts and ledger entries explain what happened
- logs explain code-path detail when deeper diagnosis is needed

---

## 17. What Is Not Advertised Yet

This manual intentionally does not claim that NeoPsyke already has:

- out-of-process third-party connector isolation
- a fully zero-trust external tool host
- authenticated remote operator approvals
- a mature multi-tenant or hostile-network deployment model

Those areas are planned, partially stubbed, or explicitly left as future work.

---

## 18. Summary

The current NeoPsyke security model is based on five implemented ideas:

1. classify trust and provenance at ingress
2. validate actions deterministically before LLM judgment
3. authorize action lifecycle progression through explicit policy
4. persist staged work, approvals, receipts, and denials durably
5. enforce a final no-authorization-no-commit guard at execution time

That is already materially stronger than a prompt-only, direct-tool-call agent
architecture.

The core remaining gap is not the internal action lifecycle. It is the external
connector boundary: plugins are still trusted in-process code today. When that
changes, this manual must be updated to describe the real new trust boundary,
secret model, and operator controls.
