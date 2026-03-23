# TODO: External Connector Runtime

> Status: Planned, not implemented
>
> Date: 2026-03-23
>
> Purpose: Handoff document for the future implementation session that adds
> out-of-process external tool/connectors plus the associated security model.

---

## 1. Scope

This document covers the future implementation of:

- out-of-process third-party connector runtime
- external tool integration path
- connector security model
- NeoPsyke-native action wrapping over external tools
- MCP-aligned ecosystem access

This is intentionally implementation-oriented. It assumes the broader security
redesign in [SECURITY_STRATEGY_SPEC.md](/Users/victor.toral/atomitl/ai/psyke/docs/security/SECURITY_STRATEGY_SPEC.md).

Whenever this TODO is implemented, the current-state manual in
[SECURITY_MODEL_MANUAL.md](/Users/victor.toral/atomitl/ai/psyke/docs/security/SECURITY_MODEL_MANUAL.md)
must be updated in the same change set. External connector hosting will change
the real trust boundary, secret-handling model, approval surface, and remaining
risks section of the manual.

---

## 2. Locked Decisions

These decisions are already made and should be treated as constraints:

1. Third-party connectors must be zero-trust by default.
2. The runtime should use `stdio` transport for the first host implementation.
3. The ecosystem target should be MCP-aligned compatibility, not a purely custom
   connector protocol.
4. NeoPsyke-specific policy, staging, approval, and commit controls must remain
   above the connector protocol boundary.
5. We want `MCP tools + NeoPsyke action manifests`, not raw MCP tools as the
   final agent-facing abstraction.
6. Third-party connectors must be install-disabled by default behind an
   operator-controlled config flag.
7. Retry-from-outbox stays internal-only in v1.
8. External connector outputs should be treated as untrusted by default unless
   explicit operator policy says otherwise.

---

## 3. What “MCP Tools + NeoPsyke Action Manifests” Means

The connector runtime should not expose raw third-party tool names directly as
the final agent planning/execution surface.

Target layering:

1. Connector process speaks an MCP-aligned protocol over `stdio`
2. NeoPsyke discovers the connector's low-level tools/capabilities
3. NeoPsyke maps approved capabilities into NeoPsyke-native action manifests
4. Ego/Superego/ActionControl operate on NeoPsyke actions, not arbitrary raw
   tool names

Example:

- connector low-level tools:
  - `gmail.search_messages`
  - `gmail.get_message`
  - `gmail.send_message`

- NeoPsyke-native actions:
  - `email_observe_search`
  - `email_prepare_reply`
  - `email_commit_send`

The important point is:

- MCP gives ecosystem compatibility
- NeoPsyke action manifests preserve NeoPsyke's cognitive model, policy model,
  staged lifecycle, effect classes, ordering keys, and approval/autonomy rules

---

## 4. Current Relevant Code

Current foundation already in repo:

- connector boundary stub:
  - [ConnectorBoundaryModels.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/ConnectorBoundaryModels.kt)
- action plugin contract:
  - [ActionPluginContracts.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/ActionPluginContracts.kt)
- action security / lifecycle:
  - [ActionControlService.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionControlService.kt)
  - [ActionControlStore.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionControlStore.kt)
  - [SqliteActionControlStore.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/SqliteActionControlStore.kt)
- action models:
  - [ActionLifecycleModels.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/model/ActionLifecycleModels.kt)
- policy:
  - [ActionAuthorizationPolicy.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionAuthorizationPolicy.kt)

Current state:

- connector runtime is still in-process only
- third-party hosting is not implemented
- external tool discovery is not yet mapped into NeoPsyke action manifests
- no MCP host/runtime exists yet

---

## 5. Target Architecture

### 5.1 High-level flow

1. Operator installs/enables a connector manifest locally
2. NeoPsyke starts the connector in a separate subprocess
3. Host boundary performs MCP-aligned handshake over `stdio`
4. NeoPsyke discovers available external tools/capabilities
5. NeoPsyke validates connector manifest + operator policy
6. NeoPsyke exposes approved capabilities as NeoPsyke-native action manifests
7. Planner/Superego/ActionControl use those manifests like first-party actions
8. Motor/connector host translate manifest execution into connector protocol

### 5.2 Boundary layers

- `Connector Process`
  - third-party code
  - low-level MCP-aligned tool exposure

- `Connector Host`
  - subprocess lifecycle
  - stdio protocol
  - timeout handling
  - crash handling
  - health
  - stdout/stderr capture
  - secret-handle injection

- `Connector Registry`
  - installed connector manifests
  - enabled/disabled state
  - operator allowlists
  - policy binding

- `Action Manifest Layer`
  - maps discovered connector capabilities into NeoPsyke-native actions
  - assigns effect class / trust rules / staging rules / execution key strategy

- `NeoPsyke Action Lifecycle`
  - prepare/stage/authorize/commit/record
  - always remains the final control layer

---

## 6. Security Requirements

### 6.1 Hard requirements

1. No in-process third-party code execution
2. No raw `System.getenv()` exposure to connector code
3. No connector-owned approval/autonomy policy
4. No direct bypass of staged/authorized commit lifecycle
5. No trust upgrade from connector output without explicit policy
6. No agent write access to operator connector policy/manifests by default

### 6.2 Secret handling

Preferred model:

- connectors declare required secret handles
- host resolves/injects secrets per request or per process start under operator
  policy
- connectors do not receive broad ambient env by default

Open implementation question remains whether first v1 should inject:

- per-process scoped env vars
- or per-request materialization only

### 6.3 Provenance

Connector outputs should enter NeoPsyke as:

- `EXTERNAL_DATA` by default
- `SANITIZED_EXTERNAL_DATA` only after explicit sanitization
- never `TRUSTED_INSTRUCTION` just because a connector is installed

### 6.4 Policy ownership

Connector manifests may describe capabilities, but they do not decide:

- whether autonomous commit is allowed
- whether approval is required
- whether public posting is enabled
- whether a side effect is direct-commit eligible

Those remain NeoPsyke/operator policy decisions.

---

## 7. Proposed Runtime Components

Future implementation should likely introduce something close to:

- `ConnectorManifest`
- `InstalledConnector`
- `ConnectorHostProcess`
- `ConnectorHostClient`
- `McpConnectorProtocolClient`
- `ConnectorCapabilityDescriptor`
- `ExternalToolBinding`
- `ActionManifestGenerator`
- `ConnectorPolicyBinding`
- `ConnectorSecretResolver`

Do not assume these exact names are final. The important thing is the boundary
separation, not the literal class names.

---

## 8. Action Manifest Requirements

Each NeoPsyke-native external action manifest should declare:

- action family id
- underlying connector id
- mapped tool/capability id
- effect class
- allowed instruction trust
- allowed argument data trust
- direct-commit eligibility
- autonomous-commit support
- execution-key derivation strategy
- deterministic payload validation
- planner-facing description/guidance

The agent should plan using these manifests, not raw connector methods.

---

## 9. Phased Rollout

### Phase 1: Connector Host Foundation

- operator config flag to enable third-party connectors
- local installed connector manifests only
- subprocess launcher
- stdio transport
- MCP-aligned handshake/discovery
- health + timeout + crash accounting
- structured logs with connector ids

### Phase 2: Policy and Manifest Layer

- connector allowlist / enable-disable model
- per-capability allowlists
- action manifest generation or explicit mapping
- trust/provenance defaults
- secret-handle resolution

### Phase 3: Runtime Integration

- connector-backed actions appear in action registry
- Superego and policy can evaluate them
- staged action lifecycle works end-to-end
- receipts/ledger/log correlation works for connector-backed actions

### Phase 4: First Reference Connector

Use a low-risk connector first. Good candidates:

- read-only calendar
- read-only tasks
- read-only RSS/news

Avoid starting with send/delete/publish.

### Phase 5: Higher-risk Commits

After foundation is proven:

- inbox drafting
- email send
- social draft/publish
- recurring automation tied to connector-backed tools

---

## 10. Logging and Observability Requirements

Normal logs must remain first-class. Durable receipts/ledger do not replace
logs.

Connector-host related logs should include stable correlation fields:

- `connector_id`
- `manifest_id`
- `root_input_id`
- `staged_action_id`
- `authorization_id`
- `receipt_id`
- `action_type`
- `reason_code`

We want both:

- durable structured truth for UI/audit
- detailed logs for coding agents and incident/debug inspection

---

## 11. Open Decisions Still To Make

These decisions were not locked yet and should be resolved before or during the
implementation session:

1. Secret delivery model in v1:
   - per-process scoped env injection
   - per-request secret materialization

2. Manifest mapping strategy in v1:
   - explicit operator-authored mapping file
   - generated mapping with operator allowlist
   - hybrid

3. Capability granularity for first rollout:
   - only allow wrapping read-only MCP capabilities first
   - or allow draft-capable tools in the same slice

4. Connector installation metadata location:
   - YAML under repo/runtime config
   - separate user data directory
   - mixed model

5. Process lifecycle model:
   - persistent connector subprocess
   - on-demand subprocess per call

6. Whether first MCP-aligned host should be:
   - strict subset adapter for the exact NeoPsyke use case
   - or fuller MCP client implementation from the start

---

## 12. Recommended Defaults For Those Open Decisions

My current recommendations:

1. Secret delivery:
   - start with per-process scoped env injection from explicit secret handles
   - then tighten further later if needed

2. Manifest mapping:
   - hybrid
   - generated discovery plus explicit operator allowlist/mapping overrides

3. First capability scope:
   - read-only first

4. Install metadata location:
   - dedicated user/runtime config directory, not scattered in repo docs

5. Process lifecycle:
   - persistent subprocess per connector first

6. MCP host depth:
   - strict useful subset first, but do not design a protocol dead end

---

## 13. Implementation Notes For Future Session

When coding this:

- do not collapse raw MCP tools directly into planner-visible actions without a
  NeoPsyke manifest/policy layer
- do not let connector code define autonomy/approval policy
- do not overfit the first host to one connector; keep the host boundary clean
- keep logs and durable ledger correlated
- prefer one well-designed reference connector over broad shallow integration

The desired end state is:

- good external ecosystem reach through MCP-aligned tooling
- NeoPsyke-native safety/lifecycle semantics
- no return to a full-trust tool/plugin architecture
