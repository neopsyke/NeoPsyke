# Input and Threading Diagram

This file covers linguistic ingress, security/context binding, and the handoff into the scheduler.
For planner-internal routing, see [../PLANNER_FLOW_DIAGRAM.md](../PLANNER_FLOW_DIAGRAM.md). For later loop stages, see [EGO_LOOP_DIAGRAM.md](EGO_LOOP_DIAGRAM.md).

## L1: Channel and Auth Ingress

```mermaid
flowchart LR
    U["User / Web UI"] --> SC["SensoryCortex"]
    TG["Telegram Owner Chat"] --> TWH["TelegramWebhookBridge"]
    TG --> TLP["TelegramPollingBridge"]
    TWH --> SC
    TLP --> SC
    Ctx["ConversationContext<br/>(sessionId required; security carried end-to-end)"] --> SC

    GOU["Google OAuth Browser Flow"] --> GOA["GoogleWorkspaceOAuthBridge"]
    GOA --> GCS["GoogleWorkspaceCredentialStore"]
    GOA --> GVP["Gmail Profile Verification"]
    GOA -.-> GOBS["Native Google Observe Actions"]

    SC --> SIC["StimulusIngressCoordinator"]
```

## L1: Sensory Boundary to Thread Binding

```mermaid
sequenceDiagram
    participant Source as User / Telegram / Assignment / Id
    participant SC as SensoryCortex
    participant Ego
    participant CTS as CognitiveThreadStore
    participant Dash as DashboardStateStore/API
    participant Sched as AttentionScheduler

    Source->>SC: Typed stimulus
    SC->>Ego: StimulusReceived (stimulus + percept)
    Note over SC,Ego: ConversationContext, provenance, rootInputId, and timing cross the sensory boundary here
    Ego->>CTS: bind percept to root-scoped cognitive thread
    CTS-->>Ego: cognitiveThreadId + thread trust state
    Ego->>Dash: emit cognitive_thread_updated
    CTS-->>Ego: policy-shaped opportunity
    Ego->>Sched: enqueue ScheduledOpportunity
    Ego->>Dash: emit opportunity_enqueued
    Note over CTS,Dash: Thread snapshots are retained for live and terminal roots and exposed through /api/obs/threads
```

## L2: Stimulus Classification Before Planning

```mermaid
flowchart TB
    Stim["StimulusReceived"] --> SIC["StimulusIngressCoordinator"]
    SIC --> Classify{"Typed signal kind"}

    Classify -->|User input| Input["enqueueInput()"]
    Classify -->|Action feedback| Feedback["enqueueFeedback()"]
    Classify -->|Assignment cue| Assignment["enqueueAssignment()"]
    Classify -->|Id impulse cue| Impulse["bindImpulseWake()"]

    Input --> Opp["Opportunity queue"]
    Feedback --> Opp
    Assignment --> Opp
    Impulse --> Opp

    Opp --> Sched["AttentionScheduler.nextTask()"]
```
