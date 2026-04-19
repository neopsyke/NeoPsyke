# Memory Redesign TODO

This file is the single source of truth for the remaining long-term memory
redesign work in NeoPsyke.

It only tracks work that is still missing. Completed refactor stages are not
repeated here.

## Current Baseline

Already in place:

- `Hippocampus` is the stable cognitive boundary.
- NeoPsyke uses a transport-agnostic provider SPI below `Hippocampus`.
- `memory=default` uses the external `neopsyke-pgvector-memory` provider over
  HTTP.
- NeoPsyke bootstraps the published provider artifact into
  `.neopsyke/providers/neopsyke-pgvector-memory/current/`.
- Managed provider processes are shut down on normal exit and `Ctrl-C`.
- `memory=external` exists and supports HTTP-compatible providers in v1.
- MCP remains available as a transitional/optional adapter, but it is no longer
  the default runtime path.

## Major Remaining Architecture Work

- Do the real physical module split:
  - `memory-api`
  - `memory-core`
  - `memory-spi`
  - `default-memory-bootstrap`
- Finish exploiting the typed memory API end-to-end instead of remaining mostly
  narrative-oriented at runtime.
- Finish routing episodic memory through one real long-term facade path instead
  of leaving it behaviorally and structurally partially separate.
- Implement and schedule `Hippocampus.consolidate(...)` instead of leaving it as
  an unused stub.

## Provider / Bootstrap Hardening

- Replace the fixed provider release URL with real version resolution:
  - resolve compatible provider releases by version policy instead of pinning
    only `v0.1.0`
  - enforce the agreed compatibility rule: compatible minor versions within the
    same major version
  - fail clearly on major-version incompatibility
- Improve the managed-provider install manifest/update story:
  - record installed version and source metadata clearly
  - detect stale installs explicitly
  - define update/reinstall behavior
- Harden bootstrap failure handling:
  - clearer user-facing errors for download failure, checksum failure, and
    provider startup failure
  - offline/air-gapped behavior
  - retry policy where appropriate
- Strengthen artifact trust beyond raw checksum verification:
  - release signing and signature verification
  - documented trust policy for downloaded provider artifacts

## Validation Gaps

- Add a real end-to-end bootstrap smoke against the published provider release:
  - download artifact
  - install artifact
  - start provider
  - pass `/v1/health`
  - execute real imprint/recall
- Add a tracked memory smoke path that exercises the real external provider
  instead of only mocks/fakes/config tests.
- Decide whether any non-live deterministic integration can cover more of the
  provider-backed path without paid model calls.

## Runtime / Product Cleanup

- Decide the final fate of `McpHippocampus`:
  - delete it if no longer needed
  - or move it fully behind the provider SPI as an optional legacy adapter
- Revisit whether root `docker-compose.yml` should still contain any memory-era
  leftovers now that the provider owns Docker-backed pgvector bootstrap.
- Replace temporary refactor-oriented wording in docs with stable OSS
  architecture/setup docs once the module split is done.

## Episodic Memory Follow-up

- Keep the current SQLite episodic logbook working, but finish its architectural
  relationship to the long-term memory system:
  - unify naming and domain boundaries
  - reduce duplicated “episodic vs long-term” runtime branching
  - decide whether SQLite remains permanent or becomes one pluggable backend
- Adapt `MemorySystem` and call sites so episodic recall/imprint flow through
  the unified long-term facade more cleanly than they do today.

## Pending Event Wiring

- Wire `MemoryEventType.FACT_CORRECTED` when fact supersession/correction paths
  become explicit in the long-term memory flow.
- Wire `MemoryEventType.RELATION_INFERRED` when relation extraction or
  relation-imprint paths become explicit in memory-core.
- Wire `MemoryEventType.CONSOLIDATION_RUN` when `Hippocampus.consolidate(...)`
  is implemented and invoked by a bounded background path.
- Wire `MemoryEventType.ASSIGNMENT_UPDATED` wherever durable assignment-state changes can
  be journaled without changing current Ego logic.
- Wire `MemoryEventType.PREFERENCE_REINFORCED` once preference reinforcement
  can be distinguished from first-time imprint with confidence.

## Future Expansion

- Extend `memory=external` beyond HTTP-only support when MCP/direct adapters are
  actually ready.
- Revisit whether `recall` should remain a single request object or evolve into
  a sealed hierarchy after the refactor reveals the real divergence between
  semantic and episodic recall.
- Revisit relation/graph-aware recall once shared `memory-core` extraction is
  complete.
- Revisit first-class external providers such as Mem0 once the provider SPI and
  default pgvector path are stable.
