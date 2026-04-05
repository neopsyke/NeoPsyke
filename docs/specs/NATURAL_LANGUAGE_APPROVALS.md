# Spec: Natural-Language Approvals

> Status: Draft
>
> Last reviewed: 2026-04-04
>
> Purpose: Define a channel-native approval flow that lets the owner approve or
> deny staged actions directly in natural language from any verified owner chat
> surface without routing approval understanding through Ego.

---

## 1. Problem

NeoPsyke already stages higher-risk actions durably through action control, but
operator approval currently lives only in the dashboard action-control panel.
That is workable for local inspection, but it is not the right long-term UX for
owner-facing chat channels such as dashboard chat, Freud chat interfaces,
Telegram, or future verified owner channels like Slack or WhatsApp.

The missing capability is not merely "remote approve/deny." The runtime needs a
channel-agnostic way to:

- ask for approval directly in the owner's chat surface
- understand natural-language owner replies
- convert those replies into durable action-control decisions
- keep Ego isolated from the approval interpretation path
- preserve auditability and no-authorization-no-commit guarantees

This must be treated as control-plane work, not as ordinary conversational
reasoning.

---

## 2. Goal

Allow the owner to approve, deny, or redirect staged work directly from any
verified owner chat channel using natural language, while preserving the current
action-control durability, trust boundaries, and final execution guards.

The resulting operator experience should feel like:

- the agent asks for approval in chat
- the owner answers naturally in chat
- the system reliably interprets the answer and advances or cancels the staged
  action
- if the owner gives a different instruction, the staged action is denied and
  the new instruction enters the runtime as a normal owner message

The internal runtime reality should remain:

- staged actions are still durable action-control artifacts
- approvals are still durable control-plane artifacts
- Ego does not parse, infer, or own approval semantics
- the final execution path still requires a valid authorization artifact

---

## 3. Non-Goals

- Replacing the durable action-control ledger with conversational memory
- Letting Ego interpret approval replies
- Treating arbitrary chat replies as implicit approval without explicit pending
  approval context
- Solving hostile multi-tenant deployment or generalized remote operator auth in
  this phase
- Removing the existing dashboard action-control UX before the new flow is
  trusted in production
- Designing transport-specific widgets or button-first UX as the primary path
- Specifying low-level implementation classes, DB schemas, or API shapes in
  this document

---

## 4. Locked Decisions

These decisions are already made for this feature and should not be reopened
unless a stronger security or architectural objection appears.

1. Natural-language approval is the primary target flow.
2. The same flow must work across all verified owner chat channels, not only the
   dashboard.
3. Ego must remain unaware of any chat interception or admin-side approval
   interpretation details.
4. Approval understanding must be handled by a separate admin/control-plane
   path, not by Ego.
5. Staging still happens normally through the existing action-control lifecycle.
6. The approval interpreter should be stateless or near-stateless and operate on
   minimal context.
7. The approval interpreter may use a cheap model only as fallback when
   deterministic parsing is insufficient.
8. If the owner provides a new instruction instead of approving, the staged
   action is denied/cancelled and the raw owner instruction re-enters through
   normal ingress.
9. Internal storage and runtime semantics must preserve that approval handling
   is admin/control-plane activity, not ordinary assistant chat.
10. Existing dashboard action-control remains as a backup path during rollout.
11. When a thread is blocked on approval, the scheduler must not continue
    executing opportunities, intentions, or actions for that root until the
    approval request reaches a terminal state.
12. Only one live approval prompt may be surfaced per conversation at a time.
13. Conversation-origin approvals go back to the same originating verified owner
    channel in v1.
14. Non-conversation-origin approvals use an active verified owner channel
    resolver with YAML-configurable default channel and channel priority order.
15. Approval TTL is YAML-configurable; the initial default is 5 minutes.
16. During approval, a small admin-only explanatory surface is allowed, but only
    from staged-action metadata and without general reasoning or tool use.
17. The approval interpreter's LLM configuration and routing must follow the
    same design style as existing cognitive-role LLM configuration rather than
    introducing a one-off model wiring path.
18. The initial default model for the cheap fallback approval classifier should
    be OpenAI `gpt-5-nano`, while remaining configurable through the standard
    runtime model-routing abstractions.
19. Durable approval-resolution records must preserve channel/source provenance,
    timestamps, and other operator-resolution metadata needed for audit and
    replay.
20. The natural-language approval flow must remain compatible with Freud
    record/replay so approvals and denials can be recorded and replayed
    deterministically.

---

## 5. Core Principles

### 5.1 Approval is not conversation memory

Approval remains a durable authorization event bound to a staged action. Chat is
only the transport surface for collecting the decision.

### 5.2 No approval understanding inside Ego

Ego may request authorization and later observe the result, but it must not be
responsible for interpreting approval replies or deciding whether a natural
language response authorizes a side effect.

### 5.3 Intercept before normal chat ingress

Owner replies that may resolve a pending approval must be evaluated before they
become normal sensory input. If interception happens after ordinary ingress, the
approval path becomes spoofable and the agent loop can see control-plane traffic
it should never reason about.

### 5.4 Same semantics across channels

The runtime must not implement one approval model for dashboard chat, another
for Telegram, and another for future channels. Channel adapters may differ in
presentation and delivery, but not in security semantics or lifecycle meaning.

### 5.5 Denial and redirection must be explicit

If the owner denies the staged action, that denial is durable. If the owner also
provides a new instruction, that instruction is a separate normal ingress event,
not a hidden rewrite of the staged action.

### 5.6 Ambiguity fails closed

If the runtime cannot clearly determine whether the owner approved, denied, or
redirected the action, it must not authorize the staged action. It should ask a
clarifying control-plane question.

### 5.7 Blocked threads must actually stop running

Approval-backed staging is not only a UI or telemetry state. Once a cognitive
thread is blocked on approval, the scheduler must treat that root as
non-runnable until the approval request reaches a terminal resolution state.

### 5.8 One surfaced approval prompt per conversation

The runtime may continue processing other work in other roots, including other
conversations, other cognitive threads, Id work, goal work, and feedback
processing. But within one conversation, only one approval prompt may be
actively awaiting owner resolution at a time.

### 5.9 Terminal-once approval resolution

Each staged action may transition into terminal approval resolution exactly
once. Duplicate events, retries, stale replies, or competing control-plane
surfaces must not create a second terminal resolution.

### 5.10 Prompt-instance binding

Approval replies must bind to the latest live approval prompt instance, not only
to a general pending approval state. A stale reply to an older prompt instance
must not resolve a newer live approval request for the same staged action.

### 5.11 Canonicalized approval interpretation input

Approval interpretation must consume deterministic canonicalized input rather
than raw staged payload text, raw tool output, or arbitrary planner text.

---

## 6. Proposed Runtime Model

### 6.1 New conceptual boundary

Introduce a dedicated admin-side approval conversation layer that sits between
verified owner chat ingress and ordinary sensory ingress.

Its responsibilities are:

- track pending approval prompts bound to staged actions
- render approval prompts into owner chat channels
- inspect incoming owner replies for approval resolution
- resolve clear replies deterministically where possible
- use a cheap admin-side classifier only when deterministic resolution is
  insufficient
- emit one of the allowed control-plane outcomes:
  - approve
  - deny
  - deny + new owner instruction
  - unclear -> ask clarifying question
  - answer a metadata-only explanatory question while keeping the approval
    pending

Its responsibilities do not include:

- planning alternative actions
- rewriting the owner's new intent into a synthetic trusted instruction
- deciding whether an action should have been staged in the first place
- bypassing action control

### 6.2 High-level flow

1. Ego selects an action and normal policy review/action-control staging happens.
2. If action control returns a staged action waiting for authorization, the admin
   layer creates an approval request artifact bound to that staged action.
3. The channel adapter emits a chat-native approval prompt to the owner in the
   originating verified owner channel.
4. The next owner reply in that channel is intercepted before ordinary ingress if
   there is an active approval request in scope.
5. The admin layer classifies the reply into one of the allowed outcomes.
6. The admin layer then:
   - authorizes the staged action, or
   - denies/cancels the staged action, or
   - denies/cancels the staged action and forwards the raw owner text through
     normal ingress as a fresh owner message, or
   - asks a clarifying question and keeps the staged action pending
7. Ego only observes the resulting staged-action outcome and any separately
   ingressed new owner instruction.

### 6.3 Admin broker artifact

Every natural-language approval request must be represented as a first-class
control-plane artifact bound to:

- `staged_action_id`
- staged action hash / immutable action identity
- prompt instance id / prompt version
- allowed approver identity
- originating conversation/session scope
- originating provider/channel scope
- expiry / TTL
- prompt state
- terminal resolution state

This artifact is not merely UI state. It is the runtime truth for "a pending
natural-language approval exists for this staged action in this owner channel."

When the approval request is resolved, the durable resolution record must also
capture enough provenance to explain how the resolution happened, including at
minimum:

- resolution outcome
- resolution timestamp
- approving or denying channel/provider
- approving or denying conversation/session scope when applicable
- approving principal identity
- prompt instance id / prompt version that was resolved
- whether the reply was classified deterministically or via fallback model
- whether the reply became `DENY_AND_REISSUE`
- whether explanatory or clarification turns occurred before terminal
  resolution
- any provider-native delivery or acceptance metadata captured for the approval
  prompt when available

### 6.4 Scheduling prerequisite

Before the natural-language approval UX is considered complete, the scheduler
must be aligned with cognitive thread blocking semantics.

Current runtime direction already marks threads as blocked when an action is
staged for approval, but the scheduling layer must also honor that state.

Required architectural outcome:

- once a staged action creates a pending approval request for a root, no further
  opportunities, intentions, or actions for that same root may execute
- this must be enforced by scheduling semantics, not only by observability state
- other roots remain runnable
- unblocking happens only when the approval request reaches a terminal state
  such as approval, denial, cancellation, expiry, or supersession

This is a prerequisite for making "approval pending" a real runtime suspension
boundary rather than merely a dashboard-visible status.

---

## 7. Decision Surface

The approval interpreter must classify replies into a deliberately small set of
control-plane outcomes.

### 7.1 `APPROVE`

Meaning: the owner clearly authorizes execution of the already staged action.

Effect:

- call action-control authorization for the bound staged action
- do not forward the reply to Ego as a normal message
- record durable approval resolution metadata

### 7.2 `DENY`

Meaning: the owner clearly denies the staged action and is not issuing a new
actionable instruction.

Effect:

- call action-control deny/cancel for the bound staged action
- do not forward the reply to Ego as a new message
- record durable denial resolution metadata

### 7.3 `DENY_AND_REISSUE`

Meaning: the owner is declining the staged action but is also giving a new
 instruction or redirecting the task.

Examples:

- "No, ask him first."
- "Not now, draft it to Bob instead."
- "Don't send it. Summarize the issue for me first."

Effect:

- deny/cancel the current staged action
- forward the raw owner reply through normal ingress as a fresh owner message
- keep the new owner message semantically separate from the approval event

### 7.4 `UNCLEAR`

Meaning: the reply is ambiguous, partial, malformed, or otherwise not safe to
treat as approval or denial.

Effect:

- leave the staged action pending
- ask a clarifying admin-side question in chat
- do not forward the ambiguous message to Ego

### 7.5 `ANSWER_METADATA_ONLY`

Meaning: the owner is asking a narrow explanatory question about the pending
approval rather than resolving it.

Effect:

- answer only from currently available staged-action metadata
- do not involve Ego, planning, or tool use
- keep the same approval request pending
- do not authorize, deny, or reissue the staged action

### 7.6 Allowlisted explanatory metadata view

The admin-only explanatory surface must expose a derived operator-safe
explanatory view, not raw staged-action metadata.

Allowed by default:

- action type
- short human-readable summary
- commit mode / approval mode
- high-level target description
- high-level effect description
- why approval is required
- origin class (conversation, goal, Id, or system)
- originating provider/channel label
- created-at timestamp
- expiry timestamp

Conditionally allowed only through redacted and bounded rendering:

- recipient display labels
- destination labels
- short message preview
- short body preview
- high-level resource labels or file names

Never exposed by default:

- secrets, credentials, tokens, or authorization material
- raw authorization artifacts or hashes
- hidden routing metadata
- raw opaque internal ids unless explicitly allowlisted
- raw tool outputs
- unredacted payloads
- localhost or internal-network targets unless explicitly safe to display
- content already classified as sensitive by policy or payload validation

The explanatory surface must render from a dedicated allowlisted view rather
than directly from raw staged-action storage objects.

---

## 8. Deterministic-First Interpretation Policy

The interpreter should use the narrowest, cheapest, and most deterministic path
that can safely resolve the reply.

### 8.1 Deterministic resolution first

Deterministic resolution should be attempted before any model call. This
includes:

- exact-match approval phrases
- exact-match denial phrases
- obvious negation + redirect patterns
- explicit references to "instead", "first", "not now", "ask X", "draft
  instead", or equivalent high-confidence redirect signals
- stale/expired approval-request checks
- approver/channel mismatch checks

### 8.2 Cheap model only for ambiguity reduction

The fallback model exists only to reduce ambiguity among the already allowed
output classes. It is not a planner and not a free-form semantic interpreter.

Allowed model outputs:

- `approve`
- `deny`
- `deny_and_reissue`
- `unclear`

The model should see only:

- the raw owner reply
- the approval prompt text
- a minimal staged-action summary
- bounded metadata necessary to disambiguate the current approval scope

It should not see:

- long chat history
- planner scratchpad
- arbitrary external evidence
- unrelated tool outputs
- hidden rationale from Ego

### 8.3 Fail-closed behavior

If deterministic parsing and model fallback still do not yield a clear safe
outcome, the runtime must remain in `UNCLEAR` and ask a clarifying question.

### 8.3.1 Explicit LLM caller contract

The approval classifier must follow NeoPsyke's hardened LLM-caller pattern.

Required contract:

- schema-enforced structured output
- retry loop for transient failures
- required-field validation after parse
- safe fallback to `UNCLEAR` on exhaustion, parse failure, schema failure, or
  missing required fields
- no free-form output path that can degrade into implicit approval

The approval classifier must never default-open.

### 8.3.2 Classifier-input canonicalization

Before deterministic parsing or model fallback, approval interpretation input
must be canonicalized.

Canonicalization requirements:

- trim and whitespace normalization
- case normalization
- Unicode normalization
- punctuation normalization where appropriate
- bounded input length
- deterministic rendering of the staged-action summary
- exclusion of raw tool output, planner scratchpad, and unrelated history

The classifier must consume a dedicated canonical approval-summary view, not raw
staged payload text or arbitrary planner text.

### 8.4 LLM configuration and routing

The fallback approval classifier should follow the same configuration design
direction as existing cognitive roles such as the superego, meta-reasoner, and
memory advisor.

Required design direction:

- configure the approval interpreter through the normal LLM runtime/config
  abstractions
- allow provider/model selection through the same style of role-based routing
  already used elsewhere in the runtime
- reuse the existing model catalog concepts such as token-weight / cost-aware
  tuning where applicable
- keep the approval classifier independently configurable from planner,
  superego, meta-reasoner, and memory-advisor roles
- avoid a bespoke hardcoded model path that bypasses the runtime's established
  model-selection architecture

Initial default:

- use OpenAI `gpt-5-nano` as the default cheap fallback classifier model

Rationale:

- the approval interpreter's task is narrow and classification-heavy rather than
  open-ended reasoning-heavy
- the classifier should be fast and inexpensive because it may sit on a
  high-frequency owner-interaction path
- the model must still remain configurable, replaceable, and testable through
  the same runtime abstractions as other LLM-backed subsystems

Future direction:

- if ambiguity handling quality proves insufficient, the approval interpreter
  may later adopt a staged or escalated routing strategy similar in spirit to
  other cognitive roles, but the first version should stay simple

---

## 9. Channel Abstraction

### 9.1 Uniform approval semantics

Every verified owner channel should expose the same approval lifecycle:

- prompt delivery
- pending state
- intercepted reply handling
- durable resolution
- clarifying follow-up when needed

For v1, conversation-origin approvals return to the same originating verified
owner channel.

### 9.2 Channel adapter responsibilities

A channel adapter may decide:

- how approval prompts are visually rendered
- how pending approval state is surfaced in that channel
- whether approval prompts have special styling or metadata in the UI

But a channel adapter may not decide:

- who is allowed to approve
- whether an ambiguous reply counts as approval
- whether a new instruction silently mutates a staged action
- whether authorization artifacts can be skipped

### 9.3 Dashboard-specific note

Dashboard chat may style approval prompts differently from ordinary assistant
messages, but the security semantics must remain identical to Telegram and
future owner channels. Styling is presentation, not authority.

### 9.4 Non-conversation-origin routing

Some staged actions may originate from roots that are not currently tied to an
active user conversation, such as Id-origin or goal-origin work.

In those cases, the runtime must use an owner approval channel resolver that:

- prefers currently active verified owner channels
- supports a YAML-configurable default approval channel
- supports a YAML-configurable priority order across verified owner channels
- fails closed when no eligible verified owner channel is available

This routing choice determines where the approval prompt is sent. It does not
change the same approval semantics, durability rules, or authorization checks.

### 9.5 Channel liveness and eligibility

The runtime cannot reliably know that the owner has actually seen a message. The
 strongest truth it can know is that a channel is currently eligible for
 delivery and appears live enough to be a reasonable approval target.

The architecture should therefore distinguish:

- `deliverable`: the runtime can likely send to the channel successfully
- `live`: the runtime has recent channel-specific evidence that the surface is
  actively connected or recently active
- `seen_by_human`: not knowable in the general case and must not be treated as a
  security property

Where a provider exposes native delivery or acceptance metadata, the runtime
should record and use it as deliverability evidence. This improves operational
confidence and auditability, but it must not be treated as proof of human
attention.

Natural-language approval routing for non-conversation-origin work should rely
on `eligible = verified owner channel + deliverable + live-enough by
channel-specific policy`.

This should be abstracted behind a shared channel-liveness / channel-eligibility
interface implemented per integration.

Examples:

- Dashboard chat:
  - live evidence may include an active SSE chat subscription for the relevant
    session or another explicit dashboard-presence signal
  - if the dashboard is not open or not receiving, it should not win routing for
    non-conversation-origin approvals
- Telegram:
  - the runtime should assume the owner can receive approval prompts if the
    verified owner chat is configured correctly, outbound delivery succeeds, and
    the Telegram integration appears healthy
  - this is a deliverability assumption, not proof of human attention
  - if Telegram returns native outbound send success and message metadata, the
    runtime should record that as provider delivery evidence
  - an optional startup control-plane ACK message may be used as an outbound
    smoke check for the configured owner chat
  - that ACK confirms outbound delivery readiness only; it does not prove human
    visibility or inbound reply health

Selection policy for non-conversation-origin approvals:

1. prefer the highest-priority verified owner channel that is currently eligible
2. if none are currently live but a YAML-configured default approval channel is
   deliverable, use that channel
3. otherwise fail closed and leave the staged action pending

This must remain adapter-agnostic. Each channel defines its own health/liveness
signals, but the approval router uses a shared contract.

---

## 10. Security Invariants

These invariants must hold for the feature to be considered valid.

1. No normal chat message may authorize a staged action unless there is a live
   approval request artifact explicitly bound to that staged action.
2. No approval reply may authorize a different staged action than the one bound
   by the approval request artifact.
3. No approval may succeed if the approver identity or verified owner channel
   scope does not match the approval request policy.
4. No approval may bypass action control or the final authorization guard in the
   execution path.
5. No new owner instruction may be synthesized by paraphrasing the owner's
   reply. If reissued, the raw owner text must enter through normal ingress.
6. Ambiguous replies must fail closed.
7. Approval interpretation context must remain minimal and admin-scoped.
8. Durable audit records must preserve:
   - what was asked
   - what was replied
   - how it was classified
   - what staged action was affected
   - whether the reply was forwarded as a new owner instruction
   - through which channel/provider the approval or denial was resolved
   - when the approval or denial was resolved
   - which prompt instance/version was resolved
   - any provider-native delivery or acceptance evidence captured for the
     approval prompt when available
9. A blocked approval-bearing root must not continue executing queued work until
   the approval request reaches a terminal state.
10. At most one approval prompt may be actively awaiting owner resolution per
   conversation.
11. Channel routing must reason about deliverability and liveness, not pretend
    to know human attention.
12. Approval-classifier model selection must use the normal runtime LLM
    configuration path, not a hidden hardcoded shortcut.
13. A staged action must not be terminally approval-resolved more than once.

---

## 11. Interaction with Ego

The desired Ego contract is intentionally narrow:

- Ego can request authorization-backed progression.
- Ego can learn that an action was staged and is awaiting authorization.
- Ego can later observe that the staged action was authorized, denied, or
  cancelled.
- Ego can receive a separate fresh owner message if the owner gave a different
  instruction.

While approval is pending for a root, Ego must not continue executing further
work for that blocked root.

Ego should not know:

- whether the approval request came from dashboard, Telegram, or another
  channel-specific adapter
- whether the admin layer asked a clarifying question
- whether deterministic parsing or the cheap classifier resolved the reply
- whether the owner's reply was intercepted before normal ingress

This preserves a clean separation between agent reasoning and operator control.

---

## 12. Lifecycle and State Model

The natural-language approval flow adds a control-plane state machine alongside
the existing staged-action lifecycle.

### 12.1 Approval request states

- `PENDING_PROMPT`
- `AWAITING_OWNER_REPLY`
- `AWAITING_CLARIFICATION`
- `APPROVED`
- `DENIED`
- `DENIED_AND_REISSUED`
- `EXPIRED`
- `SUPERSEDED`
- `ANSWERED_METADATA_ONLY`

### 12.2 Terminality rules

- `APPROVED`, `DENIED`, `DENIED_AND_REISSUED`, `EXPIRED`, and `SUPERSEDED` are
  terminal.
- `ANSWERED_METADATA_ONLY` is non-terminal and returns to waiting-for-owner
  resolution on the same approval request.
- A staged action may have at most one live approval request per owner/channel
  resolution scope.
- A superseded or expired approval request must never authorize execution.

### 12.3 Clarification loop

Clarification must remain control-plane bounded:

- limited retry count
- limited TTL extension policy
- no drift into general-purpose conversation

If clarification fails repeatedly, the staged action should remain denied or
expire rather than silently escalating ambiguity.

### 12.4 Conversation approval queue

Approval requests must also obey a conversation-scoped queue discipline:

- only one live approval prompt may be surfaced at a time in a given
  conversation
- additional staged actions in the same conversation may still be recorded
  durably, but their approval prompts must wait until the active approval
  request resolves
- this queueing rule is independent from root-level execution blocking

This avoids unsafe ambiguity such as multiple unresolved approval prompts in the
same owner chat competing for a generic "yes" or "no".

---

## 13. Audit and Visibility

The operator should be able to inspect, after the fact:

- the original staged action
- the approval prompt shown to the owner
- the owner reply
- the classification result
- whether the reply was treated as approval, denial, reissue, or unclear
- whether the system answered a metadata-only explanatory question while keeping
  the approval pending
- through which channel/provider the approval or denial was resolved
- when the approval or denial was resolved
- any provider-native delivery or acceptance evidence captured for the approval
  prompt when available
- the final action-control mutation

This visibility belongs in durable control-plane records first and UI surfaces
second.

The owner-facing chat transcript may show the natural interaction, but the
runtime source of truth must remain the control-plane ledger.

Expiry should also emit a short owner-facing control-plane message in the active
approval channel, for example:

- approval request expired after 5 minutes
- request denied by default

This visible expiry notice is in addition to the durable terminal state change.

---

## 14. Rollout Strategy

The legacy dashboard action-control approve/deny path remains available during
rollout as a backup path and debugging surface.

The new natural-language flow should become the primary approval UX for verified
owner chat channels as soon as it is trustworthy enough, but the backup path
should remain until:

- ambiguity behavior is acceptable
- auditability is complete
- the same semantics are working across at least dashboard chat and one remote
  owner chat integration
- blocked-root scheduling semantics are enforced by the runtime rather than only
  represented in thread status

---

## 15. Risks and Failure Modes

### 15.1 Wrong-action approval

Risk: a generic "yes" resolves the wrong staged action.

Mitigation direction:

- bind every approval request tightly to one staged action
- do not permit unscoped approval interpretation

### 15.2 Over-broad semantic approval

Risk: natural language is interpreted more broadly than the owner intended.

Mitigation direction:

- narrow output classes
- minimal context
- ambiguity fails closed
- durable prompt/reply inspection

### 15.3 Approval spoofing via ordinary chat

Risk: non-owner or non-pending messages trigger authorization semantics.

Mitigation direction:

- interception only in verified owner channels
- require active approval-request artifact
- exact approver/channel binding

### 15.4 Drift into hidden plan mutation

Risk: the approval interpreter starts rewriting owner intent or mutating staged
actions instead of cancelling and reissuing.

Mitigation direction:

- explicit `DENY_AND_REISSUE` semantics
- raw message re-entry through normal ingress
- no hidden rewrite path

### 15.5 Channel-specific behavior drift

Risk: each integration evolves different approval semantics.

Mitigation direction:

- central approval broker
- thin adapters
- shared invariants and tests

### 15.6 Clarification loops

Risk: the control-plane clarification flow becomes noisy or stalls the owner.

Mitigation direction:

- bounded clarification loops
- TTLs
- explicit terminal expiry behavior

### 15.7 Fake blocking

Risk: a thread is marked blocked in inspection state, but the scheduler still
processes queued work for that root.

Mitigation direction:

- make blocked-root suppression a hard scheduler rule
- treat this as a prerequisite architectural correction, not an optional polish

### 15.8 Non-conversation-origin delivery confusion

Risk: approvals triggered by goal or Id roots are sent to the wrong owner
surface or to no visible owner surface at all.

Mitigation direction:

- explicit active-channel resolver
- YAML-configurable default approval channel
- YAML-configurable channel priority order
- fail closed when no verified owner channel is suitable

### 15.9 Explanatory drift

Risk: approval-time explanatory answers expand into general-purpose
conversation, planning, or tool-backed reasoning.

Mitigation direction:

- metadata-only explanatory surface
- no planning
- no tool use
- no open-ended assistant behavior
- preserve pending approval after explanatory answer

### 15.10 False certainty about user visibility

Risk: the runtime mistakes successful transport delivery for proof that the
owner saw the approval prompt.

Mitigation direction:

- explicitly model deliverability/liveness, not human attention
- use channel-specific liveness checks behind a shared interface
- avoid security decisions based on assumed message visibility

### 15.11 Natural-language ambiguity residual

Risk: some owner replies remain genuinely ambiguous even with deterministic
parsing and cheap-model fallback.

Mitigation direction:

- ambiguity fails closed
- bounded clarifications
- no default-open path

### 15.12 Normalization edge cases

Risk: Unicode, punctuation, casing, or shorthand edge cases cause unsafe
misclassification or inconsistent deterministic parsing.

Mitigation direction:

- explicit canonicalization rules
- deterministic normalization before interpretation
- targeted tests for edge cases

### 15.13 Time-of-check / time-of-use drift

Risk: the action the owner approves is no longer identical to the action the
runtime later executes.

Mitigation direction:

- immutable staged-action approval identity
- action-hash verification at authorization time
- no silent staged-action mutation after prompting

### 15.14 Duplicate delivery and replay noise

Risk: duplicate webhook deliveries, polling repeats, retries, or replay traffic
create multiple apparent resolution attempts.

Mitigation direction:

- terminal-once approval resolution
- prompt-instance binding
- idempotent resolution logic

### 15.15 Approval-queue starvation

Risk: a root, goal, or subsystem can monopolize the conversation approval queue
with repeated staged actions.

Mitigation direction:

- queue caps and rate limits
- expiry handling
- visible operator signals for suppressed or deferred approval prompts

### 15.16 Upstream action-summary integrity

Risk: the approval prompt or explanatory view misrepresents what the staged
action will actually do because upstream summaries or target descriptions are
wrong.

Mitigation direction:

- canonical approval-summary rendering
- action-hash / identity verification
- tests tying prompt content to staged-action truth

### 15.17 Audit-log integrity expectations

Risk: durable records exist but are still insufficient to reconstruct control
truth after failures, retries, or replay.

Mitigation direction:

- complete provenance fields
- prompt-instance tracking
- reissue provenance
- replay-compatible control records

---

## 16. Architectural Direction

This feature should be treated as a first-class control-plane architecture
addition, not as a connector-specific patch.

If existing chat ingress paths make clean interception impossible, a refactor is
preferred over layered heuristics. The long-term design should favor:

- a shared verified-owner ingress abstraction
- a distinct pre-ingress control-plane interception layer
- clear separation between admin/control-plane routing and ordinary sensory
  ingress
- channel adapters that are thin, replaceable, and security-semantic-neutral
- channel-specific liveness/eligibility providers behind a shared routing
  contract
- approval-classifier LLM routing that follows the same role-based configuration
  style as other LLM-backed runtime subsystems

Backward compatibility is not a priority. Clean separation and future-proof
channel scaling matter more than preserving accidental current boundaries.

---

## 17. Success Criteria

This feature is successful when all of the following are true:

1. A staged action can be approved or denied from dashboard chat and at least
   one non-dashboard verified owner chat channel using only natural language.
2. Approval interpretation happens outside Ego.
3. A redirected owner reply denies the staged action and re-enters as a fresh
   raw owner message.
4. Ambiguous replies fail closed and trigger clarifying admin-side questions.
5. Durable records preserve the full control-plane truth of the approval flow.
6. Channel-specific integrations share the same security semantics and approval
   lifecycle.
7. A root blocked on approval does not continue executing while the approval is
   pending.
8. At most one approval prompt is actively awaiting resolution per conversation.
9. Non-conversation-origin approvals route through the best currently eligible
   verified owner channel according to shared liveness semantics and configured
   priority.
10. The fallback approval classifier uses the standard LLM runtime configuration
    design and is not wired as a special-case model dependency.

---

## 18. Acceptance Criteria

The feature is considered complete only when all criteria in this section are
implemented and validated.

### 18.1 Architecture and boundary criteria

1. Natural-language approval handling exists as a distinct admin/control-plane
   subsystem and is not implemented inside Ego.
2. Owner approval replies that may resolve a pending approval are intercepted
   before they become ordinary sensory input.
3. The interception/control-plane path is abstracted so it can be reused across
   dashboard chat, Freud chat interfaces, Telegram, and future verified owner
   chat integrations.
4. Channel-specific delivery/liveness logic is abstracted behind a shared
   interface rather than hardcoded into one integration path.
5. Existing action-control durability remains the runtime source of truth for
   staged actions, authorization, denial, cancellation, and receipts.
6. The scheduler enforces blocked-root suspension as runtime behavior, not only
   as dashboard-visible state.
7. Approval-classifier model routing is configured through the same style of
   runtime abstractions used by existing cognitive-role LLM components.

### 18.2 Scheduling and thread-state criteria

1. When an approval request is created for a staged action, the issuing root is
   marked blocked.
2. While that approval request remains non-terminal, no opportunities,
   intentions, or actions for that same root may execute.
3. Blocking one root does not stop unrelated work in:
   - other conversations
   - other cognitive threads
   - Id-origin work
   - goal-origin work
   - async feedback processing for unrelated roots
4. Once the approval request reaches a terminal state, the blocked root can
   become runnable again according to normal runtime semantics.
5. The runtime must not silently continue planner/deferred work for a blocked
   root because of stale queued tasks that were already enqueued before the
   block occurred.
6. Resolution of the blocked staged action is terminal exactly once even under
   duplicate or competing control-plane events.

### 18.3 Conversation approval queue criteria

1. At most one approval prompt may be actively awaiting owner resolution in a
   given conversation at any time.
2. Additional staged actions in the same conversation may still be recorded
   durably while an approval is pending.
3. Those additional staged actions must not surface a second approval prompt in
   that conversation until the active approval request resolves.
4. A generic approval reply such as "yes" or "ok" must never resolve an
   approval request that was not the currently active surfaced approval prompt
   for that conversation.
5. A reply to an older prompt instance must not resolve a newer live prompt
   instance for the same staged action.

### 18.4 Decision-classification criteria

1. The approval interpreter resolves replies into exactly the allowed control
   outcomes:
   - `APPROVE`
   - `DENY`
   - `DENY_AND_REISSUE`
   - `UNCLEAR`
   - metadata-only explanatory response while preserving the same pending
     approval request
2. Deterministic resolution is attempted before any model fallback.
3. The cheap fallback model is used only to choose among the allowed output
   classes, never to plan, rewrite, or enrich the owner's intent.
4. The model fallback context is minimal and bounded to:
   - raw owner reply
   - approval prompt
   - minimal staged-action summary
   - bounded control metadata required for disambiguation
5. The interpreter must fail closed if neither deterministic logic nor model
   fallback can safely resolve the reply.
6. The approval classifier follows an explicit hardened LLM-caller contract:
   - schema-enforced output
   - retries
   - required-field validation
   - fallback=`UNCLEAR`
7. The classifier consumes canonicalized approval input rather than raw staged
   payload text.

### 18.5 Approval/denial behavior criteria

1. A clear approval reply authorizes only the specific staged action bound to
   the pending approval request.
2. A clear denial reply denies or cancels only that staged action.
3. Any owner reply that changes the requested operation, adds constraints,
   changes recipient/timing/target, or otherwise modifies the staged action
   resolves as `DENY_AND_REISSUE`.
4. In `DENY_AND_REISSUE`, the staged action is denied or cancelled first.
5. In `DENY_AND_REISSUE`, the raw owner reply is then forwarded through normal
   ingress as a fresh owner message.
6. The system must not mutate the staged action in place based on the owner's
   natural-language modification.
7. Authorization must verify that the approved action still matches the staged
   action identity and hash the owner was asked to approve.

### 18.6 Admin-only explanatory surface criteria

1. During a pending approval, the owner may ask narrow explanatory questions
   about the pending staged action.
2. These answers are generated only from currently available staged-action
   metadata.
3. These answers do not involve:
   - Ego
   - planning
   - tool use
   - external lookups
   - free-form assistant behavior outside the pending approval context
4. After an explanatory answer, the same approval request remains pending unless
   the owner then explicitly resolves it.
5. The explanatory surface exposes only the allowlisted operator-safe metadata
   view by default.
6. Sensitive or internal fields are redacted or omitted by explicit rule rather
   than caller discretion.

### 18.7 Channel-routing criteria

1. For conversation-origin staged actions, the approval prompt is routed back to
   the same originating verified owner channel in v1.
2. For non-conversation-origin staged actions, approval routing uses a shared
   channel-eligibility resolver.
3. That resolver supports:
   - channel-specific liveness checks
   - channel-specific deliverability checks
   - YAML-configurable default approval channel
   - YAML-configurable priority order across verified owner channels
4. Routing must not assume that successful transport delivery proves human
   attention.
5. If no eligible verified owner channel is available for a non-conversation
   origin, the runtime fails closed and leaves the staged action pending rather
   than guessing.

### 18.8 Dashboard-specific criteria

1. Dashboard chat approval prompts are visibly distinguishable from ordinary
   assistant chat in the UI.
2. Dashboard liveness for non-conversation-origin routing is based on an
   explicit dashboard-side signal such as active SSE chat presence or another
   equivalent live-receiving signal.
3. If the dashboard is not open or not receiving, it must not incorrectly win
   routing for non-conversation-origin approvals merely because the dashboard
   server is running.

### 18.9 Telegram-specific criteria

1. Telegram owner approval prompts can be delivered and resolved through natural
   language from the verified owner chat.
2. Telegram eligibility for non-conversation-origin routing is based on verified
   owner configuration plus healthy-enough outbound delivery semantics.
3. Telegram routing must treat successful send capability as deliverability, not
   as proof the owner has seen the message.
4. The runtime supports an optional startup Telegram control-plane ACK message
   for the configured verified owner chat.
5. Successful startup ACK delivery counts only as outbound-readiness evidence,
   not as proof of inbound reply readiness or human attention.
6. When Telegram provides native outbound send success and message metadata, the
   runtime records and uses that as delivery evidence for operational status and
   auditability.

### 18.10 Expiry and clarification criteria

1. Approval TTL is configurable in YAML.
2. The default configured TTL used by the shipped runtime is 5 minutes unless
   intentionally changed later.
3. When an approval request expires, it reaches a terminal denied-by-default
   state.
4. On expiry, the owner receives a short visible control-plane message such as
   "approval request expired after X time, request denied by default."
5. Clarification loops are bounded to 2 turns by default.
6. After clarification attempts are exhausted without a clear resolution, the
   request must fail closed rather than drift into open-ended conversation.

### 18.11 Security and audit criteria

1. No approval may succeed without a live approval request artifact explicitly
   bound to the staged action.
2. No approval reply may resolve the wrong staged action.
3. Approver identity and verified owner channel constraints are enforced before
   authorization is granted.
4. Approval interpretation does not bypass action control or the final
   no-authorization-no-commit execution guard.
5. Durable records preserve:
   - staged action identity
   - approval prompt content or durable prompt reference
   - owner reply
   - classification result
   - final control-plane mutation
   - approval/denial channel and provider
   - approval/denial timestamp
   - approving principal identity
   - approving conversation/session scope when applicable
   - prompt instance id / prompt version
   - provider-native delivery or acceptance metadata for approval prompts when
     available
   - whether the reply was reissued as a fresh owner message
   - provenance fields on reissued owner messages linking back to the approval
     request and staged action
   - explanatory-response events
   - expiry events
6. The durable records are sufficient to reconstruct what the system asked, how
   it interpreted the reply, and what action-control mutation followed.
7. Approval and denial decisions remain recordable and replayable through Freud
   without semantic divergence in the control-plane path.

### 18.12 Backward-path and rollout criteria

1. The legacy dashboard action-control path remains functional during rollout.
2. The new natural-language approval flow can be enabled and validated without
   requiring the legacy path to be removed first.
3. If the natural-language approval path is unavailable or unhealthy, the system
   fails closed rather than silently approving.
4. Freud record/replay compatibility for action-control decisions is preserved
   during rollout.

### 18.13 Required test depth

The feature is not complete with only unit coverage. It must be validated across
four layers:

1. Unit tests
2. Integration tests
3. End-to-end deterministic runtime tests
4. Channel-specific approval-flow tests

### 18.14 Required unit test coverage

Unit tests must cover at minimum:

1. deterministic classification of clear approve replies
2. deterministic classification of clear deny replies
3. deterministic classification of modification replies into
   `DENY_AND_REISSUE`
4. `UNCLEAR` behavior on ambiguous replies
5. bounded fallback-model input assembly
6. fail-closed behavior when model classification fails or is unavailable
7. blocked-root scheduler suppression
8. conversation approval queue enforcement
9. TTL expiry transitions
10. clarification turn counting
11. admin-only explanatory answers from staged-action metadata
12. channel-eligibility resolver policy ordering
13. approval-classifier runtime config loading and default-model selection
14. provider-native delivery or acceptance metadata capture when available
15. prompt-instance binding and stale-reply rejection
16. terminal-once approval resolution under duplicate events
17. allowlisted explanatory metadata rendering with redaction
18. reissued-message provenance field generation
19. Freud record/replay compatibility for approval and denial decisions

### 18.15 Required integration test coverage

Integration tests must cover at minimum:

1. staged action -> approval request creation -> prompt routing
2. approve path -> action-control authorize
3. deny path -> action-control deny
4. `DENY_AND_REISSUE` path -> deny first, then fresh owner ingress
5. explanatory question path -> metadata answer, approval remains pending
6. expiry path -> terminal deny + visible expiry notice
7. one-active-approval-per-conversation enforcement with multiple staged actions
8. unrelated roots continuing while blocked root is suspended
9. non-conversation-origin routing through eligible channel selection
10. fail-closed behavior when no eligible owner channel exists
11. approval-classifier routing follows the configured role/model path rather
    than a hidden hardcoded model
12. provider-native delivery evidence is captured and persisted when available
13. prompt-instance binding is enforced
14. approval resolution remains terminal exactly once across competing paths
15. reissued owner messages carry approval-origin provenance
16. Freud record/replay captures and replays approval and denial outcomes

### 18.16 Required end-to-end runtime test coverage

Deterministic runtime or scenario-pack style tests must demonstrate at minimum:

1. a conversation-origin staged action can be approved entirely through natural
   language in dashboard chat
2. a conversation-origin staged action can be denied entirely through natural
   language in dashboard chat
3. a modification reply becomes `DENY_AND_REISSUE` and the new instruction is
   processed as a fresh owner message
4. the blocked thread does not continue while awaiting approval
5. another unrelated thread can continue progressing while the first is blocked
6. the same semantics hold for at least one non-dashboard verified owner chat
   integration
7. the fallback approval classifier can run through the configured role/model
   path with the expected default behavior
8. approval and denial decisions can be recorded and replayed through Freud
   without approval-path divergence

### 18.17 Required channel-specific test coverage

For each supported verified owner channel, tests must verify:

1. approval prompt delivery
2. pending approval reply interception before normal Ego ingress
3. natural-language approval resolution
4. natural-language denial resolution
5. explanatory question behavior
6. expiry notice delivery
7. channel-specific liveness / eligibility semantics used by the approval router
8. optional Telegram startup ACK behavior and its effect on outbound-readiness
   state
9. provider-native delivery or acceptance metadata capture when available
10. duplicate inbound-event handling and idempotent terminal resolution

### 18.18 Residual quality bar

The feature is not complete if it only "usually works." Completion requires that
the implementation is:

- architecturally isolated from agent reasoning
- channel-agnostic in core semantics
- fail-closed on ambiguity and routing uncertainty
- durably auditable
- aligned with the runtime's existing LLM role-configuration architecture
- verified by tests at the levels described above
