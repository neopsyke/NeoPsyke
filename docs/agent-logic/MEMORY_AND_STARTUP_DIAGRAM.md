# Memory and Startup Diagram

This file covers provider startup gates, memory tiers, long-term persistence controls, and scratchpad behavior.
For the unified runtime entrypoint, see [../../AGENT_RUNTIME_LOGIC.md](../../AGENT_RUNTIME_LOGIC.md).

## L2: LLM Provider Configuration

- Each cognitive role can use an independent provider, API key, base URL, and model from `llm-runtime.yaml`.
- Supported providers: `anthropic`, `groq`, `google`, `mistral`, `ollama`, `openai`.
- `meta_reasoner_fallback` is optional and only used on repeated technical failures.
- Optional `model_catalog` adds `tier`, `token_weight`, and cost metadata.
- Superego and `LongTermMemoryAdvisor` read `token_weight` for dynamic completion-budget scaling.
- `web_search` routing is configured independently from cognitive roles.

## L2: Startup Memory Gate

- `memory=off` -> `NoopHippocampus`
- `memory=default` -> managed provider bootstrap and health wait
- `memory=external` -> external HTTP provider only, never auto-started
- Startup failures downgrade memory to noop for the run.
- Managed closeables are registered with the JVM shutdown hook.

```mermaid
flowchart LR
    A["runInteractiveMode"] --> B["Resolve memory mode from memory-runtime.yaml"]
    B -->|memory=off| C["NoopHippocampus (memory unavailable)"]
    B -->|memory=default| D["Check managed HTTP provider health"]
    D -->|healthy| E["Provider-backed Hippocampus enabled"]
    D -->|unhealthy| F["Install managed provider artifact if needed,<br/>start provider command, wait for /v1/health"]
    F -->|pass| E
    F -->|fail| C
    B -->|memory=external| X["Check external HTTP provider health"]
    X -->|healthy| E
    X -->|unhealthy / unsupported| C
    E --> Z["Register managed closeables with JVM shutdown hook<br/>so Ctrl-C / SIGTERM also closes the provider process"]
    E --> H["Emit action_capabilities(memory=available)"]
    C --> G["Emit action_capabilities(memory=unavailable + warning)"]
```

## L2: Startup LLM Provider Health Gate

- Startup probes each role with `GET base_url/models`.
- URL joins are normalized.
- Google-specific HTTP 404 probes fall back to `/v1beta/models`.
- Transient failures are retried once.
- `meta_reasoner_fallback` is optional and can be disabled for the run without aborting startup.

```mermaid
flowchart LR
    A["runInteractiveMode"] --> B["Per-role provider health probe: GET base_url/models"]
    B --> C["Normalize URL join (trim trailing slash)"]
    C --> D{"Provider is Google and probe is HTTP 404?"}
    D -->|yes| E["Fallback probe: GET /v1beta/models (native Gemini endpoint)"]
    D -->|no| F["Report initial probe status"]
    E --> G["Report fallback status"]
    F --> H{"Unavailable and retryable?"}
    G --> H
    H -->|yes| I["Retry health probe once"]
    H -->|no| J{"Role is optional meta_reasoner_fallback?"}
    I --> J
    J -->|yes + still unavailable| K["Warn and disable fallback for this run"]
    J -->|no| L["Required role unavailable -> abort startup"]
```

## L1: Memory System

- File: `src/main/kotlin/ai/neopsyke/agent/ego/MemorySystem.kt`
- Four tiers:
  1. short-term `MemoryStore`
  2. long-term `Hippocampus` plus `LongTermMemoryAdvisor`
  3. episodic `Logbook`
  4. per-request `ScratchpadStore`

## L1: Memory Subsystem View

```mermaid
flowchart LR
    Ego["Ego"] --> Mem["MemorySystem"]
    Mem --> STM["MemoryStore (Short-term)"]
    Mem --> Hip["Hippocampus (Long-term facade)"]
    Mem --> Adv["LongTermMemoryAdvisor"]
    Mem --> Log["Logbook (SQLite + FTS5)"]
    Log -.->|"event-type narrative normalization"| Mem
    Mem --> Lessons["Reflection Lessons"]
    Mem -.->|"temporal intent -> episodic recall + vector cues"| Log

    Ego --> TWS["ScratchpadStore"]
    Ego --> TWF["ScratchpadFinalizer"]
```

### L2: Short-Term Memory
- File: `src/main/kotlin/ai/neopsyke/agent/memory/shortterm/MemoryStore.kt`
- Recent turns live in an `ArrayDeque`; older turns are folded into `rolledSummary`.
- Per turn content is capped and compaction keeps promptable memory bounded.
- `summaryForPrompt(maxTokens)` builds the short-term planner summary.

### L2: Long-Term Recall and Consolidation
- `Hippocampus` exposes `recall`, `imprint`, `consolidate`, and `health`; admin methods include `stats`, `forget`, and `reset`.
- Default implementation is `NoopHippocampus`.
- `LongTermMemoryAdvisor` decides `save` or `skip` with confidence, tags, and summary text.
- Saved summaries use first-person agent perspective.
- Subject classification distinguishes `user` and `self`, and self-origin memories are normalized away from user-preference framing.
- Oversized dialogue and recall blocks are compressed before the advisor prompt.
- Persistence is guarded by interval, cooldown, confidence, duplicate-fingerprint, and parse-fallback protections.
- Every blocked persistence emits `long_term_memory_persistence_skipped`.

### L2: Episodic Logbook
- File: `src/main/kotlin/ai/neopsyke/agent/memory/episodic/Logbook.kt`
- SQLite + FTS5 backend for events like `INPUT_RECEIVED` and `REFLECTION_SESSION`.
- Entries carry active channel, principal, and policy-scope metadata.
- Recall defaults to cross-session unless the user explicitly asks for filters.
- Temporal intent maps into episodic recall plus vector cues.

### L2: Reflection Lessons
- `ReflectionLesson` entries are created for denied-action and repeated-denied loops.
- Persisted as `MemoryImprint(source=ego_reflection_lesson)`.
- Deduplicated by recent fingerprint window.
- Skipped for technical and system failures.
- Injected back into planner prompts as reflection-lesson context.

### L2: Scratchpad (Thread Workspace)
- File: `src/main/kotlin/ai/neopsyke/agent/memory/scratchpad/ScratchpadStore.kt`
- Enabled by default and gated by plan activation.
- Per-thread workspace survives waits and resumes.
- Draft sequences are internal, excluded from planner prompt summaries, and reset when cognition leaves answer drafting.
- `promptSummary(...)` builds planner-facing scratchpad context.
- `buildFinalCompilation(...)` assembles the terminal answer candidate.
- Confidence estimate combines sections, evidence, and assignment signal.
- `ScratchpadFinalizer` can rewrite the final answer but keeps the original payload on failure.
- Dashboard workspace telemetry carries `root_input_id` and `root_input_received_at_ms`, and full snapshots are served on demand.

## L1: Per-Loop Recall and Assessment

```mermaid
flowchart TD
    Opportunity["Opportunity / continuation"] --> Recall["Recall context + short-term summary"]
    Recall --> Lessons["Targeted reflection-lesson recall"]
    Recall --> Workspace["Create or update thread workspace"]
    Workspace --> Reasoning["Planner / Superego / action work"]

    Reasoning --> Trigger{"Assessment trigger"}
    Trigger -->|interval tick| Assess["maybeAssessLongTermMemory()"]
    Trigger -->|allowed action| Assess
    Trigger -->|terminal answer| Assess

    Assess --> Advisor["LongTermMemoryAdvisor"]
    Advisor --> Hip["Hippocampus imprint"]
    Assess --> Log["Logbook persistence"]
```
