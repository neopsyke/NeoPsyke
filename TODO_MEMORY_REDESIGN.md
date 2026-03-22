# Memory Redesign TODO

This file tracks deferred wiring and follow-up work for the long-term memory
refactor so pending pieces are not lost during the structural changes.

It is intentionally short and execution-oriented.

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

## Future Expansion

- Revisit whether `recall` should remain a single request object or evolve into
  a sealed hierarchy after the refactor reveals the real divergence between
  semantic and episodic recall.
- Revisit relation/graph-aware recall once the shared memory-core extraction is
  complete.
- Revisit whether episodic storage should remain SQLite-backed permanently or
  become a pluggable backend under the unified long-term memory system.
