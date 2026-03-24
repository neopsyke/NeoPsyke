# TODO: External Connector Runtime

> Status: In progress
>
> Last reviewed: 2026-03-24
>
> Purpose: Track the remaining security and runtime work for external
> connectors and adjacent native integrations. This document now reflects the
> code already landed and focuses only on what is still left to do.

---

## 1. Scope

This document covers the remaining work for:

- out-of-process third-party connector hosting
- MCP-aligned compatibility under NeoPsyke-owned policy/lifecycle control
- native high-trust integrations that fill the same product needs
- connector/runtime security hardening that is still incomplete

It assumes and must remain aligned with:

- [SECURITY_STRATEGY_SPEC.md](/Users/victor.toral/atomitl/ai/psyke/docs/security/SECURITY_STRATEGY_SPEC.md)
- [SECURITY_MODEL_MANUAL.md](/Users/victor.toral/atomitl/ai/psyke/docs/security/SECURITY_MODEL_MANUAL.md)
- [SECURE_TOOLS_REVIEW.md](/Users/victor.toral/atomitl/ai/psyke/docs/security/SECURE_TOOLS_REVIEW.md)

Whenever a remaining item here is implemented, update the manual and the
runtime-logic docs in the same change set.

---

## 2. Locked Decisions

These are already decided and should not be reopened casually:

1. MCP is the compatibility boundary, not the security boundary.
2. NeoPsyke action manifests remain the planner-facing abstraction, not raw MCP
   tool names.
3. The first external host is local subprocess only over `stdio`.
4. Remote MCP servers are out of scope for the first host.
5. Third-party connectors are zero-trust by default.
6. Third-party connectors are disabled by default and require explicit
   allowlisting.
7. Connector startup, handshake, validation, pinning, or health failures fail
   closed.
8. Connector outputs are external/untrusted by default unless NeoPsyke
   explicitly sanitizes and classifies them.
9. Secret injection uses explicit handles only; no ambient `System.getenv()`
   passthrough to connector processes.
10. Workflow examples such as Morning Briefing and Inbox Management are goal
    compositions, not special workflow actions.

---

## 3. Current State

The following foundation is already implemented:

### 3.1 Connector/runtime foundation

- `ConnectorRuntimeConfig` exists and loads from runtime YAML/env.
- A shipped curated catalog plus local installed state exists.
- Connector host/runtime support exists for local subprocess connectors.
- Connector-backed actions can be surfaced through the normal action registry.
- Connector outputs already route through the shared
  [ExternalContentPipeline.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/support/ExternalContentPipeline.kt).

Relevant code:

- [ConnectorRuntimeConfig.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/config/ConnectorRuntimeConfig.kt)
- [ConnectorCatalogLoader.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/connectors/ConnectorCatalogLoader.kt)
- [ConnectorCatalogModels.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/connectors/ConnectorCatalogModels.kt)
- [ConnectorHostRuntime.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/connectors/ConnectorHostRuntime.kt)
- [ConnectorActionPlugins.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/connectors/ConnectorActionPlugins.kt)

### 3.2 Native high-trust integrations

These are implemented as first-party/native code instead of external published
connectors:

- owner-only Telegram channel ingress/egress
- native Google OAuth start/callback flow
- Gmail read/search actions
- Google Calendar read actions

Relevant code:

- [NativeIntegrationsConfig.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/config/NativeIntegrationsConfig.kt)
- [TelegramWebhookBridge.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/integrations/telegram/TelegramWebhookBridge.kt)
- [TelegramBotApiClient.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/integrations/telegram/TelegramBotApiClient.kt)
- [GoogleWorkspaceOAuthBridge.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/integrations/google/GoogleWorkspaceOAuthBridge.kt)
- [GoogleWorkspaceObserveActionPlugins.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/google/GoogleWorkspaceObserveActionPlugins.kt)

### 3.3 Security redesign already landed

The following review items are no longer TODOs here:

- unified external-content ingestion path
- sticky per-root trust degradation
- `reflect_internal` / `reflect_evidence` split
- quarantined evidence-memory lane
- centralized per-root action-family rate limits
- Telegram webhook auth for the Telegram channel itself
- verifier disabled by default behind planner config

Relevant code:

- [ExternalContentPipeline.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/support/ExternalContentPipeline.kt)
- [ReflectActionPlugin.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/builtin/ReflectActionPlugin.kt)
- [EvidenceArtifactStore.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/EvidenceArtifactStore.kt)
- [MemorySystem.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/ego/MemorySystem.kt)
- [ActionControlService.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionControlService.kt)
- [LlmEgoPlanner.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/ego/LlmEgoPlanner.kt)

---

## 4. What Still Needs To Be Done

The remaining work is no longer "build the connector runtime from scratch". The
remaining work is the hardening and productization layer on top of the
foundation that now exists.

### 4.1 Third-party connector trust hardening

Still needed:

1. Strong tool-description / manifest pinning policy
   - pin reviewed connector capability metadata, not just connector ids
   - fail closed on drift
   - make operator-facing drift reasons explicit

2. Better provenance/correlation in durable records
   - ensure connector id, manifest id, capability id, and pin set flow through
     receipts, ledger entries, and action history consistently

3. Connector process isolation review
   - verify current subprocess host boundaries are tight enough for genuinely
     untrusted third-party MCP servers
   - decide whether extra sandboxing is required before enabling any real
     external published connector

4. Redaction and secret-spill hardening
   - ensure connector stdout/stderr/log capture cannot leak tokens or OAuth
     material into logs or UI surfaces

### 4.2 Secret and credential hardening

Current explicit-handle secret injection is good enough for v1, but not the end
state.

Still needed:

1. Move sensitive persistent credentials toward OS keychain-backed storage
   - Google tokens are currently encrypted locally; keychain-backed storage is
     still a remaining step

2. Add stronger token lifecycle controls
   - revocation / reconnect flows
   - operator-visible auth health
   - last-success / last-failure / scope diagnostics

3. Harden connector-specific secret resolution further
   - per-connector handle policies
   - better secret usage audit trail
   - explicit deny surface for undeclared handles

### 4.3 Policy/runtime hardening still incomplete

Centralized rate limiting exists, but the remaining policy work is:

1. Add time-window based rate limits on top of per-root-input limits
   - especially for outbound messaging, future email send, future social post,
     and future repo/file mutation

2. Add finer-grained destination/resource buckets
   - per Telegram chat
   - per mailbox/account
   - per repo / filesystem root / external account when those actions exist

3. Strengthen deterministic execution templates for future commit actions
   - current reflection/contact/goal normalization is not enough for future
     Gmail send, publish, repo admin, or file mutation

4. Keep `allowedArgumentDataTrust` enforcement universal as new action families
   land
   - every future observe path and every future side-effecting action must be
     checked against trust policy instead of relying on plugin-local discipline

### 4.4 Operator UX and runtime visibility

Still needed:

1. Clear setup/auth status UX for Google and Telegram
   - auth connected/disconnected
   - webhook configured/misconfigured
   - last health failure reason

2. Connector install/enable/allowlist UX
   - which curated connectors are shipped
   - which ones are installed locally
   - which ones are enabled and planner-visible
   - why a connector is blocked

3. Approval and audit UX for future high-risk outbound actions
   - Gmail send
   - social publish
   - repo/file mutation

### 4.5 First real external published connector path

No external published connector has been integrated yet. This remains a major
next step and must continue to require explicit user review before choosing a
real connector.

Still needed:

1. Define connector selection criteria
   - maintenance quality
   - capability scope
   - local `stdio` support
   - install/update story
   - secret handling model
   - license/provenance

2. Review and approve the first real external candidate with the user before
   integrating it

3. Add conformance tests for any approved published connector
   - startup/handshake
   - pinning drift
   - sanitization/trust downgrade
   - crash/timeout behavior

### 4.6 Future inbound channels

Telegram inbound auth is implemented, but generic inbound channel security is
not generalized yet.

Still needed:

1. extract a shared inbound-channel auth/allowlist framework only when a second
   inbound channel is added
2. ensure every future inbound channel uses the same fail-closed,
   owner/tenant-scoped, rate-limited ingress model

### 4.7 Deliberately deferred high-risk capabilities

These stay out of scope until the connector/runtime hardening above is stronger:

- Gmail send / archive / unsubscribe / label mutation
- social publish
- local filesystem mutation
- repo administration / contributor management
- shell/exec style connectors
- nested coding-agent delegation as a generic tool surface
- arbitrary remote MCP servers

These require a separate security review before implementation.

---

## 5. Product-Facing Capability Priorities

The product goal is still to let goals compose safe primitive actions for:

- Morning Briefing
- Email / Inbox Management
- later: Content & Social Media Automation

That means the remaining capability priorities are:

### Priority A: stabilize native Morning Briefing / Inbox primitives

- Gmail read/search
- Calendar read
- Telegram two-way owner chat and digest delivery
- news/web fetch through existing observe tools

Remaining work here:

- improve auth/setup/operator visibility
- improve health reporting
- add stronger approval/autonomy defaults before broadening outbound behavior

### Priority B: add first reviewed external published connector

Not because native support is missing, but because the ecosystem path needs to
be proven under NeoPsyke control.

Recommended first category:

- low-blast-radius read-only connector

Do not start with:

- public-posting connector
- filesystem connector
- repo admin connector
- generic shell connector

---

## 6. Remaining Work Packages

The next implementation sessions should roughly follow this order:

1. Finish connector hardening
   - tool-description / manifest pinning
   - durable provenance correlation
   - stdout/stderr redaction review
   - process isolation review

2. Improve native integration operator visibility
   - Google auth health/status
   - Telegram webhook/delivery health/status
   - setup guidance in dashboard/docs

3. Add stronger credential lifecycle support
   - revoke/reconnect
   - better token storage
   - explicit handle audit trail

4. Tighten rate limiting and commit policy for future side effects
   - time-window limits
   - destination/resource buckets
   - stronger deterministic payload shaping

5. Select and review the first real external published connector with the user
   before integration

6. Only then consider high-risk capability families

---

## 7. Open Questions

These remain open on purpose:

1. When to move from encrypted local token files to OS keychain-backed storage
   for all long-lived credentials
2. Whether tool-description pinning should hash only raw MCP metadata or also
   the generated NeoPsyke manifest mapping
3. Whether the first approved published connector should be read-only email,
   news/RSS, or something even narrower
4. Whether future curated catalog entries should remain plain filesystem
   manifests or move to signed bundles
5. What additional sandboxing is required before enabling truly untrusted
   third-party local subprocess connectors by default

---

## 8. Guardrails For Future Sessions

When continuing this work:

- do not treat raw MCP compatibility as trust
- do not expose raw MCP tool names directly to the planner
- do not let connectors define approval/autonomy policy
- do not widen outbound authority before stronger deterministic payload and
  approval UX exists
- do not add high-risk capability families without a fresh security review
- do not pick an external published connector without confirming the candidate
  with the user first

The desired end state remains:

- strong ecosystem reach through MCP-aligned compatibility
- NeoPsyke-owned policy, staging, approval, and trust boundaries
- no return to ambient-trust tools or plugin architectures
