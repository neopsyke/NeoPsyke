# TODO: Security Hardening

> Status: In progress
>
> Last reviewed: 2026-04-03
>
> Purpose: Track remaining security work across all areas: connector trust,
> credential management, policy hardening, operator visibility, and future
> high-risk capability gating.
>
> Reference: [docs/security.md](../security.md) describes what is implemented
> today.

---

## 1. Locked Decisions

These are already decided and should not be reopened casually:

1. MCP is the compatibility boundary, not the security boundary.
2. NeoPsyke action manifests remain the planner-facing abstraction, not raw MCP
   tool names.
3. The first external host is local subprocess only over `stdio`.
4. Remote MCP servers are out of scope for the first host.
5. Third-party connectors are zero-trust by default and disabled until
   explicitly allowlisted.
6. Connector startup, handshake, validation, pinning, or health failures fail
   closed.
7. Connector outputs are external/untrusted by default unless NeoPsyke
   explicitly sanitizes and classifies them.
8. Connector subprocesses get an explicit environment: minimal runtime vars plus
   declared secret handles only; no ambient `System.getenv()` passthrough.
9. Workflow examples (Morning Briefing, Inbox Management) are assignment compositions,
   not special workflow actions.
10. Policy YAML is trusted operator configuration. The agent must not have write
    access to policy files.
11. Direct commit requires per-action explicit opt-in.
12. Autonomous public posting defaults to deny-until-enabled per
    connector/account.
13. Deterministic policy is final; denials feed Ego replanning.
14. Recurring assignment creation requires stricter commit policy than one-shot assignment
    updates.
15. Policy/config reload requires restart in v1.

---

## 2. Third-Party Connector Trust Hardening

### 2.1 Tool-description / manifest pinning

- Pin reviewed connector capability metadata, not just connector IDs.
- Fail closed on drift.
- Make operator-facing drift reasons explicit.
- Open question: whether pinning should hash only raw MCP metadata or also the
  generated NeoPsyke manifest mapping.

### 2.2 Provenance correlation in durable records

- Ensure connector ID, manifest ID, capability ID, and pin set flow through
  receipts, ledger entries, and action history consistently.

### 2.3 Connector process isolation

- Verify current subprocess host boundaries are tight enough for genuinely
  untrusted third-party MCP servers.
- Decide whether extra sandboxing is required before enabling any real external
  published connector.
- Open question: what additional sandboxing is required before enabling truly
  untrusted third-party local subprocess connectors by default.

### 2.4 Redaction and secret-spill hardening

- Ensure connector stdout/stderr/log capture cannot leak tokens or OAuth
  material into logs or UI surfaces.

---

## 3. Secret and Credential Hardening

### 3.1 OS keychain-backed storage

- Move sensitive persistent credentials toward OS keychain-backed storage.
- Google tokens are currently encrypted locally; keychain-backed storage is
  still a remaining step.
- Open question: when to make this transition.

### 3.2 Token lifecycle controls

- Revocation / reconnect flows.
- Operator-visible auth health.
- Last-success / last-failure / scope diagnostics.

### 3.3 Connector-specific secret resolution

- Per-connector handle policies.
- Better secret usage audit trail.
- Explicit deny surface for undeclared handles.

---

## 4. Policy and Runtime Hardening

### 4.1 Time-window rate limits

- Add time-window based rate limits on top of per-root-input limits.
- Especially for outbound messaging, future email send, future social post,
  and future repo/file mutation.

### 4.2 Finer-grained destination/resource buckets

- Per Telegram chat.
- Per mailbox/account.
- Per repo / filesystem root / external account when those actions exist.

### 4.3 Stronger deterministic execution templates

- Current reflection/contact/assignment normalization is not enough for future
  Gmail send, publish, repo admin, or file mutation.

### 4.4 Universal `allowedArgumentDataTrust` enforcement

- Every future observe path and every future side-effecting action must be
  checked against trust policy.
- Cannot rely on plugin-local discipline alone.

### 4.5 Keep the IntentionKind/CommitMode split aligned (implemented)

The richer intention model (`OBSERVE`, `PREPARE`, `STAGE`,
`REQUEST_AUTHORIZATION`, `COMMIT`, `DEFER`) and separate `CommitMode`
(`APPROVAL_BACKED`, `POLICY_AUTONOMOUS`, `ADMIN_OVERRIDE`) are now live in
the runtime. `PolicyScope` is a typed enum (`DEFAULT`, `DEPLOYMENT_RESTRICTED`,
`FULL_AUTONOMY`) configurable via YAML and env var.

Remaining work: keep future actions, policy rules, operator UX, and tests
aligned with that split so new capability families do not collapse back into
action-type-specific workflow aliases.

### 4.6 Policy scoping (implemented)

Channel, principal, action, and full-autonomy policy shaping are operational.
Deployment scope is a placeholder for future non-local deployments.
See `docs/security.md` section 4.5 for full policy scope documentation.

### 4.7 Cognitive runtime security (implemented)

The cognitive runtime migration is complete. The following security-relevant
stages are live runtime objects, not just type definitions:

- `Percept`: normalizes trust/data/control semantics before cognition
- `CognitiveThread`: owns live security frame, trust degradation, taint tracking
- `Opportunity`: policy-shaped admissible moves pruned before Ego chooses
- `Intention`: explicit lifecycle object separating intent from commit mode
- Feedback re-entry: action outcomes re-enter as typed stimuli through
  `SensoryCortex` for thread-aware cognitive processing
- Scratchpad layering: thread-scoped context persists across suspension;
  intention-scoped drafts are ephemeral

Remaining cognitive-runtime gaps (not yet blocking, future work):

- Feedback re-entry is not fully uniform: fallback-bypass and autonomous-worker
  paths still update deliberation state directly without emitting feedback
  stimuli through SensoryCortex.
- Concurrency boundary: preparation is not yet parallelized; execution remains
  mostly centralized.
- Goal-thread continuity: assignment work uses thread continuations but still enters
  via a separate queue branch rather than fully unified thread orchestration.

---

## 5. Operator UX and Runtime Visibility

### 5.1 Integration auth status

- Clear setup/auth status UX for Google and Telegram.
- Auth connected/disconnected.
- Webhook configured/misconfigured.
- Last health failure reason.

### 5.2 Connector install/enable/allowlist UX

- Which curated connectors are shipped.
- Which are installed locally.
- Which are enabled and planner-visible.
- Why a connector is blocked.

### 5.3 Approval and audit UX for future high-risk outbound actions

- Gmail send.
- Social publish.
- Repo/file mutation.

---

## 6. First External Published Connector

No external published connector has been integrated yet. This is a major next
step.

### 6.1 Selection criteria

- Maintenance quality.
- Capability scope.
- Local `stdio` support.
- Install/update story.
- Secret handling model.
- License/provenance.

### 6.2 Process

- Review and approve the first real external candidate with the maintainer
  before integrating it.
- Recommended first category: low-blast-radius read-only connector.
- Do not start with: public-posting, filesystem, repo admin, or generic shell
  connectors.

### 6.3 Conformance tests

- Startup/handshake.
- Pinning drift.
- Sanitization/trust downgrade.
- Crash/timeout behavior.

---

## 7. Future Inbound Channels

Telegram inbound auth is implemented, but generic inbound channel security is
not generalized yet.

- Extract a shared inbound-channel auth/allowlist framework only when a second
  inbound channel is added.
- Ensure every future inbound channel uses the same fail-closed,
  owner/tenant-scoped, rate-limited ingress model.

---

## 8. Security Testing

### 8.1 Deterministic tests (partially implemented)

- Owner trusted chat can create an assignment.
- External participant cannot create an assignment.
- Tainted external content cannot directly become commit payload.
- Staged action without authorization cannot commit.
- Policy-hidden actions do not appear in planner action set.
- Low-risk observe/commit loop does not require staging when policy allows
  direct commit.
- Deferred intentions re-enter the loop without losing thread security context.

### 8.2 Scenario/red-team tests (not yet implemented)

- Poisoned email tries to create recurring assignment.
- Poisoned RSS tries to trigger public post.
- Calendar event title tries to write memory instructions.
- Compacted conversation should not bypass authorization.
- Repeated send loop hits action policy/rate limit.

### 8.3 Future plugin-host tests

- Mutated connector metadata.
- Connector attempts env access outside declared secret handles.
- Connector returns prompt-injecting payload.

---

## 9. Deferred High-Risk Capabilities

These stay out of scope until the hardening above is stronger:

- Gmail send / archive / unsubscribe / label mutation
- Social publish
- Local filesystem mutation
- Repository administration / contributor management
- Shell/exec style connectors
- Nested coding-agent delegation as a generic tool surface
- Arbitrary remote MCP servers

These require a separate security review before implementation.

---

## 10. Code Quality: Hardcoded Provider and Telemetry Strings

The `"stdin"`, `"webapp"`, `"id"` provider strings and the Ego telemetry keys
are scattered as bare string literals. These predate the cognitive-security
refactor and are not policy/security logic, but should be extracted to named
constants for consistency with the `CognitiveCueMetadata` pattern.

- `SensoryCortex.kt`: `"stdin"`, `"stdin-user"` repeated
- `ChatRuntimeBridge.kt`, `DashboardServer.kt`: `"webapp"` repeated
- `Ego.kt`: `"id"` source, 20+ event metadata key literals
- `AppModeRunners.kt`: `"freud-live"` provider

---

## 11. Open Questions

1. When to move from encrypted local token files to OS keychain-backed storage
   for all long-lived credentials.
2. Whether tool-description pinning should hash only raw MCP metadata or also
   the generated NeoPsyke manifest mapping.
3. Whether the first approved published connector should be read-only email,
   news/RSS, or something even narrower.
4. Whether future curated catalog entries should remain plain filesystem
   manifests or move to signed bundles.
5. What additional sandboxing is required before enabling truly untrusted
   third-party local subprocess connectors by default.

---

## 12. Guardrails for Future Work

When continuing this work:

- Do not treat raw MCP compatibility as trust.
- Do not expose raw MCP tool names directly to the planner.
- Do not let connectors define approval/autonomy policy. Connector manifests may
  describe effect class and trust bounds, but runtime-owned policy must decide
  commit/autonomy behavior.
- Do not widen outbound authority before stronger deterministic payload and
  approval UX exists.
- Do not add high-risk capability families without a fresh security review.
- Do not pick an external published connector without confirming the candidate
  with the maintainer first.
