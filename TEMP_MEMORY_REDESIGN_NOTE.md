# Memory Redesign Design Note

This document captures the target design direction for the NeoPsyke memory
refactor.

It does not describe the current runtime as-is. The source of truth for the
current runtime remains:

- `AGENT_LOGIC_SUMMARY.md`
- `AGENT_LOGIC_DIAGRAM.md`

This note is intentionally temporary and decision-oriented. It should evolve
into a more stable architecture document once the refactor starts landing.

## Purpose

The redesign should make long-term memory:

- easier to install and run for open-source users
- easier to understand as part of NeoPsyke itself
- more flexible about storage and retrieval strategies
- cleaner to evolve toward hybrid memory backends
- cleanly separated from short-term memory

The main architectural intent is to treat long-term memory as a coherent
subsystem with one stable agent-facing boundary, instead of a collection of
special cases split across vector memory, episodic logbook, and MCP transport.

## Confirmed Direction

### 1. Keep `Hippocampus` as the stable agent-facing boundary

`Hippocampus` remains the cognitive long-term memory facade used by `Ego` and
`MemorySystem`.

We keep cognitive verbs such as:

- `recall`
- `imprint`

Reasoning:

- these verbs fit the architecture better than purely technical names such as
  `search` and `write`
- they preserve the abstraction that memory strategy selection belongs behind
  the memory boundary
- they keep `Ego` insulated from storage details such as namespaces, ranking
  strategy, graph expansion, or persistence mode

What changes is not the vocabulary, but the payload richness.

### 2. Enrich `Hippocampus` types instead of flattening everything to text

Current `recall` and `imprint` are too narrow because they primarily move
rendered text and simple summaries.

The new `Hippocampus` API should:

- return structured memory items, not only prompt-ready text
- keep `renderedText` in `RecallResult` for now to preserve current planner
  wiring and current expected behavior during refactor
- support typed imprint variants such as:
  - narrative/note
  - fact
  - relation
  - episode
  - lesson
  - preference
  - goal
  - constraint
- advertise backend capabilities explicitly instead of pretending all
  backends are equivalent

### 3. Keep backend-specific storage concerns out of `Ego`

`Ego` should not need to specify backend storage concepts such as:

- namespace
- partition
- retrieval strategy
- graph traversal depth
- dedupe mode

Those should be resolved by:

- runtime wiring
- memory configuration
- backend policy
- memory-core orchestration

The `Hippocampus` request may include semantic context, but not backend
administrative detail.

### 4. Separate short-term memory from long-term memory more clearly

The redesign should make a cleaner distinction:

- short-term memory: rolling prompt context and scratch work for the active
  cognitive loop
- long-term memory: durable recall, episodic history, facts, lessons,
  preferences, and derived relations

Short-term memory should remain outside `Hippocampus`.

Long-term memory should be grouped under the `Hippocampus` boundary, even if
the implementation uses multiple internal stores.

## Proposed Module Split

The current memory implementation should be reorganized into these layers:

### `memory-api`

Stable domain-facing memory contracts and types.

Owns:

- `Hippocampus`
- recall/imprint request and result DTOs
- structured memory item models
- capability models
- health/status models
- long-term episodic domain types moved under the long-term memory module
  (`Logbook` API move now; backend rewrite later)

This layer should use cognitive/domain language, not transport-specific or
backend-specific language.

### `memory-core`

Backend-agnostic memory logic and technical orchestration.

Owns:

- retrieval fusion/ranking logic
- dedupe policy
- fact correction/supersession rules
- typed imprint normalization
- episodic-to-semantic bridging
- memory rendering helpers
- internal storage interfaces used by concrete backends

This layer may use more technical vocabulary such as `search`, `write`,
`upsertFact`, `expandRelations`, and `compact`.

### `memory-embedded`

Default local backend path for open-source usage.

Current intended direction:

- local managed pgvector-backed runtime for strong default memory quality
- no MCP in the default local path
- implementation may internally combine vector store plus episodic store

This is "embedded" from NeoPsyke's product perspective, not necessarily
"in-process with no auxiliary database runtime".

### `memory-mcp-server`

Thin transport/server adapter over `memory-core`.

Owns:

- MCP tool registration
- MCP payload translation
- MCP transport/runtime concerns

It should stop owning the core memory semantics.

## Memory Modes

The target runtime profiles are:

- `memory=off`
- `memory=embedded`
- `memory=mcp`

Notes:

- `memory=off` is the default for tests, CI, and minimal runs
- `memory=embedded` is the recommended OSS local mode
- `memory=mcp` remains useful for external/shared/remote memory services and
  experimentation

## Long-Term Memory Grouping

The current long-term memory concerns are split like this:

- semantic/vector-style durable recall via `Hippocampus`
- episodic timeline recall via `Logbook`
- long-term consolidation policy via `LongTermMemoryAdvisor`

This redesign should group semantic, episodic, factual, and relational memory
more clearly under the long-term memory subsystem.

### Recommendation

Move episodic memory under the `Hippocampus` boundary conceptually and at the
API/domain level now, but not necessarily as a single physical store.

That means:

- `Ego` should interact with one long-term memory facade
- episodic recall should be exposed through `Hippocampus`, not as an adjacent
  side interface on `MemorySystem`
- the current logbook domain types/interfaces move under the long-term memory
  module during this refactor
- the implementation can still delegate to an internal episodic store such as
  SQLite
- semantic/vector and episodic storage can remain distinct internally if that
  is the simplest implementation

This gives NeoPsyke one coherent long-term memory subsystem without forcing
all long-term memory into one database engine.

### Why absorb episodic memory into `Hippocampus`

- episodic memory is clearly long-term memory, not short-term memory
- the planner already benefits from episodic recall and should continue to do
  so
- temporal recall, event history, and "what happened when" should live under
  one long-term boundary
- hybrid recall becomes easier if episodic and semantic memory can be fused
  behind one facade

### Important implementation note

Absorbing episodic memory into `Hippocampus` does not mean:

- rewriting the current SQLite logbook backend immediately
- forcing episodic data into pgvector
- removing exact timestamped event storage semantics

The likely target shape is:

- `Hippocampus` orchestrates multiple long-term memory sources
- one internal source remains an exact episodic event store
- another internal source may remain vector/fact/graph-oriented

Confirmed decision:

- Logbook API/domain move: now
- logbook storage/backend rewrite: not now

## Episodic Memory Requirements

Episodic memory exists to answer questions such as:

- what did the agent do
- when did it do it
- in which session or interaction context did it happen
- what sequence of events led to an outcome

That requires properties different from semantic recall:

- exact timestamps
- event typing
- temporal filtering
- timeline rendering
- preservation of corrections and historical sequence

These requirements should remain first-class and should not be degraded into
plain semantic notes.

## Planner Access To Episodic Recall

The planner already receives episodic recall today through planner context.

Target direction:

- planner should continue receiving episodic recall as a dedicated signal
- planner should be able to express explicit episodic recall intent
- `Hippocampus` should decide how to satisfy that intent internally

Likely future shape:

- a recall request includes an intent such as `episodic`, `fact`, `lesson`,
  `preference`, or `general`
- `Hippocampus` may satisfy that with one strategy or a hybrid strategy

Confirmed direction for phase 1:

- `recall` uses a single request object
- `imprint` uses a sealed request hierarchy
- if episodic and semantic recall diverge materially during refactor, `recall`
  can later evolve into a sealed hierarchy too

This preserves a clean contract:

- planner states what kind of memory it needs
- `Hippocampus` decides which storage/retrieval paths to use

## Memory Event Taxonomy

`MemoryEventType` replaces `EpisodicEventType` in phase 1.

Confirmed direction:

- broaden the enum now instead of keeping it as a pure 1:1 rename
- wire new event types where possible without changing current `Ego` logic
- where immediate wiring is not possible without broader behavior changes,
  leave explicit TODOs so the event taxonomy is not forgotten

Phase-1 `MemoryEventType` should include at least:

- `INPUT_RECEIVED`
- `PLANNER_DECISION`
- `ACTION_EXECUTED`
- `ACTION_DENIED`
- `CONTACT_DELIVERED`
- `MEMORY_IMPRINT`
- `SELF_INITIATED`
- `FACT_CORRECTED`
- `RELATION_INFERRED`
- `CONSOLIDATION_RUN`
- `GOAL_UPDATED`
- `PREFERENCE_REINFORCED`

Guidance:

- `FACT_CORRECTED`, `RELATION_INFERRED`, and `CONSOLIDATION_RUN` are primarily
  future-facing in phase 1 unless a straightforward wiring path exists during
  refactor
- `GOAL_UPDATED` can likely be introduced without side effects where goal
  state updates are already observable
- `PREFERENCE_REINFORCED` should be emitted only when the system can
  distinguish reinforcement from first-time imprint with confidence

The presence of these event types does not require immediate new planner or
Ego behaviors. It only establishes the vocabulary and ensures wiring work is
tracked.

## API Direction

At the agent-facing layer, keep cognitive verbs and richer typed payloads.

Illustrative direction only:

- `recall(request)`
- `imprint(request)`
- `consolidate(request)` as a stubbed background-memory lifecycle hook
- `health()`
- `capabilities`

The key decision is:

- do not replace cognitive verbs with purely technical verbs at the
  `Hippocampus` boundary
- do use structured request/result types so the boundary is future-safe

Explicitly out of the agent-facing cognitive surface:

- metrics/statistics retrieval
- destructive clear/reset operations
- backend-specific admin/repair actions

Those move to a separate technical/admin interface.

### Typed imprint direction

The long-term design should use:

- one cognitive verb: `imprint`
- one top-level request family: `ImprintRequest`
- a typed hierarchy of imprint request variants, not one oversized catch-all
  model and not a family of overloaded top-level methods

Recommended shape:

- `sealed interface ImprintRequest`
- shared fields exposed on the base contract where appropriate:
  - `context`
  - `confidence`
  - `tags`
  - `source`
- concrete variants such as:
  - `NarrativeImprint`
  - `FactImprint`
  - `RelationImprint`
  - `EpisodeImprint`
  - future variants such as `LessonImprint`, `PreferenceImprint`, or
    `GoalImprint` only if needed

Confirmed decision:

- keep one cognitive verb: `imprint(...)`
- do not introduce a family of overloaded top-level imprint methods
- do not force all imprint kinds into one oversized flat request model

Why this direction is preferred over multiple overloaded imprint methods:

- it keeps one stable cognitive action at the `Hippocampus` boundary
- it makes capabilities and validation easier to reason about
- it scales better when a hybrid backend supports many memory kinds
- it avoids API sprawl such as `imprintEpisode`, `imprintLesson`,
  `imprintFact`, and repeated overload families
- it keeps shared fields such as confidence, tags, source, and context in one
  conceptual contract while allowing strongly typed specialized fields
- it avoids forcing unrelated shapes into one flat request with many nullable
  fields or duplicated `intent + content` discriminators

This is also preferred over a single giant `ImprintRequest(...)` model when
episodic and factual data shapes diverge materially.

Overloads are still acceptable as small convenience adapters inside concrete
implementations or tests, but not as the primary long-term `memory-api`
contract.

## Capabilities

Backends should advertise what they can do instead of relying on implicit
behavior.

Candidate capabilities:

- semantic recall
- episodic recall
- fact storage
- relation storage
- hybrid recall
- forget
- reset
- versioned facts
- background consolidation

This matters because future backends may differ significantly:

- local managed pgvector
- remote MCP service
- future graph-enhanced memory
- hybrid vector + graph + episodic systems

## Admin And Operational Interface

Metrics/statistics and destructive/admin operations move to a separate
technical interface rather than staying on `Hippocampus`.

Reasons:

- `Ego` should depend only on cognitive long-term memory functions
- destructive operations should not sit beside normal recall/imprint calls on
  the same primary interface
- metrics are operational/diagnostic concerns, not cognitive ones
- backends can support admin operations optionally without forcing them into
  the main facade

Current intended contents of the separate admin interface:

- `stats()`
- `forget(...)`
- `reset(...)`

Confirmed naming/shape decisions:

- use `stats()`, not `metrics()`
- use explicit `ResetRequest(clearAll = true)` rather than a looser reset
  shorthand

Explicitly not part of this admin surface for now:

- schema migration
- transport restart
- raw backend-specific admin commands

## Background Memory Lifecycle

The old placeholder term here was `maintenance`.

That word is too vague for the `Hippocampus` boundary.

### Preferred term: `consolidate`

Recommended verb:

- `consolidate`

Why:

- it fits both cognitive and technical meanings
- it maps well to "dream-like" offline integration
- it is broader than just summarization, but less vague than `maintenance`
- it is more architectural than `dream`, which is evocative but too specific
  and anthropomorphic for the API

`dream` can still be used as an internal metaphor or scheduler label.

### What consolidation should achieve

`consolidate` should cover background long-term memory organization work such
as:

- derive durable lessons from recent episodes
- compress redundant notes
- strengthen or supersede corrected facts
- derive relations from repeated co-occurrence or confirmed structure
- prune stale or low-value transient artifacts
- prepare fused summaries for future retrieval
- refresh indexes or backend-specific derived views if needed

Not every backend must support every consolidation behavior.

### What should not live in `consolidate`

Avoid putting raw backend admin tasks directly into the agent-facing
consolidation API, for example:

- schema migration
- transport restarts
- raw database vacuum/reindex commands

Those are backend/runtime concerns and should stay below the `Hippocampus`
facade or in an explicit technical/admin layer if ever exposed.

### When consolidation should run

Candidate triggers:

- after successful turns, when there is durable new material
- during idle windows
- periodically after N turns or M minutes
- after explicit learning/reflection events
- on a background scheduler

### Who should trigger consolidation

Preferred ownership:

- triggered by `Ego` or a future background cognitive-work scheduler
- not by the planner in the middle of urgent user response unless explicitly
  needed

Possible future tie-in:

- an `Id` need or internal drive can request background consolidation in the
  same spirit as sleep/dream-driven integration

This fits the architecture well, but should be implemented as a bounded
background task, not as an uncontrolled free-running loop.

## Recommended Boundary Between Cognitive and Technical APIs

Use two layers of language:

### Cognitive boundary (`memory-api`)

Use terms such as:

- recall
- imprint
- consolidate

### Technical boundary (`memory-core`)

Use terms such as:

- search
- write
- upsertFact
- appendEpisode
- expandRelations
- compact
- prune

This split preserves the Freudian/cognitive architecture while still giving
implementations the precise language they need.

## Initial Refactor Phases

### Phase 0: document and stabilize direction

- create this design note
- confirm module split and API direction
- confirm episodic-memory placement under long-term memory conceptually

### Phase 1: extract API types

- create `memory-api`
- move/replace current `Hippocampus` contract with richer request/result models
- add health and capability models
- move current long-term episodic/logbook domain interfaces and models into the
  new long-term memory module without changing the underlying SQLite backend
- replace `EpisodicEventType` with broader long-term `MemoryEventType`
- wire newly introduced memory event types where possible without changing
  current `Ego` logic; track deferred wiring in a dedicated TODO file

### Phase 2: extract shared core

- move reusable storage semantics out of `mcp-memory-pgvector`
- keep MCP server functioning as an adapter over the extracted core

### Phase 3: absorb episodic memory under long-term facade

- adapt `MemorySystem` and call sites to speak to one long-term memory facade
- route episodic recall/imprint through that unified facade while preserving
  the current exact episodic storage behavior
- keep exact episodic store implementation behind that facade

### Phase 4: add embedded local backend

- direct local backend without MCP for the default local runtime
- app-managed local pgvector path

### Phase 5: optional hybrid expansion

- add fact/graph/hybrid retrieval behind the same `Hippocampus` contract

## Open Questions

These decisions are not yet final:

- whether `consolidate` should be synchronous, asynchronous, or both
- whether episodic storage stays SQLite permanently or becomes pluggable
- whether relation/graph support arrives in phase 1 types or in a later phase
- whether planner explicit episodic recall intent should be schema-level or
  inferred from richer recall context
- whether `recall` remains a single request model long-term or later becomes a
  sealed hierarchy
- the exact final shape of phase-1 DTOs, to be finalized during implementation
  as long as they stay within the decisions recorded in this note

## Current Leaning

The current best direction is:

- keep `Hippocampus` cognitive
- enrich its request/result types
- move episodic memory under the long-term boundary conceptually and at the
  API/domain layer
- keep `recall` as a single request object in phase 1
- model `imprint` as a sealed request hierarchy with specialized variants
- keep `renderedText` in `RecallResult` for now
- replace `EpisodicEventType` with broader `MemoryEventType`
- add future-facing event types now and wire them where possible without
  changing current `Ego` logic
- introduce `consolidate` as the dream-like background integration hook
- keep short-term memory separate
- keep the current episodic SQLite backend during the API refactor
- move metrics and destructive/admin operations to a separate interface
- reduce MCP to an adapter instead of the default architecture
