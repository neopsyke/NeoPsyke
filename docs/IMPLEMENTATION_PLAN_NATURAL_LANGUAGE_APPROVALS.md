# Implementation Plan: Natural-Language Approvals

## Summary
Create `/Users/victor.toral/atomitl/ai/NeoPsyke/docs/IMPLEMENTATION_PLAN_NATURAL_LANGUAGE_APPROVALS.md` as the implementation plan for the frozen spec at `/Users/victor.toral/atomitl/ai/NeoPsyke/docs/specs/NATURAL_LANGUAGE_APPROVALS.md`.

Also create a separate progress ledger at `/Users/victor.toral/atomitl/ai/NeoPsyke/docs/IMPLEMENTATION_LEDGER_NATURAL_LANGUAGE_APPROVALS.md`.

Locking rules:
- the spec file stays frozen
- the implementation plan file also becomes frozen once implementation starts
- all cross-run progress, status, decisions made during execution, partial validation, blockers, and handoff context go only into the ledger file

Use a focused runtime refactor:
- isolate approvals from Ego reasoning
- add a shared control-plane approval subsystem before normal sensory ingress
- make blocked-thread semantics real in scheduling/runtime
- keep the core Ego loop architecture intact unless a local cleanup is clearly required

## Key Changes
### Control-plane approval subsystem
Add a new runtime subsystem outside Ego that owns approval-flow behavior:
- `OwnerIngressRouter`
  - first stop for verified owner messages from dashboard, Telegram, and future owner channels
  - classifies each owner message as:
    - approval-flow reply
    - admin-only explanatory question
    - normal owner input
- `ApprovalBroker`
  - creates and tracks approval request artifacts from staged actions
  - enforces one surfaced live approval prompt per conversation
  - tracks prompt instance, TTL, clarification count, provenance, and terminal state
- `ApprovalInterpreter`
  - deterministic parse first
  - cheap LLM fallback second
  - returns only the spec decision surface
- `ApprovalExplainer`
  - answers only from the allowlisted/redacted explanatory metadata view
- `ApprovalChannelResolver`
  - resolves eligible owner channels for non-conversation-origin approvals
- `ApprovalAuditRecorder`
  - records prompts, replies, classification path, provenance, and resolution details

Ego must not learn about admin hijacking or approval interpretation. It only sees the resulting staged-action response outcome.

### Owner ingress refactor
Refactor dashboard and Telegram owner ingress so they no longer submit verified owner chat directly to normal sensory input.

Instead:
- channel bridge constructs the verified owner message context
- passes it to `OwnerIngressRouter`
- router either consumes it as approval/control-plane traffic or forwards it unchanged into the normal sensory path

This shared router becomes the channel-agnostic insertion point for all future verified owner chat integrations.

### Blocked-thread enforcement
Fix the current runtime gap where blocked threads are marked but still schedulable.

Required behavior:
- when an approval-backed staged action is created, the issuing root/thread becomes blocked
- no opportunities, intentions, or actions for that root may execute until the approval request reaches a terminal state
- other roots continue normally
- scheduling must enforce this, not just thread metadata

Implementation should keep the current queue model if possible, but `AttentionScheduler` or an adjacent scheduling gate must become root/thread-state-aware.

### Action-control integration
Keep `ActionControlService` as the commit authority.

The new approval subsystem should:
- create approval request artifacts from staged actions
- bind each request to staged action id, action hash, prompt instance, TTL, channel scope, and approver scope
- resolve only by:
  - `authorizeStagedAction(...)`
  - `denyStagedAction(...)`
  - or deny + reissue raw owner instruction through normal ingress

Enforce:
- terminal-once staged-action resolution
- stale reply rejection
- prompt-instance binding
- idempotent resolution under duplicate delivery or competing control paths

### Config and model integration
Follow the existing domain-grouped config and cognitive-role LLM routing design.

Add a dedicated approval runtime config domain for:
- enablement
- approval TTL
- clarification limit
- default approval channel
- channel priority order
- Telegram startup ACK option
- approval interpreter model/routing
- channel liveness/deliverability settings

Add an approval-interpreter LLM role using the same runtime abstractions as Superego/MetaReasoner/MemoryAdvisor.
Initial default: `openai / gpt-5-nano`.

The approval interpreter must follow the standard LLM caller contract:
- retries
- schema-enforced structured output
- required-field validation
- safe fallback to `UNCLEAR`

### Record/replay integration
Freud record/replay must work with this flow as a core feature.

Required behavior:
- approval prompts and prompt instances are recordable
- owner replies consumed by the approval router are recordable/replayable
- approval outcomes remain captured through action-control recording
- deny-and-reissue owner instructions preserve provenance linking back to the approval request
- replay must cover approvals and denials deterministically enough for debugging

Prefer extending the existing signal recording and `RecordingActionControlService` seams.

### Docs and execution records
Update both runtime logic docs alongside implementation:
- `/Users/victor.toral/atomitl/ai/NeoPsyke/AGENT_LOGIC_SUMMARY.md`
- `/Users/victor.toral/atomitl/ai/NeoPsyke/AGENT_LOGIC_DIAGRAM.md`

Use the execution ledger as the only mutable planning artifact once implementation starts. The ledger should record:
- current phase and status
- completed work
- remaining work
- deviations from the frozen plan, if any
- validation already run and results
- blockers and follow-ups
- handoff context for the next Codex thread

## Phases
### Phase 1: Runtime foundation
Build the shared control-plane spine first:
- approval request artifacts and broker
- owner ingress router
- blocked-root scheduler enforcement
- staged-action to approval-request creation
- unblock/deny/reissue terminal handling
- core audit/provenance model

If this phase ends in a natural compile/test state:
- update the ledger
- run targeted tests
- run `./freud/bin/freud run signoff-gate`

### Phase 2: Channel integration and interpreter
Add the end-user approval path:
- dashboard integration through the shared router
- Telegram integration through the shared router
- deterministic parse + LLM fallback interpreter
- admin-only explanatory surface
- channel eligibility/liveness resolver
- Telegram startup ACK support
- persisted resolution provenance
- replay integration for approval traffic

If this phase ends in a natural compile/test state:
- update the ledger
- run targeted tests
- run `./freud/bin/freud run signoff-gate`

### Phase 3: Final validation and cleanup
Finish the feature to the frozen spec:
- close gaps in expiry UX, duplicate handling, stale reply rejection, and audit visibility
- remove or simplify any runtime paths that reduce clarity
- finalize logic docs
- finalize the ledger with completion status and remaining follow-ups, if any

Run the final validation package:
- `./freud/bin/freud run signoff-gate`
- Freud record/replay e2e tests
- interactive record/replay e2e tests
- recorded low-llm live evals
- replay-first debugging if live failures occur

## Test Plan
Add deterministic tests for:
- blocked roots never schedule while approval is pending
- non-blocked roots continue normally
- one surfaced live approval prompt per conversation
- prompt-instance binding rejects stale/out-of-order replies
- terminal-once resolution under duplicates
- deterministic approve/deny parsing
- modification replies become deny-and-reissue
- ambiguous replies become `UNCLEAR`
- admin-only explanatory questions use only allowlisted metadata
- reissued owner instructions preserve provenance
- same-channel routing for conversation-origin approvals
- eligible-channel/default/priority routing for non-conversation-origin approvals
- TTL expiry denies by default and emits the visible expiry notice
- clarification-turn limit is enforced
- Telegram startup ACK only counts as outbound-readiness evidence
- Freud record/replay captures and replays approval/denial flow

Add end-to-end coverage for:
- dashboard approve/deny/deny-and-reissue
- Telegram approve/deny
- explanatory question while approval remains pending
- stale reply
- duplicate inbound event
- expiry
- non-conversation-origin approval routing

## Assumptions
- spec file remains frozen and is not edited further
- implementation plan file is written once, then frozen when implementation starts
- all future execution progress goes into the separate ledger file
- focused runtime refactor is the chosen boundary
- no backwards compatibility work is required unless an external contract forces it
- approval logic remains outside Ego reasoning
- action-control remains the final authority for commit/deny
- dashboard action-control can remain as backup during rollout, but natural-language approval is the primary target architecture
