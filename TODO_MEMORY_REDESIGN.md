# Memory Redesign TODO

This file tracks deferred wiring and follow-up work for the long-term memory
refactor so pending pieces are not lost during the structural changes.

It is intentionally short and execution-oriented.

Related note:

- `MEMORY_PROVIDER_REFACTOR_NOTE.md` records the confirmed provider-oriented
  architecture and bootstrap decisions for the next refactor stage

## Major Remaining Redesign Work

- Do the physical module split:
  - `memory-api`
  - `memory-core`
  - `memory-spi`
  - `default-memory-bootstrap`
- Stop relying on `McpHippocampus` as the effective default runtime backend.
- Implement a real `memory=default` mode backed by
  `neopsyke-pgvector-memory`.
- Finish exploiting the new typed API end-to-end instead of remaining mostly
  narrative-oriented with compatibility bridging.
- Finish routing episodic memory through one real long-term facade path instead
  of leaving it behaviorally partially separate.
- Implement and schedule `Hippocampus.consolidate(...)`.

## OSS Readiness Blockers

- Build the agreed default memory story:
  - `memory=default`
  - external `neopsyke-pgvector-memory` provider
  - Docker-backed provider bootstrap
  - NeoPsyke-owned memory config
- Make installability match the product promise for open-source users.
- Reduce MCP to an adapter/integration surface instead of the practical
  default architecture.
- Add the transport-agnostic provider SPI and the default HTTP provider path.

## Pending Wiring

- Wire `MemoryEventType.FACT_CORRECTED` when fact supersession/correction paths
  become explicit in the new long-term memory flow.
- Wire `MemoryEventType.RELATION_INFERRED` when relation extraction or
  relation-imprint paths become explicit in memory-core.
- Wire `MemoryEventType.CONSOLIDATION_RUN` when `Hippocampus.consolidate(...)`
  is implemented and invoked by a bounded background path.
- Wire `MemoryEventType.GOAL_UPDATED` wherever durable goal-state changes can
  be journaled without changing current Ego logic.
- Wire `MemoryEventType.PREFERENCE_REINFORCED` once preference reinforcement
  can be distinguished from first-time imprint with confidence.

## Pending API / Runtime Work

- Implement `Hippocampus.consolidate(...)` as a stub in phase 1 and leave the
  method unused until a bounded scheduler or Id-driven trigger is designed.
- Introduce the separate admin/operational interface alongside `Hippocampus`:
  `stats()`, `forget(...)`, `reset(...)`.
- Replace `EpisodicEventType` with `MemoryEventType` across the moved long-term
  episodic domain.
- Move `Logbook` domain interfaces/models into the long-term memory module
  while keeping the current SQLite backend behavior unchanged.
- Adapt `MemorySystem` and call sites so episodic recall/imprint flow through
  the unified long-term memory facade.
- Rework runtime wiring so the default local path no longer depends on
  `McpHippocampus`.
- Add the real `memory=default` mode after the module split and provider
  wiring are settled.
- Replace package-level extraction with the real physical module split.
- Add code-level SPI comments/TODOs that make future HTTP/MCP/direct provider
  adapters obvious to contributors.
- Keep `memory=external` present in config/runtime shape even if initially
  stubbed.

## Future Expansion

- Revisit whether `recall` should remain a single request object or evolve into
  a sealed hierarchy after the refactor reveals the real divergence between
  semantic and episodic recall.
- Revisit relation/graph-aware recall once the shared memory-core extraction is
  complete.
- Revisit whether episodic storage should remain SQLite-backed permanently or
  become a pluggable backend under the unified long-term memory system.
- Revisit first-class external providers such as Mem0 once the provider SPI and
  default pgvector path are stable.
