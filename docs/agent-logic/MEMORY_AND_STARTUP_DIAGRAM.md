# Memory and Startup Diagram

This file covers memory components, per-loop memory touchpoints, and startup health gates.

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

## L2: Startup Memory Gate

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
