# Memory Provider Refactor Design Note

This note captures the next-stage design for NeoPsyke long-term memory below
the `Hippocampus` boundary.

It is the decision record for the provider-oriented refactor. It assumes the
phase-1 `Hippocampus` redesign already landed and focuses on what comes next:

- making the default memory story strong and release-ready
- preserving a clean, stable agent-facing API
- allowing future backend/provider swaps without changing `Ego`

This document is intentionally implementation-oriented. It should be enough for
new contributors to understand the intended direction by reading code plus this
note, without having to infer the architecture from older discussions.

## Goals

- Keep `Hippocampus` as the stable cognitive long-term memory facade.
- Split NeoPsyke-owned memory logic into explicit layers:
  - `memory-api`
  - `memory-core`
  - `memory-spi`
  - `default-memory-bootstrap`
- Make NeoPsyke's recommended/default install use the real pgvector-backed
  memory implementation.
- Keep the default story opinionated and simple for open-source users.
- Preserve future extensibility for other providers such as Mem0 or custom
  long-term memory systems.
- Avoid exposing `Ego` to backend, transport, or provisioning details.

## Confirmed Product Position

NeoPsyke should have one clearly recommended full installation path:

- `memory=default`

That full install should use NeoPsyke's own pgvector-backed long-term memory
provider. If memory is disabled, NeoPsyke should make that limitation explicit.

Supported user-facing runtime modes:

- `memory=off`
- `memory=default`
- `memory=external`

Internal meaning:

- `memory=off`
  - no external semantic long-term memory provider
  - used for tests, CI, and minimal runs
- `memory=default`
  - use `neopsyke-pgvector-memory`
  - this is the recommended full install
- `memory=external`
  - reserved for future providers such as Mem0 or custom integrations
  - may initially be stubbed/documented without full implementation

The public story should stay focused on `memory=default`. `memory=external`
exists for architecture and future extensibility, not as the primary onboarding
path.

## Repo Ownership

NeoPsyke repo owns:

- `memory-api`
- `memory-core`
- `memory-spi`
- `default-memory-bootstrap`

Separate provider repo owns:

- `neopsyke-pgvector-memory`

This means NeoPsyke owns the stable domain contract and orchestration, while
the default provider can evolve as a separate deployable/runtime component.

## Architecture

### Stable Agent Boundary

`Hippocampus` stays exactly where the agent-facing memory abstraction belongs:
above transport, above storage, and above provider-specific semantics.

`Ego` and `MemorySystem` should continue to depend only on:

- `Hippocampus`
- `HippocampusAdmin` for operational/admin paths when needed

They should not depend on:

- HTTP
- MCP
- Docker
- pgvector
- provider bootstrap/install logic
- provider-specific config shapes

### New Layers Below `Hippocampus`

#### `memory-api`

Owns:

- `Hippocampus`
- `HippocampusAdmin`
- recall/imprint DTOs
- capabilities
- health/status models

This remains cognitive/domain-facing.

#### `memory-core`

Owns NeoPsyke's long-term memory policy and orchestration:

- request normalization
- rendering and result shaping
- memory reasoning and routing policy
- provider selection
- future recall fusion across multiple sources/providers
- translation between cognitive memory requests and provider SPI requests

This layer is where NeoPsyke's memory behavior should live.

#### `memory-spi`

Owns the technical provider contract used by `memory-core`.

This SPI is transport-agnostic by design.

Important design rule:

- the SPI should describe provider behavior
- it should not hardcode HTTP, MCP, or in-process assumptions into the contract

The SPI should be documented in code with comments/TODOs that make the intended
future adapter directions obvious:

- HTTP adapter
- MCP adapter
- direct/in-process adapter

Those adapters do not need to be fully implemented immediately, but the code
structure should make their future place obvious.

#### `default-memory-bootstrap`

Owns the opinionated install/startup path for NeoPsyke's default memory mode.

This layer should:

- download/install/start the default provider
- write NeoPsyke memory config automatically
- verify provider health
- avoid exposing Postgres provisioning details to the app runtime

NeoPsyke should bootstrap one provider service, not manually provision
Postgres/pgvector internals itself.

## Provider SPI Direction

The provider SPI should be transport-agnostic and minimal in v1.

Confirmed v1 provider responsibilities:

- `recall`
- `imprint`
- `health`
- `metrics`

Not required in provider SPI v1:

- episodic memory
- admin/destructive operations
- graph-specific APIs
- provider-specific retrieval knobs leaking into `Hippocampus`

Reasoning:

- v1 should be strong enough for the default pgvector provider
- v1 should stay small enough that a future Mem0/custom provider can implement
  it without inheriting NeoPsyke-specific storage details

## Provider Transport Decision

For `neopsyke-pgvector-memory`, the default transport from NeoPsyke should be
HTTP.

Reasoning:

- simpler bootstrap, health checks, and diagnostics than MCP stdio
- better fit for a separately deployed provider repo
- easier future interoperability with non-JVM tooling
- easier operational debugging for open-source users

MCP should remain supported in the provider project as an optional interface
for external clients and tooling, but not as NeoPsyke's default runtime path.

That means:

- NeoPsyke default provider path => HTTP
- provider may also expose MCP for other clients
- the stable provider HTTP contract is versioned under `v1`
  - `/v1/health`
  - `/v1/metrics`
  - `/v1/recall`
  - `/v1/imprint`
  - `/v1/admin/forget`
  - `/v1/admin/reset`
- breaking wire changes require a new namespace such as `/v2/...`

## Default Install / Bootstrap Flow

The default open-source install should be opinionated.

Target behavior:

1. User installs/runs NeoPsyke.
2. NeoPsyke sees `memory=default`.
3. NeoPsyke bootstrap ensures `neopsyke-pgvector-memory` is installed and
   running.
4. The provider ensures its own pgvector runtime is ready.
5. NeoPsyke writes or updates one memory config owned by NeoPsyke.
6. NeoPsyke uses the provider automatically.

### Bootstrap Responsibility Split

NeoPsyke owns:

- deciding that default memory should exist
- invoking the provider bootstrap/start path
- writing NeoPsyke's memory config
- refusing or warning clearly when default memory is unavailable

The provider owns:

- its own runtime boot contract
- ensuring pgvector is available and healthy
- Docker-backed provisioning of its runtime dependencies
- any schema/setup internal to the provider

NeoPsyke should not directly own Postgres provisioning details.

### Default Install Mechanism

Confirmed default mechanism:

- NeoPsyke first-party install/bootstrap script downloads the provider release
  artifact
- the provider uses Docker-backed bootstrap for pgvector/runtime provisioning

This is the practical release-unblocking choice for now.

It keeps the product default strong without forcing NeoPsyke itself to become a
database installer.

## Config Ownership

NeoPsyke should own one memory configuration surface.

The bootstrap flow should write that config automatically.

This keeps the user-facing runtime consistent:

- one place to understand which memory mode is active
- one place to see which provider is configured
- one place to switch between `off`, `default`, and `external`

The provider may have its own internal config, but NeoPsyke should not depend
on users editing provider-native config as the normal setup path.

## Versioning / Compatibility Policy

NeoPsyke and `neopsyke-pgvector-memory` should be compatible by version range:

- same major version required
- minor versions within a major version are expected to remain compatible
- major version change means the contract changed

This should apply to:

- provider SPI expectations
- HTTP contract used by NeoPsyke for the default provider

## Episodic Memory Scope

Episodic memory stays behind `Hippocampus` conceptually, but it remains out of
the external provider v1 scope.

Current decision:

- keep episodic logbook in its current embedded SQLite implementation for now
- keep it separate from external semantic long-term memory provider work
- no extra provider configuration is needed for episodic memory in v1

This keeps the provider refactor smaller while preserving the long-term
architectural boundary at the `Hippocampus` level.

## External Providers

`memory=external` should be present architecturally now, but can remain minimal
or stubbed at first.

Near-term expectation:

- code should make the future provider path obvious
- comments and stubs should signal where Mem0/custom providers plug in
- the default docs/story should still focus on NeoPsyke pgvector

This means:

- support the extension point now
- do not let external-provider complexity dominate the first OSS onboarding path

## Code-Level Documentation Expectations

Future contributors should be able to discover the direction from code.

That means the implementation should prefer:

- clear type names
- package/module names that reflect the layer split
- provider SPI comments that explain intended adapter directions
- TODOs in strategic extension points

Avoid requiring contributors to reconstruct the intended architecture from
historical documents alone.

Examples of what code should make obvious:

- `memory-core` owns NeoPsyke memory policy
- `memory-spi` is transport-agnostic
- HTTP is the default provider transport
- MCP remains optional at the provider layer
- episodic memory is intentionally out of provider v1
- `external` provider mode is future-facing but real in the architecture

## What This Unblocks

This decision set unblocks:

- extracting real `memory-api`, `memory-core`, and `memory-spi` modules
- replacing `McpHippocampus` as the practical default path
- building a separate `neopsyke-pgvector-memory` repo/release
- adding an HTTP provider client in NeoPsyke
- building a first-run bootstrap/install flow for default memory
- keeping future custom providers possible without touching `Ego`

## Remaining Implementation Work

These are implementation tasks, not open strategy questions:

- extract the physical modules in NeoPsyke
- define the v1 provider SPI types and package/module placement
- add the HTTP provider client used by default memory mode
- design the bootstrap installer/startup command flow
- move current pgvector memory logic toward the new provider repo structure
- keep MCP in the provider project as an optional interface
- wire `memory=off`, `memory=default`, and `memory=external` in NeoPsyke
- keep `memory=external` advanced and HTTP-first in v1; add MCP/direct later

## Relationship To Existing Notes

- `TEMP_MEMORY_REDESIGN_NOTE.md`
  - broader long-term memory redesign and `Hippocampus` direction
- `TODO_MEMORY_REDESIGN.md`
  - deferred wiring and follow-up tasks

This note is specifically about the provider-oriented architecture decision
below `Hippocampus`.
