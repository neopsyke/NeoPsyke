# Durable Work Diagram

This file covers assignment/runtime ownership, wake reasons, and plan handoff.
For planner-side assignment routing, see [../PLANNER_FLOW_DIAGRAM.md](../PLANNER_FLOW_DIAGRAM.md).

## L1: Plan Refinement and Durable Work Plan Ownership

```mermaid
flowchart TD
    subgraph "Inline Ego Plans"
        P1["Planner lane returns<br/>EgoDecision.EnqueuePlan"] --> PR1["PlanRefiner.refine()"]
        PR1 --> DD["DecisionDispatcher<br/>(hash / dedup / enqueue)"]
    end

    subgraph "Durable Work CREATE"
        GP["Assignment planner generates<br/>AssignmentCommand.Create<br/>with planSteps"] --> PR2["PlanRefiner.refine()"]
        PR2 --> FI["EgoDecision.FormIntention<br/>(CREATE payload with refined plan)"]
        FI --> AC["ActionControlService<br/>(stage with approvalContext)"]
        AC --> AR["ApprovalRuntime<br/>(shows plan in prompt)"]
        AR -->|"APPROVE"| DWR["AssignmentRuntime<br/>(uses pre-built plan)"]
        AR -->|"DENY_AND_REISSUE"| RI["Reissue to Ego<br/>(re-plan with feedback)"]
    end

    subgraph "Durable Work REVISE_PLAN"
        REV["Ego builds revised plan<br/>from work-item context"] --> PR3["PlanRefiner.refine()"]
        PR3 --> FI2["EgoDecision.FormIntention<br/>(REVISE_PLAN with plan)"]
        FI2 --> DWR2["AssignmentRuntime<br/>(applies supplied plan)"]
    end
```

## L1: Durable Work Boundary

```mermaid
flowchart LR
    TS["TimerScheduler"] --> PM["AssignmentRuntime"]
    WM["WaitConditionMonitor (timeouts + async poll/event restore)"] --> PM
    AOR["AsyncOperationRegistry / Provider Adapters"] --> WM

    PM --> PSM["WorkItemStateMachine"]
    PM --> PP["AssignmentCommandBuilder"]
    PM --> PV["WorkStepVerifier"]
    PM --> AJ["ActivationJournal"]
    PM --> EL["WorkEffectLedger"]

    PSM --> PCS["WorkItemCommand stream"]
    PCS -->|persist| Store["WorkItemStore / assignment-events.jsonl + assignment-events.archive.jsonl + assignment.json + assignment-snapshot.json"]
    PCS -->|work ready| Sig["AssignmentCue"]

    TS -->|"cron tick after completed / failed recurring work item"| PSM
    PSM -->|"reset plan steps + clear produced keys"| PCS

    Sig --> Ego["Ego"]
    Ego -->|nextWorkFromCue| PM
    Ego -->|assignment-origin action outcomes| PM
    Ego -->|beforeActionExecution gate| EL
```

## L2: Wake to Execution Feedback Loop

```mermaid
sequenceDiagram
    participant Timer as TimerScheduler / WaitConditionMonitor
    participant Runtime as AssignmentRuntime
    participant Ego
    participant Sched as AttentionScheduler
    participant Motor as MotorCortex

    Timer->>Runtime: cron_due / overdue_check / wait_resolved / recovery wake
    Runtime->>Ego: AssignmentCue + activation context
    Ego->>Sched: enqueue assignment opportunity
    Note over Runtime,Ego: Activation context carries wake reason and stable per-step root ownership
    Sched->>Ego: next assignment task
    Ego->>Ego: plan assignment step or continuation
    Ego->>Motor: execute assignment-origin action
    Motor-->>Ego: outcome / WAITING handle
    Ego->>Runtime: onActionExecuted / allowFollowUp / finalize cycle after root drains
    Note over Runtime: ActivationJournal and WorkEffectLedger preserve resumable state across wakes
```
