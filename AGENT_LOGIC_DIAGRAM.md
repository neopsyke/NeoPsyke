# Agent Logic Diagram (Living Document)

This file complements `AGENT_LOGIC_SUMMARY.md` with simple, editable Mermaid diagrams.
Keep diagrams high signal: small, readable, and updated as runtime logic evolves.

## 1) Component View

```mermaid
flowchart LR
    U["User / Web UI"] --> SC["SensoryCortex (typed cognitive stimuli ingress)"]
    TG["Telegram Owner Chat"] --> TWH["TelegramWebhookBridge"]
    TG --> TLP["TelegramPollingBridge"]
    TWH --> SC
    TLP --> SC
    GOU["Google OAuth Browser Flow"] --> GOA["GoogleWorkspaceOAuthBridge"]
    SC --> E["Ego Orchestrator"]
    NoteCtx["ConversationContext(sessionId required, unknown interlocutor resolved at sensory boundary, security context carried end-to-end)"] --> SC

    E --> AS["AttentionScheduler"]
    AS --> E
    ID["Id (Autonomous Drives)"] --> AS
    E --> ID

    E --> P["LlmEgoPlanner"]
    P --> GBR["Goal-Creation Branch"]
    P --> AV["Action Verifier LLM Call"]
    E --> TV["DecisionVerifier (Deterministic Task Gate)"]
    E --> S["Superego"]
    S --> S1["SingleStage Review Engine"]
    S --> S2["TwoStage Escalation Engine"]
    E --> ACP["ActionAuthorizationPolicy (YAML)"]
    E --> ACS["ActionControlService"]
    E --> ACW["ActionControlAutonomousWorker"]
    ACS --> ACDB["ActionControl SQLite (staged / auth / receipts)"]
    ACW --> ACS
    %% Goal deletes are delete-sensitive: delete_all always stages, single-goal delete direct commit restricted to owner-direct channels with exact goal_id
    E --> AR["ActionRegistry (ServiceLoader Discovery)"]
    AR --> CR["Connector Runtime (curated catalog + local install state + stdio host)"]
    E --> M["MotorCortex"]
    E --> BG["LLM Token Budget Gate"]
    E --> MCat["Model Catalog (ROI token_weight)"]

    E --> DE["DeliberationEngine"]
    DE --> MR["LlmMetaReasoner"]
    MR -.-> MRF["MetaReasoner Fallback Model (optional, repeated technical failures: empty-content or schema-validation)"]

    E --> MC["MemorySystem"]
    MC --> MS["MemoryStore (Short-term)"]
    MC --> H["Hippocampus (Long-term facade: recall/imprint/health/consolidate stub)"]
    MC --> LTM["LlmLongTermMemoryAdvisor"]
    MC --> LB["Logbook (Episodic backend, SQLite+FTS5, grouped under long-term domain)"]
    LB -.->|"event-type narrative normalization: User timeline vs agent first-person memory/reflection"| MC
    MC --> RL["Reflection Lessons (Recall + Imprint Filters)"]
    MC -.->|"temporal intent → episodic recall + vector cues"| LB
    E --> TWS["ScratchpadStore (Thread Workspace + Intention Drafts)"]
    E --> TWF["ScratchpadFinalizer (Noop or LLM)"]
    E --> PG["GoalsGateway (optional goal runtime boundary)"]
    PG --> PM["GoalManager / Goal Runtime"]
    PM --> PP["GoalPlanner"]
    PM --> PV["GoalStepVerifier"]
    PM --> AOR["AsyncOperationRegistry"]
    PM --> PS["GoalStateMachine + GoalStore"]

    AR --> AP["Action Plugins (self-described)"]
    CR --> AP
    AP --> M
    %% Connector bundles are install presets only; goals compose primitive actions rather than executing bundle workflows directly
    AP -.->|"Actions emit structured effects; reflection emits durable-memory-save only on successful persistence"| MC
    M --> SC

    M --> WS["Web Search Handler/Engine"]
    CfgWS["WebSearch Provider Config (provider/key/base/model)"] --> WS
    M --> MF["Fetch Tool"]
    M --> EM["Email Send (Microsoft Graph)"]
    M --> COG["ConversationOutputGateway"]
    COG --> TGA["Telegram Bot API"]
    TWH -.-> GAUTH["Native OAuth Auth Primitives (signed state + encrypted pending store)"]
    GOA --> GCS["GoogleWorkspaceCredentialStore (encrypted)"]
    GOA --> GAP["Google OAuth + Gmail Profile Verification"]
    M --> GOBS["Native Google Observe Actions"]
    GOBS --> GAP
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
    DS --> OT["Thread Inspection (`/api/obs/threads*`)"]
    DS --> OX["Action Control Page (`/action-control`)"]
    DS --> ACAPI["Action Control API + SSE (`/api/action-control/*`)"]
    %% Action control UI defaults to SIGNAL activity items; can opt into BACKGROUND or TRACE ledger visibility; live updates replace polling
    %% Telegram ingress is owner-only: POST webhook + shared secret + direct-chat restriction + owner chat/user allowlist
    %% Native Google auth: signed state tokens + encrypted pending-auth storage; no plaintext refresh-token staging
    %% Google auth: explicit public callback URL, signed state, PKCE, owner-email verification, encrypted local credential storage
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
    participant CTS as CognitiveThreadStore
    participant Mem as MemorySystem
    participant TWS as ScratchpadStore
    participant Dash as DashboardStateStore/API

    User->>SC: Web chat input text
    SC->>Ego: StimulusReceived (stimulus + percept)
    Note over SC,Ego: Stimulus carries ConversationContext [sessionId + security], provenance, rootInputId [identity], receivedAtMs [timing]
    Ego->>CTS: bind percept to root-scoped cognitive thread
    CTS-->>Ego: cognitiveThreadId + thread trust state
    Ego->>Dash: emit cognitive_thread_updated
    CTS-->>Ego: policy-shaped Opportunity\n(intentions + commit modes + planner-visible action surface)
    Ego->>Sched: enqueue ScheduledOpportunity(opportunity + trigger)
    Ego->>Dash: emit opportunity_enqueued
    Note over CTS,Dash: Thread snapshots are retained for live and terminal roots and exposed through `/api/obs/threads`

    loop While pending work and step limit not reached
        Ego->>Sched: nextTask()
        Sched-->>Ego: ScheduledOpportunity/intention/action
        Ego->>Ego: activateSession(task.conversationContext)
        Ego->>Delib: startStep()

        alt Task = impulse opportunity
    Note over Ego,Mem: Id-driven recall/planning can see shared ambient context: goals, scratchpad themes, useful updates, open loops, and recent exact learning topics
    Note over Ego,Mem: Ambient context is a cached best-effort snapshot, not a real-time synchronized view
            Ego->>Planner: decide(context + idState)
            Planner-->>Ego: defer/intend/plan/noop
            Ego->>Sched: enqueue impulse-derived work with origin=ID
            Note over Ego,Sched: Impulse final result is deferred until all work for root_impulse_id drains
        else Task = goal work opportunity
            Ego->>PG: finalizeGoalCycle(rootInputId) after queues drain for that goal root
    Note over Ego,PG: Goal runtime now resumes from a stable per-step thread root, creates scratchpad state only when work is actually processed, and may re-emit a goal runtime cue for resumable steps
    Note over SC,Ego: StimulusIngressCoordinator classifies post-sensory stimuli into input, feedback, goal-work, or wake-only ingress before scheduler work begins
        else Task = input or feedback opportunity
            Ego->>Mem: recall and short-term summary
            Note over Ego,Mem: Planner context now includes targeted reflection-lesson recall
            Ego->>TWS: create or update thread workspace and index summary
            Ego->>Dash: emit scratchpad_head (with optional debug snapshot)
            Note over Ego,Planner: For Id-origin thoughts, Ego reapplies Id convergence state and action filtering before planner decide internalize without escalation removes contact_user and reflect_internal so planner-visible terminal reflection stays evidence-bound
            Note over Ego,Planner: Planner-visible actions are prefiltered by conversation instruction trust, current thread data trust, and action contract metadata before prompt build
            Note over Ego,Planner: Layered early policy shaping now also applies policy-scope, channel-surface, principal-role, and action-effect rules before proposal time control-plane actions are hidden from non-admin/non-internal surfaces and restricted scopes lose direct/autonomous commit semantics early
            Ego->>Planner: decide(context)
            Note over Ego,Planner: PromptBudgetAllocator reserves required-core/context floors with message-overhead accounting, trims optional first, and emits prompt_budget_allocation
            Note over Ego,Planner: Planner prompt includes conversation security summary, thread trust state, percept summary/family, opportunity summary/kind, allowed intentions/commit modes, and trigger provenance summary untrusted external content is framed as data, not instruction
            Note over Ego,Planner: Obvious persistent reminder / monitoring / goal-creation inputs route into a dedicated goal-creation branch before the generic planner path
            Note over Ego,Planner: Goal-creation branch uses a narrow schema prompt plus deterministic recurring schedule detection for supported forms like every N minutes / every N hours
            Note over Ego,Planner: Planner requests schema-enforced structured output. LLM layer owns compatibility degradation from strict to relaxed to prompt-only JSON. Parse failures do truncation-budget retry then strict-JSON retry before noop fallback
            Planner-->>Ego: defer/intend/plan/noop
            Ego->>Delib: maybeApplyPressureOverride
            Note over Ego: Runtime opportunity guard rejects invalid intention kind, action surface, or commit-mode violations before scheduling execution work
            Ego->>Sched: enqueue explicit intentions (observe/prepare/stage/request_authorization/commit/defer)
            Note over Ego,Sched: Planner now forms explicit lifecycle-aware intentions directly; plan steps and recovery/follow-up work become deferred intentions, not first-class thought queue items
            Note over Ego,Sched: Plans gated by budget, pressure, hash dedup, pending-plan check
            Note over Ego,Planner: Redundancy is planner-side soft cost control [prompt and verifier], with telemetry event external_action_redundancy_signal
            Note over Ego,Planner: Action verifier is disabled by default and only runs when planner.actionVerifierEnabled is true. when enabled it uses strict json_schema with relaxed-schema fallback. parse failures do truncation-budget retry then strict retry and may trip temporary verifier bypass [scoped per root_input and action_type]
            Note over Ego,Planner: Follow-up thoughts carry structured origin metadata [originActionType + observedEvidence]. verifier repairs back to the same evidence action are ignored for evidence-backed answers unless user asked refresh/retry. no-op verifier repairs collapse to approve
            Note over Ego,Planner: For contact_user, verifier repairs are limited to meaning-preserving surface cleanup. semantic answer rewrites are ignored and the original answer is kept
            Note over Ego,Planner: Verifier rejects now preserve denied action metadata in noop-retry deferred continuations repeated non-technical reject of the same answer payload on a follow-up defer is treated as verifier disagreement planner keeps the answer and dispatcher does not re-block it as a normal repeated denied action
            Note over Ego,Planner: Follow-up evidence thoughts explicitly request one raw JSON planner decision and forbid tool/function wrappers
        else Task = intention
            alt Deferred continuation
                Ego->>Ego: rebuild deferred continuation context from DEFER intention
                Ego->>Planner: decide(context for deferred continuation)
                Note over Ego,Sched: Non-Id defer chains carry fallback permission by default; if repeated defers hit max passes, Ego converts the chain into a fallback contact_user action instead of ending silently
            else Action-bearing intention
                Ego->>Ego: materialize PendingAction with intention metadata
                Note over Ego: PendingAction now carries intentionId, intentionKind, and requestedCommitMode
            end
        else Task = action
            alt Fallback explanation action
                Ego->>Motor: execute (bypass Superego)
                Note over Ego,ACS: Bypass execution is still mirrored into durable staged/receipt state
            else Normal action
                Ego->>TV: review(action, evidence/recent dialogue)
                Note over Ego,TV: DecisionVerifier classifies intent + volatility. evidence required only for volatile/unknown factual intents
                alt decision verifier deny
                    TV-->>Ego: deny (with reason_code)
                    Ego->>Sched: enqueue safe-alternative thought
                    Ego->>Mem: maybeRecordReflectionLesson(filtered)
                else decision verifier allow
                    Note over Ego,TV: If volatile evidence is required but tools are unavailable, verifier returns graceful allow [TASK_EVIDENCE_UNAVAILABLE_GRACEFUL]
                    Ego->>Sup: deterministic checks + authorization policy
                    alt deterministic deny
                        Sup-->>Ego: deny (hard deny)
                        Ego->>Sched: enqueue safe-alternative thought
                        Ego->>Mem: maybeRecordReflectionLesson(filtered)
                    else deterministic pass
                        alt action = id-origin reflect
                            Note over Ego,Sup: Internal-only reflect_internal bypasses LLM Superego review after deterministic payload validation trusted-data only. reflect_evidence remains evidence-bound
                            Sup-->>Ego: allow
                        else all other actions
                            Ego->>Sup: llm review(action)
                            Note over Ego,Sup: Stage-1 uses cheaper model from catalog when two-stage is enabled
                            Note over Ego,Sup: Superego prompt build uses same prompt allocator contract, includes action-origin context, and emits prompt_budget_allocation
                            Note over Ego,Sup: Escalate on low confidence, policy-risk, or technical fallback
                            Note over Ego,Sup: Superego completion max_tokens scales with prompt estimate [bounded floor/hard-cap] and model token_weight
                            Note over Ego,Sup: Structured output is schema-enforced [response_format=json_schema]
                            Note over Ego,Sup: Stage parse failures trigger one schema-enforced retry before default deny
                            Sup-->>Ego: allow or deny (with reason_code on deny)
                        end
                        alt allow
                            alt action = resolution_draft
                                Ego->>TWS: record active draft-sequence chunk
                                Note over Ego,TWS: Draft chunks are internal only, excluded from planner prompt summaries, and no user-visible assistant turn is emitted
                            else action = contact_user
                                Ego->>TWS: final-pass compilation from thread workspace + active draft sequence
                                Note over Ego,CTS: Before clearing per-input ephemera, normal completion marks the owning cognitive thread RESOLVED and keeps a bounded terminal snapshot
                                Ego->>TWF: rewrite candidate payload (if enabled)
                                Note over Ego,TWS: Final-pass skip requires both no evidence and insufficient drafts [less than max of 2 or activation_min_plan_steps]; draft sequence resets when cognition leaves answer-drafting work
                        Note over Ego,TWF: Apply workspace-confidence gate first, then model-confidence gate
                            end
                            Ego->>ACS: stage / authorize / commit
                            alt stage required
                                ACS->>ACDB: save staged action
                                ACS->>ACDB: save signal/background ledger entry
                                ACS-->>Ego: staged action (`WAITING_AUTHORIZATION` or `READY`)
                                Note over Ego: Action review emits explicit intention transitions for STAGE and, when needed, REQUEST_AUTHORIZATION
                                Ego->>Sched: enqueue approval-or-alternative deferred intention
                                Note over Dash,ACAPI: Dashboard action-control page watches a dedicated SSE lane and refreshes on staged/authorization lifecycle updates rather than polling
                                Note over ACW,ACS: Background autonomous worker polls SQL-filtered runnable READY actions, preserving same-thread order [threadSequence] and same-target serialization [executionKey] before atomic claim + execute
                        else direct commit allowed
                                ACS->>ACDB: save staged action + authorization
                                ACS->>Motor: execute(action, authorization)
                                Motor-->>ACS: outcome
                                Motor->>SC: emit ActionFeedbackCue (non-contact outcomes)
                                SC->>Ego: StimulusReceived(feedback percept)
                                ACS->>ACDB: save receipt + ledger + final staged status
                                ACS-->>Ego: executed outcome
                            end
                            Note over Ego,Motor: Actions may complete immediately or return WAITING + async operation handles
                            Note over SC,Ego: Feedback continuations and terminal thread resolution are decided only after feedback re-enters through SensoryCortex, and WAITING outcomes suspend the thread instead of auto-queuing a continuation
                            Note over Ego,Motor: contact_user delivery is channel-aware. Telegram sessions send through Bot API, dashboard sessions continue through local/dashboard delivery
                            Note over ACAPI,Dash: Dashboard-approved staged executions can append a completion/answer message back into the originating chat session before root-session mapping is cleared
                            Note over Ego,PG: Goal-origin WAITING without handles is rejected as a contract violation
                            Ego->>Ego: PromptInjectionDefense sanitize untrusted tool output
                            alt action = contact_user
                                Ego->>Sched: clear pending thought and action work for same root-session scope
                                Ego->>TWS: capture session digest for resolved input
                                Ego->>TWS: destroy workspace for resolved input
                                Note over Ego,Dash: Workspace telemetry carries root_input_id [identity] and root_input_received_at_ms [timing]
                                Ego->>Dash: drawer reads full snapshots via /api/obs/workspace/{rootId}
                                Ego->>Mem: maybeAssessLongTermMemory(post_terminal_answer, forced)
                            end
                            Ego->>TWS: record non-contact_user/non-resolution_draft action outcomes/evidence
                            Note over Ego,TWS: External evidence is stored as typed artifacts first and rendered into scratchpad with trust/source labels
                            Ego->>PG: onActionExecuted / allowFollowUp (generic action lifecycle observer)
                            Note over Ego,Sched: Id-origin satisfying actions clear remaining same-root queued work before any follow-up can be enqueued
                            Ego->>Sched: enqueue follow-up defer (for evidence actions)
                            Ego->>Mem: maybeAssessLongTermMemory(post_allowed_action, optional force)
                            Note over Ego,Mem: Blocked imprints emit long_term_memory_persistence_skipped [reason_code, reason_detail] for timeline visibility
                        else deny
                            Ego->>ACS: save durable denial/refusal ledger entry
                            Ego->>Sched: enqueue safe-alternative thought
                            Ego->>Mem: maybeRecordReflectionLesson(filtered)
                        end
                    end
                end
            end
        end

        Ego->>Delib: maybeForceTerminalAnswer
        Note over Ego,Delib: Deliberation state is session-scoped evidence, root-session thread trust is sticky for the request, and action control rate limits are enforced per root-session scope
        Note over Ego,Delib: Meta-reasoner output is schema-enforced. repeated empty-content or schema-validation failures can trigger optional fallback endpoint
        Ego->>Mem: maybeAssessLongTermMemory(interval or explicit remember-intent)
        Note over Ego,Mem: Episodic recall filters session/interlocutor only when explicitly requested by user input
        Note over Ego,Mem: Memory-advisor completion max_tokens scales with prompt estimate [bounded floor/hard-cap] and model token_weight
        Note over Ego,Mem: Long dialogue/recall blocks are compressed before advisor prompt
        Note over Ego,Mem: Saved durable memories are normalized to first-person agent perspective before imprint
        Note over Ego,Mem: Episodic logbook entries carry active channel/principal/policy-scope metadata
        Note over Ego,Mem: INTERNAL latest-salient turns switch long-term assessment into self-origin mode. reasons/tags/source are normalized away from user-preference framing
        Note over Ego,Mem: MCP fact/reference subject is stamped as "me" for agent-authored durable memories
        Note over Ego,Mem: Successful learning reflections track exact recent topic fingerprints. only learning retrieval uses them as freshness pressure, while other needs may still reuse the same topic context
    end

    Note over User,SC: Terminal stdin is control-only in interactive mode [exit command]. non-command text is not enqueued as chat input
    Note over User,SC: Interactive linguistic ingress currently comes from dashboard chat sessions or owner-only Telegram webhook updates
    Note over Ego,GOBS: Gmail and Calendar are native read-only observe actions, intended for goals such as Morning Briefing and Inbox Management
```

## 2.1) Goals Boundary

```mermaid
flowchart LR
    TS["TimerScheduler"] --> PM["GoalManager"]
    WM["WaitConditionMonitor (timeouts + async poll/event restore)"] --> PM
    AOR["AsyncOperationRegistry / Provider Adapters"] --> WM
    PM --> PSM["GoalStateMachine"]
    PM --> PP["GoalPlanner"]
    PM --> PV["GoalStepVerifier"]
    PSM --> PCS["GoalCommand stream"]
    PCS -->|persist| Store["GoalStore / goal-events.jsonl + goal.json + goal-snapshot.json"]
    PCS -->|work ready| Sig["GoalRuntimeCue"]
    %% Cron-backed goals do not emit initial work-ready on creation; first execution waits for a cron wake
    TS -->|"cron tick after completed/failed recurring goal"| PSM
    PSM -->|"reset plan steps + clear produced keys"| PCS
    Sig --> Ego["Ego"]
    %% Goal work is re-entered with a trusted internal automation conversation/security context
    %% Eval/live launches should isolate GoalStore root from the default user runtime store
    Ego -->|nextWorkFromCue| PM
    Ego -->|goal-origin action outcomes| PM
```

## 2.5) Interactive Startup Memory Gate

```mermaid
flowchart LR
    A["runInteractiveMode"] --> B["Resolve memory mode from memory-runtime.yaml"]
    B -->|memory=off| C["NoopHippocampus (memory unavailable)"]
    B -->|memory=default| D["Check managed HTTP provider health"]
    D -->|healthy| E["Provider-backed Hippocampus enabled"]
    D -->|unhealthy| F["Install managed provider artifact if needed,\nstart provider command, wait for /v1/health"]
    F -->|pass| E
    F -->|fail| C
    B -->|memory=external| X["Check external HTTP provider health"]
    X -->|healthy| E
    X -->|unhealthy/unsupported| C
    E --> Z["Register managed closeables with JVM shutdown hook\nso Ctrl-C / SIGTERM also closes the provider process"]
    E --> H["Emit action_capabilities(memory=available)"]
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
    F --> H{"Unavailable and retryable?"}
    G --> H
    H -->|yes| I["Retry health probe once"]
    H -->|no| J{"Role is optional meta_reasoner_fallback?"}
    I --> J
    J -->|yes + still unavailable| K["Warn and disable fallback for this run"]
    J -->|no| L["Required role unavailable -> abort startup"]
```

## 3) Convergence and Fallback States

```mermaid
stateDiagram-v2
    [*] --> Processing

    Processing --> Planning: input/deferred-intention task
    Planning --> ActionQueued: decision=intend
    Planning --> ThoughtQueued: decision=defer/plan/noop-retry
    Planning --> ThoughtQueued: plan suppressed (budget/pressure/hash/pending) -> convergence/recovery defer

    ActionQueued --> TaskReview: non-fallback action
    ActionQueued --> Executing: fallback explanation action
    TaskReview --> Denied: task verifier deny

    TaskReview --> PolicyReview: task verifier allow
    PolicyReview --> Denied: deterministic hard deny / contract deny / superego deny
    Denied --> ThoughtQueued: enqueue safe alternative defer
    Note right of ThoughtQueued: Repeat-denied payload block is skipped for technical or transient denial reasons (prefer reason_code classification) reflection lessons persist only for non-technical and non-system denials

    PolicyReview --> Executing: allow_commit
    PolicyReview --> Executing: allow_stage (legacy runtime compatibility path)
    Executing --> ThoughtQueued: action=resolution_draft (plan continues)
    Executing --> EvidenceObserved: external action succeeded
    Executing --> EvidenceMissing: tool/provider failure
    Executing --> WebSearchUnavailable: web search init/config failure
    WebSearchUnavailable --> ThoughtQueued: planner uses remaining available actions
    EvidenceObserved --> ThoughtQueued: feedback-driven defer
    EvidenceMissing --> ThoughtQueued: retry/adjust defer
    EvidenceMissing --> ActionDisabled: retry-budget cooldown trips (non-retryable action failures)
    ActionDisabled --> ThoughtQueued: planner uses remaining available actions

    Processing --> HighPressure: pressure threshold reached
    HighPressure --> ForcedTerminal: force terminal contact_user enqueue
    ForcedTerminal --> Executing

    Processing --> StepLimit: max loop steps with pending work
    StepLimit --> FallbackAttempt: dequeue fallback explanation action
    StepLimit --> Complete: force-deny active impulse lifecycles
    FallbackAttempt --> Executing

    Executing --> CleanupResolvedInput: action=contact_user clears same-input queued work + destroys scratchpad
    CleanupResolvedInput --> Complete
    Processing --> Complete: queues drained
    Complete --> [*]
```

## Edit Rules
- Keep this file synced with `AGENT_LOGIC_SUMMARY.md`.
- Prefer updating existing diagrams over adding a large monolith.
- If behavior changes, update only affected diagram sections and labels.
