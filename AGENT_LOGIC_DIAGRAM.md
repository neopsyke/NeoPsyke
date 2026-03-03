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
    E --> S["Superego"]
    E --> M["MotorCortex"]

    E --> DE["DeliberationEngine"]
    DE --> MR["LlmMetaReasoner"]

    E --> MC["MemoryCoordinator"]
    MC --> MS["MemoryStore (Short-term)"]
    MC --> H["Hippocampus (Long-term Recall/Imprint)"]
    MC --> LTM["LlmLongTermMemoryAdvisor"]

    M --> WS["Web Search Handler/Engine"]
    M --> MT["MCP Time Tool"]
    M --> MF["MCP Fetch Tool"]

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
            Planner-->>Ego: thought/action/plan/noop
            Ego->>Delib: maybeApplyPressureOverride
            Ego->>Sched: enqueue thought/action/plan steps
            Note over Ego,Sched: Plans gated by budget → pressure → hash dedup → pending-plan check
        else Task = action
            alt Fallback explanation action
                Ego->>Motor: execute (bypass Superego)
            else Normal action
                Ego->>Sup: review(action)
                Sup-->>Ego: allow/deny
                alt allow
                    Ego->>Motor: execute(action)
                    alt action = answer
                        Ego->>Sched: clear pending thought/action work for same root input
                    end
                    Ego->>Sched: enqueue follow-up thought (for evidence actions)
                    Ego->>Mem: maybeAssessLongTermMemory(post_action)
                else deny
                    Ego->>Sched: enqueue safe-alternative thought
                end
            end
        end

        Ego->>Delib: maybeForceTerminalAnswer
        Ego->>Mem: maybeAssessLongTermMemory(interval)
    end
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

    PolicyReview --> Denied: superego deny
    Denied --> ThoughtQueued: enqueue safe alternative thought

    PolicyReview --> Executing: superego allow
    Executing --> EvidenceObserved: external action succeeded
    Executing --> EvidenceMissing: tool/provider failure
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
