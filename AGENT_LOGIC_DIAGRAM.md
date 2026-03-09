# Agent Logic Diagram (Living Document)

This file complements `AGENT_LOGIC_SUMMARY.md` with simple, editable Mermaid diagrams.
Keep diagrams high signal: small, readable, and updated as runtime logic evolves.

## 1) Component View

```mermaid
flowchart LR
    U["User / Web UI + Terminal Control"] --> SC["SensoryCortex (Async Web Chat Input + Stdin Control)"]
    SC --> E["Ego Orchestrator"]
    NoteCtx["ConversationContext(sessionId required)"] --> SC

    E --> AS["AttentionScheduler"]
    AS --> E

    E --> P["LlmEgoPlanner"]
    P --> AV["Action Verifier LLM Call"]
    E --> TV["TaskVerifier (Deterministic Task Gate)"]
    E --> S["Superego"]
    S --> S1["SingleStage Review Engine"]
    S --> S2["TwoStage Escalation Engine"]
    E --> AR["ActionRegistry (ServiceLoader Discovery)"]
    E --> M["MotorCortex"]
    E --> BG["LLM Token Budget Gate"]
    E --> MCat["Model Catalog (ROI token_weight)"]

    E --> DE["DeliberationEngine"]
    DE --> MR["LlmMetaReasoner"]
    MR -.-> MRF["MetaReasoner Fallback Model (optional, repeated empty-content failures)"]

    E --> MC["MemoryCoordinator"]
    MC --> MS["MemoryStore (Short-term)"]
    MC --> H["Hippocampus (Long-term Recall/Imprint)"]
    MC --> LTM["LlmLongTermMemoryAdvisor"]
    MC --> LB["Logbook (Episodic, SQLite+FTS5)"]
    MC --> RL["Reflection Lessons (Recall + Imprint Filters)"]
    MC -.->|"temporal intent → episodic recall + vector cues"| LB
    E --> TWS["TaskWorkspaceStore (Ephemeral Per Request)"]
    E --> TWF["TaskWorkspaceFinalizer (Noop or LLM)"]

    AR --> AP["Action Plugins (self-described)"]
    AP --> M

    M --> WS["Web Search Handler/Engine"]
    CfgWS["WebSearch Provider Config (provider/key/base/model)"] --> WS
    M --> MT["MCP Time Tool"]
    M --> MF["Fetch Tool"]
    M --> EM["Email Send (Microsoft Graph)"]
    WS --> PID["PromptInjectionDefense"]
    MT --> PID
    MF --> PID
    PID --> E

    BG --> P
    BG --> S
    BG --> MR
    BG --> LTM
    BG --> WS
    MCat --> S
    MCat --> LTM

    E --> I["InstrumentationBus + Metrics"]
    I --> DS["DashboardStateStore"]
    DS --> CP["Conversations Page (`/`) + Chat API (`/api/chat/*`)"]
    DS --> OP["Observability Page (`/dashboard`) + Obs API (`/api/obs/*`)"]
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
    participant TWS as TaskWorkspaceStore
    participant Dash as DashboardStateStore/API

    User->>SC: Web chat input text
    SC->>Ego: InputReceived
    Note over SC,Ego: Input carries ConversationContext(sessionId), rootInputId(identity), receivedAtMs(timing)
    Ego->>Sched: enqueueInput

    loop While pending work and step limit not reached
        Ego->>Sched: nextTask()
        Sched-->>Ego: input/thought/action
        Ego->>Ego: activateSession(task.conversationContext)
        Ego->>Delib: startStep()

        alt Task = input or thought
            Ego->>Mem: recall and short-term summary
            Note over Ego,Mem: Planner context now includes targeted reflection-lesson recall
            Ego->>TWS: create or update request workspace and index summary
            Ego->>Dash: emit task_workspace_head (with optional debug snapshot)
            Ego->>Planner: decide(context)
            Note over Ego,Planner: On non-parseable planner JSON, planner issues one strict-JSON retry before noop fallback
            Planner-->>Ego: thought/action/plan/noop
            Ego->>Delib: maybeApplyPressureOverride
            Ego->>Sched: enqueue thought/action/plan steps
            Note over Ego,Sched: Plans gated by budget → pressure → hash dedup → pending-plan check
            Note over Ego,Planner: Redundancy is planner-side soft cost control (prompt and verifier), with telemetry event external_action_redundancy_signal
            Note over Ego,Planner: Action verifier runs after action decisions parse failures trigger one strict retry and may trip temporary verifier bypass (scoped per root_input and action_type)
            Note over Ego,Planner: Follow-up thoughts carry structured origin metadata (originActionType + observedEvidence) verifier repairs back to the same evidence action are ignored for evidence-backed answers unless user asked refresh/retry no-op verifier repairs collapse to approve
        else Task = action
            alt Fallback explanation action
                Ego->>Motor: execute (bypass Superego)
            else Normal action
                Ego->>TV: review(action, evidence/recent dialogue)
                alt task verifier deny
                    TV-->>Ego: deny (with reason_code)
                    Ego->>Sched: enqueue safe-alternative thought
                    Ego->>Mem: maybeRecordReflectionLesson(filtered)
                else task verifier allow
                    Ego->>Sup: deterministic checks
                    alt deterministic deny
                        Sup-->>Ego: deny (hard deny)
                        Ego->>Sched: enqueue safe-alternative thought
                        Ego->>Mem: maybeRecordReflectionLesson(filtered)
                    else deterministic pass
                        Ego->>Sup: llm review(action)
                        Note over Ego,Sup: Stage-1 uses cheaper model from catalog when two-stage is enabled
                        Note over Ego,Sup: Escalate on low confidence, policy-risk, or technical fallback
                        Note over Ego,Sup: Superego completion max_tokens scales with prompt estimate (bounded floor/hard-cap) and model token_weight
                        Note over Ego,Sup: Structured output is schema-enforced (response_format=json_schema)
                        Note over Ego,Sup: Stage parse failures trigger one schema-enforced retry before default deny
                        Sup-->>Ego: allow or deny (with reason_code on deny)
                        alt allow
                            alt action = answer
                                Ego->>TWS: final-pass compilation from workspace index/evidence
                                Ego->>TWF: rewrite candidate payload (if enabled)
                                Note over Ego,TWF: Apply workspace-confidence gate first, then model-confidence gate
                            end
                            Ego->>Motor: execute(action)
                            Ego->>Ego: PromptInjectionDefense sanitize untrusted tool output
                            alt action = answer
                                Ego->>Sched: clear pending thought and action work for same root-session scope
                                Ego->>TWS: destroy workspace for resolved input
                                Note over Ego,Dash: Workspace telemetry carries root_input_id(identity) and root_input_received_at_ms(timing)
                                Ego->>Dash: drawer reads full snapshots via /api/obs/workspace/{rootId}
                                Ego->>Mem: maybeAssessLongTermMemory(post_terminal_answer, forced)
                            end
                            Ego->>TWS: record non-answer action outcomes/evidence
                            Ego->>Sched: enqueue follow-up thought (for evidence actions)
                            Ego->>Mem: maybeAssessLongTermMemory(post_allowed_action, optional force)
                            Note over Ego,Mem: Blocked imprints emit long_term_memory_persistence_skipped (reason_code, reason_detail) for timeline visibility
                        else deny
                            Ego->>Sched: enqueue safe-alternative thought
                            Ego->>Mem: maybeRecordReflectionLesson(filtered)
                        end
                    end
                end
            end
        end

        Ego->>Delib: maybeForceTerminalAnswer
        Note over Ego,Delib: Deliberation state is session-scoped evidence and circuit state is scoped by root-session
        Note over Ego,Delib: Meta-reasoner output is schema-enforced, repeated empty-content transport failures can trigger optional fallback endpoint
        Ego->>Mem: maybeAssessLongTermMemory(interval or explicit remember-intent)
        Note over Ego,Mem: Episodic recall filters session/interlocutor only when explicitly requested by user input
        Note over Ego,Mem: Memory-advisor completion max_tokens scales with prompt estimate (bounded floor/hard-cap) and model token_weight
        Note over Ego,Mem: Long dialogue/recall blocks are compressed before advisor prompt
    end

    Note over User,SC: Terminal stdin is control-only in interactive mode (exit command), non-command text is not enqueued as chat input
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

## 2.6) Interactive Startup LLM Provider Health Gate

```mermaid
flowchart LR
    A["runInteractiveMode"] --> B["Per-role provider health probe: GET base_url/models"]
    B --> C["Normalize URL join (trim trailing slash)"]
    C --> D{"Provider is Google and probe is HTTP 404?"}
    D -->|yes| E["Fallback probe: GET /v1beta/models (native Gemini endpoint)"]
    D -->|no| F["Report initial probe status"]
    E --> G["Report fallback status"]
```

## 3) Convergence and Fallback States

```mermaid
stateDiagram-v2
    [*] --> Processing

    Processing --> Planning: input/thought task
    Planning --> ActionQueued: decision=action
    Planning --> ThoughtQueued: decision=thought/plan/noop-thought
    Planning --> ThoughtQueued: plan suppressed (budget/pressure/hash/pending) -> convergence/recovery thought

    ActionQueued --> TaskReview: non-fallback action
    ActionQueued --> Executing: fallback explanation action
    TaskReview --> Denied: task verifier deny

    TaskReview --> PolicyReview: task verifier allow
    PolicyReview --> Denied: deterministic hard deny / superego deny
    Denied --> ThoughtQueued: enqueue safe alternative thought
    Note right of ThoughtQueued: Repeat-denied payload block is skipped for technical or transient denial reasons (prefer reason_code classification) reflection lessons persist only for non-technical and non-system denials

    PolicyReview --> Executing: superego allow
    Executing --> EvidenceObserved: external action succeeded
    Executing --> EvidenceMissing: tool/provider failure
    Executing --> WebSearchUnavailable: web search init/config failure
    WebSearchUnavailable --> ThoughtQueued: planner uses remaining available actions
    EvidenceObserved --> ThoughtQueued: follow-up thought
    EvidenceMissing --> ThoughtQueued: retry/adjust path
    EvidenceMissing --> ActionDisabled: retry-budget cooldown trips (non-retryable action failures)
    ActionDisabled --> ThoughtQueued: planner uses remaining available actions

    Processing --> HighPressure: pressure threshold reached
    HighPressure --> ForcedTerminal: force answer enqueue
    ForcedTerminal --> Executing

    Processing --> StepLimit: max loop steps with pending work
    StepLimit --> FallbackAttempt: dequeue fallback explanation action
    FallbackAttempt --> Executing

    Executing --> CleanupResolvedInput: action=answer clears same-input queued work + destroys task workspace
    CleanupResolvedInput --> Complete
    Processing --> Complete: queues drained
    Complete --> [*]
```

## 4) OpenAI Standalone Moderation Utility

```mermaid
flowchart LR
    A["Caller invokes moderateWithOpenAi(input)"] --> B["OpenAiModerationClient.moderate(input)"]
    B --> C["POST /moderations (omni-moderation-latest)"]
    C --> D["Return decision(flagged, categories, model, id)"]
    E["OpenAiChatClient chat calls"] --> F["POST /chat/completions only"]
```

## Edit Rules
- Keep this file synced with `AGENT_LOGIC_SUMMARY.md`.
- Prefer updating existing diagrams over adding a large monolith.
- If behavior changes, update only affected diagram sections and labels.
