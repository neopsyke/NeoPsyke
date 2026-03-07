# Agent Logic Diagram (Living Document)

This file complements `AGENT_LOGIC_SUMMARY.md` with simple, editable Mermaid diagrams.
Keep diagrams high signal: small, readable, and updated as runtime logic evolves.

## 1) Component View

```mermaid
flowchart LR
    U["User / Stdin"] --> SC["SensoryCortex"]
    SC --> E["Ego Orchestrator"]

    E --> AS["AttentionScheduler"]
    AS --> E

    E --> P["LlmEgoPlanner"]
    P --> AV["Action Verifier LLM Call"]
    E --> S["Superego"]
    E --> M["MotorCortex"]

    E --> DE["DeliberationEngine"]
    DE --> MR["LlmMetaReasoner"]

    E --> MC["MemoryCoordinator"]
    MC --> MS["MemoryStore (Short-term)"]
    MC --> H["Hippocampus (Long-term Recall/Imprint)"]
    MC --> LTM["LlmLongTermMemoryAdvisor"]

    M --> WS["Web Search Handler/Engine"]
    CfgWS["WebSearch Provider Config (provider/key/base/model)"] --> WS
    M --> MT["MCP Time Tool"]
    M --> MF["MCP Fetch Tool"]
    WS --> PID["PromptInjectionDefense"]
    MT --> PID
    MF --> PID
    PID --> E

    E --> I["InstrumentationBus + Metrics"]
```

## 2) Loop Sequence (Per Input)

```mermaid
sequenceDiagram
    participant User
    participant SC as SensoryCortex
    participant Ego
    participant Sched as AttentionScheduler
    participant Planner as LlmEgoPlanner
    participant Sup as Superego
    participant Motor as MotorCortex
    participant Delib as DeliberationEngine
    participant Mem as MemoryCoordinator

    User->>SC: Input text
    SC->>Ego: InputReceived
    Ego->>Sched: enqueueInput

    loop While pending work and step limit not reached
        Ego->>Sched: nextTask()
        Sched-->>Ego: input/thought/action
        Ego->>Delib: startStep()

        alt Task = input or thought
            Ego->>Mem: recall + short-term summary
            Ego->>Planner: decide(context)
            Note over Ego,Planner: On non-parseable planner JSON, planner issues one strict-JSON retry before noop fallback
            Planner-->>Ego: thought/action/plan/noop
            Ego->>Delib: maybeApplyPressureOverride
            Ego->>Sched: enqueue thought/action/plan steps
            Note over Ego,Sched: Plans gated by budget → pressure → hash dedup → pending-plan check
            Note over Ego,Planner: Action verifier runs after action decisions; parse failures trigger one strict retry and may trip temporary verifier bypass (scoped per root_input + action_type)
        else Task = action
            alt Fallback explanation action
                Ego->>Motor: execute (bypass Superego)
            else Normal action
                Ego->>Sup: deterministic checks
                alt deterministic deny
                    Sup-->>Ego: deny (hard deny)
                    Ego->>Sched: enqueue safe-alternative thought
                else deterministic pass
                    Ego->>Sup: llm review(action)
                    Note over Ego,Sup: Superego parse failures trigger one strict-JSON retry before default deny
                    Sup-->>Ego: allow/deny (+ reason_code on deny)
                    alt allow
                        Ego->>Motor: execute(action)
                        Ego->>Ego: PromptInjectionDefense sanitize untrusted tool output
                        alt action = answer
                            Ego->>Sched: clear pending thought/action work for same root input
                            Ego->>Mem: maybeAssessLongTermMemory(post_terminal_answer, forced)
                        end
                        Ego->>Sched: enqueue follow-up thought (for evidence actions)
                        Ego->>Mem: maybeAssessLongTermMemory(post_allowed_action, optional force)
                        Note over Ego,Mem: Blocked imprints emit long_term_memory_persistence_skipped (reason_code + reason_detail) for timeline visibility
                    else deny
                        Ego->>Sched: enqueue safe-alternative thought
                    end
                end
            end
        end

        Ego->>Delib: maybeForceTerminalAnswer
        Ego->>Mem: maybeAssessLongTermMemory(interval or explicit remember-intent)
    end
```

## 2.5) Interactive Startup Memory Gate

```mermaid
flowchart LR
    A["runInteractiveMode"] --> B["Resolve MCP memory command"]
    B -->|missing/disabled| C["NoopHippocampus (memory unavailable)"]
    B -->|present| D["MCP memory health probe (long-lived stdio connect + listTools)"]
    D -->|pass| E["McpHippocampus enabled"]
    D -->|fail| C
    E --> F["Emit action_capabilities(memory=available)"]
    C --> G["Emit action_capabilities(memory=unavailable + warning)"]
```

## 3) Convergence and Fallback States

```mermaid
stateDiagram-v2
    [*] --> Processing

    Processing --> Planning: input/thought task
    Planning --> ActionQueued: decision=action
    Planning --> ThoughtQueued: decision=thought/plan/noop-thought
    Planning --> ThoughtQueued: plan suppressed (budget/pressure/hash/pending) -> convergence thought

    ActionQueued --> PolicyReview: non-fallback action
    ActionQueued --> Executing: fallback explanation action
    ActionQueued --> Denied: deterministic hard deny

    PolicyReview --> Denied: superego deny
    Denied --> ThoughtQueued: enqueue safe alternative thought
    Note right of ThoughtQueued: Repeat-denied payload block is skipped for technical/transient denial reasons (prefer reason_code classification)

    PolicyReview --> Executing: superego allow
    Executing --> EvidenceObserved: external action succeeded
    Executing --> EvidenceMissing: tool/provider failure
    Executing --> WebSearchUnavailable: web search init/config failure
    WebSearchUnavailable --> ThoughtQueued: planner uses remaining available actions
    EvidenceObserved --> ThoughtQueued: follow-up thought
    EvidenceMissing --> ThoughtQueued: retry/adjust path
    EvidenceMissing --> ActionDisabled: circuit breaker trips (non-retryable fetch failures)
    ActionDisabled --> ThoughtQueued: planner uses remaining available actions

    Processing --> HighPressure: pressure threshold reached
    HighPressure --> ForcedTerminal: force answer enqueue
    ForcedTerminal --> Executing

    Processing --> StepLimit: max loop steps with pending work
    StepLimit --> FallbackAttempt: dequeue fallback explanation action
    FallbackAttempt --> Executing

    Executing --> CleanupResolvedInput: action=answer clears same-input queued work
    CleanupResolvedInput --> Complete
    Processing --> Complete: queues drained
    Complete --> [*]
```

## Edit Rules
- Keep this file synced with `AGENT_LOGIC_SUMMARY.md`.
- Prefer updating existing diagrams over adding a large monolith.
- If behavior changes, update only affected diagram sections and labels.
