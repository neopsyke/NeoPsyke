# Agent Logic Diagram (Living Document)

This file complements `AGENT_LOGIC_SUMMARY.md` with small, editable Mermaid diagrams.
Keep this file as the landing page for the diagram set. Put detailed subsystem diagrams in the split docs under `docs/agent-logic/`.

---

## L0: System-Level Component View

High-level view of the major subsystems and their interactions.

```mermaid
flowchart LR
    Input["Stimuli\n(User, Telegram, Durable Work, Id)"] --> SC["SensoryCortex"]
    SC --> Ego["Ego"]
    Ego --> Planner["HierarchicalEgoPlanner\n(Typed Lane Dispatch)"]
    Planner --> Ego
    Ego --> Sup["Superego\n(Review Gate)"]
    Sup --> Ego
    Ego --> MC["MotorCortex\n(Action Execution)"]
    MC -->|"ActionFeedbackCue"| SC
    Id["Id\n(Autonomous Drives)"] -->|"Impulse"| SC
    Ego --> Mem["Memory System\n(MemoryStore/Hippocampus/Logbook/ScratchpadStore)"]
    Mem --> Ego
    Ego --> Assignments["AssignmentGateway\n(Persistent Work Items)"]
    Assignments -->|"AssignmentCue"| SC
    MC --> Output["ConversationOutputGateway\n(Dashboard, Telegram)"]
```

---

## L1: Main Loop Sequence (Simplified)

Clean overview of the per-input happy path without subsystem detail.

```mermaid
sequenceDiagram
    participant User
    participant SC as SensoryCortex
    participant Ego
    participant Sched as AttentionScheduler
    participant Planner as HierarchicalEgoPlanner
    participant Sup as Superego
    participant ACS as ActionControlService
    participant Motor as MotorCortex
    participant Mem as MemorySystem

    User->>SC: Input (chat, Telegram)
    SC->>Ego: StimulusReceived (stimulus + percept)
    Ego->>Sched: enqueue ScheduledOpportunity

    loop While pending work and step limit not reached
        Ego->>Sched: nextTask()
        Sched-->>Ego: opportunity / intention / action

        alt Opportunity (input / feedback / impulse / assignment)
            Ego->>Mem: recall context
            Ego->>Planner: decide(context)
            Planner-->>Ego: intend / plan / noop
            Ego->>Sched: enqueue continuations or intentions
        else Continuation
            Ego->>Planner: decide(continuation context)
            Planner-->>Ego: decision
        else Intention
            Ego->>Ego: materialize PendingAction
        else Action
            Ego->>Sup: reviewAuthorization(action)
            Sup-->>Ego: allow / stage / deny
            alt allow
                Ego->>ACS: stage + authorize + commit
                ACS->>Motor: execute(action)
                Motor-->>SC: ActionFeedbackCue
                SC->>Ego: FeedbackReceived(cue)
            else deny
                Ego->>Sched: enqueue retry continuation
            end
        end
    end

    Ego->>Mem: maybeAssessLongTermMemory
```

---

## Diagram Map

Detailed diagrams now live in separate area docs.

| Area | File | Scope |
|---|---|---|
| Input and thread binding | [docs/agent-logic/INPUT_AND_THREADING_DIAGRAM.md](docs/agent-logic/INPUT_AND_THREADING_DIAGRAM.md) | Channel ingress, security/context binding, thread creation, scheduler handoff |
| Planner | [docs/PLANNER_FLOW_DIAGRAM.md](docs/PLANNER_FLOW_DIAGRAM.md) | Typed trigger routing, L1/L2 planner lanes, post-planner dispatch |
| Split ego loop | [docs/agent-logic/EGO_LOOP_DIAGRAM.md](docs/agent-logic/EGO_LOOP_DIAGRAM.md) | Per-input loop split into queueing, planning branches, and completion |
| Action review and execution | [docs/agent-logic/ACTION_REVIEW_AND_EXECUTION_DIAGRAM.md](docs/agent-logic/ACTION_REVIEW_AND_EXECUTION_DIAGRAM.md) | Grounding, superego review, staging, approvals, execution, feedback re-entry |
| Durable work runtime | [docs/agent-logic/DURABLE_WORK_DIAGRAM.md](docs/agent-logic/DURABLE_WORK_DIAGRAM.md) | Assignment boundary, wake reasons, plan ownership, assignment feedback |
| Memory and startup gates | [docs/agent-logic/MEMORY_AND_STARTUP_DIAGRAM.md](docs/agent-logic/MEMORY_AND_STARTUP_DIAGRAM.md) | Memory subsystem wiring, per-loop recall/assessment, startup health gates |
| Convergence and fallback | [docs/agent-logic/CONVERGENCE_AND_FALLBACK_DIAGRAM.md](docs/agent-logic/CONVERGENCE_AND_FALLBACK_DIAGRAM.md) | Pressure, step limits, denial/retry, fallback terminal paths |

---

## Edit Rules
- Keep this file synced with `AGENT_LOGIC_SUMMARY.md`.
- Source of truth is the code, not this document.
- Keep this landing page small; detailed diagrams belong in the split area docs.
- If behavior changes, update only the affected diagrams and links.
