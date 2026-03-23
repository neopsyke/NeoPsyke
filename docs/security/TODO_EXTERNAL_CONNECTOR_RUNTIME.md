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
4. MCP is the compatibility boundary, not the security or policy boundary.
5. NeoPsyke-specific policy, staging, approval, and commit controls must remain
   above the connector protocol boundary.
6. The first host implementation should support local subprocess connectors only,
   not arbitrary remote MCP servers.
7. We want `MCP tools + NeoPsyke action manifests`, not raw MCP tools as the
   final agent-facing abstraction.
8. Third-party connectors must be install-disabled by default behind an
   operator-controlled config flag.
9. Retry-from-outbox stays internal-only in v1.
10. External connector outputs should be treated as untrusted by default unless
    explicit operator policy says otherwise.
11. The default connector failure mode must be fail-closed.
12. If handshake, pinning, manifest validation, or health checks fail, the
    connector must expose no planner-visible actions.

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

### 3.1 MCP is the compatibility layer, not the control layer

This runtime should treat MCP as the protocol used to reach mature external
tool ecosystems, not as the final authority for what the agent may do.

That means:

- MCP tools are discovered at the connector boundary
- NeoPsyke decides which capabilities are exposed at all
- NeoPsyke assigns effect classes, trust rules, staging rules, and commit policy
- NeoPsyke remains the only layer that can authorize autonomous or
  approval-backed commits

Put differently:

- good: `MCP/stdio connector -> NeoPsyke host -> NeoPsyke action manifest`
- bad: `raw MCP tool -> planner-visible action`

This is the core design choice that allows ecosystem extensibility without
handing security control to the connector protocol.

---

## 4. Current Relevant Code

Current foundation already in repo:

- connector boundary stub:
  - [ConnectorBoundaryModels.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/ConnectorBoundaryModels.kt)
- action plugin contract:
  - [ActionPluginContracts.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/ActionPluginContracts.kt)
- action registry / execution boundary:
  - [ActionRegistry.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actions/ActionRegistry.kt)
  - [MotorCortex.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/cortex/motor/MotorCortex.kt)
- action security / lifecycle:
  - [ActionControlService.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionControlService.kt)
  - [ActionControlStore.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionControlStore.kt)
  - [SqliteActionControlStore.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/SqliteActionControlStore.kt)
- action models:
  - [ActionLifecycleModels.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/model/ActionLifecycleModels.kt)
- policy:
  - [ActionAuthorizationPolicy.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/actioncontrol/ActionAuthorizationPolicy.kt)
- runtime config loading:
  - [AgentConfig.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/config/AgentConfig.kt)
  - [ActionControlConfig.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/agent/config/ActionControlConfig.kt)
  - [AgentRuntimeConfig.kt](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/ai/neopsyke/config/AgentRuntimeConfig.kt)

Current state:

- connector runtime is still in-process only
- third-party hosting is not implemented
- external tool discovery is not yet mapped into NeoPsyke action manifests
- no MCP host/runtime exists yet
- `ActionDescriptor` does not yet carry connector manifest metadata
- `ActionPluginFactoryContext` still exposes broad ambient env by default, which
  is acceptable for first-party builtins but not for zero-trust third-party
  connectors
- `AgentConfig` has no dedicated connector-runtime config domain yet
- there is no installed-connector registry, allowlist store, or manifest pinning
  path

### 4.1 Concrete gaps to close next

The remaining work is not "invent a new action system". The remaining work is:

1. Add a real connector runtime/config boundary under agent runtime config
2. Add a shipped curated connector catalog plus local installed-state registry
3. Add a subprocess host/client for MCP-aligned `stdio` connectors
4. Add capability-to-action-manifest mapping with NeoPsyke-owned policy fields
5. Add connector-scoped secret resolution that does not expose ambient env
6. Thread connector provenance and correlation through execution, receipts, and
   logs

That means the secure action lifecycle already in code should be reused, not
replaced.

---

## 5. Target Architecture

### 5.1 High-level flow

1. NeoPsyke ships curated connector definitions and product bundles
2. Operator installs/enables a connector or bundle locally
3. NeoPsyke resolves local install state and connector policy
4. NeoPsyke starts the connector in a separate subprocess
5. Host boundary performs MCP-aligned handshake over `stdio`
6. NeoPsyke discovers available external tools/capabilities
7. NeoPsyke validates connector manifest + operator policy
8. NeoPsyke exposes approved capabilities as NeoPsyke-native action manifests
9. Planner/Superego/ActionControl use those manifests like first-party actions
10. Motor/connector host translate manifest execution into connector protocol

### 5.2 Boundary layers

- `Curated Connector Catalog`
  - shipped with NeoPsyke
  - read-only trusted definitions
  - curated connector manifests
  - curated product bundles
  - default policy and action-mapping templates

- `Connector Process`
  - third-party code
  - low-level MCP-aligned tool exposure

- `Local Install State`
  - enabled/disabled state
  - installed binary/package path
  - resolved version/hash
  - local pinsets
  - local overrides

- `Connector Host`
  - subprocess lifecycle
  - stdio protocol
  - timeout handling
  - crash handling
  - health
  - stdout/stderr capture
  - secret-handle injection

- `Connector Registry`
  - curated definitions + local installed state
  - enabled/disabled state
  - operator allowlists
  - policy binding

- `Action Manifest Layer`
  - maps discovered connector capabilities into NeoPsyke-native actions
  - assigns effect class / trust rules / staging rules / execution key strategy

- `NeoPsyke Action Lifecycle`
  - prepare/stage/authorize/commit/record
  - always remains the final control layer

### 5.3 First thin slice to build

To keep the first implementation useful without recreating the OpenClaw-style
attack surface, the first slice should be:

1. `news_observe_read`
   - read-only
   - observe-class
   - external data only
2. `gmail_observe_search`
   - read-only
   - observe-class
   - external data only
3. `gmail_observe_message`
   - read-only
   - observe-class
   - external data only
4. `telegram_commit_send_digest`
   - private commit
   - owner-targeted allowlist only
   - approval-backed by default until the confirmation/autonomy policy is proven

Explicitly not in the first slice:

- Gmail send / unsubscribe / archive / label mutation
- inbound Telegram webhook control plane
- social publishing
- local filesystem read/write
- shell/exec style tools
- spawning Codex/Claude/sandbox workers as generic connector actions

Those are valid future goals, but they require stronger policy and isolation
than the initial connector host foundation.

### 5.4 Ready-to-use curated install presets

To support "works out of the box" integrations without making them ambiently
trusted, NeoPsyke should ship curated install presets for high-value capability
sets.

Examples:

- `morning-briefing`
  - news read
  - weather read
  - calendar read
  - Gmail read
  - Telegram digest send
- `inbox-management`
  - Gmail search/read first
  - send/unsubscribe stays disabled until later phases
- `social-automation`
  - RSS/newsletter draft first
  - public posting stays disabled until later phases

Out of the box should mean:

- predesigned
- pre-mapped
- pre-pinned
- pre-policy-shaped

It should not mean:

- auto-enabled
- auto-credentialed
- auto-authorized for commits

These presets are install/enablement convenience only.

- They may expand the connector allowlist or installation set.
- They must not become planner-visible workflow actions by themselves.
- The actual runtime execution model should remain:
  - primitive actions/tools
  - composed by NeoPsyke goals when the user asks for recurring routines such as
    Morning Briefing or Inbox Management

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

Working v1 decision:

- start with per-process scoped injection from explicit secret handles only
- keep the resolver interface compatible with future per-request materialization

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

### 6.5 Catalog and install-state separation

The runtime should separate:

- shipped curated catalog
  - read-only definitions shipped with the app/repo
  - trusted source for curated connectors and bundles
- local installed state
  - mutable machine-specific runtime state under `.neopsyke/connectors/`

This separation is required so NeoPsyke can ship ready-to-use safe integrations
without letting the agent or a compromised connector rewrite the trusted source
of truth.

### 6.6 Failure semantics

Connector runtime failures must fail closed.

If any of these fail:

- connector startup
- MCP handshake/discovery
- manifest validation
- tool/manifest pinning
- health check

Then:

- the connector is unavailable
- no planner-visible actions are exposed from that connector
- no partial trust upgrade occurs
- the runtime emits clear logs and health status for operator inspection

---

## 7. Proposed Runtime Components

Future implementation should likely introduce something close to:

- `CuratedConnectorCatalog`
- `ConnectorManifest`
- `ConnectorBundleManifest`
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

- add `ConnectorRuntimeConfig` as a new AgentConfig domain
- operator config flag to enable third-party connectors
- shipped curated connector catalog plus bundle metadata
- dedicated local installed-state directory under `.neopsyke/connectors/`
- subprocess launcher
- stdio transport
- MCP-aligned handshake/discovery
- local subprocess connectors only; no remote MCP server connections in v1
- health + timeout + crash accounting
- structured logs with connector ids
- no planner-visible actions yet

### Phase 2: Policy and Manifest Layer

- connector allowlist / enable-disable model
- per-capability allowlists
- action manifest generation or explicit mapping
- trust/provenance defaults
- secret-handle resolution
- connector metadata on planner-visible descriptors
- manifest/tool description pinning for reviewed connectors

### Phase 3: Runtime Integration

- connector-backed actions appear in action registry
- Superego and policy can evaluate them
- staged action lifecycle works end-to-end
- receipts/ledger/log correlation works for connector-backed actions
- final execution guard still lives in `MotorCortex`

### Phase 4: First Read-Only Reference Connectors

Implement the lowest-risk useful product slice first:

- read-only RSS/news
- Gmail read/search only
- optional calendar/tasks read after the first two are stable

The acceptance bar here is:

- connector outputs always land as `EXTERNAL_DATA`
- observe actions can be planned/executed without widening commit authority
- receipts/logs clearly correlate connector activity
- connector crashes/timeouts degrade cleanly without poisoning the action system

### Phase 5: First Constrained Outbound Path

After read-only connectors are stable, add one narrow private outbound path:

- Telegram digest delivery to explicit owner-approved destinations
- approval-backed by default
- deterministic payload validation
- clear execution-key serialization by destination/account

This is the proving ground for connector-backed commit actions before Gmail send
or social posting.

### Phase 6: Higher-risk Commits

Only after the earlier phases are stable:

- inbox drafting
- Gmail send
- unsubscribe / archive / labeling
- social draft/publish
- recurring automation tied to connector-backed tools
- generic high-risk connectors (repo administration, local file mutation,
  sandboxed coding-agent delegation)

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

## 11. Initial Implementation Decisions

These should be treated as the working decisions for the first implementation
pass unless a later design review changes them:

1. Secret delivery model in v1:
   - per-process scoped injection from explicit secret handles only
   - do not expose raw ambient env to connector subprocesses
   - keep the resolver interface compatible with a future keychain-backed store

2. Manifest mapping strategy in v1:
   - hybrid
   - discovery can propose mappings, but operator allowlists and overrides remain
     authoritative

3. Capability granularity for first rollout:
   - read-only capabilities first
   - plus one explicitly constrained private-send action family for Telegram
     digest delivery

4. Connector installation metadata location:
   - split model
   - shipped curated catalog lives in a read-only app/repo path
   - mutable local installed state lives under `.neopsyke/connectors/`
   - repo docs do not become the installation database

5. Process lifecycle model:
   - persistent subprocess per connector first

6. Whether first MCP-aligned host should be:
   - strict useful subset first
   - do not design a dead-end that blocks future fuller MCP compatibility
   - local `stdio` subprocess connectors only in the first implementation

7. First outbound commit path:
   - Telegram private send only
   - no public posting in the first connector-backed commit slice

8. Gmail scope in the first slice:
   - observe/search/read only
   - do not include send/modify/unsubscribe in the initial rollout

9. Default failure mode:
   - fail closed
   - connectors with failed startup, handshake, validation, pinning, or health
     checks expose no planner-visible actions

10. Third-party enablement stance:
   - disabled by default
   - explicit per-connector allowlisting only
   - curated bundles do not bypass enablement or policy review

---

## 12. Remaining Design Questions After The First Slice

These decisions can remain open until after the host foundation and first
reference connectors exist:

1. When to move from env-backed secret resolution to OS keychain-backed storage
2. Whether tool-description pinning should hash raw MCP metadata only or the
   generated NeoPsyke mapping artifact too
3. Whether inbound Telegram/WhatsApp webhook receivers land in the same runtime
   package or a separate channel-host package
4. Whether future connector manifests should be YAML only or support a stricter
   signed bundle format
5. What additional approval UX is needed before Gmail send, social publish, or
   local file mutation can be enabled
6. Whether curated catalog entries should be filesystem manifests only or also
   support signed packaged distributions

---

## 13. Work Packages For The Next Implementation Session

The next coding session should attack the work in this order:

1. Config domain and install metadata
   - add `ConnectorRuntimeConfig`
   - load connector runtime config from `AgentRuntimeConfig`
   - define curated catalog paths and local install-state paths
2. Connector host/process layer
   - subprocess lifecycle
   - stdio transport
   - timeout/crash handling
   - structured connector logs
3. Registry and manifest layer
   - curated connector manifest model
   - connector bundle manifest model
   - installed connector state model
   - capability discovery model
   - action manifest mapping model
   - tool/manifest pinning
4. Runtime integration
   - surface connector-backed descriptors through `ActionRegistry`
   - preserve current `ActionAuthorizationPolicy` and `MotorCortex` guardrails
   - propagate connector ids into receipts/ledger/logs
5. First reference actions
   - news read
   - Gmail search/read
   - Telegram digest send
6. Tests
   - deterministic runtime/manifest tests
   - staged-action lifecycle tests for connector-backed actions
   - crash/timeout/pinning regression tests

---

## 14. Implementation Notes For Future Session

When coding this:

- do not collapse raw MCP tools directly into planner-visible actions without a
  NeoPsyke manifest/policy layer
- do not treat MCP compatibility as permission to trust connector tool
  descriptions, outputs, or autonomy claims
- do not let connector code define autonomy/approval policy
- do not overfit the first host to one connector; keep the host boundary clean
- do not support arbitrary remote MCP servers in the first host slice
- keep logs and durable ledger correlated
- reuse the current action lifecycle instead of inventing a parallel execution
  path
- tighten `ActionPluginFactoryContext` / secret handling before third-party
  connector code can consume plugin factory inputs
- keep generic high-risk capabilities (filesystem mutation, repo administration,
  nested coding-agent execution) behind a later dedicated security review

The desired end state is:

- good external ecosystem reach through MCP-aligned tooling
- NeoPsyke-native safety/lifecycle semantics
- no return to a full-trust tool/plugin architecture
