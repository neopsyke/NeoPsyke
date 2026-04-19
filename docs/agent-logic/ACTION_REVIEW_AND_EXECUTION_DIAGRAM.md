# Action Review and Execution Diagram

This file covers the path from `PendingAction` to staged or committed execution and feedback re-entry.
For the loop context around this path, see [EGO_LOOP_DIAGRAM.md](EGO_LOOP_DIAGRAM.md).

## L1: Review and Execution Stack

```mermaid
flowchart LR
    Pending["PendingAction"] --> GG["GroundingGate"]
    GG --> Sup["Superego"]
    Sup --> ACS["ActionControlService"]
    ACS --> Motor["MotorCortex"]
    Motor --> SC["SensoryCortex<br/>(ActionFeedbackCue)"]

    Policy["ActionAuthorizationPolicy (YAML)"] --> Sup
    ACS --> ACDB["ActionControl SQLite"]

    Registry["ActionRegistry"] --> Plugins["Action Plugins"]
    Runtime["Connector Runtime"] --> Plugins
    Plugins --> Motor

    Motor --> PID["PromptInjectionDefense"]
    PID --> Ego["Ego"]
```

## L1: Allow-Stage Approval Path

```mermaid
flowchart TD
    Review["Grounding + Superego allow_stage"] --> ACS["ActionControlService"]
    ACS --> Stage["Persist staged action + signal ledger"]
    Stage --> AIR["ApprovalRuntime"]
    AIR --> ACDB["Approval audit trail"]
    AIR --> Dash["Dashboard approval prompt"]
    AIR --> TG["Telegram approval prompt"]

    Dash --> Owner["Owner decision"]
    TG --> Owner
    Owner --> Decision{"Approve / deny / expire"}

    Decision -->|Approve| Ready["READY / authorized action"]
    Ready --> Worker["AutonomousWorker or same-thread executor"]
    Worker --> Claim["Atomic claim + execute"]
    Claim --> Motor["MotorCortex.execute()"]

    Decision -->|Deny / expire| Blocked["Blocked root resolved<br/>safe-alternative continuation may be queued"]
```

## L1: Direct Commit and Feedback Re-entry

```mermaid
sequenceDiagram
    participant Ego
    participant GG as GroundingGate
    participant Sup as Superego
    participant ACS as ActionControlService
    participant Motor as MotorCortex
    participant SC as SensoryCortex
    participant PG as AssignmentRuntime

    Ego->>GG: review(action, groundingMetadata)
    GG-->>Ego: allow / deny
    Ego->>Sup: deterministic + LLM review
    Sup-->>Ego: allow_commit / allow_stage / deny
    Ego->>ACS: stage + authorize + commit
    ACS->>Motor: execute(action, authorization)
    Motor-->>ACS: outcome
    Motor->>SC: ActionFeedbackCue (non-contact outcomes)
    SC->>Ego: FeedbackReceived(cue)
    ACS-->>Ego: receipt + final staged status
    Ego->>PG: onActionExecuted / allowFollowUp
```
