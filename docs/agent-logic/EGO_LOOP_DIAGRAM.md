# Ego Loop Diagram

This file replaces the old single full-loop sequence with smaller stage diagrams.
For planner internals, see [../PLANNER_FLOW_DIAGRAM.md](../PLANNER_FLOW_DIAGRAM.md). For execution review, see [ACTION_REVIEW_AND_EXECUTION_DIAGRAM.md](ACTION_REVIEW_AND_EXECUTION_DIAGRAM.md).

## L1: Scheduler Branches

```mermaid
flowchart TD
    Next["AttentionScheduler.nextTask()"] --> Kind{"LoopTask kind"}

    Kind -->|Opportunity: input / feedback| Recall["Recall context + short-term summary"]
    Recall --> Workspace["Create or update ScratchpadStore"]
    Workspace --> Plan["HierarchicalEgoPlanner.decide()"]

    Kind -->|Opportunity: impulse| Ambient["Assemble ambient context + idState"]
    Ambient --> PlanImpulse["HierarchicalEgoPlanner.decide()"]

    Kind -->|Opportunity: assignment| Assign["Load assignment activation context"]
    Assign --> PlanAssignment["HierarchicalEgoPlanner.decide()"]

    Kind -->|Continuation| Cont["Rebuild continuation context"]
    Cont --> PlanCont["HierarchicalEgoPlanner.decide()"]

    Plan --> Delib["DeliberationEngine"]
    PlanImpulse --> Delib
    PlanAssignment --> Delib
    PlanCont --> Delib
    Delib --> Dispatch["DecisionDispatcher"]
    Dispatch --> Queue["Enqueue continuations / intentions / plan steps"]

    Kind -->|Intention| Pending["Materialize PendingAction<br/>(intentionId, intentionKind, commitMode)"]
    Pending --> Review["Action review and execution"]
```

## L1: Action Result to Next Work

```mermaid
flowchart TD
    Exec["Allowed action executed"] --> Outcome{"Outcome / action kind"}

    Outcome -->|resolution_draft| Draft["Record active draft chunk"]
    Draft --> Continue["Queue plan continuation if needed"]

    Outcome -->|External evidence / tool result| Evidence["Record typed evidence in scratchpad"]
    Evidence --> Follow["Queue typed follow-up continuation"]

    Outcome -->|WAITING + async handles| Suspend["Suspend root until typed feedback re-enters"]

    Outcome -->|contact_user| Finalize["Compile workspace + optional final rewrite"]
    Finalize --> Clear["Clear same-root queued work<br/>Destroy workspace<br/>Mark thread resolved"]
    Clear --> TerminalAssess["maybeAssessLongTermMemory(post_terminal_answer)"]

    Continue --> ActionAssess["maybeAssessLongTermMemory(post_allowed_action)"]
    Follow --> ActionAssess
```

## L2: Terminal Answer Cleanup

```mermaid
sequenceDiagram
    participant Ego
    participant TWS as ScratchpadStore
    participant TWF as ScratchpadFinalizer
    participant ACS as ActionControlService
    participant Dash as DashboardStateStore/API
    participant Mem as MemorySystem

    Ego->>TWS: compile workspace + active draft sequence
    Ego->>TWF: rewrite candidate payload (if enabled)
    Ego->>ACS: stage / authorize / commit contact_user
    ACS-->>Ego: delivered outcome
    Ego->>Dash: clear root-session workspace telemetry
    Ego->>TWS: capture digest then destroy workspace
    Ego->>Mem: maybeAssessLongTermMemory(post_terminal_answer, forced)
```
